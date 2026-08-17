# Supertonic LiteRT v0.1 — External TTS + Chunk Pre-generation Fixed

이 버전은 `SupertonicLiteRT-v0.1-ScreenOffGapFix - 정상작동.zip`의 외부 Android TTS 안정성을 기준으로, 수정본에 들어간 Chunk Pre-generation / queue / chunk gap / trailing silence trim 기능을 외부 앱 TTS 경로에서도 유지하도록 구성한 버전입니다.

## 반영 내용

- Android `SpeechTextToSpeechService`에서 `synthesizeStreaming()` 경로 유지
- 외부 앱이 Android 시스템 TTS로 호출해도 설정된 Chunk Pre-generation 사용
- Pre-generation queue 2/3 지원 유지
- Chunk gap 최소/최대 설정 유지
- Trailing silence trim 유지
- Kotlin `NativeBridge`와 JNI의 4개 신규 설정 함수 이름/시그니처를 일치시킴
- 화면이 꺼진 상태에서도 TTS가 계속 계산되도록 `WAKE_LOCK` 및 `PARTIAL_WAKE_LOCK` 복구
- TTS 요청 완료 후 5초 linger 후 WakeLock 해제
- 마지막 TTS 청크가 이미 출력된 뒤 남아 있는 speculative pre-generation future를 cancel 후 join하여 불필요한 최종 지연을 줄임
- `OFF` / `2청크` / `3청크` 설정은 외부 TTS에서도 동일하게 적용

## 중요한 동작

`OFF`는 일반 단일 엔진 경로이고, `2청크`/`3청크`는 외부 TTS에서도 다음 청크를 별도 LiteRT 엔진으로 미리 생성합니다.

외부 앱 → Android TTS Service → `synthesizeStreaming()` → Chunk Pre-generation → `audioAvailable()` 순서로 동작합니다.

## 빌드

Windows에서 `BUILD_NOW.bat` 또는 `BUILD_ALL.bat`을 사용합니다.

이 작업 환경에서는 Android SDK/Gradle 배포본을 새로 다운로드할 수 없어 APK 자체의 최종 Gradle 빌드는 수행하지 못했습니다. 따라서 이 ZIP은 소스 패치가 반영된 빌드 가능한 프로젝트입니다.


## Launcher icon fix
- Added Android 8+ adaptive icon resources under `app/src/main/res/mipmap-anydpi-v26/`.
- Separated the waveform foreground from the legacy dark rounded-square/white-edge artwork.
- Adaptive icon background is `#14151A`; foreground is transparent waveform art.
- Manifest already references `@mipmap/ic_launcher` and `@mipmap/ic_launcher_round`, so the v26 resources are selected automatically on Android 8+.
