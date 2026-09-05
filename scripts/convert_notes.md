# Asset installation notes: sherpa-onnx archives

The model catalog downloads the publisher archives intact, verifies their SHA-256 digests, and extracts them beneath `filesDir/models/`. `ModelDownloader` writes a `.<sha256>.installed` marker only after extraction succeeds.

Expected runtime layout:

```
models/
  sherpa-onnx-whisper-tiny/
    tiny-encoder.int8.onnx
    tiny-decoder.int8.onnx
  sherpa-onnx-kws-zipformer-gigaspeech-3.3M-2024-01-01-mobile/
    encoder-epoch-12-avg-2-chunk-16-left-64.int8.onnx
    decoder-epoch-12-avg-2-chunk-16-left-64.onnx
    joiner-epoch-12-avg-2-chunk-16-left-64.int8.onnx
    tokens.txt
  kokoro-int8-multi-lang-v1_0/
    model.int8.onnx
    voices.bin
    tokens.txt
    lexicon-us-en.txt
    espeak-ng-data/...
  vits-piper-hi_IN-pratham-medium/...
  vits-piper-en_US-lessac-medium/...
```

Kokoro v1.0 is intentional: its speaker table includes the English and Hindi voices used by the app. Speaker IDs are `af_heart=3`, `am_adam=11`, `hf_alpha=31`, and `hm_omega=33`.

For the demo-phone fast path, run `scripts/download_models.sh`, then `scripts/push_models.sh`. The download script verifies every artifact and creates the same installation markers expected by `ModelPaths`.
