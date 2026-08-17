#!/bin/bash
set -euo pipefail

# Setup script for speech-android development environment.
# LiteRT (TFLite) runtime — Google's libLiteRt C API, matching speech-core's
# third_party/litert/ headers. 2.1.5 ships libLiteRt.so per ABI on Google Maven.
LITERT_VERSION="2.1.5"
LITERT_URL="https://dl.google.com/dl/android/maven2/com/google/ai/edge/litert/litert/${LITERT_VERSION}/litert-${LITERT_VERSION}.aar"

ROOT="$(cd "$(dirname "$0")" && pwd)"
LITERT_DIR="${ROOT}/litert"

echo "=== Supertonic TTS LiteRT setup ==="

# --- speech-core submodule ---

if [ ! -f "${ROOT}/speech-core/CMakeLists.txt" ]; then
    echo "Adding speech-core submodule..."
    cd "$ROOT"
    git submodule add https://github.com/soniqo/speech-core.git speech-core 2>/dev/null || true
    git submodule update --init --recursive
fi

# --- LiteRT runtime (libLiteRt) ---

if [ ! -f "${LITERT_DIR}/arm64-v8a/libLiteRt.so" ]; then
    echo "Downloading LiteRT ${LITERT_VERSION}..."

    TMP_DIR=$(mktemp -d)
    AAR_FILE="${TMP_DIR}/litert.aar"
    curl -L -o "$AAR_FILE" "$LITERT_URL"

    cd "$TMP_DIR"
    unzip -q "$AAR_FILE"

    # libLiteRt.so per ABI (+ optional GPU accelerator, runtime-loaded).
    for abi in arm64-v8a armeabi-v7a x86 x86_64; do
        if [ -f "jni/${abi}/libLiteRt.so" ]; then
            mkdir -p "${LITERT_DIR}/${abi}"
            cp "jni/${abi}/libLiteRt.so" "${LITERT_DIR}/${abi}/"
            [ -f "jni/${abi}/libLiteRtClGlAccelerator.so" ] && \
                cp "jni/${abi}/libLiteRtClGlAccelerator.so" "${LITERT_DIR}/${abi}/"
        fi
    done

    rm -rf "$TMP_DIR"
    echo "LiteRT installed to ${LITERT_DIR}"
else
    echo "LiteRT already installed"
fi

echo ""
echo "Done. This is a TTS-only Supertonic project."
echo "  ./gradlew :app:assembleDebug"
