#!/usr/bin/env bash
# Downloads every model in PLAN Section 10 to a DESKTOP dir for push_models.sh.
# URLs mirror models/ ModelRegistry.kt — if you change one place, change both.
# Usage: ./scripts/download_models.sh [--all] [--dest DIR] [DIR]
#   Default: default install set. --all also downloads the low-memory LLM.
set -uo pipefail

DEST="models"
INCLUDE_ALL=0

usage() {
  echo "Usage: $0 [--all] [--dest DIR] [DIR]"
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --all) INCLUDE_ALL=1; shift ;;
    --dest) DEST="${2:?--dest needs a directory}"; shift 2 ;;
    -h|--help) usage; exit 0 ;;
    -*) echo "Unknown flag: $1" >&2; usage; exit 2 ;;
    *) DEST="$1"; shift ;;
  esac
done

declare -a URLS=(
  # VAD (2 MB)
  "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/silero_vad_v5.onnx|silero_vad.onnx|6b99cbfd39246b6706f98ec13c7c50c6b299181f2474fa05cbc8046acc274396"
  # English STT int8
  "https://huggingface.co/csukuangfj/sherpa-onnx-streaming-zipformer-en-2023-06-26/resolve/main/encoder-epoch-99-avg-1-chunk-16-left-128.int8.onnx|sherpa-onnx-streaming-zipformer-en-2023-06-26-int8/encoder.onnx|563fde436d16cf7607cf408cd6b30909819d03162652ef389c2450ced3f45ac1"
  "https://huggingface.co/csukuangfj/sherpa-onnx-streaming-zipformer-en-2023-06-26/resolve/main/decoder-epoch-99-avg-1-chunk-16-left-128.onnx|sherpa-onnx-streaming-zipformer-en-2023-06-26-int8/decoder.onnx|7bf787f90b194b307e5a4ad6a34fadb4e748304c35f78a8d66358a05b13ee6ef"
  "https://huggingface.co/csukuangfj/sherpa-onnx-streaming-zipformer-en-2023-06-26/resolve/main/joiner-epoch-99-avg-1-chunk-16-left-128.onnx|sherpa-onnx-streaming-zipformer-en-2023-06-26-int8/joiner.onnx|210591f72b3c56b8364f85f345dca240bc2b4c00632848f4aa923630d5639d3b"
  "https://huggingface.co/csukuangfj/sherpa-onnx-streaming-zipformer-en-2023-06-26/resolve/main/tokens.txt|sherpa-onnx-streaming-zipformer-en-2023-06-26-int8/tokens.txt|49e3c2646595fd907228b3c6787069658f67b17377c60aeb8619c4551b2316fb"
  # Hindi STT (180 MB)
  "https://huggingface.co/parismitaglobalsolutions/indicconformer-sherpa-onnx/resolve/main/hi/model.int8.onnx|indicconformer-hi-int8/model.onnx|915c71e04dd7e5378a4057fdebb252b3a587188e4e99db6d7ce0909ad5ad05fa"
  "https://huggingface.co/parismitaglobalsolutions/indicconformer-sherpa-onnx/resolve/main/tokens.txt|indicconformer-hi-int8/tokens.txt|ee60967630213f31951817ac8b402b92ec18cce80718a24a49b388e56672dfb2"
  "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-whisper-tiny.tar.bz2|sherpa-onnx-whisper-tiny.tar.bz2|c46116994e539aa165266d96b325252728429c12535eb9d8b6a2b10f129e66b1"
  "https://github.com/k2-fsa/sherpa-onnx/releases/download/kws-models/sherpa-onnx-kws-zipformer-gigaspeech-3.3M-2024-01-01-mobile.tar.bz2|sherpa-onnx-kws-zipformer-gigaspeech-3.3M-2024-01-01-mobile.tar.bz2|2e6ac2577310bfa2f4b6b5fab0478b868c9d0b2cb2c51b3e13b50581b588864d"
  "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/kokoro-int8-multi-lang-v1_0.tar.bz2|kokoro-int8-multi-lang-v1_0.tar.bz2|75654a84864be26f345f020f4070c2c019e96dd1b7f9bf6e2ffd59efac6aa5a3"
  # Local LLM 2B Q4_K_M (~1.2 GB)
  "https://huggingface.co/TheStageAI/Qwen3.5-2B-GGUF/resolve/e42cdd7a10a99b70833c43bb1516a696c8075ea8/Qwen3.5-2B-M-TS-Q4_K_M.gguf|Qwen3.5-2B-Instruct-Q4_K_M.gguf|8d497863b95e392baf022258f864c34f4a28613df340c500bb647486c52657ae"
  # Piper fallbacks (archives unpack to the runtime directories)
  "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/vits-piper-hi_IN-pratham-medium.tar.bz2|vits-piper-hi_IN-pratham-medium.tar.bz2|2084d321e1d2752f2b64ed3012ba27751df01a80da46f52920098cdcb7e35648"
  "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/vits-piper-en_US-lessac-medium.tar.bz2|vits-piper-en_US-lessac-medium.tar.bz2|9e3febfacf0abf4270172d2958bcec246032b7e88efc2720840cc80c93de334e"
)

