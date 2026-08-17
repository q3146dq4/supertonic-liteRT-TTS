@echo off
setlocal
cd /d "%~dp0"
call "%~dp0BUILD_ALL.bat"
exit /b %errorlevel%
