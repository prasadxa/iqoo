# Aasra Voice Companion: Build Plan

Hackathon: iQOO City Battles 2026
App: on-device voice assistant for elderly users. No backend server. Simple turns
run fully offline on the phone; hard turns escalate to CallMissed inference APIs
called directly from the device.

Status legend: `[ ]` not implemented, `[~]` implemented but awaiting physical-device verification, `[x]` verified complete.

---

## 0. Decisions (locked)

| Decision | Choice | Why |
|---|---|---|
| Platform | Android native, Kotlin, Jetpack Compose | Best on-device AI runtimes (sherpa-onnx, llama.cpp, LiteRT-LM) are Kotlin-first; OS tools (calls, SMS, alarms) need native access; demo device is an iQOO Snapdragon flagship |
| Backend | None. Direct HTTPS/WSS from phone to `api.callmissed.com` | Requirement: serverless |
| Min SDK | 29 (Android 10), target 35 | Covers the demo device and most 2019+ phones |
| Device budget | 12 GB RAM flagship (primary), 6 GB mid-range (degraded mode) | Model tiering below |
| Languages offline | English + Hindi/Hinglish | Everything else goes to the cloud (`saaras:v4` handles 22 Indic langs) |
| Cloud provider | CallMissed only | OpenAI-compatible, free tier covers every model we need |
| LLM (local) | Qwen3.5 2B Q4_K_M GGUF via llama.cpp | Tool calling, Hindi, 26-40 tok/s on Snapdragon 8 Elite |
| LLM (cloud) | `sarvam-105b-conversations` (primary), `kimi-k2.5` (fallback) | Tuned for voice, tool calling, Indic; both free tier |
| STT (local) | sherpa-onnx streaming Zipformer (en) + IndicConformer int8 (hi) + Silero VAD | True streaming partials, verified on budget Android |
| STT (cloud) | `saaras:v4` (Indic/code-mixed), `whisper-large-v3-turbo` (other) | Current CallMissed model; free tier supports both |
| TTS (local) | Kokoro-82M int8 v1.0 via sherpa-onnx (en + hi), Piper VITS as instant fallback | v1.0 includes Hindi voices; Piper is the speed fallback |
| TTS (cloud) | `sonic-3.6` voice `kabir`/`riya`, `speed` 0.9 | Current eligible low-latency CallMissed voices |
| Cloud voice mode | Managed Voice Agent with `saaras:v4`, `sarvam-105b-conversations`, and `sonic-3.6` | Plain WebSocket + raw PCM, no SDK; all three models were live-eligible on 2026-09-04 |
| Pipeline base | Fork of `loyality7/speech-to-speech-mobile` (Kotlin AAR) | Already has VAD -> STT -> LLM -> chunker -> TTS with barge-in |
| Stretch | Gemma 4 E2B via LiteRT-LM (audio in + reasoning + tools in one model) | Only if time allows |

---

## 1. Architecture

```
                    +------------------------------------------------------+
  Mic (16 kHz PCM)  |  ON-DEVICE PIPELINE (always running)                  |
  ------------------>  Silero VAD -> streaming STT -> Router -> local LLM   |
                    |      |             |               |          |        |
                    |  barge-in     partial text     escalate?   tool calls |
                    |      |             |               |          |        |
  Speaker <---------|  Kokoro TTS <- sentence chunker <-+----------+        |
                    +------------------------------|-----------------------+
                                                   | escalate(reason)
                                                   v
                    +------------------------------------------------------+
                    |  CLOUD PATH (CallMissed, called directly from phone)  |
                    |  a) text turn:  POST /v1/chat/completions (stream)    |
                    |  b) re-listen:  POST /v1/audio/transcriptions saaras  |
                    |  c) better TTS: POST /v1/audio/speech sonic-3.6       |
                    |  d) full cloud: WSS /v2/voice/agent (managed agent)   |
                    |  e) facts:      POST /v1/search                       |
                    +------------------------------------------------------+
```

Three run modes, switchable in Settings (and auto-selected by connectivity):

1. **Offline mode**: everything local. Used when no network or user opts out.
2. **Hybrid mode (default)**: local loop, cloud only on `escalate`.
3. **Cloud mode**: Managed Voice Agent WebSocket carries the whole conversation.
   This is the safety net if local models fail to load on demo day.

