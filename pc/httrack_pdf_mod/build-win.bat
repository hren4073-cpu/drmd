@echo off
REM ======================================================================
REM  Build httrack2pdf.exe - a self-contained Windows executable.
REM
REM  The resulting .exe is fully static (no extra DLLs) and "autorun":
REM  on first launch, if no Chrome/Edge is found, it automatically
REM  downloads a portable headless Chromium next to itself - the end user
REM  installs nothing.
REM
REM  Requirements: MinGW-w64 gcc in PATH (easiest via MSYS2:
REM    pacman -S mingw-w64-x86_64-gcc   then use the "MSYS2 MinGW 64-bit" shell,
REM    or add C:\msys64\mingw64\bin to PATH).
REM ======================================================================
setlocal
where gcc >nul 2>nul
if errorlevel 1 (
  echo [error] gcc not found. Install MinGW-w64 ^(e.g. via MSYS2^) and add it to PATH.
  exit /b 1
)

gcc -O2 -std=gnu99 -Wall -DHTSPDF_STANDALONE -static -static-libgcc -s ^
    -o httrack2pdf.exe httrack_pdf.c
if errorlevel 1 (
  echo [error] build failed
  exit /b 1
)

echo.
echo [ok] built httrack2pdf.exe
echo.
echo Example:
echo   httrack2pdf.exe "C:\my_mirror" "export,merge,clean=lj,pagesize=A4,concurrency=4"
echo.
echo On first run it will auto-download headless Chromium into .\chromium\
echo (only once). Use "noautorun" to disable that.
endlocal
