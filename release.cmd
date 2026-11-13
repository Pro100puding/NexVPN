@echo off
rem Release NexVPN: build + sign + publish to GitHub.
rem Before running: bump versionName/versionCode in app\build.gradle,
rem fill in keystore.properties, push code to GitHub.
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0release.ps1"
echo.
pause
