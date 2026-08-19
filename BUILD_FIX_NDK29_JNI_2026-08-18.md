# NDK 29 JNI build fix — 2026-08-18

## Failure

The Android C++ JNI wrapper in NDK 29 declares:

```cpp
jint AttachCurrentThread(JNIEnv** p_env, void* thr_args)
```

`sdk/src/main/cpp/jni_bridge.cpp` passed a `void**` produced by
`reinterpret_cast<void**>(&env)`, so Clang rejected the call before linking.

## Fix

The call now passes `&env` directly:

```cpp
jvm_->AttachCurrentThread(&env, nullptr)
```

`GetEnv` still uses `void**`, which matches its JNI declaration and should not
be changed.

The same review also:

- migrated Kotlin JVM 17 configuration to `compilerOptions`;
- removed the redundant manifest `extractNativeLibs` attribute while retaining
  Gradle `useLegacyPackaging = true`;
- replaced the deprecated embedded-synthesis voice feature flag with the API
  21+ `networkConnectionRequired=false` voice property;
- made the Android TTS data check report unavailable voices until the model
  bundle has actually been downloaded and verified;
- cleaned JNI global references when accelerator method discovery fails;
- rejected invalid native backend identifiers;
- documented that Interpreter delegates do not prove 100% per-operation GPU/NPU coverage.

## Rebuild

Run `BUILD_ALL.bat`. The first new error, if any, will now be after the JNI
translation unit that previously stopped the build.
