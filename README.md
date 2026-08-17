# Supertonic LiteRT TTS

**Supertonic 3 · LiteRT · Android System TTS**

An offline Android Text-to-Speech engine based on **Supertonic 3** and Google's **LiteRT/TFLite runtime**.

This project packages Supertonic 3 as a native Android TTS engine and exposes it through the standard Android `TextToSpeechService` API. It is designed primarily for **offline reading, screen readers, e-book readers, accessibility tools, and applications that use Android System TTS**.

The synthesis backend runs locally on the device. After the model bundle has been downloaded, speech synthesis itself does not require a network connection.

> **Current project version:** SupertonicLiteRT-v0.1
> **Backend:** Supertonic 3 / LiteRT / 4 graphs
> **Android TTS:** Supported
> **Minimum Android version:** Android 8.0 / API 26

---

## Features

### Supertonic 3 LiteRT backend

The native engine uses four LiteRT/TFLite graphs:

1. `duration_predictor.tflite`
2. `text_encoder.tflite`
3. `vector_estimator.tflite`
4. `vocoder.tflite`

The four graphs are loaded natively through `speech-core` and executed with the CPU/XNNPACK runtime.

The Android SDK currently uses:

* LiteRT runtime `2.1.5`
* Android NDK `29.0.14206865`
* CMake `3.22.1`
* Java/Kotlin target 17

The native implementation supports configurable CPU thread counts and currently exposes `2`, `4`, and `8` thread presets in the application UI.

---

## Voices

### Built-in voices

The bundled Supertonic 3 model provides:

* `F1`
* `F2`
* `F3`
* `F4`
* `F5`
* `M1`
* `M2`
* `M3`
* `M4`
* `M5`

The Android application can also import compatible Supertonic 3 voice-style JSON files.

### Custom Voice

A Supertonic 3-compatible voice-style JSON containing:

* `style_ttl`
* `style_dp`

can be imported directly from the application.

Imported voices are stored in the local model's `voice_styles` directory and are exposed both to the application and to Android System TTS.

System TTS voice names use the following form:

```text
supertonic-f1
supertonic-f2
...
supertonic-m5
supertonic-custom-<name>
```

Custom voices can be removed from the application's Custom Voice manager.

See:

[`CUSTOM_VOICE_AND_REGEX.md`](CUSTOM_VOICE_AND_REGEX.md)

for the supported JSON format.

---

## Language support

Supertonic 3 supports 31 languages in this project:

| Language   | Code |
| ---------- | ---- |
| Arabic     | `ar` |
| Bulgarian  | `bg` |
| Croatian   | `hr` |
| Czech      | `cs` |
| Danish     | `da` |
| Dutch      | `nl` |
| English    | `en` |
| Estonian   | `et` |
| Finnish    | `fi` |
| French     | `fr` |
| German     | `de` |
| Greek      | `el` |
| Hindi      | `hi` |
| Hungarian  | `hu` |
| Indonesian | `id` |
| Italian    | `it` |
| Japanese   | `ja` |
| Korean     | `ko` |
| Latvian    | `lv` |
| Lithuanian | `lt` |
| Polish     | `pl` |
| Portuguese | `pt` |
| Romanian   | `ro` |
| Russian    | `ru` |
| Slovak     | `sk` |
| Slovenian  | `sl` |
| Spanish    | `es` |
| Swedish    | `sv` |
| Turkish    | `tr` |
| Ukrainian  | `uk` |
| Vietnamese | `vi` |

### `na` language mode

The project also supports Supertonic's language-agnostic `na` mode.

This is particularly useful for text containing multiple languages, for example:

```text
안녕하세요. This is a mixed Korean and English sentence.
```

The application can optionally use `na` automatically when mixed-language text is detected.

The Android System TTS service uses the same language handling rules.

---

# Android System TTS

This project is a real Android `TextToSpeechService`, not merely an application that generates WAV files.

After installation, Android can select **Supertonic LiteRT** as a system TTS engine.

Applications such as:

* e-book readers
* accessibility tools
* screen readers
* browsers
* reading applications
* Android applications using `android.speech.tts`

can therefore use the engine through the normal Android TTS API.

The service implements the standard:

```text
android.intent.action.TTS_SERVICE
```

interface.

The APK also registers the standard TTS configuration, check-data, sample-text, and install-data activities expected by Android's TTS framework.

---

## Settings are shared with System TTS

The application's stored settings are used when an external application invokes Android System TTS.