---

## 2. Repository layout

```
iqoo/
  PLAN.md
  app/                         Compose UI, navigation, DI, foreground service
  core-audio/                  AudioRecord/AudioTrack, resampling, ring buffers
  core-pipeline/               VAD, STT, LLM, TTS interfaces + orchestrator (from fork)
  engine-sherpa/               sherpa-onnx wrappers: VAD, streaming STT, TTS, KWS
  engine-llama/                llama.cpp JNI wrapper, GGUF loading, tool-call parsing
  cloud-callmissed/            OkHttp client: chat, transcriptions, speech, search, voice-agent WS
  tools/                       Device tools: call, SMS, alarm, reminders, SOS, flashlight, time
  models/                      ModelRegistry, downloader, integrity checks, tiering
  data/                        Room DB: contacts, reminders, conversation log
  scripts/                     model download + conversion scripts (desktop)
```

---

## 3. Phase 0: Environment and accounts (Day 0, ~2 h)

- [~] Install Android Studio (Ladybug or newer), NDK r27, CMake 3.22+, JDK 17.
- [ ] Enable USB debugging on the iQOO phone; confirm `adb devices`.
- [~] Create a CallMissed account at console.callmissed.com, create an API key with
      `llm`, `stt`, `tts`, `search` permissions. It looks like `cm_...` and is shown once.
- [~] Verify the key from the laptop:
      ```bash
      curl https://api.callmissed.com/v1/chat/completions \
        -H "Authorization: Bearer $CM_KEY" -H "Content-Type: application/json" \
        -d '{"model":"sarvam-105b-conversations","messages":[{"role":"user","content":"Namaste"}]}'
      ```
- [~] Check plan limits. Free plan: 100 LLM calls, 50 STT, 50 TTS per month, 60 RPM,
      100 credits/month + 1000 signup credits. Cloud mode burns these quickly:
      budget the demo, or move to Starter before the event. Watch `X-RateLimit-Remaining`.
- [~] Put the key in `local.properties` as `CALLMISSED_API_KEY=cm_...` and expose it via
      `BuildConfig`. Add `local.properties` to `.gitignore`. Never commit the key.
- [~] `git init`, first commit with this plan.

---

## 4. Phase 1: Project skeleton and on-device loop (Day 1)

### 4.1 Fork the pipeline
- [~] Clone `loyality7/speech-to-speech-mobile`; copy `bindings/android/` into
      `core-pipeline/` and `engine-*` modules. Keep its stage interfaces:
      `AudioInput`, `AudioOutput`, `SpeechRecognizer`, `LanguageModel`,
      `SpeechSynthesizer`, `TextChunker`, `Tools`.
- [~] Add sherpa-onnx from JitPack (`com.github.k2-fsa:sherpa-onnx:v1.13.5`) and the
      llama.cpp Android binding it uses (Llamatik). Build once, run the demo unchanged.

### 4.2 Audio I/O
- [~] `AudioRecord`: 16 kHz mono PCM16, `VOICE_RECOGNITION` source, 20 ms frames.
- [~] `AudioTrack`: 24 kHz mono for Kokoro output, low-latency `PERFORMANCE_MODE_LOW_LATENCY`.
- [~] Echo handling: mute VAD input while TTS plays unless barge-in energy exceeds
      threshold (the fork already does this; verify on the iQOO speaker).
- [~] Foreground service with a persistent notification so listening survives screen-off.

### 4.3 VAD + wake word
- [~] Silero VAD v5 ONNX. Tune for elderly speech: `min_silence_duration = 1.3 s`,
      `min_speech_duration = 0.25 s`, threshold 0.5.
- [~] sherpa-onnx keyword spotter with keywords "aasra", "help", "madad".
      Wake word only when the app is in background; foreground uses continuous VAD.

### 4.4 STT (local)
- [~] English: `sherpa-onnx-streaming-zipformer-en-2023-06-26` (int8) via `OnlineRecognizer`.
- [~] Hindi/Hinglish: `parismitaglobalsolutions/indicconformer-sherpa-onnx` (hi, int8)
      via `OfflineRecognizer` on VAD-segmented audio; Hinglish Whisper variant optional.
- [~] Language selection: user setting first; if "auto", run spoken language ID
      (sherpa-onnx whisper-tiny LID) on the first segment of each session.
