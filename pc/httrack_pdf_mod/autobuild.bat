@echo off
REM ======================================================================
REM  autobuild.bat - one-click autobuilder + autorun for the PC tools.
REM
REM  Double-click it. The script:
REM    1) finds MinGW-w64 gcc (in PATH or a local .\mingw64\), and if none
REM       exists, DOWNLOADS a portable MinGW-w64 next to this file (~80 MB,
REM       one time) - you install nothing;
REM    2) builds both binaries:
REM         httrack2pdf.exe      - the graphical app (Win32 GUI)
REM         httrack2pdf-cli.exe  - the command-line tool
REM    3) offers to launch the GUI.
REM
REM  Everything is statically linked, so the produced .exe files need no DLLs.
REM ======================================================================
setlocal enabledelayedexpansion
cd /d "%~dp0"

set "MINGW_DIR=%~dp0mingw64"
REM Portable MinGW-w64 (WinLibs UCRT build). Bump this URL to update the toolchain.
set "MINGW_URL=https://github.com/brechtsanders/winlibs_mingw/releases/download/14.2.0posix-19.1.1-12.0.0-ucrt-r2/winlibs-x86_64-posix-seh-gcc-14.2.0-mingw-w64ucrt-12.0.0-r2.zip"
set "ZIP=%~dp0mingw.zip"
set "GCC="

REM 1) gcc already on PATH?
where gcc >nul 2>nul && set "GCC=gcc"

REM 2) previously bootstrapped local copy?
if not defined GCC if exist "%MINGW_DIR%\bin\gcc.exe" set "GCC=%MINGW_DIR%\bin\gcc.exe"

REM 3) download a portable MinGW-w64
if not defined GCC (
  echo No MinGW gcc found - downloading a portable copy ^(~80 MB, one time^)...
  powershell -NoProfile -ExecutionPolicy Bypass -Command "[Net.ServicePointManager]::SecurityProtocol=[Net.SecurityProtocolType]::Tls12; Invoke-WebRequest -Uri '%MINGW_URL%' -OutFile '%ZIP%'"
  if errorlevel 1 ( echo [error] download failed. Check your connection, or install MinGW-w64 manually and re-run. & pause & exit /b 1 )
  echo Extracting MinGW...
  powershell -NoProfile -ExecutionPolicy Bypass -Command "Expand-Archive -Force '%ZIP%' '%~dp0'"
  if errorlevel 1 ( echo [error] extraction failed. & pause & exit /b 1 )
  del "%ZIP%" >nul 2>nul
  if exist "%MINGW_DIR%\bin\gcc.exe" set "GCC=%MINGW_DIR%\bin\gcc.exe"
)

if not defined GCC ( echo [error] could not obtain a gcc compiler. & pause & exit /b 1 )
echo Using compiler: !GCC!
echo.

echo [1/2] Building httrack2pdf-cli.exe ...
"!GCC!" -O2 -std=gnu99 -Wall -DHTSPDF_STANDALONE -static -static-libgcc -s ^
    -o httrack2pdf-cli.exe httrack_pdf.c
if errorlevel 1 ( echo [error] CLI build failed. & pause & exit /b 1 )

echo [2/2] Building httrack2pdf.exe ^(GUI^) ...
"!GCC!" -O2 -std=gnu99 -Wall -DHTSPDF_STANDALONE -DHTSPDF_GUI ^
    -static -static-libgcc -s -mwindows ^
    -o httrack2pdf.exe httrack_pdf_gui.c httrack_pdf.c ^
    -lcomdlg32 -lshell32 -lole32 -lcomctl32
if errorlevel 1 ( echo [error] GUI build failed. & pause & exit /b 1 )

echo.
echo [ok] Build complete:
echo     httrack2pdf.exe       - graphical app
echo     httrack2pdf-cli.exe   - command-line tool
echo.
echo On first run the GUI auto-downloads a headless Chromium if no browser
echo is found ^(separate ~150 MB, also one time^).
echo.
choice /C YN /M "Launch the graphical app now"
if errorlevel 2 goto :end
start "" "httrack2pdf.exe"
:end
endlocal
