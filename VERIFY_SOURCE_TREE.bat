@echo off
setlocal EnableExtensions
set "ROOT=%~dp0"
set "FAILED=0"

echo === Source tree preflight ===
for %%F in (
  "speech-core\src\models\litert\litert_silero_vad.cpp"
  "speech-core\src\models\litert\litert_supertonic_tts.cpp"
  "speech-core\src\models\litert\supertonic_tokenizer.cpp"
  "speech-core\src\models\litert\supertonic_c.cpp"
  "speech-core\examples\litert\kokoro_tts_bench.cpp"
  "speech-core\examples\litert\tensor_bench.cpp"
  "speech-core\CMakeLists.txt"
  "sdk\src\main\cpp\CMakeLists.txt"
  "sdk\src\main\cpp\jni_bridge.cpp"
  "sdk\src\main\kotlin\audio\soniqo\speech\DelegateSupertonicRunner.kt"
  "setup.sh"
) do (
  if not exist "%ROOT%%%~F" (
    echo [ERROR] Missing source file: %%~F
    set "FAILED=1"
  )
)

if "%FAILED%"=="1" (
  echo.
  echo [ERROR] Source ZIP is incomplete. Build was not started.
  exit /b 1
)

echo Source tree OK.
exit /b 0
