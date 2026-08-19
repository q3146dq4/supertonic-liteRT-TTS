# Qualcomm JIT setup fix

The previous setup invoked Google's `fetch_qualcomm_library.sh` directly under Git Bash.
That helper downloaded the full ~1.5 GB QAIRT SDK and then failed on Windows with
`caution: filename not matched: *.so` because the QAIRT archive stores libraries under
nested `qairt/<version>/lib/...` paths.

This revision does not execute that extraction helper. It uses the helper only as the
version source, then:

1. packages LiteRT's matching Qualcomm JIT compiler/dispatch libraries from the small
   `litert_npu_runtime_libraries_jit.zip` release asset;
2. reads the QAIRT version declared by that bundle;
3. resolves the matching official `com.qualcomm.qti:qnn-runtime` Maven AAR via Gradle.

This avoids the 1.5 GB SDK download and keeps the QNN runtime version aligned with the
LiteRT JIT compiler/dispatch bundle.
