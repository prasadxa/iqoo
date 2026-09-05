# Aasra Voice Companion: Project Analysis

App: on-device voice assistant for elderly users. Simple turns run fully offline on the phone; hard turns escalate to CallMissed inference APIs called directly from the device (no backend server).

Hackathon: iQOO City Battles 2026. Demo device: iQOO Snapdragon flagship (12 GB RAM).

---

## 1. Tech stack

| Layer | Technology | Version |
|---|---|---|
| Language | Kotlin | 2.2.21 |
| Build system | Gradle Kotlin DSL, version catalog | AGP 8.11.1, Java 17 |
| UI | Jetpack Compose, Material3, Navigation Compose | BOM 2025.08.01 |
| Async | Kotlin Coroutines | 1.8.1 |
| State | Kotlinx Flow, Compose StateFlow, DataStore Preferences | DataStore 1.1.1 |
| Networking | OkHttp (REST + SSE + WebSocket), kotlinx.serialization | OkHttp 4.12.0, serialization 1.7.3 |
| Database | Room + KSP (contacts, reminders, conversation log) | Room 2.8.4 |
| Local STT | sherpa-onnx (Zipformer streaming, IndicConformer, Silero VAD, whisper-tiny LID, KWS) | v1.13.5 via JitPack |
| Local TTS | sherpa-onnx (Kokoro-82M, Piper VITS fallback) | same |
| Local LLM | llama.cpp via Llamatik Kotlin bridge (Qwen3.5 GGUF, Q4_K_M) | Llamatik 1.7.0 |
| Cloud | CallMissed API (OpenAI-compatible REST + SSE + Managed Voice Agent WSS) | - |
| Testing | JUnit 4.13.2 (pipeline, router, chunker, confirmation, cloud chat) | - |
| SDK | minSdk 29 (Android 10), target 35, compile 36 | - |

---

## 2. Multi-module structure

Single Android app composed of 9 Gradle modules:

| Module | Package | Role |
|---|---|---|
| `app/` | `com.aasra.companion` | Compose UI, navigation, DI (`AppContainer`), foreground `VoiceService`, real orchestrator wiring |
| `core-audio/` | `com.aasra.audio` | `AudioRecorder` (16 kHz mono PCM16, VOICE_RECOGNITION source), `AudioPlayer` (24 kHz low-latency), `Resampler`, `RingBuffer`, `EchoController` (mute VAD during TTS playback for barge-in) |
| `core-pipeline/` | `com.aasra.pipeline` | Engine-agnostic stage interfaces (`AudioInput`, `AudioOutput`, `SpeechRecognizer`, `LanguageModel`, `SpeechSynthesizer`, `TextChunker`), `PipelineOrchestrator`, `Router`, `SentenceChunker`, `PipelineState` |
| `engine-sherpa/` | `com.aasra.sherpa` | sherpa-onnx wrappers: `SherpaVad` (Silero v5), `StreamingZipformerStt` (en), `IndicConformerStt` (hi int8), `LanguageId`, `KeywordSpotter`, `SherpaTts` (Kokoro), `PiperFallbackTts` |
| `engine-llama/` | `com.aasra.llama` | `LlamaEngine` over Llamatik `LlamaBridge`, `StreamingGenerator` (strips `<tool_call>` blocks so JSON is never spoken), `ToolCallParser`, `QwenChatTemplate`, `RamTier` (2B vs 0.8B by free RAM), `ThermalGuard` |
| `cloud-callmissed/` | `com.aasra.cloud` | `CallMissedClient` facade: `ChatApi` (SSE stream + tool calls), `SttApi` (saaras:v4), `TtsApi` (sonic-3.6), `SearchApi`, `VoiceAgentSocket` (WSS), `VoiceSessionsApi`, `UsageCounter`, `CloudErrors` (402/429/503 handling), `FakeCallMissedClient` |
| `tools/` | `com.aasra.tools` | Device actions: `SystemTools` (time, date, volume, flashlight), `ContactTools` (fuzzy match incl. nicknames like "beta"/"bahu"), `SmsTools`, `ReminderTools` (AlarmManager + Room), `SosTools` (call + SMS with location), `NotificationReader`, `ToolSchema` (shared JSON schema for both LLMs) |
| `models/` | `com.aasra.models` | `ModelRegistry` (names, URLs, sizes, SHA-256), `ModelDownloader` (OkHttp Range resume), `ModelPaths` |
| `data/` | `com.aasra.data` | Room: `Contact` + DAO, `Reminder` + DAO, `ConversationLog` + DAO, `CaregiverStats` |

Build wiring: the API key comes from `local.properties` (`CALLMISSED_API_KEY`), exposed via `BuildConfig`. Dependency graph is declared in `settings.gradle.kts` (includes JitPack for sherpa-onnx).

---

## 3. How everything works

### 3.1 The voice loop (always running, on device)

