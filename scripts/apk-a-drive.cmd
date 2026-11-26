@echo off
rem Doble clic: compila y deja el APK en Google Drive. Ver apk-a-drive.ps1.
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0apk-a-drive.ps1" -Build %*
pause
