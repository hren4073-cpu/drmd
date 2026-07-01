import yargs from 'yargs';
import { hideBin } from 'yargs/helpers';
import { PaperFormat, PDFMargin } from 'puppeteer-core';

export interface AppConfig {
  mode: 'single' | 'dir' | 'list';
  // single
  input?: string;
  output?: string;
  // dir
  inputDir?: string;
  outputDir?: string;
  // list
  listFile?: string;

  extensions: string[];
  concurrency: number;
  nameByTitle: boolean;

  merge: boolean;
  mergeFile?: string;

  format: PaperFormat | 'default';
  margin: PDFMargin;
  scale: number;
  printBackground: boolean;
  landscape: boolean;
  userCssPath?: string;
  mediaType: 'print' | 'screen';
  waitAfterLoadMs: number;
  timeoutMs: number;

  logFile?: string;
  quiet: boolean;
}

export function parseArgs(argv = hideBin(process.argv)): AppConfig {
  const a = yargs(argv)
    .scriptName('html2pdf')
    .usage(
      [
        'Конвертер HTML -> PDF (headless Chromium).',
        '',
        'Примеры:',
        '  html2pdf input.html output.pdf',
        '  html2pdf --input-dir "C:\\my_site" --output-dir "C:\\pdfs"',
        '  html2pdf --input-dir "C:\\my_site" --output-dir "C:\\pdfs" --merge --concurrency 4',
        '  html2pdf --list files.txt --output-dir "C:\\pdfs" --name-by-title',
      ].join('\n')
    )
    .option('input-dir', { type: 'string', describe: 'Папка с .html для массовой конвертации' })
    .option('output-dir', { type: 'string', describe: 'Папка для PDF (структура подпапок сохраняется)' })
    .option('list', { type: 'string', describe: 'Текстовый файл со списком HTML-файлов (по одному на строку)' })
    .option('ext', {
      type: 'string',
      default: '.html,.htm',
      describe: 'Расширения для обхода папки (через запятую)',
    })
    .option('concurrency', { type: 'number', default: 4, describe: 'Число параллельных конвертаций' })
    .option('name-by-title', {
      type: 'boolean',
      default: false,
      describe: 'Имя PDF из <title> или <h1> вместо имени файла',
    })
    .option('merge', { type: 'boolean', default: false, describe: 'Объединить все PDF в один (с оглавлением)' })
    .option('merge-file', { type: 'string', describe: 'Путь к объединённому PDF (по умолч. <output-dir>/_merged.pdf)' })
    .option('format', {
      type: 'string',
      default: 'A4',
      describe: 'Формат страницы: A4, Letter, Legal, A3, ... или "default" (брать из CSS @page)',
    })
    .option('margin-top', { type: 'string', default: '0' })
    .option('margin-bottom', { type: 'string', default: '0' })
    .option('margin-left', { type: 'string', default: '0' })
    .option('margin-right', { type: 'string', default: '0' })
    .option('scale', { type: 'number', default: 1.0, describe: 'Масштаб 0.1..2.0' })
    .option('background', {
      type: 'boolean',
      default: true,
      describe: 'Печатать фоны/графику (--no-background чтобы отключить)',
    })
    .option('landscape', { type: 'boolean', default: false, describe: 'Альбомная ориентация' })
    .option('user-css', { type: 'string', describe: 'Файл с печатными CSS-стилями (внедряется перед печатью)' })
    .option('media', {
      choices: ['print', 'screen'] as const,
      default: 'print',
      describe: 'Эмулируемый media type',
    })
    .option('wait', { type: 'number', default: 300, describe: 'Доп. ожидание после загрузки, мс (для тяжёлого JS)' })
    .option('timeout', { type: 'number', default: 60000, describe: 'Таймаут на файл, мс' })
    .option('log', { type: 'string', describe: 'Файл лога (по умолч. рядом с output)' })
    .option('quiet', { type: 'boolean', default: false, describe: 'Тихий режим (минимум вывода)' })
    .help('help')
    .alias('h', 'help')
    .alias('v', 'version')
    .version('1.0.0')
    .wrap(Math.min(110, process.stdout.columns || 110))
    .parseSync();

  const positionals = (a._ as (string | number)[]).map(String);

  const margin: PDFMargin = {
    top: a['margin-top'] as string,
    bottom: a['margin-bottom'] as string,
    left: a['margin-left'] as string,
    right: a['margin-right'] as string,
  };

  const extensions = String(a.ext)
    .split(',')
    .map((e) => e.trim())
    .filter(Boolean)
    .map((e) => (e.startsWith('.') ? e : '.' + e));

  let mode: AppConfig['mode'];
  if (a.list) mode = 'list';
  else if (a['input-dir']) mode = 'dir';
  else mode = 'single';

  return {
    mode,
    input: positionals[0],
    output: positionals[1],
    inputDir: a['input-dir'] as string | undefined,
    outputDir: a['output-dir'] as string | undefined,
    listFile: a.list as string | undefined,
    extensions,
    concurrency: Math.max(1, Number(a.concurrency) || 1),
    nameByTitle: Boolean(a['name-by-title']),
    merge: Boolean(a.merge),
    mergeFile: a['merge-file'] as string | undefined,
    format: String(a.format) as PaperFormat | 'default',
    margin,
    scale: Number(a.scale) || 1.0,
    printBackground: Boolean(a.background),
    landscape: Boolean(a.landscape),
    userCssPath: a['user-css'] as string | undefined,
    mediaType: a.media as 'print' | 'screen',
    waitAfterLoadMs: Math.max(0, Number(a.wait) || 0),
    timeoutMs: Math.max(1000, Number(a.timeout) || 60000),
    logFile: a.log as string | undefined,
    quiet: Boolean(a.quiet),
  };
}
