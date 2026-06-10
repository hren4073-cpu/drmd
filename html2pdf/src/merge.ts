import {
  PDFDocument,
  PDFName,
  PDFNumber,
  PDFArray,
  PDFDict,
  PDFHexString,
  PDFNull,
  PDFFont,
  StandardFonts,
  rgb,
} from 'pdf-lib';
import fontkit from '@pdf-lib/fontkit';
import * as fsp from 'fs/promises';
import { longPath, writeBinaryFile } from './util';
import { Logger } from './logger';

export interface MergeItem {
  pdfPath: string;
  title: string;
}

interface OutlineEntry {
  title: string;
  pageIndex: number; // 0-based в итоговом документе
}

const A4_WIDTH = 595.28;
const A4_HEIGHT = 841.89;

/** Пытается встроить шрифт с поддержкой кириллицы (Arial из Windows), иначе Helvetica. */
async function embedFont(doc: PDFDocument): Promise<{ font: PDFFont; unicode: boolean }> {
  doc.registerFontkit(fontkit);
  const candidates = [
    'C:/Windows/Fonts/arial.ttf',
    'C:/Windows/Fonts/segoeui.ttf',
    'C:/Windows/Fonts/calibri.ttf',
  ];
  for (const c of candidates) {
    try {
      const bytes = await fsp.readFile(longPath(c));
      const font = await doc.embedFont(bytes, { subset: true });
      return { font, unicode: true };
    } catch {
      /* пробуем следующий */
    }
  }
  const font = await doc.embedFont(StandardFonts.Helvetica);
  return { font, unicode: false };
}

/** Для не-Unicode шрифта заменяем неподдерживаемые символы, чтобы не падать. */
function safeText(text: string, unicode: boolean): string {
  if (unicode) return text;
  // оставляем только печатаемый ASCII/Latin-1
  return text.replace(/[^\x20-\x7E\xA0-\xFF]/g, '?');
}

function truncateToWidth(
  text: string,
  font: PDFFont,
  size: number,
  maxWidth: number
): string {
  if (font.widthOfTextAtSize(text, size) <= maxWidth) return text;
  const ell = '…';
  let t = text;
  while (t.length > 1 && font.widthOfTextAtSize(t + ell, size) > maxWidth) {
    t = t.slice(0, -1);
  }
  return t + ell;
}

/** Создаёт PDF-закладки (outline) для навигации по объединённому документу. */
function addOutline(doc: PDFDocument, entries: OutlineEntry[]): void {
  if (entries.length === 0) return;
  const context = doc.context;
  const pages = doc.getPages();

  const outlinesRef = context.nextRef();
  const itemRefs = entries.map(() => context.nextRef());

  entries.forEach((entry, i) => {
    const pageRef = pages[entry.pageIndex].ref;
    const dest = PDFArray.withContext(context);
    dest.push(pageRef);
    dest.push(PDFName.of('XYZ'));
    dest.push(PDFNull);
    dest.push(PDFNull);
    dest.push(PDFNull);

    const map = new Map<PDFName, any>();
    map.set(PDFName.of('Title'), PDFHexString.fromText(entry.title));
    map.set(PDFName.of('Parent'), outlinesRef);
    if (i > 0) map.set(PDFName.of('Prev'), itemRefs[i - 1]);
    if (i < entries.length - 1) map.set(PDFName.of('Next'), itemRefs[i + 1]);
    map.set(PDFName.of('Dest'), dest);

    context.assign(itemRefs[i], PDFDict.fromMapWithContext(map, context));
  });

  const outlinesMap = new Map<PDFName, any>();
  outlinesMap.set(PDFName.of('Type'), PDFName.of('Outlines'));
  outlinesMap.set(PDFName.of('First'), itemRefs[0]);
  outlinesMap.set(PDFName.of('Last'), itemRefs[itemRefs.length - 1]);
  outlinesMap.set(PDFName.of('Count'), PDFNumber.of(entries.length));
  context.assign(outlinesRef, PDFDict.fromMapWithContext(outlinesMap, context));

  doc.catalog.set(PDFName.of('Outlines'), outlinesRef);
}

