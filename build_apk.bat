@echo off
setlocal EnableExtensions EnableDelayedExpansion
set "ROOT=%~dp0"
set "BUILD_DRIVE=Z:"
set "DRIVE_MAPPED=0"

rem Build through a short SUBST drive so CMake/Ninja dependency paths stay below Windows MAX_PATH.
subst %BUILD_DRIVE% >nul 2>&1
if not errorlevel 1 (
    subst %BUILD_DRIVE% /d >nul 2>&1
)
subst %BUILD_DRIVE% "%ROOT%"
if errorlevel 1 (
    echo [ERROR] Could not create short build drive %BUILD_DRIVE%.
    pause
    exit /b 1
)
set "DRIVE_MAPPED=1"
cd /d "%BUILD_DRIVE%\"

if not defined ANDROID_HOME if exist "%LOCALAPPDATA%\Android\Sdk" set "ANDROID_HOME=%LOCALAPPDATA%\Android\Sdk"
if not defined ANDROID_SDK_ROOT if defined ANDROID_HOME set "ANDROID_SDK_ROOT=%ANDROID_HOME%"

rem ================================================================
rem Use Java 17 for the Android/Gradle build.
rem To avoid Windows batch quoting problems with paths like
rem "C:\Program Files\...", this script deliberately avoids nested
rem FOR/pipe parsing and uses a project-local Temurin 17 JDK.
rem If JAVA17_HOME is already defined and valid, that JDK is used.
rem ================================================================

if defined JAVA17_HOME if exist "%JAVA17_HOME%\bin\java.exe" goto :have_java

set "LOCAL_JDK=%ROOT%.tools\jdk17"
if exist "%LOCAL_JDK%\bin\java.exe" (
    set "JAVA17_HOME=%LOCAL_JDK%"
    goto :have_java
)

echo.
echo Java 17 was not found. Downloading a local Temurin 17 JDK...
echo This is a one-time download and may take a few minutes.
if not exist "%ROOT%.tools" mkdir "%ROOT%.tools"

powershell -NoProfile -ExecutionPolicy Bypass -Command "$ProgressPreference='SilentlyContinue'; Invoke-WebRequest -UseBasicParsing -Uri 'https://api.adoptium.net/v3/binary/latest/17/ga/windows/x64/jdk/hotspot/normal/eclipse' -OutFile '%ROOT%.tools\temurin17.zip'"
if errorlevel 1 (
    echo [ERROR] Failed to download Java 17.
    echo Install a JDK 17 manually, set JAVA17_HOME, and run again.
    pause
    exit /b 1
)

powershell -NoProfile -ExecutionPolicy Bypass -Command "$zip='%ROOT%.tools\temurin17.zip'; $tmp='%ROOT%.tools\jdk17.tmp'; $dst='%ROOT%.tools\jdk17'; if(Test-Path $tmp){Remove-Item $tmp -Recurse -Force}; if(Test-Path $dst){Remove-Item $dst -Recurse -Force}; Expand-Archive -Path $zip -DestinationPath $tmp -Force; $d=Get-ChildItem $tmp -Directory | Select-Object -First 1; New-Item -ItemType Directory -Force -Path $dst | Out-Null; Copy-Item ($d.FullName+'\*') $dst -Recurse -Force"
if errorlevel 1 (
    echo [ERROR] Failed to extract Java 17.
    pause
    exit /b 1
)

rmdir /s /q "%ROOT%.tools\jdk17.tmp" 2>nul
del /q "%ROOT%.tools\temurin17.zip" 2>nul

if not exist "%LOCAL_JDK%\bin\java.exe" (
    echo [ERROR] Java 17 installation was not created correctly.
    pause
    exit /b 1
)

set "JAVA17_HOME=%LOCAL_JDK%"

:have_java
set "JAVA_HOME=%JAVA17_HOME%"
set "PATH=%JAVA_HOME%\bin;%PATH%"

if not defined ANDROID_HOME (
    echo [ERROR] Android SDK was not found.
    echo Expected: %LOCALAPPDATA%\Android\Sdk
    pause
    exit /b 1
)

echo JAVA_HOME=%JAVA_HOME%
"%JAVA_HOME%\bin\java.exe" -version
if errorlevel 1 (
    echo [ERROR] Java 17 could not be started.
    pause
    exit /b 1
)

echo ANDROID_HOME=%ANDROID_HOME%
echo.
echo === Building APK with Java 17 ===
call gradlew.bat --stop >nul 2>&1
call gradlew.bat :app:assembleDebug
if errorlevel 1 (
    echo.
    echo [ERROR] Build failed.
    echo.
    echo For a detailed diagnostic, run:
    echo   gradlew.bat :app:assembleDebug --stacktrace
    if "%DRIVE_MAPPED%"=="1" subst %BUILD_DRIVE% /d >nul 2>&1
    pause
    exit /b 1
)

echo.
echo BUILD SUCCESSFUL
copy /y "%ROOT%app\build\outputs\apk\debug\app-debug.apk" "%ROOT%Supertonic-TTS-v3.4-debug.apk" >nul
echo APK: %ROOT%Supertonic-TTS-v3.4-debug.apk
echo.
echo Next: run INSTALL_ON_PHONE.bat
if "%DRIVE_MAPPED%"=="1" subst %BUILD_DRIVE% /d >nul 2>&1
pause
exit /b 0
