# Safe Speech and Supported Controls

Documentation checked 2026-09-05. **Current app policy: Sonic 3.6 with Preeti
only** (`92da9281-7cf3-4c61-be0f-face03a3312f`). Older provider comparisons below
are research notes, not available app options. These are source-backed capabilities, not
claims from a live listening test. No paid synthesis requests were made.

## Provider Contract

| Path | Supported here | Not claimed or forwarded |
| --- | --- | --- |
| CallMissed `sonic-3.6` REST | `voice`, base `language`, `speed` 0.6-1.5, PCM16 mono at requested 24 kHz | SSML pass-through, `generation_config`, emotion, volume, pitch, `instructions`, `temperature`, Sonic audio streaming |
| CallMissed `bulbul:v3` REST | Existing `temperature` option, validated 0.01-2.0; speed 0.5-2.0 | SSML, explicit emotions, pause markup |
| Local sherpa Kokoro | Speaker ID, generation speed, punctuation in plain text | SSML, emotion, pitch, phoneme/alias overrides |
| Local sherpa Piper | Model/language selection, generation speed, punctuation in plain text | SSML, emotion, pitch, phoneme/alias overrides |
| Managed voice agent | Existing model/voice settings and normalized client greeting | Undocumented speed/emotion settings; client-side normalization of server-generated replies |

CallMissed explicitly lists `kabir` and `riya` as native-Hindi Sonic aliases.
REST callers may pass `hi-IN`/`en-IN`; `TtsApi` sends `hi`/`en` for Sonic as its
documentation requires. Voice and language otherwise retain caller selection.
The enum is still the existing Sonic-oriented pair, not a cross-provider voice
catalog: changing the model does not make those voices portable.

Direct Cartesia exposes SSML-like `speed`, `volume`, `emotion`, `break`, and
`spell` tags, plus request-level `generation_config`. **That is not proof that
CallMissed accepts or forwards them.** CallMissed's REST parameter table does
not document these controls for Sonic. Cartesia also labels emotion guidance
experimental and its emotion tags English-only. Hindi emotional performance is
not promised. No fake emotion SDK or undocumented pass-through was added.

For other existing REST `model` overrides, only documented speed fields are
sent: Gnani 0.85-1.15, Deepgram Aura-1/2 0.7-1.5, GPT mini TTS 0.25-4.0.
Unsupported/unknown models get no speed field. All non-finite speeds are rejected.
Finite values are clamped to the provider's range. `temperature` is sent only
for the documented `bulbul:v3` model. GPT delivery instructions are documented
upstream but not exposed by this Sonic-focused wrapper.

## Input Contract

Normalization occurs immediately before `TtsApi` builds the HTTP body and before
both local wrappers call native `generate`. Managed-agent `defaultSettings`
also normalizes the greeting; it does not rewrite the system prompt or tool data.
The low-level `connect(settingsOverride)` accepts caller-owned serialized settings
and is not a second settings/SSML parser.

| Input | Result |
| --- | --- |
| Plain Hindi, Hinglish, English, user spelling | Preserve text and pronunciation spelling; collapse whitespace only |
| Ordinary comparisons, arithmetic, underscores, negative numbers | Preserve, not a blanket punctuation/symbol deletion |
| XML predefined and numeric character references | Decode to text once; normal JSON serialization performs wire escaping |
| `speak`, `emphasis`, `prosody`, vendor tags, `voice`, `lang`, `phoneme`, `sub`, etc. | Keep textual children, discard tags and attributes; do not claim their delivery effects |
| `break` | A comma/space boundary if needed, never a duration promise or inserted PCM silence |
| `p`, `s` | Whitespace and sentence punctuation, avoiding a second stop after existing punctuation |
| `audio` | Keep fallback text, never fetch the URL |
| Common paired Markdown emphasis/code/strikethrough | Strip formatting, retain content, especially negations |
| Markdown headings, code fences, HTTP(S) links | Strip supported formatting, keep code and visible link text |
| Malformed, excessive, or encoded markup | Reject the entire input; never synthesize a partially parsed prefix |

This is conservative formatting cleanup, **not a complete SSML or Markdown
renderer**. It does not transliterate Hindi, expand numbers/dosages, invent
pronunciations, infer mood, add filler words, or discard struck-through text.
For pronunciation, supply the desired spoken spelling in the text. `ph`,
`alias`, `interpret-as`, `rate`, `time`, `pitch`, `volume`, and emotion attributes
are not applied. Exact timing, spelling mode, audio embedding, per-span speed,
and voice changes require separately supported engine features.

Literal comparisons such as `2 < 3`, `a<b`, and `x<y && y>0` survive. Complete
angle-bracket words such as `<name>` are treated as markup, and ambiguous
tag-like incomplete input is rejected rather than guessed. XML declarations,
comments, DTDs, processing instructions, and CDATA are deliberately rejected.

