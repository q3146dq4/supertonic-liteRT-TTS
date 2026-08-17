@echo off
setlocal
set "ROOT=%~dp0"
set "APK=%ROOT%app\build\outputs\apk\debug\app-debug.apk"

if not exist "%APK%" (
  echo [ERROR] APK not found.
  echo Build it first with build_apk.bat
  pause
  exit /b 1
)

set "ADB="
if exist "%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe" set "ADB=%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe"
if not defined ADB if exist "%ANDROID_HOME%\platform-tools\adb.exe" set "ADB=%ANDROID_HOME%\platform-tools\adb.exe"
if not defined ADB if exist "%ANDROID_SDK_ROOT%\platform-tools\adb.exe" set "ADB=%ANDROID_SDK_ROOT%\platform-tools\adb.exe"

if not defined ADB (
  echo [ERROR] adb.exe was not found.
  echo Install Android SDK Platform-Tools and enable USB debugging.
  pause
  exit /b 1
)

echo Installing Supertonic TTS...
"%ADB%" install -r "%APK%"
if errorlevel 1 (
  echo [ERROR] Install failed. Accept the USB debugging prompt on the phone.
  pause
  exit /b 1
)

echo.
echo Installed successfully.
echo Open "Supertonic TTS" once, download the model, then:
echo Android Settings -^> Text-to-speech output -^> Default engine -^> Supertonic TTS
pause
