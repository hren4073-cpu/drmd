#!/usr/bin/env node
import * as path from 'path';
import * as fsp from 'fs/promises';
import pLimit from 'p-limit';
import cliProgress from 'cli-progress';
import pc from 'picocolors';

import { parseArgs, AppConfig } from './cli';
import { Logger } from './logger';
import { ensureBrowser } from './browser';
import { Converter, ConversionResult, PdfSettings } from './converter';
import { mergePdfs, saveMerged, MergeItem } from './merge';
import {
  walkFiles,
  readTextFile,
  ensureDir,
  pathExists,
  sanitizeFileName,
  formatDuration,
  longPath,
} from './util';

interface Task {
  input: string;
  output: string;
}

/** Гарантирует уникальность пути вывода (добавляет _2, _3, ...). */
function uniqueOutput(desired: string, used: Set<string>): string {
  const dir = path.dirname(desired);
  const ext = path.extname(desired);
  const base = path.basename(desired, ext);
  let candidate = desired;
  let i = 2;
  while (used.has(candidate.toLowerCase())) {
    candidate = path.join(dir, `${base}_${i}${ext}`);
    i++;
  }
  used.add(candidate.toLowerCase());
  return candidate;
}

async function buildTasks(cfg: AppConfig, logger: Logger, used: Set<string>): Promise<Task[]> {
  const tasks: Task[] = [];

  if (cfg.mode === 'single') {
    if (!cfg.input) {
      throw new Error('Не указан входной файл. Пример: html2pdf input.html output.pdf');
    }
    if (!(await pathExists(cfg.input))) {
      throw new Error(`Файл не найден: ${cfg.input}`);
    }
    const output =
      cfg.output ||
      path.join(path.dirname(path.resolve(cfg.input)), path.basename(cfg.input, path.extname(cfg.input)) + '.pdf');
    tasks.push({ input: path.resolve(cfg.input), output: uniqueOutput(path.resolve(output), used) });
    return tasks;
  }

  if (cfg.mode === 'dir') {
    if (!cfg.inputDir) throw new Error('Не указан --input-dir.');
    if (!cfg.outputDir) throw new Error('Для режима папки укажите --output-dir.');
    const files = await walkFiles(cfg.inputDir, cfg.extensions);
    logger.info(`Найдено HTML-файлов: ${files.length}`);
    const base = path.resolve(cfg.inputDir);
    for (const f of files) {
      const rel = path.relative(base, f);
      const relPdf = rel.slice(0, rel.length - path.extname(rel).length) + '.pdf';
      const out = path.resolve(cfg.outputDir, relPdf);
      tasks.push({ input: f, output: uniqueOutput(out, used) });
    }
    return tasks;
  }

  // list mode
  if (!cfg.listFile) throw new Error('Не указан --list.');
  const content = await readTextFile(cfg.listFile);
  const lines = content
    .split(/\r?\n/)
    .map((l) => l.trim())
    .filter((l) => l && !l.startsWith('#'));
  logger.info(`В списке файлов: ${lines.length}`);
  for (const line of lines) {
    const input = path.resolve(line);
    if (!(await pathExists(input))) {
      logger.warn(`Пропущен (нет файла): ${line}`);
      continue;
    }
    let out: string;
    if (cfg.outputDir) {
      out = path.resolve(cfg.outputDir, path.basename(input, path.extname(input)) + '.pdf');
    } else {
      out = path.join(path.dirname(input), path.basename(input, path.extname(input)) + '.pdf');
    }
    tasks.push({ input, output: uniqueOutput(out, used) });
  }
  return tasks;
}

/** Переименование вывода по заголовку (для --name-by-title). */
async function renameByTitle(
  result: ConversionResult,
  used: Set<string>
): Promise<string> {
  if (!result.title) return result.output;
  const dir = path.dirname(result.output);
  const desired = path.join(dir, sanitizeFileName(result.title) + '.pdf');
  if (path.resolve(desired).toLowerCase() === path.resolve(result.output).toLowerCase()) {
    return result.output;
  }
  const finalPath = uniqueOutput(desired, used);
  try {
    await fsp.rename(longPath(result.output), longPath(finalPath));
    return finalPath;
  } catch {
    return result.output; // не удалось переименовать — оставляем как есть
  }
}