This includes:

* Voice
* Speed
* Flow steps
* CPU thread count
* Chunk size
* Pre-generation settings
* Chunk gap settings
* Trailing silence trim
* pronunciation / regex rules

This is important because some Android TTS clients may send a stale voice name with their synthesis request.

The service deliberately prioritizes the project's persisted voice selection when appropriate so that changing the voice in **Supertonic LiteRT** actually affects subsequent System TTS requests.

---

# Streaming synthesis

Long-form reading is handled through a chunked streaming pipeline.

Instead of waiting for the complete sentence or chapter to finish synthesizing, the native engine can deliver playable audio chunks as soon as they are available.

The basic path is:

```text
Android application
        │
        ▼
SpeechTextToSpeechService
        │
        ▼
Text preprocessing / pronunciation rules
        │
        ▼
Supertonic chunking
        │
        ├── chunk 1 ──► LiteRT
        ├── chunk 2 ──► LiteRT
        ├── chunk 3 ──► LiteRT
        │
        ▼
PCM streaming
        │
        ▼
TextToSpeech.Callback.audioAvailable()
```

This reduces **TTFA (Time To First Audio)** and avoids waiting for an entire long passage before Android receives audio.

---

# Chunking

The project does not simply split text at an arbitrary character position.

Chunking is designed around Supertonic's token/input limitations and long-text behavior.

Available presets:

| Preset       |       Chunk size |
| ------------ | ---------------: |
| Conservative |    40 codepoints |
| Balanced     |    64 codepoints |
| Long         |    88 codepoints |
| Manual       | 24–96 codepoints |

The default is:

```text
Balanced · 64
```

Smaller chunks generally improve first-audio latency but increase:

* number of synthesis operations
* chunk boundaries
* potential seam processing
* native scheduling overhead

Larger chunks can improve throughput and reduce the number of boundaries, but increase TTFA and may increase the chance of duration overflow.

For most devices, `64` is a reasonable starting point.

---

## Korean / NFKD handling

Supertonic's preprocessing can perform Unicode normalization.

This is significant for Korean because a Hangul syllable can decompose into multiple Unicode codepoints under NFKD normalization.

For example, a chunk that appears short before normalization may become substantially longer after normalization.

The current chunking pipeline therefore checks the **post-NFKD token length** before emitting a chunk.

This prevents long Korean passages from silently exceeding the effective model input capacity.

---

# Duration overflow protection

The native Supertonic implementation uses a fixed graph/input capacity internally.

A chunk can therefore be too long even if it appears valid according to the initial text split.

The current implementation monitors the generated duration and detects potential graph-duration truncation.

When an overflow is detected, the chunk is split again and synthesized rather than accepting truncated audio.

The retry logic prefers meaningful boundaries such as:

1. whitespace / word boundaries
2. punctuation
3. safe midpoint fallback

This is particularly important for long Korean text and mixed-language passages.

---

# Chunk seam handling

Streaming chunks cannot simply be concatenated blindly.

The project therefore applies seam processing to reduce audible discontinuities between chunks.

Current behavior includes:

* approximately 5 ms seam crossfade
* conservative edge-silence handling
* configurable chunk-gap limits
* trailing silence trimming

The implementation intentionally avoids aggressive waveform trimming.

Earlier versions attempted more aggressive chunk-edge silence removal, which could remove actual speech tails or heads. The current implementation retains the full model waveform where possible and uses conservative processing instead.

---

# Chunk pre-generation

The engine can synthesize future chunks before the currently playing chunk has finished.

Available modes:

```text
OFF
2 chunks
3 chunks
```

### OFF

Uses the normal single-engine synthesis path.

Lowest memory/CPU overhead.

### 2 chunks

Uses an additional LiteRT engine to pre-generate the next chunk.

### 3 chunks

Allows a deeper speculative queue.

This can significantly reduce gaps during long-form reading, but increases:

* CPU utilization
* memory usage
* concurrent LiteRT engine count

For devices with limited RAM or CPU resources, `OFF` or `2 chunks` is recommended.

---

# Chunk gap control

The application exposes:

```text
Minimum gap
Maximum gap
```

in milliseconds.

The minimum value controls intentional spacing between chunks.

The maximum value is primarily used as a gap threshold/monitoring boundary; it cannot force a slow synthesis operation to complete faster.

Pre-generation is therefore the primary mechanism for reducing real synthesis gaps.

---

# Trailing silence trim

