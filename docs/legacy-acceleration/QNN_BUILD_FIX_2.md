# QNN build fix 2

The previous build added `com.google.ai.edge.litert:litert:2.1.0`. That is the LiteRT 2.x implementation and no longer contains the legacy `org.tensorflow.lite.Delegate` API expected by Qualcomm `QnnDelegate`.

This revision removes that dependency and adds:

```kotlin
implementation("com.google.ai.edge.litert:litert-api:1.4.2")
```

`litert-api` is API-only, so it supplies `org.tensorflow.lite.Delegate` to Kotlin/D8 without bundling another native inference runtime. The Supertonic native runtime/GPU accelerator remains LiteRT 2.1.6.