async function main(): Promise<void> {
  const cfg = parseArgs();
  const logger = new Logger(cfg.quiet);
  const startAll = Date.now();

  console.log(pc.bold(pc.cyan('html2pdf')) + pc.dim(' — HTML → PDF (headless Chromium)\n'));

  const settings: PdfSettings = {
    format: cfg.format,
    margin: cfg.margin,
    scale: cfg.scale,
    printBackground: cfg.printBackground,
    landscape: cfg.landscape,
    userCssPath: cfg.userCssPath,
    mediaType: cfg.mediaType,
    waitAfterLoadMs: cfg.waitAfterLoadMs,
    timeoutMs: cfg.timeoutMs,
  };

  const used = new Set<string>();
  const tasks = await buildTasks(cfg, logger, used);

  if (tasks.length === 0) {
    logger.warn('Нет файлов для конвертации.');
    return;
  }

  const executablePath = await ensureBrowser(logger);
  const converter = new Converter(executablePath, settings, logger);
  await converter.start();

  const limit = pLimit(cfg.concurrency);
  const results: ConversionResult[] = [];

  const bar = cfg.quiet
    ? null
    : new cliProgress.SingleBar(
        {
          format:
            'Прогресс |' + pc.cyan('{bar}') + '| {percentage}% | {value}/{total} | ✓{ok} ✗{fail} | {eta_formatted}',
          hideCursor: true,
          clearOnComplete: false,
        },
        cliProgress.Presets.shades_classic
      );
  let okCount = 0;
  let failCount = 0;
  if (bar) bar.start(tasks.length, 0, { ok: 0, fail: 0 });

  await Promise.all(
    tasks.map((task) =>
      limit(async () => {
        const res = await converter.convertFile(task.input, task.output);
        if (res.ok) {
          okCount++;
          if (cfg.nameByTitle) {
            res.output = await renameByTitle(res, used);
          }
          logger.success(`${path.basename(res.input)} → ${path.basename(res.output)} (${res.durationMs}ms)`);
        } else {
          failCount++;
          logger.error(`Не удалось: ${res.input} — ${res.error}`);
        }
        results.push(res);
        if (bar) bar.increment({ ok: okCount, fail: failCount });
      })
    )
  );

  if (bar) bar.stop();
  await converter.stop();

  // Слияние, если запрошено.
  const successes = results.filter((r) => r.ok).sort((a, b) => a.input.localeCompare(b.input));
  if (cfg.merge && successes.length > 0) {
    const mergeFile =
      cfg.mergeFile ||
      path.resolve(cfg.outputDir || path.dirname(successes[0].output), '_merged.pdf');
    logger.info(`Объединение ${successes.length} PDF → ${mergeFile}`);
    const items: MergeItem[] = successes.map((r) => ({
      pdfPath: r.output,
      title: r.title || path.basename(r.output, '.pdf'),
    }));
    try {
      await ensureDir(path.dirname(mergeFile));
      const bytes = await mergePdfs(items, logger);
      await saveMerged(bytes, mergeFile);
      logger.success(`Объединённый документ создан: ${mergeFile}`);
    } catch (e: any) {
      logger.error(`Ошибка объединения: ${e?.message}`);
    }
  }

  // Итоговый отчёт.
  const totalMs = Date.now() - startAll;
  console.log('\n' + pc.bold('Итог:'));
  console.log(`  Всего файлов:   ${results.length}`);
  console.log(`  ${pc.green('Успешно:')}       ${okCount}`);
  console.log(`  ${failCount ? pc.red('С ошибками:') : 'С ошибками:'}    ${failCount}`);
  console.log(`  Время:          ${formatDuration(totalMs)}`);

  const problems = results.filter((r) => !r.ok);
  if (problems.length) {
    console.log('\n' + pc.yellow('Файлы с ошибками:'));
    for (const p of problems) {
      console.log(`  - ${p.input}\n      ${pc.red(p.error || 'неизвестная ошибка')}`);
    }
  }

  // Файл лога.
  const logFile =
    cfg.logFile ||
    path.resolve(cfg.outputDir || path.dirname(results[0].output), 'html2pdf.log');
  try {
    await logger.saveTo(logFile);
    console.log(pc.dim(`\nЛог сохранён: ${logFile}`));
  } catch {
    /* лог не критичен */
  }

  process.exit(failCount > 0 ? 1 : 0);
}

main().catch((err) => {
  console.error(pc.red('Фатальная ошибка: '), err?.message || err);
  process.exit(2);
});
