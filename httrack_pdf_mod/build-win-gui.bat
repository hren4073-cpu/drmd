@echo off
REM ======================================================================
REM  Build httrack2pdf.exe — self-contained Windows GUI application.
REM
REM  The resulting .exe has a full graphical interface (Win32 window),
REM  no console, and is fully static (no extra DLLs needed).
REM  On first launch it auto-downloads portable headless Chromium if
REM  no Chrome/Edge is found — the end user installs nothing.
REM
REM  Requirements: MinGW-w64 gcc in PATH (easiest via MSYS2:
REM    pacman -S mingw-w64-x86_64-gcc  then use "MSYS2 MinGW 64-bit" shell,
REM    or add C:\msys64\mingw64\bin to PATH).
REM ======================================================================
setlocal

where gcc >nul 2>nul
if errorlevel 1 (
  echo [error] gcc not found. Install MinGW-w64 ^(e.g. via MSYS2^) and add it to PATH.
  exit /b 1
)

gcc -O2 -std=gnu99 -Wall ^
    -DHTSPDF_STANDALONE -DHTSPDF_GUI ^
    -static -static-libgcc -s -mwindows ^
    -o httrack2pdf.exe ^
    httrack_pdf_gui.c httrack_pdf.c ^
    -lcomdlg32 -lshell32 -lole32 -lcomctl32

if errorlevel 1 (
  echo [error] build failed
  exit /b 1
)

echo.
echo [ok] built httrack2pdf.exe  (GUI version)
echo.
echo Double-click httrack2pdf.exe to open the graphical interface.
echo On first run it will auto-download headless Chromium into .\chromium\
echo (only once, ~150 MB). Use the "Advanced paths" section to point to
echo an existing Chrome/Edge installation instead.
echo.
endlocal
