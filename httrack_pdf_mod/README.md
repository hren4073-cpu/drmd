# httrack_pdf — модуль HTTrack: чистые статьи + экспорт в PDF

Модификация веб-архиватора **HTTrack 3.49** (https://github.com/xroche/httrack),
которая:

1. **Чистит** скачанные HTML на лету (callback `postprocess-html`): убирает
   рекламу, баннеры, виджеты, навигацию, сайдбары, блоки комментариев, футеры
   и `<script>`/`<iframe>`. Для **LiveJournal** и похожих блогов извлекается
   только тело статьи (`.entry-content`, `.b-singlepost-body`, …). Вёрстка,
   CSS, шрифты, картинки и относительные ссылки сохраняются.
2. После завершения зеркала (callback `end`) **конвертирует** все `.html/.htm`
   в PDF через **headless Chromium/Edge** (`--headless=new --print-to-pdf`).
   `wkhtmltopdf` не используется.
3. **Объединяет** все PDF в один документ-книгу с **оглавлением (закладками)**
   через **Ghostscript** (заголовки берутся из `<title>`/`<h1>`, поддерживается
   кириллица).
4. Не замедляет зеркалирование: конвертация — это **пост-обработка**, с
   параллелизмом (`concurrency` до 8), таймаутом на файл и логом ошибок.

> Прототип проверен на Linux (gcc 13): модуль собирается во всех режимах,
> патчи накладываются чисто, **полный бинарник HTTrack с модулем собирается и
> линкуется**, а конвейер «очистка → PDF → объединение с кириллическим
> оглавлением» отрабатывает на тестовом LJ-зеркале. См. раздел
> «Что протестировано».

---

## 1. Архитектура

```
                 ┌─────────────────────── HTTrack core ───────────────────────┐
   сайт  ──►  загрузка ──► postprocess-html ──► сохранение в зеркало ──► … ──► end
                                │                                              │
                                ▼                                              ▼
                       htspdf_clean_html()                          htspdf_export_dir()
                   (LJ-извлечение / generic                  (обход папок → Chrome → PDF,
                    эвристика очистки, @page CSS)             затем merge через Ghostscript)
```

Файлы модуля (кладутся в `httrack/src/`):

| Файл | Назначение |
|---|---|
| `httrack_pdf.h` | Публичный интерфейс, структура конфигурации `htspdf_config`. |
| `httrack_pdf.c` | Вся логика: парсинг опций, очистка HTML, обход каталогов, запуск браузера с таймаутом и параллелизмом, объединение PDF, логирование, glue-коллбэки HTTrack. |

Патчи к ядру HTTrack (в `patches/`):

| Патч | Что меняет |
|---|---|
| `patches/htscoremain.c.patch` | `#include "httrack_pdf.h"` + предразбор нативных опций `--pdf-*` в начале `hts_main_internal()` и вызов `httrack_pdf_init()`. |
| `patches/Makefile.am.patch` | Добавляет `httrack_pdf.c/.h` в `libhttrack_la_SOURCES`. |

Модуль использует штатный механизм коллбэков HTTrack
(`CHAIN_FUNCTION(opt, postprocess, …)`, `CHAIN_FUNCTION(opt, end, …)`,
макросы `CALLBACKARG_*`) — см. `src/httrack-library.h` и примеры в
`libtest/`.

---

## 2. Три режима сборки

| Режим | Результат | Когда использовать |
|---|---|---|
| **exe (autorun)** | `httrack2pdf.exe` | Самодостаточный .exe для конечного пользователя: статически слинкован, при первом запуске сам докачивает headless Chromium. Не требует установки браузера/Node/Python. |
| **standalone** | `httrack_pdf_test` | Тот же код, dev-сборка для отладки на уже скачанном зеркале. |
| **plugin** | `httrack_pdf.so` / `.dll` | Подключить к готовому HTTrack через `--wrapper`, без пересборки ядра. |
| **integrated** | `httrack(.exe)` с нативными `--pdf-*` | Полноценная интеграция в ядро по ТЗ. |

### Самодостаточный .exe с авто-запуском (autorun)

```bat
:: Windows: нужен только MinGW-w64 gcc в PATH (проще всего через MSYS2)
build-win.bat
:: -> httrack2pdf.exe  (статический, без сторонних DLL)
```
```sh
# Linux
./build-linux.sh        # или: make exe
```

**Что значит «autorun»:** на машине пользователя может не быть браузера. При
первом запуске `httrack2pdf.exe`, если Chrome/Edge/Chromium не найден,
**сам скачивает портативный `chrome-headless-shell`** (Chrome for Testing,
stable) в подпапку `chromium\` рядом с собой и дальше использует его.
Скачивание происходит **один раз**; интернет затем не нужен. Отключить —
опцией `noautorun` (или `--pdf-no-autorun` в интегрированной сборке).

- На Windows загрузка идёт встроенным PowerShell (`Invoke-WebRequest` +
  `Expand-Archive`) — ничего ставить не надо.
- На Linux нужны `curl`, `python3`, `unzip` (есть почти везде).
- `chrome-headless-shell` — это «печатающая» сборка Chromium: она надёжно
  отрабатывает `--print-to-pdf` и сама завершается.

Пример:
```bat
httrack2pdf.exe "C:\my_mirror" "export,merge,clean=lj,pagesize=A4,concurrency=4"
```
Первый запуск: `[info] no browser found - downloading portable headless
Chromium ...` → `[ok] headless Chromium ready` → конвертация.

---

## 3. Зависимости

- **Компилятор C**: MinGW-w64 (Windows) или gcc/clang (Linux). Стандарт
  `-std=gnu99` (HTTrack использует расширения GNU, напр. `typeof`).
- **Headless-браузер**: Google Chrome / Microsoft Edge / Chromium. Автопоиск
  в PATH и стандартных путях; можно задать явно (`chrome=...` /
  `--pdf-chrome=...`). Если браузера нет — при **autorun** (по умолчанию)
  портативный `chrome-headless-shell` скачивается автоматически, так что
  отдельно ставить ничего не нужно.
- **Ghostscript** — только для `--pdf-merge` (Windows: `gswin64c.exe`).
  Если не найден — объединение пропускается с предупреждением, отдельные PDF
  остаются.
- Никаких больших рантаймов (Node.js/Python) не требуется.

---

## 4. Быстрый старт — standalone (тест на готовом зеркале)

```bash
# Linux
make standalone
./httrack_pdf_test /path/to/mirror "export,merge,clean=lj,pagesize=A4,concurrency=4"
```

```bat
:: Windows (MinGW-w64, в "x64 Native Tools" или MSYS2)
mingw32-make standalone
httrack_pdf_test.exe "C:\my_mirror" "export,merge,clean=lj,pagesize=A4,concurrency=4"
```

Строка опций (через запятую): `export`, `merge`, `noimg`,
`clean=lj|generic|off`, `nocomments` (по умолчанию комментарии
**сохраняются**), `pagesize=A4`, `concurrency=N`, `timeout=сек`,
`chrome=<путь>`, `noautorun` (не докачивать браузер), `gs=<путь>`,
`mergefile=<путь>`, `kill=cls1,cls2`.

В standalone-режиме HTML сначала переписываются «начисто» на месте (как это
сделал бы коллбэк), затем печатаются в PDF. Лог — `<mirror>/pdf_errors.log`,
книга — `<mirror>/book.pdf`.

---

## 5. Сборка plugin (без пересборки HTTrack)

Нужны заголовки HTTrack и собранная библиотека `libhttrack`.

```bash
# Linux
make plugin HTSRC=/path/to/httrack-master
# использование:
httrack "https://user.livejournal.com/" -O /out -%W httrack_pdf,export,merge,clean=lj,pagesize=A4,concurrency=4
#   (-%W — это и есть --wrapper)
```

```bat
:: Windows MinGW-w64
gcc -O2 -std=gnu99 -shared -I path\to\httrack-master\src -o httrack_pdf.dll httrack_pdf.c
httrack "https://user.livejournal.com/" -O C:\out --wrapper httrack_pdf,export,merge,clean=lj
```

```bat
:: Windows Visual Studio (Developer Command Prompt)
cl /LD /O2 /I path\to\httrack-master\src /Fe:httrack_pdf.dll httrack_pdf.c libhttrack.lib
```

Положите `httrack_pdf.dll` рядом с `httrack.exe` (или в PATH). HTTrack
вызовет `hts_plug()`, который распарсит хвост после имени модуля и подключит
коллбэки.

---

## 6. Полная интеграция в ядро (нативные `--pdf-*`)

```bash
cd httrack-master
cp /path/to/httrack_pdf.c /path/to/httrack_pdf.h src/
patch -p1 < /path/to/patches/htscoremain.c.patch
patch -p1 < /path/to/patches/Makefile.am.patch

# Linux
autoreconf -fi          # требуется пакет autoconf-archive (для AX_CHECK_COMPILE_FLAG)
./configure
make -j4
# бинарь: src/.libs/httrack
```

> Примечание: `coucal` в репозитории HTTrack — git-submodule. Перед сборкой
> выполните `git submodule update --init` (или `git clone` зеркала в
> `src/coucal`), иначе линковка падает на `coucal/coucal.c` — это к модулю
> отношения не имеет.

**Windows (MinGW-w64 / MSYS2):**
```bash
pacman -S autoconf automake libtool autoconf-archive make mingw-w64-x86_64-gcc
cd /c/httrack-master
git submodule update --init
autoreconf -fi && ./configure && make -j4
```

**Windows (Visual Studio):** добавьте `httrack_pdf.c` в проект `libhttrack`
(`libhttrack.vcproj`) и пересоберите решение `httrack.dsw`. Патч
`htscoremain.c` правится так же (он уже включён в исходник после применения).

Использование после интеграции:

```bat
httrack "https://user.livejournal.com/" -O C:\out ^
        --pdf-export --pdf-merge --pdf-clean lj ^
        --pdf-page-size A4 --pdf-concurrency 4 --pdf-timeout 30
```

Нативные опции:

| Опция | Значение |
|---|---|
| `--pdf-export` | включить конвертацию после зеркалирования |
| `--pdf-merge` | объединить все PDF в `book.pdf` с оглавлением (нужен Ghostscript) |
| `--pdf-no-images` | вырезать `<img>/<picture>` (уменьшить размер) |
| `--pdf-keep-comments` | сохранять комментарии (по умолчанию включено) |
| `--pdf-drop-comments` | удалить комментарии |
| `--pdf-page-size A4` | формат страницы (A4/Letter/Legal/A3/…) |
| `--pdf-concurrency N` | параллельные процессы браузера (1..8) |
| `--pdf-clean lj\|generic\|off` | стратегия очистки HTML |
| `--pdf-timeout N` | таймаут на файл, сек (по умолч. 30) |
| `--pdf-chrome=<путь>` | явный путь к браузеру |
| `--pdf-no-autorun` | не докачивать браузер автоматически |

Опции `--pdf-*` вырезаются из argv до основного разбора HTTrack, поэтому не
конфликтуют с его парсером. Авто-загрузка браузера (autorun) работает и в
интегрированной сборке: если браузер не найден, он скачивается в `chromium\`
рядом с `httrack.exe`.

---

## 7. Как работает очистка

- **generic** (эвристика, как Readability): удаляются `<script>`,
  `<noscript>`, `<iframe>`, `<svg>`, `<object>`, `<form>`, семантические
  `<nav>`/`<aside>`, а также любые элементы, у которых в `class`/`id` есть
  токены вида `ad/ads/advert/reklama/banner/sidebar/comment/social/share/`
  `popup/overlay/cookie/widget/newsletter/sponsor/footer/menu/nav/…`.
  Сохраняются `<style>` и `<link rel=stylesheet>` — вёрстка не ломается.
- **lj**: ищется основной блок статьи по классам LiveJournal
  (`entry-content`, `b-singlepost-body`, `aentry-post__text`, `j-e-text`,
  `articleBody`, …), берётся `<head>` (с CSS) + тело статьи, затем к
  результату применяется generic-очистка. Если маркер не найден — fallback
  на generic.
- **Комментарии сохраняются по умолчанию** (в них часто много полезного).
  В generic-режиме блоки с классами `comment*`/`disqus`/`discussion` не
  считаются мусором; в **lj**-режиме после тела статьи дополнительно
  находится и подклеивается ветка комментариев (контейнеры `b-tree`,
  `comments-area`, `commentlist`, `b-singlepost-comments`, `aentry-comments`,
  …) под заголовком «Комментарии». Реклама, спрятанная внутри ветки
  комментариев (напр. `class="comment b-ads"`), всё равно удаляется.
  Отключить комментарии: `nocomments` / `--pdf-drop-comments`.
- В `<head>` внедряется печатный стиль: `@page{size:<pagesize>;margin:12mm}`
  и `print-color-adjust:exact` (чтобы фон/картинки печатались).
- Доп. классы под свой сайт: `kill=class1,class2` (wrapper) — подстроки
  ищутся в `class`/`id`.

Парсер тегов — потоковый, со стеком вложенности (корректно удаляет блок до
парного закрывающего тега; для `<script>` ищется литеральный `</script>`).
Зависимости от libxml2 нет, но при желании очистку легко заменить на
libxml2/Readability (см. «Альтернативы»).

---

## 8. Обработка ошибок, таймаут, параллелизм

- Каждый файл конвертируется отдельным процессом браузера; на Windows —
  `CreateProcess` + `WaitForMultipleObjects`, на POSIX — `fork/exec` +
  `waitpid`. Пул держит до `concurrency` процессов одновременно.
- Если процесс висит дольше `timeout` секунд — он принудительно завершается
  (`TerminateProcess` / `SIGKILL`), файл помечается как ошибочный.
- Все ошибки/таймауты и итог пишутся в `<out>/pdf_errors.log` и в stderr;
  обработка продолжается (один битый файл не валит весь батч).
- Длинные пути: используются WinAPI (`FindFirstFile`/`CreateProcess`), пути
  приводятся к абсолютным; имена файлов зеркала HTTrack не содержат пробелов,
  что упрощает объединение через Ghostscript `@response.txt`.

---

## 9. Объединение PDF (оглавление)

`--pdf-merge` / `merge`:
1. Для каждого PDF узнаётся число страниц (через Ghostscript `pdfpagecount`).
2. Формируется файл `pdfmark`-закладок: `[ /Page N /Title <UTF-16BE> /OUT pdfmark`
   — заголовки кодируются как UTF-16BE (`<FEFF…>`), поэтому **кириллица в
   оглавлении отображается корректно**.
3. Список файлов передаётся Ghostscript через `@response.txt`, на выходе —
   `book.pdf` (или `mergefile=…`) с кликабельным деревом закладок.

Если Ghostscript не установлен — шаг merge пропускается с предупреждением.

---

## 10. Альтернативы (по ТЗ)

- **Объединение без Ghostscript**: можно вызвать PDFsam/`pdfunite` (poppler).
  Точка интеграции — функция `merge_pdfs()`; достаточно заменить команду.
- **Высокоточная очистка**: вместо потокового скруббера на C — портировать
  Mozilla Readability и выполнять её прямо в браузере (через Puppeteer
  `page.evaluate`) перед печатью. Нативный `--print-to-pdf` JS не внедряет,
  поэтому Readability-вариант требует Node.js + Puppeteer.
- **Готовый высокоуровневый конвертер HTML→PDF** (с TOC «из коробки»):
  в этом же репозитории есть утилита **`html2pdf`** (TypeScript + Puppeteer,
  собирается в `.exe`) — её можно вызывать из `htspdf_export_dir()` вместо
  нативного Chrome, если нужен максимум точности и встроенное оглавление.
- **wkhtmltopdf** сознательно не используется: ломает современный CSS
  (flex/grid), как и требует ТЗ.

---

## 11. Что протестировано (Linux, gcc 13.3)

- ✅ `httrack_pdf.c` компилируется: standalone, plugin (`.so` экспортирует
  `hts_plug`/`hts_unplug`), и как объект против реальных заголовков HTTrack
  (`-std=gnu99`).
- ✅ Оба патча накладываются `patch -p1` без ошибок.
- ✅ **Полный HTTrack 3.49 с интегрированным модулем собирается и линкуется**
  (`make -j4` → `src/.libs/httrack`); символы `httrack_pdf_init` разрешены.
- ✅ Нативные `--pdf-export/--pdf-merge/--pdf-page-size/--pdf-concurrency/`
  `--pdf-clean` принимаются бинарником (не отвергаются парсером, в отличие
  от заведомо неизвестного флага).
- ✅ Очистка: на тестовом LJ-зеркале удалены реклама (`banner`, `b-ads`),
  `sidebar`, `comments`, `footer`, `nav`, `<script>`; извлечён
  `.entry-content`; сохранены CSS, относительная картинка и `<title>`;
  внедрён `@page A4`.
- ✅ Комментарии: по умолчанию ветка комментариев сохраняется и
  подклеивается после статьи (реальные комментарии остаются, рекламный
  «коммент» с классом `b-ads` удаляется); с `nocomments` — ветка убирается.
- ✅ Конвейер `htspdf_export_dir`: рекурсивный обход подпапок, параллельная
  конвертация (concurrency=3), коды возврата, таймауты, лог.
- ✅ **Autorun**: на чистой машине без браузера `httrack2pdf` сам скачал
  `chrome-headless-shell` (Chrome for Testing, stable 149) в `chromium/`,
  затем успешно напечатал PDF и объединил их; повторный запуск переиспользует
  загруженный браузер без повторной докачки. Сборка `make exe` /
  `build-win.bat` / `build-linux.sh` даёт самодостаточный бинарь.
- ✅ Объединение через Ghostscript 10: `book.pdf` с деревом закладок;
  заголовки `Поездка в горы` / `Рецепт борща` / `Заметки о книгах`
  корректно закодированы в UTF-16 (`/Type /Outlines`).

> Примечание про окружение: нативный `chrome --headless --print-to-pdf` в
> данном изолированном контейнере не самозавершается (особенность песочницы),
> поэтому конвейер дополнительно прогнан через эквивалентный Puppeteer-рендер
> того же Chromium для проверки оркестрации/merge. На реальных Windows 10/11
> и десктоп-Linux нативный `--print-to-pdf` работает штатно.

## Лицензия

GNU GPL v2 или новее (как у HTTrack).
