# Local Voice Runtime

## Verified Without a Device

- The dependency is `com.llamatik:library:1.7.0`, resolving to the Android `library-release.aar`.
- Its cached arm64 `libllama.so` contains `llama_model_qwen35`, its graph implementation, and `qwen35moe`. Qwen3.5 architecture support is present; a speculative version bump is not justified.
- The [v1.7.0 source tree](https://github.com/ferranpons/Llamatik/tree/v1.7.0) pins llama.cpp to `f12cc6d0fa96d6a3c33952f06b7439ac43a3c3fe`, which contains `src/models/qwen35.cpp`.
- The AAR packages `libggml-cpu.so`, not a Vulkan backend. This binding now explicitly uses CPU. GPU acceleration requires a different, verified native build.
- The voice prompt uses the [official Qwen3.5 non-thinking prefix](https://huggingface.co/Qwen/Qwen3.5-0.8B/blob/main/chat_template.jinja). Streaming filters suppress split tool/thinking tags.

## Installation Contract

Runtime files are private app files, not arbitrary Download-folder files:

```
filesDir/models/Qwen3.5-2B-Instruct-Q4_K_M.gguf
filesDir/models/Qwen3.5-0.8B-Instruct-Q4_K_M.gguf
```

These are the registry's destination names, intentionally different from the upstream TheStageAI `M-TS` download names. The low-memory tier requires the small model; it does not attempt the full model when free RAM is low. The app checks registry hashes and archive installation receipts on `Dispatchers.IO` at model reload, then supplies the verified inventory to the LLM loader. A filename existing is not readiness.

Speech assets must likewise use the paths in `ModelRegistry`: English Zipformer has four files; Hindi IndicConformer needs model plus vocabulary; Kokoro/Piper need extracted archives including phonemizer data. Hindi is never decoded through the English-only Zipformer as a fallback. Without Silero, energy VAD remains available; without wake-word assets, background wake-word capture is unavailable. Piper is initialized on IO for the selected language, never lazily inside synthesis.

## Service Integration

`PipelineOrchestrator.setCaptureEnabled(enabled: Boolean)` has a no-op default for test doubles. Real starts with capture disabled. The service must set it true only after setup, microphone permission and foreground-service admission; set false on teardown, permission loss, or entry into managed Cloud capture.

`stop()` latches the local loop off. Foreground/preference/model-reload updates do not clear it. Talk is an explicit resume but cannot bypass disabled capture. A false-to-true capture admission also resumes; repeated true does not. Set mode and foreground before granting capture. `setAppForeground(true)` alone is not admission.

Real exposes `readiness: StateFlow<LocalReadiness>`, `installedCapabilities: Set<LocalCapability>`, `missingCapabilities: Set<LocalCapability>`, and `reloadModels(): Unit`. Readiness covers the selected language and separates installed files from LOADING/MISSING/FAILED/READY engines. Optional LID and wake-word models do not block foreground assistance. Hybrid can use real cloud STT/chat/TTS when a stage is missing; Offline checks forbid every cloud boundary.

The app container's construction-time Fake fallback is owned outside these files and must be removed by its owner. Real no longer uses Fake at all.

## Still Unverified

- Successful load/inference of the exact installed GGUF, native context creation, CPU latency and RAM/thermal behavior on the target phone.
- Hindi/English recognition quality, actual audibility and phonemizer/voice output, wake-word accuracy, and end-to-end automatic voice turns.
- Local model adherence to the requested JSON tool-call schema. Qwen3.5's upstream template also supports a different function/parameter XML schema; this app intentionally requests its existing JSON schema and does not claim native tool-call fidelity without testing.

Host unit tests cover policies, cancellation, loader failures and streaming filters. They do not execute Android native inference. No device instrumentation or large model download was run for this change.
