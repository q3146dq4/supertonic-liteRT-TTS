# Chapter-transition reliability fix — 2026-08-17

## Supertonic LiteRT TTS

- Kept external Android TTS on the streaming path at effective 1.00x, including Chunk Pre-generation.
- The native streaming layer now emits its final-audio marker before joining leftover speculative pre-generation workers.
- The Android TextToSpeechService sends `SynthesisCallback.done()` as soon as the final playable chunk has been handed to Android. This lets client-side chapter/navigation logic advance without waiting for native speculative-worker teardown.
- Speculative workers are still cooperatively cancelled and joined before the native call returns, so the synthesizer object is never reused while its worker engines are still active.
- The TTS synthesis wake lock is now explicitly released by the service lifecycle instead of using a 10-minute timeout, which is safer for long continuous reading sessions.

## Scope

This change does not remove Chunk Pre-generation, Pregen Queue 2/3, Chunk Gap, or Trailing Silence Trim.
