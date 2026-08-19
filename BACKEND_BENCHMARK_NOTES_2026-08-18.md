# Backend benchmark notes — 2026-08-18

## Latest Snapdragon 8 Elite Gen 5 device result

Same voice, speed 1.00, four flow steps, and two chunks:

| Metric | CPU/XNNPACK | QNN GPU hybrid | NNAPI relaxed FP16 |
| --- | ---: | ---: | ---: |
| Audio duration | 9.677 s | 9.851 s | 9.777 s |
| **End-to-end RTF** | **0.188** | **0.260** | **1.936** |
| Native RTF | 0.187 | 0.258 | 1.933 |
| Native total | 1.805 s | 2.541 s | 18.902 s |
| Mean VE step | 333.7 ms | 440.0 ms | 3,727.5 ms |
| Vocoder | 339.6 ms | 468.3 ms | 3,770.5 ms |

End-to-end RTF is the primary metric because it divides total generation time
by the generated audio duration. QNN GPU hybrid RTF is about 38% worse than CPU
and NNAPI RTF is about 10.3 times worse. CPU/XNNPACK is therefore recommended
for this Snapdragon 8 Elite Gen 5 test device.

NNAPI is not useful on this Snapdragon device; its purpose is to test
the separate vendor drivers on G99/Kompanio, Exynos, and Tensor devices.

## Supplied Qualcomm HTP result

The public FP32 Encoder and VE returned non-finite HTP/FP16 output, and the
interim QNN DSP VE probe terminated the Snapdragon 8 Elite Gen 5 app process.
The final HTP-vocoder-only test then returned non-finite Vocoder output as well.
This is a model/backend numerical failure, not a UI or PCM conversion failure.
Version 0.1.4 preserves the NPU selector and HTP-vocoder probe for development,
but keeps HTP Encoder/VE and the process-crashing DSP VE path blocked. A failed
probe is reported explicitly and the same request is recreated once on CPU.

Full HTP residency requires an accelerator-safe
export, most plausibly a validated INT8/INT4 quantized bundle or a Qualcomm AI
Hub context binary. The current FP32 model bundle cannot be made full HTP by
changing only a delegate flag.

## Supplied Helio G99 result

The earlier GPU route failed at vector-estimator step 1 with non-finite output.
The NNAPI route completed but was not a successful synthesis:

| Metric | NNAPI relaxed FP16 |
| --- | ---: |
| Audio duration | 12.215 s |
| **End-to-end RTF** | **2.148** |
| Native RTF | 2.145 |
| Native total | 26.200 s |
| VE step 1 / 2 / 3 / 4 | 5791.7 / 5767.9 / 5767.0 / 5757.2 ms |
| Vocoder | 2625.1 ms |
| Audio peak / RMS | 0.029 / 0.002 |

RTF 2.148 means generation took about 2.15 times the resulting audio duration.
The extremely low peak/RMS matches the reported wind-like near-silence. This is
a finite-but-invalid vendor-driver result, not “NNAPI works but is slow.”

Version 0.1.4 adds an accelerator-only audio guard for this failure class. If it
fires before audio is emitted, synthesis is repeated on CPU and the requested,
active, and fallback backends are all included in the profile.

## MediaTek retest order

For Helio G99 and Kompanio 900T, compare these modes with identical inputs:

1. CPU/XNNPACK baseline.
2. NNAPI device accelerator: VE and vocoder are offered to the vendor driver.
3. GPU: VE remains on CPU and only vocoder is offered to classic LiteRT GPU.

Keep a mode only when it improves end-to-end RTF over CPU after the first warm-up
request, produces normal audio, and reports the requested accelerator as the
active backend without fallback. Use stage timings to explain an RTF difference,
not as the primary cross-run score. A backend that merely initializes or
delegates a small partition is not a performance success.
