# Benchmark / flexible settings patch (2026-08-17)

- CPU thread selector expanded from 2/4/8 to every value from 1 through 8.
- Flow-matching steps selector expanded from the preset list to every value from 1 through 64 (the native SDK supported range).
- Added a one-button CPU thread benchmark that tests 1/2/3/4/6/8 threads using the same text, voice, steps, chunk cap, and language.
  - Reports Native time, RTF, total VE time, Vocoder time, and engine initialization time.
  - Pre-generation is forced OFF during the benchmark so thread comparisons are not distorted by speculative extra engines.
  - The user's selected thread setting is restored afterward.
  - Benchmark does not auto-play generated audio.
- Test-language selection is now persisted separately. Choosing `ko` remains `ko` after reopening the app.
- This test-language preference is only for MainActivity's built-in tester/benchmark. Android system-TTS synthesis still uses the language supplied in the Android `SynthesisRequest`; the tester selection does not override system TTS requests.
