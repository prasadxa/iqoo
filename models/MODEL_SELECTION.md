# Local Voice Model Selection

Reviewed against publisher pages on 2026-09-05. No claim of a universal best
model or phone benchmark is made. Recognition accuracy must be evaluated with
consented elderly-speaker Hindi, English and code-switched recordings.

## Implemented Selection

- English: existing int8 streaming Zipformer. Keeps low-latency partials and the
  small existing Android runtime footprint.
- Hindi: existing AI4Bharat IndicConformer CTC int8 export, about 198 MB.
- Hinglish: added Oriserve Whisper-Hindi2Hinglish-Swift via the Android-ready
  sherpa export. About 161 MB including vocabulary, 72.6M base parameters,
  Hindi transcription task, Romanized Hinglish output. Uses verified individual
  SHA-256 values and immutable revision
  `2d3ac712ec3a672444297555208dd030060962e3`. Falls back to installed IndicConformer
  if Swift cannot initialize. The extra `.weights` files are intermediate
  exports; the publisher states the int8 ONNX graphs are self-contained.
- Local TTS: retain Kokoro-82M int8 multilingual as primary, Piper as fallback.
  Fixed explicit Hindi/English pronunciation language in `GenerationConfig`;
  choosing a speaker alone does not choose the Kokoro phonemizer language.
  Existing speed mapping was corrected to use speed rather than its reciprocal.
- Cloud TTS: CallMissed Sonic 3.6 only, verified Preeti UUID
  `92da9281-7cf3-4c61-be0f-face03a3312f`. No provider switching for expressiveness.

## Alternatives Considered

- Parakeet TDT 0.6B v3 publishes strong multilingual scores but its listed 25
  European languages exclude Hindi. It is not a replacement for this app's
  Hindi/Hinglish path.
- IndicConformer 600M multilingual supports 22 Indian languages, but a larger
  gated checkpoint is not automatically a better drop-in mobile choice.
- Hinglish Apex has stronger published recognition results than Swift but an
  approximately 1 GB int8 download; not selected without device latency/RAM tests.
- Pocket TTS and IndicF5 raw model-card requests returned HTTP 401 in this
  environment. No gated access was bypassed and neither was selected on guesses.
- Kokoro has an Apache-2.0 82M multilingual release. Its size/runtime fit is
  preferable to adding an unbenchmarked large generative TTS stack here.

The Swift model card reports material recognition error rates. It is intended
for code-switching, not a verified accuracy improvement on every utterance.
Calls, messages and health-related requests still require safe confirmation.

## Sources

- https://huggingface.co/parismitaglobalsolutions/indicconformer-sherpa-onnx
- https://huggingface.co/Oriserve/Whisper-Hindi2Hinglish-Swift
- https://huggingface.co/hexgrad/Kokoro-82M
- https://huggingface.co/ai4bharat/indic-conformer-600m-multilingual
- https://huggingface.co/nvidia/parakeet-tdt-0.6b-v3
- https://github.com/k2-fsa/sherpa-onnx/blob/v1.13.5/sherpa-onnx/kotlin-api/Tts.kt
- https://github.com/k2-fsa/sherpa-onnx/blob/v1.13.5/sherpa-onnx/csrc/offline-tts-kokoro-impl.h
- https://docs.callmissed.com/docs/tts-voices
- https://api.callmissed.com/api/v1/models/sonic-3.6/voices?q=preeti

## Verification Still Needed

Download the selected files, then test native loading, speaker echo at different
volumes, explicit interruption, offline full turns, inference latency, memory,
thermal behavior and battery drain on the intended phone. JVM tests and Compose
screen tests cannot prove acoustic quality or native-model accuracy.
