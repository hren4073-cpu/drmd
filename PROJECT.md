# HTTrack → PDF: PC и Android

Инструменты для архивации сайтов/блогов (LiveJournal и др.) в **PDF-книгу с
оглавлением**. Проект разделён на две части — для ПК и для Android.

```
pc/                      ← версии для компьютера (Windows / Linux)
  httrack_pdf_mod/         модуль HTTrack + standalone httrack2pdf
                           (CLI и Win32-GUI), очистка HTML, экспорт в PDF,
                           склейка через Ghostscript, автозагрузка браузера

android/                 ← версии для Android
  httrack-pdf/             форк официального HTTrack Android + кнопка
                           «PDF book» (фоновый сервис: WebView→PDF + PDFBox),
                           нативные .so взяты из готового APK (без NDK)
  lj2pdf/                  отдельное приложение: вводишь URL блога LiveJournal
                           и диапазон страниц → скачивает и собирает книгу

.github/workflows/       ← CI
  build.yml                автосборка: оба Android-APK (debug), Linux-CLI,
                           Windows-GUI .exe (кросс-сборка MinGW)
```

## Сборка

| Что | Как |
|-----|-----|
| ПК, Linux CLI | `make -C pc/httrack_pdf_mod exe` → `httrack2pdf` |
| ПК, Windows GUI | `pc/httrack_pdf_mod/build-win-gui.bat` (MinGW) → `httrack2pdf.exe` |
| Android `httrack-pdf` | открыть `android/httrack-pdf` в Android Studio → Build APK |
| Android `lj2pdf` | открыть `android/lj2pdf` в Android Studio → Build APK |
| Всё сразу | пуш в репозиторий → **GitHub Action** соберёт артефакты |

Подробности — в `README.md` каждого подпроекта.

## Статус

Десктопные сборки проверены на Linux. Android-проекты собираются в Android
Studio / CI; на устройстве в этом окружении не проверялись (нет Android SDK).
GitHub Action даёт готовые артефакты для скачивания на вкладке *Actions*.
