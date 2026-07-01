import * as fsp from 'fs/promises';
import pc from 'picocolors';
import { longPath } from './util';

type Level = 'info' | 'warn' | 'error' | 'success';

export interface LogEntry {
  time: string;
  level: Level;
  message: string;
}

/**
 * Простой логгер: пишет в консоль (с цветами) и накапливает записи для
 * итогового отчёта/файла лога.
 */
export class Logger {
  private entries: LogEntry[] = [];
  private quiet: boolean;

  constructor(quiet = false) {
    this.quiet = quiet;
  }

  private push(level: Level, message: string) {
    this.entries.push({ time: new Date().toISOString(), level, message });
  }

  info(msg: string) {
    this.push('info', msg);
    if (!this.quiet) console.log(pc.cyan('[info]'), msg);
  }

  warn(msg: string) {
    this.push('warn', msg);
    if (!this.quiet) console.warn(pc.yellow('[warn]'), msg);
  }

  error(msg: string) {
    this.push('error', msg);
    if (!this.quiet) console.error(pc.red('[ERROR]'), msg);
  }

  success(msg: string) {
    this.push('success', msg);
    if (!this.quiet) console.log(pc.green('[ok]'), msg);
  }

  /** Только ошибки/предупреждения — для итогового отчёта. */
  getProblems(): LogEntry[] {
    return this.entries.filter((e) => e.level === 'error' || e.level === 'warn');
  }

  async saveTo(file: string): Promise<void> {
    const text = this.entries
      .map((e) => `${e.time} [${e.level.toUpperCase()}] ${e.message}`)
      .join('\n');
    await fsp.writeFile(longPath(file), text + '\n', 'utf8');
  }
}
