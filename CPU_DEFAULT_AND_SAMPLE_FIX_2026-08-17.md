# CPU default + sample text fix

- Inference backend now migrates to **CPU / XNNPACK exactly once** on this build, even when an older test build had GPU or QNN persisted.
- After that one-time migration, a backend selected by the user is saved normally across app restarts.
- The built-in Korean test sentence no longer mentions Kaldi; it now describes the actual Supertonic-3 LiteRT system TTS engine.
- Android framework sample text (`GetSampleTextActivity`) was updated to match.
