#!/bin/bash
set -euo pipefail

# Supertonic LiteRT v0.1.4 runtime setup — guarded accelerator experiments.
#
# CPU path:
#   Native speech-core + LiteRT 2.1.5 libLiteRt.so + XNNPACK (unchanged).
# GPU path:
#   Snapdragon uses QNN GPU/hybrid precision for compatible heavy graphs.
#   Other chipsets probe vocoder with Java LiteRT 1.4.2 GpuDelegate FP16.
# HTP/NPU path:
#   Explicitly experimental. Unsafe Encoder/DSP-VE stages stay blocked; the
#   HTP/FP16 vocoder probe remains available and falls back to CPU when the
#   numerical/audio-quality guard rejects its output.
# NNAPI remains an experimental vendor-driver path for MediaTek/Samsung/Tensor.
# Qualcomm AARs are downloaded directly and cached because
#   Gradle's large qnn-runtime download was extremely slow on the user's route.

LITERT_NATIVE_VERSION="2.1.5"
QNN_VERSION="2.49.0"
ROOT="$(cd "$(dirname "$0")" && pwd)"
LITERT_DIR="${ROOT}/litert"

if [ -n "${LOCALAPPDATA:-}" ] && command -v cygpath >/dev/null 2>&1; then
    CACHE_ROOT="$(cygpath -u "$LOCALAPPDATA")/SupertonicLiteRT/native-cache"
else
    CACHE_ROOT="${HOME}/.cache/SupertonicLiteRT"
fi
CPU_CACHE="${CACHE_ROOT}/${LITERT_NATIVE_VERSION}"
QNN_CACHE="${CACHE_ROOT}/qnn-${QNN_VERSION}"
JAVA_LITERT_VERSION="1.4.2"
JAVA_CACHE="${CACHE_ROOT}/java-litert-${JAVA_LITERT_VERSION}"
mkdir -p "$CPU_CACHE" "$QNN_CACHE" "$JAVA_CACHE" "${ROOT}/app/libs"

echo "=== Supertonic LiteRT v0.1.4 native/delegate setup ==="
echo "CPU native LiteRT: ${LITERT_NATIVE_VERSION}"
echo "Java Interpreter/GPU: LiteRT 1.4.2"
echo "Qualcomm QNN Delegate: ${QNN_VERSION}"

# ---------------------------------------------------------------------------
# Native LiteRT 2.1.5 for the already-verified CPU/XNNPACK C++ path.
# ---------------------------------------------------------------------------
LITERT_AAR_URL="https://dl.google.com/dl/android/maven2/com/google/ai/edge/litert/litert/${LITERT_NATIVE_VERSION}/litert-${LITERT_NATIVE_VERSION}.aar"
CACHED_LITERT_AAR="${CPU_CACHE}/litert-${LITERT_NATIVE_VERSION}.aar"
if [ ! -s "$CACHED_LITERT_AAR" ]; then
    echo "Downloading LiteRT ${LITERT_NATIVE_VERSION} native runtime..."
    curl --fail --location --retry 3 --retry-delay 2 --output "${CACHED_LITERT_AAR}.part" "$LITERT_AAR_URL"
    mv -f "${CACHED_LITERT_AAR}.part" "$CACHED_LITERT_AAR"
else
    echo "Using cached LiteRT AAR: $CACHED_LITERT_AAR"
fi

TMP="${ROOT}/.tmp_delegate_setup"
rm -rf "$TMP"
mkdir -p "$TMP/litert"
unzip -q "$CACHED_LITERT_AAR" -d "$TMP/litert"
mkdir -p "${LITERT_DIR}/arm64-v8a" "${LITERT_DIR}/x86_64"
for abi in arm64-v8a x86_64; do
    src="${TMP}/litert/jni/${abi}/libLiteRt.so"
    if [ ! -s "$src" ]; then
        echo "[ERROR] ${abi}/libLiteRt.so missing from LiteRT ${LITERT_NATIVE_VERSION} AAR"
        exit 1
    fi
    cp -f "$src" "${LITERT_DIR}/${abi}/libLiteRt.so"
done

