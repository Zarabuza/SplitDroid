@echo off
chcp 65001 >nul
cd /d "%~dp0"

set PORT=8765
set URL=http://127.0.0.1:%PORT%/web/

echo.
echo  Раздельный туннель — локальный лендинг
echo  --------------------------------------
echo  Откроется: %URL%
echo  Остановка: Ctrl+C в этом окне
echo.

if exist "SplitDroid-debug.apk" (
  if not exist "web\downloads" mkdir "web\downloads"
  copy /Y "SplitDroid-debug.apk" "web\downloads\SplitDroid-debug.apk" >nul
)
if exist "%USERPROFILE%\Desktop\SplitDroid-debug.apk" (
  if not exist "web\downloads" mkdir "web\downloads"
  copy /Y "%USERPROFILE%\Desktop\SplitDroid-debug.apk" "web\downloads\SplitDroid-debug.apk" >nul
)

where py >nul 2>&1
if %ERRORLEVEL%==0 (
  start "" "%URL%"
  py -m http.server %PORT%
  goto :eof
)

where python >nul 2>&1
if %ERRORLEVEL%==0 (
  start "" "%URL%"
  python -m http.server %PORT%
  goto :eof
)

echo [!] Python не найден. Открываю index.html напрямую.
start "" "%~dp0web\index.html"
pause
