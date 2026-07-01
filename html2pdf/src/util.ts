import * as fs from 'fs';
import * as fsp from 'fs/promises';
import * as path from 'path';
import { pathToFileURL } from 'url';

/**
 * Поддержка длинных путей (> 260 символов) на Windows.
 * Node.js корректно работает с длинными путями только если путь абсолютный
 * и имеет префикс \\?\ (или \\?\UNC\ для сетевых путей).
 */
export function longPath(p: string): string {
  if (process.platform !== 'win32') return p;
  // Уже с префиксом
  if (p.startsWith('\\\\?\\')) return p;
  const abs = path.resolve(p);
  // UNC-путь \\server\share -> \\?\UNC\server\share
  if (abs.startsWith('\\\\')) {
    return '\\\\?\\UNC\\' + abs.slice(2);
  }
  return '\\\\?\\' + abs;
}

/** file:// URL для загрузки локального HTML в браузере (корректно кодирует пробелы/кириллицу). */
export function toFileUrl(p: string): string {
  return pathToFileURL(path.resolve(p)).href;
}

/** Рекурсивный обход директории, возврат всех путей с заданными расширениями. */
export async function walkFiles(
  dir: string,
  extensions: string[]
): Promise<string[]> {
  const exts = extensions.map((e) => e.toLowerCase());
  const out: string[] = [];

  async function recurse(current: string): Promise<void> {
    let entries: fs.Dirent[];
    try {
      entries = await fsp.readdir(longPath(current), { withFileTypes: true });
    } catch {
      return; // нет доступа — пропускаем
    }
    for (const entry of entries) {
      const full = path.join(current, entry.name);
      if (entry.isDirectory()) {
        await recurse(full);
      } else if (entry.isFile()) {
        const ext = path.extname(entry.name).toLowerCase();
        if (exts.includes(ext)) out.push(full);
      }
    }
  }

  await recurse(dir);
  out.sort((a, b) => a.localeCompare(b));
  return out;
}

export async function ensureDir(dir: string): Promise<void> {
  await fsp.mkdir(longPath(dir), { recursive: true });
}

export async function pathExists(p: string): Promise<boolean> {
  try {
    await fsp.access(longPath(p));
    return true;
  } catch {
    return false;
  }
}

export async function readTextFile(p: string): Promise<string> {
  return fsp.readFile(longPath(p), 'utf8');
}

export async function writeBinaryFile(p: string, data: Uint8Array): Promise<void> {
  await fsp.writeFile(longPath(p), data);
}

/** Очистка строки от символов, недопустимых в именах файлов Windows. */
export function sanitizeFileName(name: string): string {
  return name
    .replace(/[<>:"/\\|?*\x00-\x1F]/g, '_')
    .replace(/[. ]+$/g, '') // нельзя заканчивать точкой/пробелом
    .trim()
    .slice(0, 180) || 'untitled';
}

/** Человекочитаемая длительность. */
export function formatDuration(ms: number): string {
  const sec = Math.floor(ms / 1000) % 60;
  const min = Math.floor(ms / 60000) % 60;
  const hr = Math.floor(ms / 3600000);
  const parts: string[] = [];
  if (hr) parts.push(`${hr}ч`);
  if (hr || min) parts.push(`${min}м`);
  parts.push(`${sec}с`);
  return parts.join(' ');
}
