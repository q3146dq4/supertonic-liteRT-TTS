# Strict acceleration fix 2

- Fixed published Supertonic-3-LiteRT generic LiteRT signatures (`args_N`).
- Semantic inputs are mapped by graph positional ABI, never by signature list order.
- Mapping is shape-validated before buffer writes.
- Normal `BUILD_ALL.bat`: LiteRT 2.1.5 CPU/XNNPACK + strict Qualcomm NPU JIT.
- `BUILD_GPU_213.bat`: strict full-GPU compatibility test with the entire LiteRT native runtime pinned to 2.1.3; NPU libraries are intentionally excluded from that APK so LiteRT versions are never mixed.
- No CPU fallback is enabled in GPU/NPU strict inference.
