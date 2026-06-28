@echo off
REM ======================================================================
REM  LiveJournal-to-PDF.bat  -  one-click autorun.
REM
REM  Double-click this file. It:
REM    1) builds httrack2pdf.exe (the graphical app) if it isn't there yet,
REM    2) launches the graphical app.
REM
REM  In the app: tick "Download a blog and build a book", paste the blog
REM  URL (e.g. https://someblog.livejournal.com), set the page range, and
REM  press Start. It downloads every page, turns each into a PDF and merges
REM  them into one book.pdf with a clickable table of contents.
REM
REM  Building needs MinGW-w64 gcc in PATH (e.g. MSYS2:
REM    pacman -S mingw-w64-x86_64-gcc, then add C:\msys64\mingw64\bin to PATH).
REM  If httrack2pdf.exe already exists next to this script, no compiler is
REM  needed - it just starts.
REM ======================================================================
setlocal
cd /d "%~dp0"

if exist "httrack2pdf.exe" goto run

echo httrack2pdf.exe not found - building it now...
echo.

where gcc >nul 2>nul
if errorlevel 1 (
  echo [error] No httrack2pdf.exe and no gcc compiler found.
  echo.
  echo   Either: install MinGW-w64 ^(e.g. via MSYS2^) and add it to PATH,
  echo   or:     drop a prebuilt httrack2pdf.exe next to this script.
  echo.
  pause
  exit /b 1
)

gcc -O2 -std=gnu99 -Wall ^
    -DHTSPDF_STANDALONE -DHTSPDF_GUI ^
    -static -static-libgcc -s -mwindows ^
    -o httrack2pdf.exe ^
    httrack_pdf_gui.c httrack_pdf.c ^
    -lcomdlg32 -lshell32 -lole32 -lcomctl32

if errorlevel 1 (
  echo.
  echo [error] build failed.
  pause
  exit /b 1
)

echo [ok] built httrack2pdf.exe
echo.

:run
start "" "httrack2pdf.exe"
endlocal
