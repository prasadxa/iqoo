#!/usr/bin/env bash
# Demo-day fast-path (PLAN 6.3 / 10): push pre-downloaded models straight to
# the phone, skipping the multi-GB in-app download.
# Usage: PKG=com.aasra.app ./scripts/push_models.sh [local_models_dir]
set -euo pipefail
shopt -s nullglob

PKG="${PKG:-com.aasra.companion}"
SRC="${1:-models}"
TARGET="/sdcard/Android/data/$PKG/files/models/"

command -v adb >/dev/null || { echo "adb not found on PATH" >&2; exit 2; }
adb devices -l
[[ "$(adb get-state 2>/dev/null)" == "device" ]] || { echo "No device ready (adb get-state)" >&2; exit 2; }
adb shell "mkdir -p '$TARGET'"

[[ -d "$SRC" ]] || { echo "Model directory not found: $SRC" >&2; exit 2; }
echo "==> pushing model tree"
adb push "$SRC/." "$TARGET"

echo "Device free space at target:"
adb shell "df -h '$TARGET'"

echo "Verifying on device:"
adb shell "ls -lh '$TARGET'"

echo "Model tree pushed. The app adopts it on next launch"
echo "(ModelPaths.adoptAdbPushedFiles) and onboarding marks them ready."