- [~] Emit `partial` and `final` transcripts + a confidence proxy (avg token logprob
      or Zipformer's endpoint confidence). Low confidence is an escalation trigger.

### 4.5 LLM (local)
- [~] Download `Qwen3.5-2B-Instruct-Q4_K_M.gguf` (~1.2 GB). Load with
      `n_ctx = 4096`, `n_threads = big cores`, GPU offload via Vulkan on if stable.
- [~] System prompt (short, spoken-style, one to two sentences, no markdown, no emoji,
      address user respectfully, confirm before any action, never give dosages).
- [~] Streaming generation -> sentence chunker -> TTS queue.
- [~] Tool-call parsing (Qwen ChatML `<tool_call>` JSON). Tools listed in Section 6.
- [~] Tier fallback: if free RAM < 5 GB at load, use `Qwen3.5-0.8B` instead.

### 4.6 TTS (local)
- [~] Kokoro-82M int8 multi-lang v1.0 ONNX via sherpa-onnx `OfflineTts`, `lang = en` / `hi`,
      voice `af_heart` (en) and a Hindi voice (`hf_alpha` / `hm_omega`), `length_scale = 1.1`
      (slightly slower for elderly listeners).
- [~] Piper `vits-piper-hi_IN-*` and `vits-piper-en_US-lessac-medium` as fallbacks when
      Kokoro RTF > 1.0 on the device (measure on first launch, persist result).
- [~] Stream per sentence: start playback when the first sentence is synthesized.

### 4.7 Milestone M1
- [ ] Say "what time is it" and "mujhe paani peene ki yaad dilana" fully offline,
      hear a spoken answer, interrupt it mid-sentence, and see it stop.
- [ ] Measure and log: VAD end -> first audio. Target < 1.5 s on the iQOO.

---

## 5. Phase 2: CallMissed cloud layer (Day 2)

All calls use `Authorization: Bearer <cm_key>`, base `https://api.callmissed.com`.
Client: OkHttp + kotlinx.serialization. One `CallMissedClient` class in `cloud-callmissed/`.

### 5.1 Chat completions (text escalation)
- [~] `POST /v1/chat/completions`, `model = sarvam-105b-conversations`, `stream = true`,
      `temperature = 0.4`, `max_tokens = 300`, `reasoning_effort = "low"` (fast),
      `stream_options.include_usage = true`.
- [~] Send the same system prompt + the last 10 turns + the shared device tools; expose
      `escalate` only to local Qwen and `web_search` only to cloud models.
- [~] Parse SSE `data:` lines; feed `delta.content` into the same sentence chunker so
      TTS starts before the reply finishes. Handle `finish_reason = tool_calls`.
- [~] Fallback chain on 429/503: `sarvam-105b-conversations` -> `sarvam-105b` -> `kimi-k2.5`.
      Never retry on 402 (out of credits) or 429 with `code = quota_exceeded`.
- [ ] Optional vision: `kimi-k2.5` supports image input; use for "read this letter /
      medicine strip" via camera (stretch).

### 5.2 Speech-to-text (re-listen on low confidence or unsupported language)
- [~] `POST /v1/audio/transcriptions` multipart: `file` (WAV 16 kHz of the last VAD
      segment, kept in a ring buffer), `model = saaras:v4`, `language` omitted for auto,
      `mode = codemix` when user language is Hinglish.
- [~] Trigger when: local STT confidence < threshold, detected language not in {en, hi},
      or the user says "repeat / samjha nahi" twice in a row.

### 5.3 Text-to-speech (premium voice for long or emotional replies)
- [~] `POST /v1/audio/speech`, `model = sonic-3.6`, `voice = kabir` (male) or `riya` (female,
      user picks in onboarding), `language = hi-IN` / `en-IN`, `speed = 0.9`,
      `response_format = pcm`, `speech_sample_rate = 24000`.
- [~] Use only when local Kokoro/Piper is unavailable and the phone is online; prefer local TTS otherwise.

### 5.4 Managed Voice Agent (cloud mode / safety net)
- [~] Open `wss://api.callmissed.com/v2/voice/agent` with header `Authorization: Token cm_...`.
- [~] Wait for `Welcome`, send `Settings`:
      ```json
      {"type":"Settings",
       "audio":{"input":{"encoding":"linear16","sample_rate":16000},
                "output":{"encoding":"linear16","sample_rate":24000}},
       "agent":{"prompt":"<same system prompt>","greeting":"Namaste, main Aasra hoon.",
                "language":"hi-IN",
                "llm":{"model":"sarvam-105b-conversations","temperature":0.4},
                "stt":{"model":"saaras:v4"},
                "tts":{"model":"sonic-3.6","voice":"kabir"}},
       "tags":["aasra"]}
      ```
- [~] After `SettingsApplied`, stream mic PCM as binary frames; play received binary
      frames through `AudioTrack`.
- [~] Handle events: `UserStartedSpeaking` (flush playback buffer immediately —
      the ONLY barge-in signal), `ConversationText` (update transcript UI),
      `AgentThinking` / `AgentStartedSpeaking` / `AgentAudioDone` (turn progress;
      `AgentAudioDone` = last chunk SENT, buffer may still drain),
      `FunctionCallRequest` (run device tool, reply `FunctionCallResponse`
      `{type,id,name,content}` within 30 s; `functions[]` array, `arguments`
      is a JSON string), `LatencyReport` (log), `Warning` (non-fatal, continue),
      `Error` (reconnect). `KeepAlive` holds an idle socket; `UpdatePrompt`
      appends to the live system prompt.
- [~] Declare the same tool schema in `Settings` so cloud mode can also call contacts,
      set reminders, etc.
- [~] `GET /api/v1/voice/models` once at startup to confirm the chosen models are `eligible`.

### 5.5 Web search tool
- [~] `POST /v1/search`, `mode = shorter`, `gl = in`, `hl = hi|en`, `num_results = 5`.
      Exposed to both LLMs as tool `web_search(query)`. 1 credit per call, so gate it
      behind the router (only for "news / weather / price / when is" type questions).

### 5.6 Router (when to escalate)
Local LLM has a tool `escalate(reason: enum)`. Also hard rules before the LLM runs.

| Trigger | Action |
|---|---|
| No network | Stay local, say so if the request needs cloud |
| Local STT confidence low, or language not en/hi | 5.2 re-listen with `saaras:v4` |
| Keywords: medicine, dose, doctor, symptom, pain, BP, sugar | Cloud LLM (5.1) with a safety-first prompt |
| Needs current info: news, weather, price, "kab hai" | `web_search` then cloud LLM |
| Transcript > 60 words or multi-step instruction | Cloud LLM |
| Local LLM emits `escalate(...)` | Cloud LLM |
| Local model failed to load / OOM | Switch to Cloud mode (5.4) |

- [~] Implement as `Router.decide(transcript, confidence, network, ramState): Route`.
- [~] Log every decision with latency for the demo dashboard.

### 5.7 Milestone M2
- [ ] Hybrid: "set alarm for 7" stays local; "is it safe to take paracetamol with my
      BP medicine" escalates to `sarvam-105b-conversations`, streams back, spoken by Kokoro.
- [ ] Cloud mode: full conversation over the Managed Voice Agent with barge-in working.
- [ ] Airplane mode: app still answers local requests and explains cloud is unavailable.

---

## 6. Phase 3: Elderly-specific tools and UX (Day 3)

### 6.1 Device tools (shared JSON schema for local + cloud LLMs)
- [~] `call_contact(name)`: fuzzy match against Room `Contact` table (nickname support:
      "beta", "bahu"); confirm by voice; `ACTION_CALL` with `CALL_PHONE` permission.
- [~] `send_sms(name, message)`: `SmsManager`, confirm before send.
- [~] `set_reminder(text, time, repeat)`: `AlarmManager` exact alarms + Room record; spoken
      reminder through TTS when it fires (medicine, water, walk).
- [~] `list_reminders()`, `cancel_reminder(id)`.
- [~] `sos()`: call the primary emergency contact, SMS location to all emergency contacts,
      and expose it through the big red button.
- [ ] Add the 3x power-button AccessibilityService trigger.
- [~] `get_time()`, `get_date()`, `set_volume(level)`, `flashlight(on|off)`.
- [~] `read_notifications()`: NotificationListenerService, read the last 3 aloud.
- [~] `web_search(query)`: cloud only (5.5).
- [~] `escalate(reason)`: local only, no-op for cloud model.

### 6.2 UI (Compose), designed for low vision and tremor
- [~] One main screen: giant mic state indicator (listening / thinking / speaking) driven
      by the pipeline state, live transcript in 28 sp+ text, last answer in 24 sp+.
- [~] Three fixed big buttons: Talk (hold or tap), SOS, Repeat.
- [~] High contrast, no thin type, tap targets >= 64 dp, haptic feedback on state changes.
- [~] Onboarding wizard: language, voice (male/female), name to be addressed by,
      emergency contacts, model download with progress and Wi-Fi check.
- [~] Settings: mode (Offline / Hybrid / Cloud), speech speed, wake word toggle,
      cloud usage counter (from `X-RateLimit-Remaining` headers).
- [ ] Caregiver view (optional): today's reminders taken/missed, last SOS.
- [~] Follow the anti-slop design rules in `~/.claude/CLAUDE.md`: no gradients, no glow,
      no pill badges, real palette, one considered typeface pairing.

### 6.3 Model management
- [~] `ModelRegistry` with name, URL (Hugging Face), size, SHA-256, tier.
- [~] Downloader with resume (OkHttp Range), Wi-Fi only by default, progress notifications.
- [~] `adb push` fast-path documented for the demo phone (skip the 2 GB download).
- [~] Verify checksums before load; delete and re-download on mismatch.

### 6.4 Milestone M3
- [ ] Full demo script runs end to end (Section 9) on the iQOO device.

---

## 7. Phase 4: Hardening and demo prep (Day 4)

- [~] Thermal: log SoC temperature; if throttled, drop LLM to 0.8B and TTS to Piper.
- [~] Memory: load LLM lazily after STT/TTS warm; unload LLM after 5 min idle.
- [~] Battery: wake-word mode uses KWS only (no STT/LLM) until triggered.
- [~] Error UX: every failure has a spoken, non-technical message in the user's language.
- [ ] Offline test matrix: airplane mode, Wi-Fi without internet, cloud 402/429/503 mocked.
- [ ] Cloud budget check the night before: `GET /api/v1/models/access`, remaining caps,
      credit balance. Pre-warm one request so the first demo turn is not the slow one.
- [ ] Record a fallback video of the full demo in case the venue network or phone fails.
- [ ] Prepare a latency slide from the `LatencyReport` and local timing logs.

---

## 8. Stretch goals (only after M3)

- [ ] Gemma 4 E2B via LiteRT-LM as an alternate local brain: audio in directly (no STT),
      native `ToolSet` function calling, NPU on Snapdragon. Compare TTFT with the cascade.
- [ ] Camera "read this to me": photo -> `kimi-k2.5` (vision) -> spoken summary.
- [ ] Voice cloning of a family member for reminders with MOSS-TTS-Nano (consent required).
- [ ] Hinglish Whisper STT variant for heavy code-switching.
- [ ] Conversation memory: local embeddings (`text-embedding-3-small` via cloud, or a
      small local ONNX embedder) + Room for "what did the doctor say yesterday".

---

## 9. Demo script (5 minutes)

1. Airplane mode on. "Aasra, what time is it?" -> instant local answer. Show the
   "offline" badge.
2. "Mujhe roz subah 8 baje dawai ki yaad dilana." -> local tool `set_reminder`,
   confirmation spoken back in Hindi.
3. Airplane mode off. "Can I take paracetamol with my blood pressure tablet?" ->
   router shows "escalated: medical", cloud `sarvam-105b-conversations` streams, Kokoro
   speaks. Point out the safety wording.
4. Interrupt it mid-sentence. It stops and listens.
5. "Aaj Bangalore ka mausam kaisa hai?" -> `web_search` + cloud LLM.
6. "Call my son." -> fuzzy contact match, confirmation, dialer opens.
7. Press SOS. Show the SMS with location going out.
8. Switch to Cloud mode in Settings, hold a short conversation over the Managed Voice
   Agent, show the `LatencyReport` numbers on screen.

---

## 10. Model download list

| Model | Source | Size | Used for |
|---|---|---|---|
| silero_vad.onnx (v5) | k2-fsa/sherpa-onnx releases | 2 MB | VAD |
| sherpa-onnx-kws-zipformer (en) | k2-fsa/sherpa-onnx releases | 15 MB | wake word |
| sherpa-onnx-streaming-zipformer-en-2023-06-26 int8 | k2-fsa/sherpa-onnx releases | 80 MB | English STT |
| indicconformer-sherpa-onnx hi int8 | HF parismitaglobalsolutions | 180 MB | Hindi STT |
| whisper-tiny (sherpa-onnx) | k2-fsa/sherpa-onnx releases | 116 MB archive | spoken language ID |
| Qwen3.5-2B-Instruct Q4_K_M GGUF | HF TheStageAI | 1.07 GB | local LLM |
| Qwen3.5-0.8B-Instruct Q4_K_M GGUF | HF TheStageAI | 437 MB | low-RAM LLM |
| kokoro-int8-multi-lang-v1_0 (sherpa-onnx) | k2-fsa/sherpa-onnx releases | 132 MB archive | TTS en + hi |
| vits-piper-hi_IN-pratham-medium | k2-fsa/sherpa-onnx releases | 65 MB | Hindi TTS fallback |
| vits-piper-en_US-lessac-medium | k2-fsa/sherpa-onnx releases | 65 MB | English TTS fallback |
| gemma-4-E2B-it-litert-lm (stretch) | HF litert-community | 2.5 GB | single-model brain |

Total for the full default tier: ~1.8 GB. Push via `adb push` to
`/sdcard/Android/data/<pkg>/files/models/` on the demo phone.

---

## 11. CallMissed quick reference (for the code)

| Need | Endpoint | Model | Notes |
|---|---|---|---|
| Cloud LLM | `POST /v1/chat/completions` | `sarvam-105b-conversations` | stream, tools, `reasoning_effort: low` |
| Cloud LLM fallback | same | `sarvam-105b`, `kimi-k2.5` | Matches the app fallback chain and current catalog |
| Cloud STT | `POST /v1/audio/transcriptions` | `saaras:v4` | multipart, `mode=codemix` |
| Cloud TTS | `POST /v1/audio/speech` | `sonic-3.6` | `voice=kabir`/`riya`, `language=hi-IN`, `response_format=pcm` |
| Cloud voice agent | `WSS /v2/voice/agent` | stt saaras:v4, llm sarvam-105b-conversations, tts sonic-3.6 | `Authorization: Token cm_...`, linear16 PCM; models verified eligible 2026-09-04 |
| Voice sessions (WebRTC) | `POST /v1/voice/sessions` | any catalog LLM | Bearer auth; returns per-session `ws_url` + `token` (livekit SDK); transcript via `GET .../{id}/transcript` |
| Web search | `POST /v1/search` | n/a | `mode=shorter`, `gl=in`, 1 credit |
| Model list | `GET /v1/models` | n/a | **auth required** (Bearer) |
| Plan access | `GET /api/v1/models/access` | n/a | no auth; catalog checked 2026-09-04; the selected voice-agent models were live-eligible for the configured account |

Error handling: `401` bad key; `402` out of credits (stop); `403 model_not_available`
(paid model on free plan, switch model); `429 quota_exceeded` (monthly cap, stop);
`429 too_many_concurrent_requests` (honor `Retry-After`, one jittered retry, then
fallback model); `503` (fallback model). Rate headers (`X-RateLimit-Remaining`,
`Retry-After`) arrive mainly on 429s — the app records them opportunistically.

Security note: because there is no server, the `cm_` key is inside the APK. Acceptable
for the hackathon with a free-tier key and `local.properties`. If this ever ships, move
key handling to a Cloudflare Worker (still serverless) and rotate the key.

---

## 12. Risks and mitigations

| Risk | Mitigation |
|---|---|
| Local models too slow on the demo phone | Tiering (0.8B, Piper); Cloud mode as full fallback |
| Free-plan caps (100 LLM / 50 STT / 50 TTS per month) exhausted before demo | Count usage in-app; upgrade to Starter the day before; rehearse in Offline mode |
| Venue Wi-Fi unreliable | Demo script front-loads offline features; hotspot as backup; recorded video |
| Hindi STT accuracy on elderly voices | IndicConformer + cloud `saaras:v4` re-listen on low confidence |
| Echo causing false barge-in | Mute VAD during playback unless energy > threshold; test on speaker |
| APK size / download time | Models downloaded post-install, `adb push` for demo device |
| Medical answers | Safety prompt: never dosages, always "ask your doctor", escalate to cloud model |