# Remove stale CompiledModel GPU/QNN plugins from earlier experiments. They are
# intentionally not used by this build; Java-owned delegates are isolated from
# the native CPU runtime instead of sharing native delegate handles.
for abi in arm64-v8a x86_64; do
    jni="${ROOT}/sdk/src/main/jniLibs/${abi}"
    mkdir -p "$jni"
    rm -f "$jni"/libLiteRtClGlAccelerator.so \
          "$jni"/libLiteRtOpenClAccelerator.so \
          "$jni"/libLiteRtGpuAccelerator.so \
          "$jni"/libLiteRtDispatch_Qualcomm.so \
          "$jni"/libLiteRtCompilerPlugin_Qualcomm.so 2>/dev/null || true
    find "$jni" -maxdepth 1 -type f -name '*Qualcomm*Compiler*.so' -delete 2>/dev/null || true
done

# ---------------------------------------------------------------------------
# Java LiteRT Interpreter + GPU delegate AARs (all local; no Gradle network).
# ---------------------------------------------------------------------------
download_google_aar() {
    local artifact="$1"
    local name="${artifact}-${JAVA_LITERT_VERSION}.aar"
    local cached="${JAVA_CACHE}/${name}"
    local url="https://dl.google.com/dl/android/maven2/com/google/ai/edge/litert/${artifact}/${JAVA_LITERT_VERSION}/${name}"
    if [ ! -s "$cached" ]; then
        echo "Downloading Google ${name}..."
        curl --fail --location --retry 3 --retry-delay 2 --output "${cached}.part" "$url"
        mv -f "${cached}.part" "$cached"
    else
        echo "Using cached Google AAR: $cached"
    fi
    if ! unzip -tqq "$cached" >/dev/null 2>&1; then
        echo "[ERROR] Corrupt Google AAR: $cached"
        rm -f "$cached"
        exit 1
    fi
    cp -f "$cached" "${ROOT}/app/libs/${artifact}.aar"
}

download_google_aar litert-api
download_google_aar litert
download_google_aar litert-gpu-api
download_google_aar litert-gpu

# ---------------------------------------------------------------------------
# Qualcomm official QnnDelegate runtime + delegate AARs.
# ---------------------------------------------------------------------------
download_qnn_aar() {
    local artifact="$1"
    local name="${artifact}-${QNN_VERSION}.aar"
    local cached="${QNN_CACHE}/${name}"
    local url="https://repo.maven.apache.org/maven2/com/qualcomm/qti/${artifact}/${QNN_VERSION}/${name}"
    if [ ! -s "$cached" ]; then
        echo "Downloading Qualcomm ${name} from repo.maven.apache.org..."
        curl --fail --location --retry 3 --retry-delay 2 --output "${cached}.part" "$url"
        mv -f "${cached}.part" "$cached"
    else
        echo "Using cached Qualcomm AAR: $cached"
    fi
    if ! unzip -tqq "$cached" >/dev/null 2>&1; then
        echo "[ERROR] Corrupt Qualcomm AAR: $cached"
        rm -f "$cached"
        exit 1
    fi
    cp -f "$cached" "${ROOT}/app/libs/${artifact}.aar"
}

download_qnn_aar qnn-runtime
download_qnn_aar qnn-litert-delegate

if ! unzip -l "${ROOT}/app/libs/qnn-runtime.aar" | grep -q 'libQnnGpu.so'; then
    echo "[ERROR] qnn-runtime AAR does not contain libQnnGpu.so"
    exit 1
fi
if ! unzip -l "${ROOT}/app/libs/qnn-runtime.aar" | grep -q 'libQnnHtp.so'; then
    echo "[ERROR] qnn-runtime AAR does not contain libQnnHtp.so"
    exit 1
fi

cat > "${ROOT}/qnn-version.properties" <<EOF2
qnnRuntimeVersion=${QNN_VERSION}
qnnDelegateVersion=${QNN_VERSION}
EOF2

rm -rf "$TMP"
echo ""
echo "CPU: native LiteRT ${LITERT_NATIVE_VERSION} + XNNPACK retained."
echo "GPU: Snapdragon QNN GPU/hybrid; other chipsets probe LiteRT GPU/FP16 vocoder."
echo "Qualcomm HTP/NPU: experimental vocoder probe; unsafe Encoder/DSP-VE paths blocked."
echo "NNAPI: experimental vendor-driver VE + vocoder with guarded CPU retry."
echo "QNN network downloads are cached under: ${QNN_CACHE}"
echo "Done."
echo "  ./gradlew :app:assembleDebug"