Trailing silence trimming can remove low-energy silence at the end of generated chunks.

The configurable range is:

```text
0–500 ms
```

`0` disables trimming.

The implementation uses an energy threshold rather than simply deleting a fixed number of samples.

The default configuration is approximately:

```text
220 ms
```

The trimming is deliberately conservative to avoid cutting off actual phonemes.

---

# Speed processing

The Supertonic model synthesizes at its normal `1.0x` model speed.

For non-`1.0x` playback speed, the project uses the Android/AOSP **Sonic** time-scale processor after synthesis.

This means:

```text
Supertonic synthesis
       │
       ▼
PCM @ native model speed
       │
       ▼
AOSP Sonic
       │
       ▼
requested playback speed
```

Pitch is kept at `1.0`.

The supported speed range is:

```text
0.25x – 3.0x
```

### Why not change Supertonic's speed directly?

The project intentionally avoids relying on model-side duration shortening for arbitrary playback speeds.

Generating the normal waveform first and applying time-scale processing afterwards helps avoid high-speed duration prediction causing clipped words.

---

# 1.0x streaming vs non-1.0x playback

There are two different System TTS paths.

### Effective 1.0x

The engine uses:

```text
synthesizeStreaming()
```

so Android receives audio chunks immediately.

This is the lowest-latency path.

### Other speeds

For non-1.0x speeds, the service synthesizes the complete waveform first and then applies Sonic processing.

This avoids introducing independent time-stretch boundaries into every streaming chunk.

The result is more consistent speech-rate processing at the cost of higher TTFA.

---

# TTFA profiling

The System TTS service measures TTFA from the beginning of the synthesis request until the first successful:

```text
audioAvailable()
```

callback.

Native profiling also records the first native streaming callback.

Logs include entries such as:

```text
SYNTH_TTFA 123.4 ms mode=stream chunk=64
```

and native performance profiles contain fields such as:

```text
ttfa_ms=
streamed_chunks=
chunk_cap=
```

This makes it possible to distinguish:

* model initialization cost
* first-chunk synthesis cost
* streaming behavior
* subsequent chunk performance

rather than treating the entire synthesis time as a single number.

---

# Chapter / navigation transition handling

The project contains a specific fix for long-form reading applications.

When the final playable chunk has already been delivered to Android, the TTS service calls:

```text
SynthesisCallback.done()
```

without waiting for leftover speculative pre-generation workers to finish shutting down.

This allows clients that perform chapter/page/navigation transitions after TTS completion to advance immediately.

The native layer still cooperatively cancels and joins the speculative workers before returning, preventing reuse of a synthesizer while its worker engines are still active.

---

# Screen-off operation

The System TTS service acquires a partial Android `WakeLock` while synthesis is active.

This allows synthesis to continue while the device screen is turned off.

The wake lock is tied to the TTS service lifecycle and is explicitly released after the synthesis request rather than relying on a long fixed timeout.

This is especially useful for:

* screen readers
* audiobook/e-book reading
* long articles
* background reading

---

# Pronunciation and Regex rules

The application includes an in-app pronunciation/regex manager.

Rules can be:

* added
* edited
* deleted
* enabled/disabled
* reordered
* reset to defaults
* imported from JSON
* exported to JSON

Example:

```json
[
  {
    "term": "LLMs",
    "replacement": "L L Ems",
    "ignoreCase": true,
    "isRegex": false
  },
  {
    "word": "RTX\\s*(\\d+)",
    "pronunciation": "알티엑스 $1",
    "ignoreCase": true,
    "isRegex": true
  }
]
```

Supported input fields include:

* `term`
* `word`

Supported replacement fields include:

* `replacement`
* `pronunciation`
* `ipa`

Defaults:

```text
ignoreCase = true
isRegex = false
```

Rules are applied before Supertonic synthesis.

See:

```text
examples/pronunciation_rules_example.json
CUSTOM_VOICE_AND_REGEX.md
```

---

# Model download

The Android application downloads the Supertonic 3 LiteRT model bundle automatically on first use.

The current model source is:

```text
https://huggingface.co/soniqo/Supertonic-3-LiteRT
```

The application downloads:

```text
duration_predictor.tflite
text_encoder.tflite
vector_estimator.tflite
vocoder.tflite

tts.json
unicode_indexer.json

voice_styles/F1.json
voice_styles/F2.json
voice_styles/F3.json
voice_styles/F4.json
voice_styles/F5.json

voice_styles/M1.json
voice_styles/M2.json
voice_styles/M3.json
voice_styles/M4.json
voice_styles/M5.json
```

