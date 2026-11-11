#!/usr/bin/env bash
# Downloads the speech files that are too big for git:
#   - sherpa-onnx Android library  -> app/libs/          (needed to build)
#   - Parakeet TDT v3 INT8 model   -> models/parakeet/   (pushed to the phone, not bundled in the APK)
# Safe to re-run: files that already exist are skipped.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SHERPA_VERSION="1.13.3"
HF="https://huggingface.co/csukuangfj/sherpa-onnx-nemo-parakeet-tdt-0.6b-v3-int8/resolve/main"
mkdir -p "$ROOT/app/libs" "$ROOT/models/parakeet"

dl() { # url dest
  if [ -s "$2" ]; then echo "  (have) ${2#$ROOT/}"; else echo "  downloading ${2#$ROOT/}"; curl -fsSL -o "$2" "$1"; fi
}

echo "sherpa-onnx $SHERPA_VERSION"
dl "https://github.com/k2-fsa/sherpa-onnx/releases/download/v$SHERPA_VERSION/sherpa-onnx-$SHERPA_VERSION.aar" \
   "$ROOT/app/libs/sherpa-onnx-$SHERPA_VERSION.aar"

echo "Parakeet TDT v3 (~640 MB)"
for f in encoder.int8.onnx decoder.int8.onnx joiner.int8.onnx tokens.txt; do
  dl "$HF/$f" "$ROOT/models/parakeet/$f"
done
dl "$HF/test_wavs/en.wav" "$ROOT/models/parakeet/test.wav"