/**
 * Объединяет список PDF в один документ с оглавлением (TOC) и закладками.
 * Возвращает байты итогового PDF.
 */
export async function mergePdfs(
  items: MergeItem[],
  logger: Logger
): Promise<Uint8Array> {
  const finalDoc = await PDFDocument.create();
  const { font, unicode } = await embedFont(finalDoc);

  // 1. Узнаём число страниц каждого исходного PDF, чтобы посчитать нумерацию.
  const sources: { doc: PDFDocument; pageCount: number; title: string }[] = [];
  for (const item of items) {
    try {
      const bytes = await fsp.readFile(longPath(item.pdfPath));
      const doc = await PDFDocument.load(bytes, { ignoreEncryption: true });
      sources.push({ doc, pageCount: doc.getPageCount(), title: item.title });
    } catch (e: any) {
      logger.warn(`Пропущен при объединении (не удалось прочитать): ${item.pdfPath} — ${e?.message}`);
    }
  }

  // 2. Готовим строки оглавления и считаем сколько страниц займёт TOC.
  const titleSize = 20;
  const lineSize = 11;
  const lineHeight = 20;
  const marginX = 50;
  const topY = A4_HEIGHT - 60;
  const bottomY = 60;
  const linesPerPage = Math.floor((topY - 40 - bottomY) / lineHeight);

  const tocPageCount = Math.max(1, Math.ceil(sources.length / linesPerPage));

  // 3. Стартовый индекс каждого документа в итоговом файле (с учётом страниц TOC).
  let cursor = tocPageCount;
  const outline: OutlineEntry[] = [];
  const startIndexes: number[] = [];
  for (const s of sources) {
    startIndexes.push(cursor);
    outline.push({ title: s.title, pageIndex: cursor });
    cursor += s.pageCount;
  }

  // 4. Рисуем страницы оглавления.
  const tocPages = [];
  for (let i = 0; i < tocPageCount; i++) {
    tocPages.push(finalDoc.addPage([A4_WIDTH, A4_HEIGHT]));
  }
  tocPages[0].drawText(safeText('Оглавление', unicode), {
    x: marginX,
    y: A4_HEIGHT - 45,
    size: titleSize,
    font,
    color: rgb(0, 0, 0),
  });

  let entryIdx = 0;
  for (let p = 0; p < tocPageCount; p++) {
    const page = tocPages[p];
    let y = p === 0 ? topY - 30 : topY;
    for (; entryIdx < sources.length; entryIdx++) {
      if (y < bottomY) break; // переходим на следующую страницу TOC
      const num = startIndexes[entryIdx] + 1; // 1-based номер страницы
      const numStr = String(num);
      const numWidth = font.widthOfTextAtSize(numStr, lineSize);
      const titleMax = A4_WIDTH - marginX * 2 - numWidth - 20;
      const titleText = truncateToWidth(
        safeText(`${entryIdx + 1}. ${sources[entryIdx].title}`, unicode),
        font,
        lineSize,
        titleMax
      );
      page.drawText(titleText, { x: marginX, y, size: lineSize, font, color: rgb(0.1, 0.1, 0.1) });
      page.drawText(numStr, {
        x: A4_WIDTH - marginX - numWidth,
        y,
        size: lineSize,
        font,
        color: rgb(0.1, 0.1, 0.1),
      });
      y -= lineHeight;
    }
  }

  // 5. Копируем страницы всех исходных PDF по порядку.
  for (const s of sources) {
    const copied = await finalDoc.copyPages(s.doc, s.doc.getPageIndices());
    for (const pg of copied) finalDoc.addPage(pg);
  }

  // 6. Закладки.
  addOutline(finalDoc, outline);

  finalDoc.setTitle('Объединённый документ');
  finalDoc.setProducer('html2pdf-cli');

  return finalDoc.save();
}

export async function saveMerged(bytes: Uint8Array, output: string): Promise<void> {
  await writeBinaryFile(output, bytes);
}
