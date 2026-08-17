Supertonic v3 LiteRT TTS v3.2

이번 버전 핵심 수정:
- Android 시스템 TTS 호출 시 앱에서 저장한 Voice/Speed/Steps를 매 요청마다 실제 native synthesizer에 적용
- Voice 설정은 시스템 TTS 클라이언트가 stale F1 voiceName을 보내더라도 앱의 저장된 voice를 우선 적용
- Voice/Speed/Steps 변경 즉시 SharedPreferences에 동기 저장
- 고정 L=64 그래프 때문에 분할된 문장 내부 경계의 저레벨 tail/head silence를 제거하고 약 7ms crossfade 적용
- 기본 Speed 1.00 / Steps 4 유지
- Performance Profile에서 실제 적용 Voice/Speed/Steps와 chunk/silence/audio metrics 확인 가능

빌드: BUILD_ALL.bat
설치: INSTALL_ON_PHONE.bat


V3.4: system TTS uses stored Voice/Speed/Steps; speed is applied after 1x synthesis with AOSP Sonic; custom Supertonic-3 voice JSON import; DevGitPit-compatible pronunciation/regex JSON import/export; long-text chunking validates post-NFKD token length to prevent silent truncation.

Version 3.4.4: fixes AudioSpeedProcessor package/import mismatch that blocked app Kotlin compilation. BUILD_ALL now runs a preflight consistency check before downloads/build.


V3.4.6 changes:
- Fixed sentence/phoneme loss caused by aggressive chunk-edge silence trimming. Full model waveforms are now retained.
- Added adaptive duration-overflow retry: if a fixed 64-frame graph would truncate a chunk, the chunk is split and synthesized again instead of accepting truncated audio.
- Added in-app TTS regex/pronunciation manager: add/edit/delete, enable/disable, reorder, reset defaults, JSON import/export.
- Added Custom Voice manager with deletion.


2026-08-16 TTFA / chunk pipeline update
- Added chunk presets: conservative 40 / balanced 64 / long 88 / manual 24–96 codepoints.
- Added native chunk streaming with a 5 ms seam crossfade and conservative edge-silence trimming.
- System TTS uses streaming at effective 1.00x; non-1x keeps whole-wave Sonic speed processing to preserve established speed behavior.
- TTFA is measured in SpeechTextToSpeechService from request entry to first audioAvailable(), and native profile includes ttfa_ms=.
- Legacy and API 26+ launcher resources now use the same vector icon instead of adaptive-icon-only variants, preventing APK/install icon mismatches.
