# Accelerator delegate rework — 2026-08-18

This supersedes the earlier CompiledModel strict/adaptive GPU/QNN experiments.

## Qualcomm QNN

- Replaced C++ `CompiledModel`/borrowed-native-handle experiments with Android Java LiteRT `Interpreter` + Qualcomm `QnnDelegate`.
- Device testing showed that HTP FP16 text encoder, VE, and Vocoder all return non-finite output; VE failed at step 1 and the final Vocoder-only path also failed.
- v0.1.4 preserves an explicitly experimental HTP selector and the Vocoder-only probe so NPU development is not deleted.
- The QNN DSP VE probe added after the NaN report caused a process-level crash on the supplied Snapdragon 8 Elite Gen 5 device. It is removed rather than caught because a native abort cannot be recovered by Kotlin exception handling.
- QNN HTP is constructed only for the Vocoder probe. Encoder/VE remain blocked and a rejected result is retried on CPU.
- C++ communicates with the Java-owned graph runners through JNI direct ByteBuffers; no QNN delegate pointer crosses LiteRT runtime boundaries.

## GPU

- Removed the strict `CompiledModel` path that failed at `duration_predictor` with status 504 on both tested LiteRT versions.
- The classic delegate rejected VE (`BROADCAST_TO`, `GATHER_ND`, and a 3D `CONCATENATION`).
- Snapdragon now requests VE + vocoder through QNN GPU hybrid precision. The supplied QNN GPU FP32 run was about 46% slower than CPU end-to-end.
- Non-Qualcomm devices keep DP + encoder + VE on XNNPACK and probe vocoder independently with the classic LiteRT GPU delegate.
- Delegate creation and all invokes are pinned to one HandlerThread.
- Hybrid precision and high-performance preference are enabled on Snapdragon; classic GPU allows FP16 for the non-Qualcomm vocoder probe.

## NNAPI

- MediaTek, Exynos, and Tensor devices can test a vendor NNAPI driver route for VE and vocoder.
- Relaxed FP16 is enabled and the NNAPI CPU reference backend is disabled.
- Partial delegation is possible, so only a CPU comparison of end-to-end RTF establishes whether it is useful.
- The supplied Helio G99 result had RTF 2.148 and finite but invalid peak/RMS 0.029/0.002, so v0.1.4 adds a low-energy/over-range audio guard.

## Common safety

- Delegate initialization, non-finite tensors, and invalid audio before the first emitted chunk trigger one CPU recreation/retry.
- Profiles always expose requested backend, active backend, and fallback reason.
- Non-finite checks remain after every graph/VE step and before PCM conversion; accelerator vocoder output also receives a conservative energy/range check.
- Accelerator pre-generation is disabled.
- CPU remains the one-time migrated default backend.