The current bundled model set is approximately **380 MB**.

Model files are stored in the application's private storage and verified using a model-set/version marker before being reused.

Incomplete downloads are retried, and partial downloads can be resumed using HTTP range requests.

---

# Offline behavior

After the model bundle has been downloaded:

* TTS synthesis runs locally.
* No cloud TTS service is required.
* No per-request network connection is required.
* Android System TTS can use the locally installed engine.

The application itself requests `INTERNET` because it needs to download the model bundle and imported resources.

A `WAKE_LOCK` permission is used so that active synthesis can continue with the screen off.

---

# Building

## Requirements

For the Android build:

* Windows, macOS, or Linux
* Android SDK
* Android SDK Platform 35
* Android NDK `29.0.14206865`
* CMake `3.22.1`
* Java 17
* Git
* Git submodule support

The project uses:

```text
Android Gradle Plugin 8.13.2
Kotlin 2.2.21
Gradle 8.13
Java 17
```

---

## Windows — recommended

The repository provides:

```text
BUILD_ALL.bat
```

Run:

```bat
BUILD_ALL.bat
```

The script:

1. locates Git Bash
2. initializes/prepares `speech-core`
3. downloads the LiteRT runtime
4. locates the Android SDK
5. creates `local.properties`
6. prepares Java 17 if necessary
7. builds the debug APK

The resulting APK is copied to:

```text
Supertonic-TTS-v3.4-debug.apk
```

---

## Direct Gradle build

After dependencies have been prepared:

```bat
gradlew.bat :app:assembleDebug
```

The APK will be generated at:

```text
app/build/outputs/apk/debug/app-debug.apk
```

For a detailed build failure:

```bat
gradlew.bat :app:assembleDebug --stacktrace
```

---

# Windows path-length workaround

Native CMake/Ninja builds can encounter Windows `MAX_PATH` failures when the repository is located under a long directory path.

`build_apk.bat` therefore temporarily maps the project to a short drive:

```text
Z:\
```

using Windows `SUBST`.

This keeps generated CMake/Ninja paths short enough to avoid failures such as:

```text
Filename longer than 260 characters
```

The mapping is removed automatically after the build.

If you build manually instead of using `build_apk.bat`, keeping the repository path short is recommended.

---

# Installing on an Android device

After building:

```bat
INSTALL_ON_PHONE.bat
```

The script uses Android Debug Bridge (`adb`) to install the generated APK.

You need:

* Android Developer Options enabled
* USB debugging enabled
* a device visible through `adb devices`

You can also install the APK manually with:

```bat
adb install -r app\build\outputs\apk\debug\app-debug.apk
```

---

# Enabling Supertonic as the Android TTS engine

After installation, open Android's TTS settings.

Depending on the Android version/device:

```text
Settings
→ System
→ Languages & input
→ Text-to-speech output
```

Select:

```text
Supertonic LiteRT
```

as the preferred engine.

The exact settings path varies by Android vendor.

The engine registers itself through the standard:

```text
android.intent.action.TTS_SERVICE
```

interface.

---

# Verifying the TTS service

The repository includes:

```text
VERIFY_TTS_ENGINE.bat
```

Run it after installation.

It checks whether Android can discover:

```text
SpeechTextToSpeechService
```

and the registered:

```text
android.intent.action.TTS_SERVICE
```

service.

If the service does not appear, the issue is normally an APK/manifest installation problem rather than a Supertonic model problem.

---

# Project structure

The important parts of the repository are:

```text
.
├── app/
│   ├── src/main/
│   │   ├── AndroidManifest.xml
│   │   └── kotlin/com/supertonic/tts/
│   │       ├── MainActivity.kt
│   │       ├── PronunciationRulesActivity.kt
│   │       └── ...
│   └── build.gradle.kts
│
├── sdk/
│   ├── src/main/cpp/
│   │   ├── jni_bridge.cpp
│   │   └── CMakeLists.txt
│   │
│   └── src/main/kotlin/audio/soniqo/speech/
│       ├── ModelManager.kt
│       ├── NativeBridge.kt
│       ├── SpeechConfig.kt
│       ├── TtsSettings.kt
│       ├── audio/
│       │   └── AudioSpeedProcessor.kt
│       └── service/
│           └── SpeechTextToSpeechService.kt
│
├── speech-core/
│   ├── src/
│   ├── include/
│   ├── examples/litert/
│   │   └── supertonic_tts.cpp
│   └── ...
│
├── examples/
│   └── pronunciation_rules_example.json
│
├── setup.sh
├── BUILD_ALL.bat
├── build_apk.bat
├── INSTALL_ON_PHONE.bat
├── VERIFY_TTS_ENGINE.bat
│
├── CUSTOM_VOICE_AND_REGEX.md
└── README.md
```

