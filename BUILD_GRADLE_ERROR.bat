@echo off
setlocal EnableExtensions
cd /d "%~dp0"
set "JAVA17_HOME=%LOCALAPPDATA%\SupertonicTTS\jdk17"
if not exist "%JAVA17_HOME%\bin\java.exe" set "JAVA17_HOME=%~dp0.tools\jdk17"
if exist "%JAVA17_HOME%\bin\java.exe" goto :use_local
if defined JAVA_HOME if exist "%JAVA_HOME%\bin\java.exe" goto :use_existing
if exist "%ProgramFiles%\Android\Android Studio\jbr\bin\java.exe" set "JAVA_HOME=%ProgramFiles%\Android\Android Studio\jbr"
if exist "%JAVA_HOME%\bin\java.exe" goto :use_existing
if exist "%ProgramFiles%\Eclipse Adoptium" (
  for /d %%D in ("%ProgramFiles%\Eclipse Adoptium\jdk-17*") do if exist "%%~fD\bin\java.exe" set "JAVA_HOME=%%~fD"
)
if exist "%JAVA_HOME%\bin\java.exe" goto :use_existing
echo [ERROR] Java 17 runtime not found. Run BUILD_ALL.bat first.
pause
exit /b 1
:use_local
set "JAVA_HOME=%JAVA17_HOME%"
:use_existing
set "PATH=%JAVA_HOME%\bin;%PATH%"
echo JAVA_HOME=%JAVA_HOME%
"%JAVA_HOME%\bin\java.exe" -version
call gradlew.bat :sdk:compileDebugKotlin --stacktrace --console=plain
pause
