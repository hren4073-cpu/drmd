# HTTrack Android + PDF book (модификация)

Это **официальное приложение HTTrack для Android** (`com.httrack.android`,
исходники `xroche/httrack-android`), в которое добавлена кнопка
**«PDF book»**: после того как HTTrack скачал сайт/блог, одним нажатием все
сохранённые страницы превращаются в единый **`book.pdf` с кликабельным
оглавлением**.

Скачивание делает сам HTTrack (как и раньше — глубина, фильтры, диапазоны,
LiveJournal и т.д.). Новый код отвечает только за конвертацию уже скачанного
зеркала в книгу.

## Что именно изменено относительно оригинала

| Файл | Изменение |
|------|-----------|
| `app/src/main/java/.../PdfExportService.java` | **новый** — **фоновый** foreground-сервис: гонит конвейер и показывает прогресс в уведомлении; по готовности уведомление открывает книгу |
| `app/src/main/java/.../PdfBookEngine.java` | **новый** — сам конвейер (рендер каждой HTML-страницы в PDF через `WebView` + `PrintDocumentAdapter`), без привязки к UI |
| `app/src/main/java/.../BookBuilder.java` | **новый** — слияние PDF + оглавление/закладки через PDFBox-Android (кириллица ок) |
| `HTTrackActivity.java` | + метод `onMakePdf()` (запускает сервис); импорты `android.support.v4.*` → `androidx.*` |
| `activity_mirror_finished.xml` | + кнопка «PDF book» на экране завершения |
| `AndroidManifest.xml` | регистрация `PdfExportService` + `FOREGROUND_SERVICE`; `FileProvider` → androidx; `requestLegacyExternalStorage` |
| `strings.xml` | + строка `make_pdf_book` |
| build-файлы | jcenter→google()/mavenCentral(), AGP 2.3→7.4, Gradle 3.3→7.5, AndroidX, + зависимость PDFBox |

**Без Chrome и Ghostscript** — используется только то, что есть в самом Android
(`WebView` умеет печатать в PDF, PDFBox-Android склеивает и делает оглавление).

## Нативная часть — без NDK

Оригинальный проект собирает `libhttrack.so` из git-submodule (нужен NDK и
исходники httrack). Чтобы упростить сборку, **готовые `.so` извлечены из вашего
APK** и лежат в `app/src/main/jniLibs/armeabi-v7a/`:
`libiconv.so, libhttrack.so, libhtsjava.so, libhtslibjni.so` — ровно те имена,
которые грузит `HTTrackLib.java`. Поэтому NDK для сборки **не требуется**, и
`externalNativeBuild` убран.

> ⚠️ Только архитектура **armeabi-v7a** (как в исходном APK). На arm64-устройстве
> приложение запустится в 32-битном режиме совместимости. Если нужен arm64 —
> придётся собирать нативную часть из исходников httrack отдельно.

## Сборка (Android Studio)

1. `File → Open…` → выберите папку `httrack-android-pdf/`.
2. Дождитесь Gradle sync (нужен JDK 17 — встроен в свежие Android Studio).
3. **Build → Build Bundle(s)/APK(s) → Build APK(s)** или Run на устройстве.
   APK будет в `app/build/outputs/apk/`.

> В репозитории нет бинарного `gradle/wrapper/gradle-wrapper.jar` — Android
> Studio подставит свой Gradle сам. Для CLI один раз выполните
> `gradle wrapper` (Gradle 7.x), затем `./gradlew assembleDebug`.

## Как пользоваться

1. Создайте проект и скачайте сайт/блог как обычно в HTTrack.
2. На экране завершения нажмите **«PDF book»**.
3. Конвертация идёт **в фоне**: появляется уведомление с прогрессом, приложение
   можно свернуть и пользоваться телефоном дальше.
4. По готовности уведомление меняется на «PDF book ready — tap to open»;
   тап открывает книгу (через FileProvider).
5. Готовый файл: `book.pdf` в папке проекта
   (`…/HTTrack/<проект>/book.pdf`), временные постраничные PDF удаляются.

## Ограничения и заметки

* **Фоновый сервис.** Конвертация выполняется в `PdfExportService`
  (foreground service + уведомление), поэтому переживает сворачивание приложения.
  WebView в сервисе работает офскрин; на отдельных прошивках офскрин-рендер может
  капризничать — проверьте на своём устройстве.
* Печатается **полная** сохранённая страница (вёрстка/CSS/картинки берутся из
  локального зеркала; комментарии сохраняются — ничего не вырезается).
* Заголовок закладки = `<title>` страницы, иначе первый `<h1>`, иначе имя файла.
* Предохранитель: не более `MAX_PAGES = 500` страниц за раз (правится в
  `PdfBookEngine`).
* `targetSdk` намеренно оставлен **28**, чтобы не сломать запись зеркал в
  `/sdcard/HTTrack` из-за scoped storage.
* **Не проверено сборкой/на устройстве** в этом окружении (нет Android SDK):
  код написан под стандартный приём «WebView → PrintDocumentAdapter → файл».
  Проверьте на реальном устройстве; при необходимости подправьте задержку
  `SETTLE_MS` в `PdfBookEngine` для тяжёлых страниц.
* Сборку APK можно не делать вручную — её собирает **GitHub Action**
  (`.github/workflows/build.yml`), артефакт `httrack-pdf-debug-apk`.

## Лицензия

Производное от HTTrack (GNU GPL v3). Добавленный код — на тех же условиях.
