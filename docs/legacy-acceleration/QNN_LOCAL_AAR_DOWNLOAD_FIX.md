# QNN local AAR download fix

Supertonic LiteRT v0.1 keeps the Qualcomm runtime version matched to the QAIRT version declared by the LiteRT NPU JIT bundle, but no longer asks Gradle to download `qnn-runtime`.

`setup.sh` downloads the AAR directly from `https://repo.maven.apache.org/maven2/`, caches it under `%LOCALAPPDATA%\SupertonicLiteRT\native-cache\2.1.5`, validates it, and copies it to `app/libs/qnn-runtime.aar`. The app consumes that local AAR with `implementation(files("libs/qnn-runtime.aar"))`.

This preserves AAR packaging of Qualcomm native runtime libraries while removing the slow Gradle/Java network fetch.
