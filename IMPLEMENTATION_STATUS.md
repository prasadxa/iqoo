# Aasra Implementation and Verification

## Delivered

- Speaker-safe local and cloud playback gating through hardware drain and a
  400 ms acoustic tail. Loud speaker energy no longer overrides the gate.
  Voice interruption during playback is deliberately disabled; tap Interrupt
  remains available. Stop pauses microphone capture without changing saved mode.
- Fixed the Qwen 0.8B SHA-256, pinned Qwen URLs, complete-part recovery, HTTP 416
  retry handling, cancellation and integrity checks. Hashing runs off Main.
- Actual local engine readiness, consent-gated foreground listening, no fake
  production answers, corrected VAD and generation cancellation paths.
- Hinglish-specific Swift ONNX recognition; explicit Kokoro pronunciation
  language and corrected speech-speed mapping. See `models/MODEL_SELECTION.md`.
- CallMissed Sonic 3.6 + Preeti UUID for REST and managed cloud voice. No
  undocumented Sonic SSML/emotion parameters; safe formatting cleanup instead.
- Shared page headers/layouts, system/keyboard insets, fixed primary controls,
  focused Settings sections, compact download progress instead of model lists,
  Hindi translations, shorter labels and expandable transcript detail.
- Validated emergency contacts with add/edit/delete/primary management, SOS
  confirmation, and permission recovery. Tests never send real SOS messages.
- Calling now searches permission-gated phone contacts as well as Aasra contacts.
  Multiple names/numbers require selection and a fresh confirmation. Hindi marks,
  common family aliases, transliterated names and mobile/work/home selection are
  supported. See `tools/CALLING.md` for scope and safety guarantees.
- Daily reminders with date/time pickers and notification/alarm permission help.
- Read-only Health Connect steps and heart rate, explicit consent, source/time
  detail, and foreground refresh. No diagnosis or automatic health alerts.
- Optional MCP Streamable HTTP discovery and one-shot approved tool execution.
  No automatic AI registration, OAuth, credentials reuse or health-data sharing.

## Verification

Debug builds and unit-test tasks were run for app, cloud-callmissed, core-audio,
core-pipeline, engine-llama, engine-sherpa, models and tools. App lint reports
zero errors, with warnings including dependency updates and unused resources.

34 Compose/instrumentation checks were run on the USB-connected CPH2573 phone,
including home control visibility, contact validation and management, setup
restoration, Hindi at 200% font size, settings navigation, and read-only screen
rendering. Screenshot review covered setup, home, settings, downloads, reminders,
health and external tools. Tests use test preferences/in-memory contacts rather
than overwriting the user's saved contacts or settings.

After reconnection, the UI refinements and calling/contact update were installed
using `adb install -r`. All 34 device tests passed. Android's package permission
report confirmed READ_CONTACTS, CALL_PHONE and SEND_SMS granted on user 0.
ADB runtime-permission grants were blocked by the phone; no security setting was
weakened. The physical-device test installation is retained so the app is not
uninstalled after testing. App data and downloaded files were not cleared.
No Git operations or additional subagents were used after those were prohibited.

## UI Review

The applicable design checks were reviewed against phone captures: coherent
pine/sage palette, clear contrast and status-bar icons, native legible type,
no decorative gradients/glows, consistent gutters, wrapping labels, adequate
touch targets, honest control states, and content visible without animations.
Navigation and major actions are fixed outside long content. Conversation text,
large accessibility content, long lists and advanced server results can still
scroll; removing that fallback would hide content rather than improve it.

## Not Yet Production-Certified

- Full offline conversations require the selected model files installed on the
  target phone. New native speech quality, latency and RAM/thermal behavior have
  not been benchmarked with elderly speakers. Passing JVM/UI tests is not proof
  of acoustic performance or model accuracy.
- Echo regression tests verify gating logic, not every phone volume, room echo,
  headset, Bluetooth route or server timing condition. Physical listening tests
  remain necessary.
- Preeti's UUID was verified in the live public voice catalog. No paid live TTS
  quality benchmark was performed. Speech controls are constrained by CallMissed's
  documented Sonic interface; rich SSML pass-through is not documented there.
- Noise/realme compatibility depends on the exact watch and companion app
  writing Health Connect records. There is no universal BLE pairing or guaranteed
  real-time stream. No real watch was paired during this session.
- MCP requires a compatible trusted server. Authentication/authorization methods
  beyond the bounded unauthenticated HTTPS client are not implemented. Tools are
  not silently exposed to the LLM.
- Emergency calling/SMS, alarm delivery while killed/rebooted, revoked permission
  recovery, background restrictions and long-running microphone battery use need
  controlled physical-device acceptance testing. No real emergency was triggered.
- Before distribution: move the embedded provider key to a controlled credential
  service, review Android background/privacy and Play Health Connect/SMS policies,
  publish an accurate privacy policy, and complete release signing/security review.

## Further References

- `models/INTEGRITY.md`
- `models/MODEL_SELECTION.md`
- `engine-llama/RUNTIME.md`
- `core-audio/ECHO_POLICY.md`
- `cloud-callmissed/TTS_CAPABILITIES.md`
- `app/src/main/java/com/aasra/companion/health/README.md`
- `app/src/main/java/com/aasra/companion/mcp/README.md`
