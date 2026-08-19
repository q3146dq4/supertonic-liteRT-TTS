# Full GPU / Full NPU strict test build

This build intentionally disables mixed accelerator/CPU execution for the accelerator modes.

- CPU/XNNPACK: existing verified CPU path.
- GPU: LiteRT CompiledModel requests GPU only. Every Supertonic graph must report fully accelerated. If any graph is partial, initialization fails.
- Qualcomm NPU: LiteRT CompiledModel requests NPU only. Every graph must report fully accelerated. No CPU/GPU fallback is requested.
- Invalid accelerator output (NaN/Inf or invalid duration/waveform) aborts synthesis before PCM is emitted; there is no CPU result substitution in strict modes.
- CPU remains the default backend after the one-time settings migration.

The four graphs checked independently are Duration Predictor, Text Encoder, Vector Estimator, and Vocoder. A successful accelerator initialization means all four passed the full-acceleration check.
