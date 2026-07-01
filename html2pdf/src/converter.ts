import puppeteer, { Browser, PaperFormat, PDFMargin } from 'puppeteer-core';
import { toFileUrl, readTextFile, writeBinaryFile, ensureDir } from './util';
import * as path from 'path';
import { Logger } from './logger';

export interface PdfSettings {
  format?: PaperFormat | 'default';
  margin: PDFMargin;
  scale: number;
  printBackground: boolean;
  landscape: boolean;
  /** Доп. CSS, внедряемый перед печатью (печатные стили). */
  userCssPath?: string;
  /** Эмулировать media type 'print' (по умолч.) или 'screen'. */
  mediaType: 'print' | 'screen';
  /** Доп. ожидание после загрузки, мс (для тяжёлого JS). */
  waitAfterLoadMs: number;
  /** Таймаут навигации/конвертации, мс. */
  timeoutMs: number;
}

export interface ConversionResult {
  input: string;
  output: string;
  title: string | null;
  ok: boolean;
  error?: string;
  durationMs: number;
}

export class Converter {
  private browser: Browser | null = null;
  private userCss: string | null = null;

  constructor(
    private executablePath: string,
    private settings: PdfSettings,
    private logger: Logger
  ) {}

  async start(): Promise<void> {
    if (this.settings.userCssPath) {
      this.userCss = await readTextFile(this.settings.userCssPath);
    }
    this.browser = await puppeteer.launch({
      executablePath: this.executablePath,
      headless: true,
      args: [
        '--no-sandbox',
        '--disable-setuid-sandbox',
        '--disable-dev-shm-usage',
        '--allow-file-access-from-files',
        '--disable-gpu',
        '--font-render-hinting=none',
      ],
    });
  }

  async stop(): Promise<void> {
    if (this.browser) {
      await this.browser.close().catch(() => undefined);
      this.browser = null;
    }
  }

  /** Конвертирует один HTML-файл в PDF по указанному пути. */
  async convertFile(inputHtml: string, outputPdf: string): Promise<ConversionResult> {
    const started = Date.now();
    if (!this.browser) throw new Error('Browser не запущен (вызовите start()).');

    const page = await this.browser.newPage();
    let title: string | null = null;
    try {
      page.setDefaultNavigationTimeout(this.settings.timeoutMs);
      page.setDefaultTimeout(this.settings.timeoutMs);

      // Загружаем как file:// — относительные ресурсы (images/pic.jpg, css, шрифты)
      // подхватываются относительно расположения HTML-файла.
      const url = toFileUrl(inputHtml);
      await page.goto(url, { waitUntil: 'networkidle2', timeout: this.settings.timeoutMs });

      // Дать время динамическому JS дорисовать DOM.
      if (this.settings.waitAfterLoadMs > 0) {
        await new Promise((r) => setTimeout(r, this.settings.waitAfterLoadMs));
      }

      // Извлечь заголовок (для опции именования по заголовку / оглавления merge).
      title = await page.evaluate(() => {
        const t = (document.title || '').trim();
        if (t) return t;
        const h1 = document.querySelector('h1');
        return h1 && h1.textContent ? h1.textContent.trim() : null;
      });

      // Печатные стили / media type.
      await page.emulateMediaType(this.settings.mediaType);
      if (this.userCss) {
        await page.addStyleTag({ content: this.userCss });
      }

      await ensureDir(path.dirname(outputPdf));

      const pdfOptions: Parameters<typeof page.pdf>[0] = {
        path: outputPdf,
        margin: this.settings.margin,
        scale: this.settings.scale,
        printBackground: this.settings.printBackground,
        landscape: this.settings.landscape,
        preferCSSPageSize: this.settings.format === 'default',
        timeout: this.settings.timeoutMs,
      };
      if (this.settings.format && this.settings.format !== 'default') {
        pdfOptions.format = this.settings.format as PaperFormat;
      }

      await page.pdf(pdfOptions);

      return {
        input: inputHtml,
        output: outputPdf,
        title,
        ok: true,
        durationMs: Date.now() - started,
      };
    } catch (err: any) {
      return {
        input: inputHtml,
        output: outputPdf,
        title,
        ok: false,
        error: err?.message ? String(err.message) : String(err),
        durationMs: Date.now() - started,
      };
    } finally {
      await page.close().catch(() => undefined);
    }
  }
}
