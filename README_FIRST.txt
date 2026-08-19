Supertonic LiteRT v0.1.4

빌드:
  BUILD_ALL.bat

설치:
  INSTALL_ON_PHONE.bat

생성 APK:
  Supertonic-LiteRT-v0.1.4-debug.apk

현재 Backend:
  1) CPU / XNNPACK (기본값)
     - 기존 native LiteRT + XNNPACK 경로 유지

  2) NNAPI / 기기 가속기 (실험)
     - MediaTek G99 / Kompanio, Exynos, Tensor용 후보 경로
     - VE + Vocoder를 vendor NNAPI driver에 relaxed FP16으로 요청
     - NNAPI CPU reference backend는 금지
     - Helio G99 실측: End-to-end RTF 2.148, peak 0.029 / RMS 0.002의 손상 출력
     - 일부 연산만 위임될 수 있으므로 CPU 대비 End-to-end RTF와 출력 품질을 함께 비교

  3) GPU / 속도 우선 호환 경로
     - Snapdragon: VE + Vocoder를 QNN GPU hybrid precision에 요청
     - Snapdragon 8 Elite Gen 5 기기 최신 End-to-end RTF 0.260
       (CPU 0.188보다 약 38% 느림)
     - 비 Qualcomm: DP + Encoder + VE는 CPU/XNNPACK,
       호환되는 Vocoder만 LiteRT GpuDelegate FP16에 요청
     - GPU delegate 생성/실행은 동일한 전용 HandlerThread에서 수행
     - VE의 BROADCAST_TO/GATHER_ND/3D CONCAT 실패 경로는 사용하지 않음

Qualcomm HTP/NPU:
  - 공개 FP32 모델의 Encoder / VE / Vocoder 모두 HTP FP16에서 비유한 출력
  - QNN DSP VE는 프로세스 크래시 발생
  - v0.1.4에서 실험적 NPU 선택과 QNN HTP 경로를 다시 보존
  - Encoder/VE 및 프로세스를 종료시킨 DSP VE는 차단
  - HTP Vocoder probe만 실행하며 오류 감지 시 같은 요청을 CPU로 한 번 재실행
  - 실제 HTP 가속에는 검증된 양자화 모델 또는 AI Hub context binary 필요

Snapdragon 8 Elite Gen 5 기기 최신 동일 설정 End-to-end RTF:
  - CPU/XNNPACK: 0.188 (권장)
  - QNN GPU hybrid: 0.260, CPU보다 약 38% 느림
  - NNAPI: 1.936, CPU보다 약 10.3배 느림. Snapdragon에서는 사용 비권장

주의:
  가속기 모드는 'FULL GPU'라고 표시하지 않습니다. 현재 공개 그래프는 전체
  가속기 상주가 보장되지 않으며, 실기기/칩셋별 가능한 stage만 사용하는 hybrid입니다.
  주 비교값은 생성 오디오 길이를 보정하는 End-to-end RTF입니다. VE/Vocoder 시간은
  RTF 차이의 원인을 분석하는 보조값으로 사용하십시오.

NaN/Inf뿐 아니라 비정상 저에너지/과대 출력도 차단합니다. 첫 음성이 전달되기 전
실패하면 CPU로 한 번 자동 복구하며 Requested/Active backend와 사유를 프로필에 표시합니다.
가속기 Backend에서는 메모리/스레드 문제를 피하기 위해 다음 청크 미리 생성을 끕니다.
최초 실행 Speed 표시값은 저장된 실제 설정과 즉시 동기화됩니다.
