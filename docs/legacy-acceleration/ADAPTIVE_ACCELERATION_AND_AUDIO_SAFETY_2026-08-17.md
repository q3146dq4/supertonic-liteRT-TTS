# Adaptive GPU/QNN acceleration + corrupted-audio safety

This build keeps CPU/XNNPACK as the default and treats GPU/QNN as experimental accelerators that must prove themselves per graph on the actual device.

## What changed

- Supertonic's four graphs (duration, encoder, vector estimator, vocoder) are validated independently.
- On GPU/QNN, the first two correct real invocations are compared against an XNNPACK CPU reference using the exact same inputs.
- One numerically invalid result (NaN/Inf, duration mismatch, tensor mismatch, or absurd vocoder magnitude) rejects that accelerator graph immediately.
- After two correct samples, an accelerator graph is retained only if its average execution time is at least 5% faster than CPU/XNNPACK.
- Rejected graphs permanently switch to XNNPACK for that engine instance and their accelerator model/interpreter resources are released.
- If an accelerator Invoke itself fails, the request is replayed immediately on CPU instead of aborting TTS.
- QNN vector estimator is checked on real flow-matching steps, so errors that amplify during iterative inference are caught early.
- QNN cache tokens were versioned to avoid reusing context caches made by older experimental builds.

## Audio corruption protection

Non-finite values are now rejected at multiple layers:

1. duration output
2. text encoder output
3. every vector-estimator step
4. vocoder output
5. chunk PCM before append/stream
6. final PCM before normalization
7. JNI float32 -> PCM16 conversion, including streaming

A broken NPU output must therefore never be converted into the crackling/beeping PCM that earlier builds played. When possible the bad graph is rerun on CPU and synthesis continues with the CPU result. If even the CPU reference is invalid, synthesis fails with an explicit error instead of playing corrupted audio.

## Benchmark note

The first accelerated synthesis intentionally performs validation and can therefore be slower than normal. For performance comparison, run the same backend/text/settings a second time. The profile's Backend field reports the effective per-graph decision, for example:

`QNN/HTP adaptive [DP=CPU(duration mismatch), Enc=CPU(slower avg ...), VE=QNN(verified), Voc=CPU(accelerator non-finite)]`

or

`GPU adaptive [DP=CPU(...), Enc=GPU(verified), VE=GPU(verified), Voc=CPU(...)]`

This means low-end devices can keep only GPU/NPU graph wins while high-end Snapdragon devices keep the already-fast XNNPACK path wherever it is superior.