if [[ "$INCLUDE_ALL" == "1" ]]; then
  URLS+=(
    # Low-RAM LLM 0.8B (~0.5 GB)
    "https://huggingface.co/TheStageAI/Qwen3.5-0.8B-GGUF/resolve/fff685b81430bd58e703547bb6014f7b5d482f48/Qwen3.5-0.8B-M-TS-Q4_K_M.gguf|Qwen3.5-0.8B-Instruct-Q4_K_M.gguf|635788bdc1b0ba1335e47cca0159e531811c722ffad4e3c7b363c2b55ecc26c8"
  )
fi

mkdir -p "$DEST"

# No `set -e`: one failed file must not abort the remaining ~2 GB.
# -f fails on HTTP errors, -C - resumes partials like the in-app Range resume,
# --retry-all-errors covers 429/503 the same way the app falls back.
FAILED=()
sha256() {
  if command -v sha256sum >/dev/null 2>&1; then
    local output
    output="$(sha256sum "$1")"
    printf '%s\n' "${output%% *}"
  else
    local output
    output="$(shasum -a 256 "$1")"
    printf '%s\n' "${output%% *}"
  fi
}

for pair in "${URLS[@]}"; do
  url="${pair%%|*}"
  remainder="${pair#*|}"
  name="${remainder%%|*}"
  expected="${remainder##*|}"
  mkdir -p "$(dirname "$DEST/$name")"
  echo "==> $name"
  if [[ -f "$DEST/$name" ]] && [[ "$(sha256 "$DEST/$name")" == "$expected" ]]; then
    echo "    already verified: $name"
    continue
  fi
  if curl -fSL --retry 3 --retry-all-errors --retry-delay 5 -C - -o "$DEST/$name" "$url"; then
    actual="$(sha256 "$DEST/$name")"
    if [[ "$actual" == "$expected" ]]; then
      echo "    ok: $name"
    else
      echo "    FAILED checksum: $name" >&2
      rm -f "$DEST/$name"
      FAILED+=("$name")
    fi
  else
    echo "    FAILED: $name (re-run this script to resume it)" >&2
    FAILED+=("$name")
  fi
done

if [[ "${#FAILED[@]}" == "0" ]]; then
  ARCHIVES=(
    "sherpa-onnx-whisper-tiny.tar.bz2|sherpa-onnx-whisper-tiny|c46116994e539aa165266d96b325252728429c12535eb9d8b6a2b10f129e66b1"
    "sherpa-onnx-kws-zipformer-gigaspeech-3.3M-2024-01-01-mobile.tar.bz2|sherpa-onnx-kws-zipformer-gigaspeech-3.3M-2024-01-01-mobile|2e6ac2577310bfa2f4b6b5fab0478b868c9d0b2cb2c51b3e13b50581b588864d"
    "kokoro-int8-multi-lang-v1_0.tar.bz2|kokoro-int8-multi-lang-v1_0|75654a84864be26f345f020f4070c2c019e96dd1b7f9bf6e2ffd59efac6aa5a3"
    "vits-piper-hi_IN-pratham-medium.tar.bz2|vits-piper-hi_IN-pratham-medium|2084d321e1d2752f2b64ed3012ba27751df01a80da46f52920098cdcb7e35648"
    "vits-piper-en_US-lessac-medium.tar.bz2|vits-piper-en_US-lessac-medium|9e3febfacf0abf4270172d2958bcec246032b7e88efc2720840cc80c93de334e"
  )
  for spec in "${ARCHIVES[@]}"; do
    archive="${spec%%|*}"
    remainder="${spec#*|}"
    directory="${remainder%%|*}"
    checksum="${remainder##*|}"
    if tar -xjf "$DEST/$archive" -C "$DEST"; then
      touch "$DEST/$directory/.$checksum.installed"
    else
      FAILED+=("$archive")
    fi
  done
fi

echo "Writing checksums for ModelRegistry.kt:"
find "$DEST" -type f ! -name SHA256SUMS -exec shasum -a 256 {} + | tee "$DEST/SHA256SUMS"

if [[ "${#FAILED[@]}" -gt 0 ]]; then
  echo "FAILED files (${#FAILED[@]}): ${FAILED[*]}" >&2
  exit 1
fi
echo "Done."
