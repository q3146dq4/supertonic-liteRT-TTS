# Strict GPU/NPU I/O fix (2026-08-18)

- GPU setup now prefers the Android OpenCL CompiledModel accelerator; stale ClGl plugins are removed when OpenCL is available.
- Strict Qualcomm NPU CompiledModel I/O now allocates every input/output from `LiteRtGetCompiledModel*BufferRequirements` and `LiteRtCreateManagedTensorBufferFromRequirements`, instead of forcing generic HostMemory buffers.
- Accelerator inputs are bound by Supertonic signature names (`text_ids`, `text_mask`, `style_dp`, `style_ttl`, `noisy_latent`, `text_emb`, `latent_mask`, `current_step`, `total_step`, `latent`) rather than shape/occurrence.
- CPU/XNNPACK path is unchanged. No CPU fallback was added to strict GPU/NPU.