---

# Native architecture

The Android layer is intentionally split into three parts.

## 1. Android application

`app/`

Provides:

* settings UI
* model download UI
* voice selection
* custom voice management
* pronunciation/regex management
* WAV export
* synthesis testing
* performance information

## 2. Android TTS SDK layer

`sdk/`

Provides:

* `SpeechSynthesizer`
* `SpeechTextToSpeechService`
* persistent settings
* model management
* speed processing
* JNI bridge

## 3. Native speech engine

`speech-core/`

Provides the Supertonic LiteRT implementation and native synthesis pipeline.

The JNI bridge connects Kotlin to:

```text
LiteRTSupertonicTts
```

which owns the four LiteRT graphs.

---

# Native graph execution

Each graph is loaded into its own LiteRT interpreter.

The current native implementation uses the CPU/XNNPACK execution path.

The requested thread count is applied to the LiteRT interpreters and XNNPACK delegates.

The native engine clamps the thread count and synthesis parameters to safe ranges.

---

# Important configuration ranges

| Setting               |                             Range |    Default |
| --------------------- | --------------------------------: | ---------: |
| Speed                 |                       `0.25–3.0x` |     `1.0x` |
| Flow steps            | `1–64` native / UI presets `4–12` |        `4` |
| Chunk size            |                           `24–96` |       `64` |
| CPU threads           |        native `1–64` / UI `2,4,8` |        `4` |
| Pre-generation queue  |                             `2–3` |        `2` |
| Chunk gap             |                       `0–2000 ms` | `0–250 ms` |
| Trailing silence trim |                        `0–500 ms` |   `220 ms` |

The UI deliberately exposes a smaller set of tested/practical values rather than every native range.

---

# Recommended settings

### Fast / low latency

```text
Voice: any
Speed: 1.0x
Steps: 4
Threads: 8
Chunk: 40
Pre-generation: 2
```

Good for interactive use and short responses.

### Balanced

```text
Speed: 1.0x
Steps: 6
Threads: 4
Chunk: 64
Pre-generation: 2
```

A good general-purpose configuration.

### Higher quality

```text
Speed: 1.0x
Steps: 8–12
Threads: 4–8
Chunk: 64–88
Pre-generation: 2
```

Higher flow-step counts increase synthesis cost.

### Long-form reading

```text
Speed: 1.0x
Steps: 4–6
Chunk: 64
Pre-generation: 2 or 3
Trailing trim: 150–220 ms
```

For long books/articles, pre-generation is generally more useful than simply increasing chunk size.

---

# Performance considerations

Performance depends heavily on the Android device.

Important variables include:

* SoC
* big/LITTLE CPU topology
* thermal state
* available RAM
* Android version
* LiteRT runtime
* CPU thread count
* flow steps
* chunk size
* pre-generation depth
* text language
* text length

Do not compare TTFA or RTF values between devices without recording the configuration.

The built-in performance profile is intended to make these measurements reproducible.

---

# Troubleshooting

## The model does not download

Check:

* Internet connectivity
* available storage
* access to Hugging Face
* Android's background/network restrictions

The application retries failed downloads up to five times.

A partial download can be resumed.

---

## TTS works in the app but not in another application

Run:

```text
VERIFY_TTS_ENGINE.bat
```

Then confirm that Android's TTS settings show:

```text
Supertonic LiteRT
```

as an available engine.

Some applications cache their TTS engine/voice selection. Restarting the client application may be necessary.

---

## Long Korean text is truncated

Make sure you are using a recent version of the project.

The current chunking implementation checks the post-NFKD token length and includes duration-overflow retry splitting.

Try:

```text
Chunk: 40 or 64
Steps: 4–6
Pre-generation: 2
```

for particularly long Korean passages.

---

## Long reading has gaps

Try enabling:

```text
Pre-generation: 2 chunks
```

or:

```text
Pre-generation: 3 chunks
```

Increasing CPU threads may also help.

