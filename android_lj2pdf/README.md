# LJ → PDF book (Android)

Нативное Android-приложение: скачивает блог **LiveJournal** постранично и
собирает из него **единый `book.pdf` с кликабельным оглавлением** — мобильный
аналог десктопного `httrack2pdf`.

В отличие от десктопной версии здесь **не нужны** ни Chrome, ни Ghostscript:
* рендер каждой страницы в PDF делает встроенный системный `WebView`
  (`PrintDocumentAdapter` / `PdfDocument`);
* склейку и оглавление — библиотека **PDFBox-Android** (`PDFMergerUtility`
  + `PDDocumentOutline`, кириллица в закладках поддерживается).

Всё работает офлайн после скачивания и не требует разрешений на хранилище —
результат пишется в приватную папку приложения
`Android/data/com.drmd.lj2pdf/files/<host>_book/book.pdf` и открывается через
кнопку **Open PDF** (FileProvider → системный просмотрщик).

## Как пользоваться

1. Введите URL блога, например `https://someblog.livejournal.com`.
2. Задайте диапазон страниц **From / To** и **Entries/page** (для LiveJournal
   оставьте `20`). Страница `k` = `BASE/?skip=(k-1)×20`.
3. **Start** → приложение по очереди открывает каждую страницу, печатает её в
   PDF, затем сливает всё в `book.pdf` с оглавлением «Страница N».
4. **Open PDF** — открыть готовую книгу.

## Сборка (Android Studio)

1. `File → Open…` и выберите папку `android_lj2pdf/`.
2. Дождитесь Gradle sync (нужен JDK 17 — встроен в свежие Android Studio).
3. **Build → Build APK(s)** или Run на устройстве/эмуляторе.
   APK появится в `app/build/outputs/apk/`.

> Командная строка `./gradlew assembleDebug` тоже работает, но в репозитории
> нет бинарного `gradle/wrapper/gradle-wrapper.jar`. Android Studio подставит
> свой Gradle автоматически; для CLI один раз выполните `gradle wrapper`
> (установленным Gradle 8.x), чтобы сгенерировать wrapper.

Параметры: `compileSdk 34`, `minSdk 21`, Kotlin 1.9, AGP 8.1.

## Структура

| Файл | Назначение |
|------|-----------|
| `MainActivity.kt` | UI, оркестрация: страница за страницей → merge |
| `WebViewPdfRenderer.kt` | `WebView` → PDF через нативный print-конвейер |
| `BookBuilder.kt` | слияние PDF + оглавление (PDFBox-Android) |

## Ограничения

* Печатается **полная** страница LJ (как в браузере, с комментариями) —
  HTML-очистка от рекламы из C-версии здесь пока не применяется.
* Для приватных/закрытых записей нужна авторизация — не поддерживается.
* Проверяйте `Entries/page`: если в блоге настроено иное число записей на
  страницу, подгоните его, иначе пагинация `?skip=` сдвинется.