```
Mic (16 kHz PCM, 20 ms frames)
  -> SherpaVad (Silero; speech start/end; energy fallback if .so absent)
  -> streaming STT (Zipformer partials en / IndicConformer finals hi; LanguageId auto)
  -> PipelineOrchestrator -> Router.decide()
       LOCAL:  LlamaEngine (Qwen3.5, streams tokens) -> SentenceChunker
               -> Kokoro TTS (Piper if RTF > 1.0) -> AudioPlayer (24 kHz)
       CLOUD:  ChatApi SSE stream -> same SentenceChunker -> same local TTS
       (sonic-3.6 cloud TTS only as fallback when local TTS is down)
  -> tools/ dispatch on parsed tool calls
```

Key implementation points:

- `RealAasraOrchestrator` (app/) owns the real turn path. It adapts engines to core-pipeline stage interfaces and mirrors state to the UI via `StateFlow`s (`voiceState`, `turn`, `runMode`, `audioLevel`, `latencyMs`).
- `PipelineOrchestrator` (core-pipeline/) is the engine-agnostic loop with `Listener` callbacks (`onState`, `onTranscript`, `onLatencyMs`, `onCloudHandoff`, `onToolCall`, `onError`) and a `decideRoute` hook.
- `SentenceChunker` splits the token stream into speakable sentences so TTS starts before the reply finishes (first-sentence playback).
- Barge-in: VAD `SpeechStart` while SPEAKING flushes playback, cancels the turn job, and returns to LISTENING. `EchoController` mutes VAD input while TTS plays unless energy exceeds threshold (echo from the phone's own speaker).
- Latency: `vad_end_to_first_audio` is measured per turn and surfaced in the UI (`latencyMs`), target < 1.5 s.
- History: last 10 turns kept in memory and fed to both local and cloud LLMs.
- Confirmation gate: `call_contact` / `send_sms` never execute immediately. They set `pendingConfirm`, speak "Should I call beta?" and the next VAD segment is classified YES/NO/UNKNOWN by `ConfirmationGate`/`ConfirmationResponse` (yes-words incl. "haan", "theek hai"; no-words incl. "nahi"; unknown runs as a fresh turn).

### 3.2 The Router (when a turn leaves the device)

`Router.decide(transcript, confidence, network, ramState, language, localEscalation, modelHealthy)` implements the escalation table top-to-bottom, first match wins:

| Row | Trigger | Route |
|---|---|---|
| 7 | Local model dead / OOM | `CloudMode` (whole conversation over voice-agent WSS), else `Local` if no network |
| 1 | No network | `Local` |
| 2 | STT confidence < 0.55 or language not en/hi | `CloudSttRelisten` (re-listen last 8 s of ring buffer with saaras:v4, one-shot, never loops) |
| 3 | Medical keywords (en + transliterated: dawai, dard, bukhar...) | `CloudLlm(MEDICAL)` with safety-first prompt |
| 4 | Current-info markers (news, weather, mausam, kab hai...) | `WebSearchThenCloud` |
| 5 | > 60 words or 2+ multi-step markers (pehle/phir/then) | `CloudLlm(LONG_COMPLEX)` |
| 6 | Local LLM emitted `escalate(reason)` | `CloudLlm(reason)` |
| else | - | `Local` |

Every decision is logged with latency (`route=... words=... conf=... net=... freeRam=...`).

### 3.3 The cloud layer (CallMissed, called directly from the phone)

Base: `https://api.callmissed.com`, `Authorization: Bearer <cm_key>`. One `CallMissedClient` in `cloud-callmissed/`:

| API | Endpoint | Model | Notes |
|---|---|---|---|
| `ChatApi` | POST /v1/chat/completions | sarvam-105b-conversations | SSE `data:` lines parsed into `Content`/`ToolCalls`/`Usage`/`Done` events; deltas feed the shared chunker |
| `SttApi` | POST /v1/audio/transcriptions | saaras:v4 | multipart WAV built from the 8 s ring buffer, `mode=codemix` for Hinglish |
| `TtsApi` | POST /v1/audio/speech | sonic-3.6 | voice kabir/riya, speed 0.9, PCM 24 kHz |
| `SearchApi` | POST /v1/search | - | mode=shorter, gl=in; results injected as untrusted `<search_results>` context |
| `VoiceAgentSocket` | WSS /v2/voice/agent | managed agent (saaras + sarvam + sonic-3.6) | `Welcome` -> `Settings` -> binary PCM in/out; events: `UserStartedSpeaking` (only barge-in signal), `ConversationText`, `FunctionCallRequest` (device tool over WS, reply within 30 s), `LatencyReport`, `Warning`, `Error` |
| `VoiceSessionsApi` | POST /v1/voice/sessions | - | REST surface for per-session ws_url + token |

Cloud-mode session (`CloudVoiceSession`) is owned by `VoiceService` and streams mic PCM to the agent and plays received PCM through AudioPlayer.

Error handling: 402 out of credits (stop, spoken "limit reached"), 429 quota_exceeded (stop), 429 concurrent (Retry-After honored), 503 (fallback), everything else -> spoken non-technical failure message. `UsageCounter` tracks remaining budget from rate headers.

### 3.4 Tools (shared JSON schema for local + cloud LLMs)

Declared in `tools/ToolSchema.kt`, dispatched in `RealAasraOrchestrator.dispatchTool`:

- `get_time`, `get_date`, `set_volume` (accepts 0-100 or 0-10), `flashlight(on|off)`
- `call_contact(name)` (fuzzy + nickname match, voice confirm), `send_sms(name, message)` (voice confirm)
- `set_reminder(text, time, repeat)` (AlarmManager exact + Room, "roz"/"daily" -> DAILY), `list_reminders`, `cancel_reminder(id)`
- `sos()` (calls primary contact, SMS location to all emergency contacts, caregiver stats updated)
- `read_notifications` (NotificationListenerService, reads last 3 aloud)
- `web_search(query)` (cloud only), `escalate(reason)` (local only; no-op ack "Theek hai." for cloud)
- Unknown tool names escalate online instead of dropping the turn

### 3.5 Run modes

Three modes, switchable in Settings and persisted in DataStore:

1. **Offline**: everything local; router forced `UNAVAILABLE`.
2. **Hybrid (default)**: local loop, cloud on escalate only.
3. **Cloud**: whole conversation over the Managed Voice Agent WSS (`VoiceService.startCloud` / `CloudVoiceSession`).

Degraded-path logic: if no engine reports ready AND no cloud client configured, the orchestrator mirrors a `FakePipelineOrchestrator` (offline demo path). If the local brain is unhealthy, router row 7 sends the turn to CloudMode.

### 3.6 Models

Local models (~1.8 GB full default tier), downloaded post-install or `adb push`ed:

- silero_vad.onnx (2 MB), KWS Zipformer archive (16 MB), streaming Zipformer en int8 (75 MB), IndicConformer hi int8 (198 MB), whisper-tiny LID archive (116 MB)
- Qwen3.5-2B-Instruct Q4_K_M GGUF (1.07 GB); true Qwen3.5-0.8B Q4_K_M (437 MB) when free RAM < 5 GB (`RamTier`)
- Kokoro-82M int8 multi-lang v1.0 archive (132 MB); Piper VITS en/hi (67 MB each) as TTS fallback (measured RTF persisted on first launch)
- `ModelRegistry` + `ModelDownloader` with resume (Range), SHA-256 integrity checks

### 3.7 App layer (UI + service)

- `MainActivity` + `NavGraph`: main screen (giant mic state indicator, live transcript, last answer, Talk/SOS/Repeat buttons), onboarding wizard (language, voice, name, emergency contacts, model download), settings (mode, voice, wake word, cloud usage).
- `MainViewModel` bridges the orchestrator flows into Compose.
- `VoiceService`: foreground service (`foregroundServiceType="microphone"`, persistent low-importance notification) so listening survives screen-off; also owns the Cloud-mode session lifecycle.
- `AasraApp` + `AppContainer`: manual DI. `cloud` is null when no key is configured (Offline only); `orchestrator` selection falls back safely.

---

## 4. Module dependency graph

```
app
 +-- core-audio        (AudioRecorder/Player/Echo)
 +-- core-pipeline     (stage interfaces, Router, Chunker)
 +-- engine-sherpa     (VAD/STT/TTS/KWS via sherpa-onnx)   -> core-pipeline
 +-- engine-llama      (LLM via Llamatik/llama.cpp)
 +-- cloud-callmissed  (REST/SSE/WSS client facade)
 +-- tools             (device actions)                    -> data
 +-- models            (registry/downloader)
 +-- data              (Room DB)
```

Libraries leak intentionally through module signatures (OkHttp, coroutines, Room, serialization are also direct `app` dependencies for this reason).

---

## 5. Testing

JUnit tests cover confirmation handling, pipeline cancellation, routing, sentence chunking, cloud SSE/history/tool schemas, model catalog/runtime paths, and local prompt boundaries. A Compose instrumentation test verifies the main voice actions and state semantics; it compiles on the host and requires an Android device or emulator to execute. Native engine initialization fails closed with `ready=false` when a model or native library is unavailable.

---

## 6. Notable design decisions

- No backend: the phone talks straight to api.callmissed.com. The `cm_` key ships inside the APK via BuildConfig (accepted hackathon trade-off; documented: move to a Worker + rotate if it ships).
- Engine-agnostic core: core-pipeline has zero Android/AI dependencies beyond interfaces, so engines are swappable and testable.
- Typed binding strategy: VAD, STT, language ID, keyword spotting, Kokoro, and Piper use the sherpa-onnx v1.13.5 Kotlin API directly, catch native/model failures at initialization, and degrade to `ready=false`.
- Safety: medical turns force a cloud prompt with "no dosages, no diagnosis, ask your doctor"; search results are injected as untrusted data; confirm-before-action for calls/SMS; every failure has a spoken, non-technical message.
- Elderly UX: high-contrast Compose UI, big tap targets, haptics, Hindi-first voice, slower TTS (length_scale 1.1 / speed 0.9).
