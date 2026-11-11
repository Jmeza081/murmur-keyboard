#!/usr/bin/env bash
# Copies the Parakeet model from models/parakeet/ into the app's private files folder on the phone.
# Works with debug builds only (it uses run-as). Release builds will import the model in the app.
# Usage: scripts/push-model.sh [package]   (default: dev.murmur.keyboard.debug)
# Set ANDROID_SERIAL first if more than one device is connected.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
PKG="${1:-dev.murmur.keyboard.debug}"
TMP="/data/local/tmp/murmur-model"
FILES="encoder.int8.onnx decoder.int8.onnx joiner.int8.onnx tokens.txt test.wav"

adb shell mkdir -p "$TMP"
for f in $FILES; do
  echo "  pushing $f"
  adb push "$ROOT/models/parakeet/$f" "$TMP/$f" >/dev/null 2>&1
done
adb shell chmod 644 "$TMP"/*
echo "  copying into $PKG"
adb shell run-as "$PKG" sh -c "'mkdir -p files/models/parakeet && cp $TMP/* files/models/parakeet/'"
adb shell rm -rf "$TMP"
echo "Model is in /data/data/$PKG/files/models/parakeet"
