# Supertonic LiteRT v0.1.3 코드 검토 보고서

## 결론

첨부 로그의 빌드 실패는 `sdk/src/main/cpp/jni_bridge.cpp` 한 곳에서 발생한
NDK 29 JNI C++ 타입 불일치입니다. `AttachCurrentThread`에 `void**`를 넘기던
코드를 `JNIEnv**`인 `&env`로 수정했습니다. 로그에 함께 나온 나머지는 해당
빌드를 중단시킨 오류가 아니라 경고입니다.

이 수정으로 **로그에 나온 첫 차단 원인**은 제거됐습니다. 다만 이 환경에는
Android SDK/NDK와 프로젝트가 다운로드하는 AAR이 없으므로 전체 APK 재빌드는
Windows 개발 PC에서 `BUILD_ALL.bat`로 최종 확인해야 합니다.

## 로그 판정

| 메시지 | 등급 | 판정 및 조치 |
|---|---:|---|
| `AttachCurrentThread ... JNIEnv ** ... void **` | 빌드 오류 | 직접 원인. `AttachCurrentThread(&env, nullptr)`로 수정 |
| `jvmTarget: String is deprecated` | 경고 | Kotlin `compilerOptions` DSL로 이전 |
| Manifest `extractNativeLibs` | 경고 | Manifest 항목 제거. Gradle `useLegacyPackaging = true` 유지 |
| `SDK XML versions up to 3 ... version 4` | 경고 | Android Studio/SDK Command-line Tools 구성 불일치. SDK Manager에서 동기화 권장 |
| KISS FFT `.c`를 C++로 컴파일 | 경고 | `speech-core`가 의도적으로 C++ 전용 프로젝트로 구성한 결과 |
| Kokoro helper `unused function` | 경고 | Supertonic 빌드 실패와 무관한 미사용 함수 경고 |

## 반영한 코드 개선

1. NDK 29용 JNI 타입 오류 수정.
2. accelerator runner 생성 중 JNI 메서드 탐색이 실패할 때 global reference 정리.
3. JNI에 잘못된 backend 숫자가 들어오면 즉시 거부.
4. streaming callback 메서드 탐색 실패 시 대기 중인 Java 예외를 정리하고 명확한 오류 전달.
5. Kotlin JVM 17 설정을 최신 `compilerOptions` DSL로 이전.
6. 중복된 `extractNativeLibs` Manifest 설정 제거.
7. API 21부터 폐기된 `KEY_FEATURE_EMBEDDED_SYNTHESIS` 제거. `Voice`의
   `networkConnectionRequired=false`로 오프라인 음성을 표현.
8. 모델이 아직 없는데도 `CHECK_TTS_DATA_PASS`를 반환하던 동작 수정. 모델이
   준비되지 않았으면 음성 데이터를 unavailable로 보고.
9. GPU/NPU backend 문구를 “요청한 backend”로 정확하게 수정. Interpreter
   delegate는 지원되지 않는 연산을 CPU kernel에 남길 수 있으므로 현재 코드만으로
   100% GPU/NPU 실행을 증명할 수 없음.

## 프로젝트 구조

| 경로 | 역할 |
|---|---|
| `app/` | 설정·테스트·벤치마크 UI, Android TTS 엔진 등록, APK 패키징 |
| `sdk/` | TTS 서비스, 모델 다운로드, 설정/정규식, GPU·QNN Java runner, JNI bridge |
| `speech-core/` | C++17 음성 파이프라인과 Supertonic 네 그래프 실행 코드 |
| `setup.sh` | LiteRT 2.1.5, Java LiteRT 1.4.2, QNN 2.49.0 AAR 다운로드·배치 |
| `BUILD_ALL.bat` | 소스 확인 → 런타임 준비 → Gradle debug APK 빌드 |
| `INSTALL_ON_PHONE.bat` | ADB로 생성 APK 설치 |

## backend 실행 흐름

- CPU: C++ `speech-core` → native LiteRT 2.1.5 → XNNPACK.
- GPU: Duration/Encoder는 native CPU/XNNPACK. Snapdragon은 VE/Vocoder를
  QNN GPU hybrid precision에 요청하고, 그 외 기기는 classic GPU에 Vocoder만 요청.
- Qualcomm HTP/NPU: Encoder/VE/Vocoder 모두 HTP FP16에서 비유한 출력이
  확인되어 선택 항목과 delegate 생성을 제거. 기존 저장 설정은 CPU로 자동 이관.
- NNAPI: MediaTek/Exynos/Tensor의 vendor driver에 VE/Vocoder를 요청하는 실험 경로.
- JNI direct `ByteBuffer`가 C++ tensor 메모리와 Java Interpreter 사이를 연결.

GPU delegate 생성과 호출을 같은 `HandlerThread`에 고정한 설계는 GPU delegate의
스레드 제약에 맞습니다. native LiteRT와 Java LiteRT의 delegate pointer를 서로
넘기지 않는 것도 런타임 ABI 혼용 위험을 피하는 올바른 방향입니다.

## 빌드·설치·실행

1. Windows에 Android Studio/SDK, NDK 29.0.14206865, CMake 3.22.1,
   Git for Windows, JDK 17을 준비합니다.
2. 프로젝트 루트에서 `BUILD_ALL.bat`를 실행합니다.
3. 성공 시 루트의 `Supertonic-LiteRT-v0.1.3-debug.apk`를 확인합니다.
4. USB 디버깅을 켠 Android 기기를 연결하고 `INSTALL_ON_PHONE.bat`를 실행합니다.
5. 앱을 한 번 실행해 약 380 MB 모델을 다운로드합니다.
6. Android 설정의 텍스트 음성 변환에서 **Supertonic LiteRT**를 기본 엔진으로 선택합니다.
7. 먼저 CPU/XNNPACK으로 기준값을 측정한 뒤 NNAPI/GPU/Qualcomm QNN을 동일 조건으로 비교합니다.

## 추가로 남은 위험과 권장 순서

1. **실기기 재빌드가 최우선**: 이번 로그는 첫 C++ 오류에서 멈췄기 때문에 그
   다음 단계의 컴파일/링크 오류는 아직 관찰되지 않았습니다.
2. **가속 범위 검증**: GPU/NPU 선택 후 Android logcat과 Qualcomm/Android
   profiler로 실제 delegated op 비율을 확인해야 합니다.
3. **모델 무결성**: 현재 기존 모델 파일 검사는 버전 문자열과 1 KiB 이상인지
   여부가 중심입니다. 배포 안정성을 높이려면 파일별 SHA-256 manifest가 필요합니다.
4. **Custom Voice 검증**: 가져올 때 키 존재만 확인합니다. 배열 shape와 모든 값이
   유한한 숫자인지 저장 전에 검사하면 잘못된 JSON 때문에 엔진 초기화가 실패하는
   일을 줄일 수 있습니다.
5. **최초 시스템 TTS 호출**: 앱에서 모델을 미리 받지 않은 채 다른 앱이 TTS를
   호출하면 서비스가 큰 다운로드를 기다릴 수 있습니다. 수정된 data check는 이를
   “미설치”로 알리지만, 호출 앱의 동작도 실기기에서 확인하는 편이 좋습니다.