Safety limits: 4096 UTF-16 input units before parsing, 4096 output units, 32
nested elements, and 512 elements total, excluding the synthetic parser root.
No truncation is used: cutting off a trailing "not" is unsafe. External general
and parameter entities are explicitly disabled, the entity resolver refuses
external resolution, and declarations are blocked before SAX receives the
document. Unknown entities fail closed. Parser errors do not include private
speech text. Each call owns its parser; there is no cross-utterance state.

Empty/invalid local input returns zero samples without marking a loaded voice
unhealthy. Empty/invalid REST input raises `IllegalArgumentException` before any
HTTP request. An empty managed greeting remains allowed. CallMissed receives
`humanize: false` because the documented server humanizer rewrites numbers,
URLs and emails in addition to formatting. This avoids a second, less controlled
text rewrite; it does not disable a model's inherent text-to-speech frontend.

The two internal `SpeechInput` implementations and matching tests intentionally
share the same contract without introducing a cloud dependency into the offline
engine. A common-module extraction would need ownership outside this task;
keep both copies synchronized until that is explicitly approved.

## Speed and Streaming

Sherpa v1.13.5 `generate(text, sid, speed)` takes **speed**, not `lengthScale`.
Kokoro now uses `clamp(preference, 0.5, 1.5) / 1.1`; its model-level length scale
is neutral so sherpa's special `speed == 1` fallback cannot apply the slowdown
twice. Piper uses `0.9 * clamp(preference, 0.5, 1.5)`. Both retain a slower
baseline, increase actual speed when the preference increases, and replace
non-finite preferences with the default. Voice selection is unchanged.

Current integration audit (read-only; no pipeline/service changes):

- The shared `SentenceChunker` emits at punctuation after 12 characters, force
  flushes at 220 buffered characters, and flushes the tail on completion. It has
  **no elapsed-time flush** and is not XML-aware. A fragment split inside a tag
  or a wrapping element fails closed at this boundary; callers should generate
  plain spoken text, not stream SSML tokens. These wrappers cannot repair a
  previous chunk or recover missing markup/negation from a later one.
- Each local call uses synchronous `generate`, not `generateWithCallback`.
  Sentence queuing is not native audio-frame streaming. Preferences are read
  for each synthesis call, not changed midway through already-generated audio.
- `TtsApi` reads the complete response with `bytes()`. It returns one PCM buffer;
  it does not expose time-to-first-audio streaming. CallMissed documents REST
  `stream` for Deepgram, not Sonic; no undocumented Sonic flag is sent.
- App `playCloudTts` and `LocalTtsRouter` now pass the selected speed, with a
  0.9 baseline clamped to Sonic's 0.6-1.5 range. Both always use Preeti.
- Kokoro now calls `generateWithConfig` with an explicit `lang` override. Its
  Hindi speaker identity alone was not sufficient to select Hindi pronunciation.
- Managed-agent settings include model/voice only. Its server runs synthesis;
  received PCM cannot be text-normalized after the fact. `AgentAudioDone` means
  the last audio was sent, not that playback drained. No managed speed/emotion
  behavior is inferred from the REST API.

## Sources

- [CallMissed TTS parameters, humanize and streaming](https://docs.callmissed.com/docs/text-to-speech)
- [CallMissed Sonic aliases and language codes](https://docs.callmissed.com/docs/tts-voices)
- [CallMissed managed-agent settings and timing](https://docs.callmissed.com/docs/managed-voice-agent)
- [Cartesia SSML-like tags and streaming warnings](https://docs.cartesia.ai/build-with-cartesia/capability-guides/ssml-tags)
- [Cartesia volume, speed and emotion limitations](https://docs.cartesia.ai/build-with-cartesia/capability-guides/volume-speed-emotion)
- [sherpa v1.13.5 Kotlin TTS API](https://github.com/k2-fsa/sherpa-onnx/blob/v1.13.5/sherpa-onnx/kotlin-api/Tts.kt)
- [Kokoro native speed semantics](https://github.com/k2-fsa/sherpa-onnx/blob/v1.13.5/sherpa-onnx/csrc/offline-tts-kokoro-model.cc)
- [Piper/VITS native speed and model-specific emotion paths](https://github.com/k2-fsa/sherpa-onnx/blob/v1.13.5/sherpa-onnx/csrc/offline-tts-vits-model.cc)
- [Android Expat SAX feature support](https://android.googlesource.com/platform/libcore/+/refs/heads/main/luni/src/main/java/org/apache/harmony/xml/ExpatReader.java)

The generic VITS API has an `emotion_id` for specially trained multi-emotion
models. The Piper path does not use it; it is not an emotion control for the
installed Pratham/Lessac models. Unit tests cover normalization, rejection,
wire parameters and speed mapping. Acoustic quality, exact timing, Hindi
pronunciation and on-device JNI synthesis still require real listening tests.
