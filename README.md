# Supertonic TTS v3.3

Soniqo Supertonic-3 LiteRT 4-graph Android TTS engine.

- App name: **Supertonic TTS**
- Default speed: **1.00**
- Flow steps: 4 / 5 / 6 / 8 / 10 / 12
- Voices: F1–F5 / M1–M5
- System TTS: persisted Voice / Speed / Steps are applied on every request
- System TTS streams generated chunks as soon as they are ready at 1.00x to reduce TTFA and long pauses between chunks
- Chunk size: Conservative 40 / Balanced 64 / Long 88 / Manual 24–96 codepoints
- TTFA is profiled from system-TTS request entry to the first `audioAvailable()` callback; native profile also records the first native stream callback
- Mixed Korean/English text can use the official Supertonic-3 `na` fallback
- Long-text chunks are monitored for graph-duration truncation
- Performance profiler aggregates VE Step 1..N across all text chunks

The debug APK is copied to `Supertonic-TTS-v3.3-debug.apk` after a successful build.


## Windows build path fix
BUILD_ALL.bat/build_apk.bat now builds through a short Z: SUBST drive to avoid CMake/Ninja MAX_PATH failures such as "Filename longer than 260 characters". The ZIP is packaged without an extra nested top-level folder.
