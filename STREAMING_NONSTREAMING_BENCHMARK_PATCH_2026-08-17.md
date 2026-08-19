# Streaming + Non-streaming benchmark patch (2026-08-17)

- CPU thread selector remains freely selectable from 1 through 8.
- Flow matching steps remain freely selectable from 1 through 64.
- Thread benchmark now tests every thread count from 1 through 8.
- Added separate **Non-streaming benchmark** and **Streaming benchmark** buttons.

## Non-streaming benchmark
Uses `SpeechSynthesizer.synthesize()` and compares total native synthesis performance with identical Voice / Steps / Chunk / Language settings. Speculative pre-generation is forced OFF for a fair raw CPU comparison.

Reported per thread count:
- Native synthesis time
- Native RTF
- summed VE time
- Vocoder time
- engine initialization time

## Streaming benchmark
Uses the real `SpeechSynthesizer.synthesizeStreaming()` callback path. It keeps the currently selected pre-generation setting because that setting directly affects real streaming behavior.

Reported per thread count:
- actual callback TTFA (wall-clock from call start until first non-empty PCM callback)
- Native RTF and wall-clock RTF
- maximum / average interval between non-empty callbacks
- number of non-empty callbacks
- Native total
- summed VE time
- Vocoder time
- engine initialization time

The streaming benchmark runs at model speed 1.00x, matching the Android TTS service path that uses native streaming at effective speed 1.00x. It does not play the generated audio.

The streaming BEST result is ranked by user-perceived responsiveness: TTFA first, then maximum callback gap, then RTF. The non-streaming BEST result remains ranked by Native RTF.

## Build verification note
A Gradle compile was attempted, but the environment did not have the Gradle 8.13 distribution cached and network access to services.gradle.org was unavailable. Source-level changes were packaged, but a full local Gradle compile could not be completed here.