Do not assume that increasing the chunk size will always reduce gaps. Larger chunks increase the amount of audio that must be generated before the next chunk becomes available.

---

## Speech is cut at chunk boundaries

Avoid excessively small chunks.

Recommended starting point:

```text
64 codepoints
```

Also avoid setting aggressive trailing silence trimming values.

The current implementation intentionally uses conservative trimming because earlier aggressive trimming could remove actual speech at chunk boundaries.

---

## 1.0x is much faster than 1.5x/2.0x to start

This is expected.

At `1.0x`, System TTS uses native streaming:

```text
Supertonic → first chunk → audioAvailable()
```

At other speeds:

```text
Supertonic → complete waveform → Sonic → audioAvailable()
```

This is intentional so that time-stretching does not introduce seams between independently processed chunks.

---

# Current limitations

This project is an Android-focused deployment of Supertonic 3.

It should not be confused with the official Supertonic repository's general-purpose SDK examples.

In particular:

* The Android app is focused on local System TTS.
* The current native backend is CPU/LiteRT based.
* GPU/NNAPI acceleration is not the primary execution path.
* Custom voices require compatible Supertonic 3 voice-style JSON data.
* Speed changes other than `1.0x` require post-processing and therefore generally have higher TTFA.
* More aggressive chunking can increase CPU and memory pressure.
* Performance varies significantly by Android device.

---

# Relationship to upstream Supertonic

This repository is an Android/LiteRT deployment project built around **Supertonic 3**.

The upstream Supertonic project provides the model and broader inference ecosystem, while this repository focuses on:

* Android deployment
* LiteRT conversion/runtime
* Android System TTS
* long-form streaming
* chunk scheduling
* TTFA reduction
* custom voice management
* pronunciation rules
* Android-specific lifecycle handling

The upstream project currently documents Supertonic 3 as its latest model generation, with 31-language support and multiple runtime implementations.

Upstream repository:

[supertone-inc/supertonic](https://github.com/supertone-inc/supertonic?utm_source=chatgpt.com)

---

# Development notes

The repository contains several dated patch/design notes.

These documents are useful when investigating the history of a particular behavior:

```text
README_FIRST.txt
IMPROVEMENT_NOTES_2026-08-16.md
PATCH_NOTES_2026-08-16.txt
TTFA_CHUNK_PREGEN_PATCH_2026-08-16.txt
THREAD_AND_CHUNK_GAP_PATCH_2026-08-16.txt
CHAPTER_TRANSITION_FIX_2026-08-17.md
CUSTOM_VOICE_AND_REGEX.md
PROFILER_GUIDE.txt
```

The dated patch notes should be considered development history rather than the primary user documentation.

This README describes the resulting behavior of the current codebase.

---

# License

This repository is licensed under:

```text
Apache License 2.0
```

See:

```text
LICENSE
```

for the full license text.

Third-party components may have their own licenses.

See:

```text
THIRD-PARTY-SONIC-NOTICE.txt
speech-core/THIRD-PARTY-NOTICES.md
```

for additional notices.

---

# Acknowledgements

This project builds on:

* Supertonic / Supertone
* `speech-core`
* Google LiteRT / TensorFlow Lite
* Android TextToSpeech APIs
* Android/AOSP Sonic
* Kotlin
* CMake
* Android NDK

The underlying Supertonic architecture is described in the SupertonicTTS research work by Supertone. The upstream project also provides the ONNX-based reference implementations and model releases.

---

# Status

**Current status: functional Android Supertonic 3 LiteRT TTS engine**

The current implementation includes:

* [x] Supertonic 3 LiteRT 4-graph backend
* [x] Android System TTS
* [x] 31-language support
* [x] `na` mixed-language fallback
* [x] F1–F5 / M1–M5 voices
* [x] Custom Voice JSON import/delete
* [x] Persistent Voice / Speed / Steps
* [x] Streaming TTS
* [x] Chunk pre-generation
* [x] Configurable chunk size
* [x] Chunk gap control
* [x] Trailing silence trimming
* [x] Chunk seam crossfade
* [x] Duration overflow detection/retry
* [x] Korean post-NFKD chunk validation
* [x] Pronunciation/regex rules
* [x] JSON import/export
* [x] TTFA profiling
* [x] Screen-off synthesis
* [x] Chapter-transition completion handling
* [x] Windows long-path build workaround
* [x] Automatic LiteRT/model preparation

For the most accurate description of the current implementation, prefer the code and the configuration files in this repository over older dated patch notes.
