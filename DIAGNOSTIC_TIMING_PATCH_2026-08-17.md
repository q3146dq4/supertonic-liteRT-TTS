# Supertonic TTS timing diagnostic patch

This patch is intended to isolate the large gap between `Native total` and app `End-to-end` time on slower Android devices such as Helio G99.

## Changes

1. JNI timing breakdown in `sdk/src/main/cpp/jni_bridge.cpp`
   - mutex wait
   - Java String -> std::string conversion
   - core `TTSInterface::synthesize()` call
   - float PCM -> int16 conversion
   - Java ByteArray allocation
   - ByteArray copy
   - total JNI call time
   - PCM sample count

2. Core profiler correction
   - VE step timings are now accumulated across all chunks instead of being reset for every chunk.
   - Added chunking/tokenization/latent setup/append/stream emit/pre-generation cleanup/final postprocess timings.

3. App-side timing breakdown in `MainActivity`
   - Coroutine worker dispatch wait
   - engine acquire/init
   - native settings calls
   - SDK synthesize call
   - speed-processing dispatch wait
   - WAV write
   - total time including WAV write

4. Non-streaming JNI synthesis now passes an empty callback (`{}`) instead of a no-op lambda.
   - Previously the no-op lambda made the core believe streaming was enabled, so it performed streaming-only bookkeeping even for non-streaming benchmark synthesis.

5. Starting a new benchmark synthesis now calls `synthesizer.stop()` before cancelling the previous coroutine if it is still active.
   - Kotlin coroutine cancellation alone cannot interrupt a blocking JNI synthesis.
   - Without this, a subsequent synthesis can wait on `SynthesizerHandle::mutex`; the new `JNI mutex wait` field will expose this directly.

## What to look for on the Helio G99

Run the same text once after launching the app, then run it a second time without rapidly tapping Generate.

The most important new fields are:

- `Worker dispatch wait`
- `SDK synthesize call`
- `JNI mutex wait`
- `JNI core call`
- `JNI PCM f32->s16`
- `JNI ByteArray alloc`
- `JNI ByteArray copy`
- `JNI total`
- `WAV write`

Interpretation:

- Large `JNI mutex wait`: an earlier synthesis is still running / overlapping.
- `JNI core call ~= Native total` and `JNI total ~= JNI core call`: the missing time is outside JNI, likely coroutine scheduling / engine init / SDK wrapper.
- `JNI total - JNI core call` large: JNI conversion/allocation/copy is the bottleneck.
- `SDK synthesize call - JNI total` large: SDK property/profile calls or ART/JNI return scheduling are implicated.
- `Worker dispatch wait` large: `Dispatchers.Default` starvation/scheduling is involved.

## Build verification

A full Gradle build could not be completed in the analysis environment because the Gradle wrapper attempted to download Gradle 8.13 from `services.gradle.org`, while that environment has no external network access. The source edits were applied directly to the supplied project tree.
