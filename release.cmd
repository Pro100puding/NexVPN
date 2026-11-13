@echo off
rem Релиз NexVPN: сборка + подпись + публикация на GitHub.
rem Перед запуском: подними versionName/versionCode в app\build.gradle,
rem заполни keystore.properties, запуши код в GitHub.
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0release.ps1"
echo.
pause
