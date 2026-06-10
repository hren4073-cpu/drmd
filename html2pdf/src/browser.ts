import * as path from 'path';
import * as os from 'os';
import {
  install,
  resolveBuildId,
  detectBrowserPlatform,
  computeExecutablePath,
  Browser,
  Cache,
} from '@puppeteer/browsers';
import { Logger } from './logger';
import { pathExists, ensureDir } from './util';

/**
 * Каталог для встроенного/скачанного Chromium.
 * При запуске из собранного .exe (pkg) кладём рядом с .exe — утилита остаётся
 * портативной. В обычном режиме — в каталог пользователя.
 */
export function getCacheDir(): string {
  // process.pkg выставляется упаковщиком pkg/@yao-pkg
  const isPackaged = Boolean((process as any).pkg);
  if (isPackaged) {
    return path.join(path.dirname(process.execPath), 'chromium');
  }
  return path.join(os.homedir(), '.html2pdf-cli', 'chromium');
}

/**
 * Гарантирует наличие Chromium. Если его нет — скачивает (один раз).
 * Возвращает путь к исполняемому файлу браузера.
 */
export async function ensureBrowser(logger: Logger): Promise<string> {
  const cacheDir = getCacheDir();
  await ensureDir(cacheDir);

  const platform = detectBrowserPlatform();
  if (!platform) {
    throw new Error('Не удалось определить платформу для загрузки Chromium.');
  }

  // Стабильный buildId для канала "stable".
  const buildId = await resolveBuildId(Browser.CHROME, platform, 'stable');

  const executablePath = computeExecutablePath({
    browser: Browser.CHROME,
    buildId,
    cacheDir,
  });

  if (await pathExists(executablePath)) {
    return executablePath;
  }

  logger.info(
    `Chromium не найден. Выполняется автоматическая загрузка (build ${buildId}). Это происходит только при первом запуске...`
  );

  let lastPct = -1;
  await install({
    browser: Browser.CHROME,
    buildId,
    cacheDir,
    downloadProgressCallback: (downloaded: number, total: number) => {
      const pct = total ? Math.floor((downloaded / total) * 100) : 0;
      if (pct !== lastPct && pct % 5 === 0) {
        lastPct = pct;
        process.stdout.write(`\r  Загрузка Chromium: ${pct}%   `);
      }
    },
  });
  process.stdout.write('\n');
  logger.success('Chromium успешно установлен.');

  // На всякий случай проверим кэш
  const cache = new Cache(cacheDir);
  void cache; // (Cache используется неявно computeExecutablePath)

  return executablePath;
}
