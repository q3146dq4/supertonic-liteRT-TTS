# Incomplete source ZIP packaging fix (2026-08-18)

The previous `strict-gpu-npu-io-fix` ZIP accidentally omitted the complete
`speech-core/src/models/litert` and `speech-core/examples/litert` source
subtrees even though they were present in the working project directory.
CMake therefore failed during configure with missing files such as
`litert_silero_vad.cpp`, `kokoro_tts_bench.cpp`, and `tensor_bench.cpp`.

This package restores the complete source tree and adds `VERIFY_SOURCE_TREE.bat`.
`BUILD_ALL.bat` runs the preflight before runtime setup/Gradle so an incomplete
archive fails immediately with a clear message rather than later in CMake.

No GPU/NPU runtime behavior was changed by this packaging fix. The strict I/O
changes from the previous build are retained, including strict full delegation,
NPU CompiledModel buffer requirements, semantic signature-name binding, and
non-finite output blocking.
