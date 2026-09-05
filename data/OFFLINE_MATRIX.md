# Offline test matrix — Track E (tools + data)

PLAN refs: 5.1 fallback chain + never-retry rules, 5.6 router triggers, 7
hardening. All cases run on the iQOO device; cloud legs are mocked with
MockWebServer in `cloud-callmissed` tests or a laptop stub — Track E itself
is fully offline and asserts the *local* side of each row.

Spoken lines below are the contract: every failure must produce a
non-technical message in the user's language, never a code or exception.

## A. Connectivity

| # | Case | How to reproduce | Expected | Pass |
|---|---|---|---|---|
| A1 | Airplane mode, local Q | Airplane ON → "Aasra, what time is it?" | Instant local answer + "offline" badge (demo script step 1) | < 1.5 s VAD-end → first audio |
| A2 | Airplane mode, reminder | Airplane ON → "roz subah 8 baje dawai ki yaad dilana" | `set_reminder` stored in Room + AlarmManager; Hindi confirmation (demo step 2) | Row in `reminders`; alarm fires with TTS |
| A3 | Airplane mode, cloud need | Airplane ON → "is paracetamol safe with BP medicine?" | Stays local; says cloud help is unavailable, gives safety line ("ask your doctor") | No network attempt, no crash |
| A4 | Wi-Fi without internet | Connect to dead hotspot / `adb shell svc wifi enable` + block upstream | Router treats as offline (A1–A3 behaviour); no long connect timeout blocks the turn | Turn resolves < 3 s |
| A5 | Cloud-needing Q offline | Offline → "Aaj Bangalore ka mausam kaisa hai?" | Says weather needs internet; offers nothing fake | No hallucinated forecast |

## B. Mocked cloud errors (402 / 429 / 503)

Mock at the HTTP layer (MockWebServer rewrites base URL). Rules from PLAN 11.

| # | Case | Mock | Expected | Pass |
|---|---|---|---|---|
| B1 | 402 out of credits | `402` on chat completions | STOP: no retry, no fallback; spoken "cloud help is out of minutes"; local tools still work | Exactly 1 request |
| B2 | 429 quota_exceeded | `429` + `code=quota_exceeded` | STOP like B1 (monthly cap — retrying is pointless) | Exactly 1 request |
| B3 | 429 concurrent | `429` + `code=too_many_concurrent_requests` | Retry with jitter, then next model | ≤ 3 attempts, then `kimi-k2.5` |
| B4 | 503 | `503` on sarvam | Fallback chain `sarvam-105b-conversations` → `kimi-k2.5` → `gpt-oss-120b` (PLAN 5.1) | Spoken answer, usage counter +1 only |
| B5 | All cloud dead | 503 on every model | Local answer or honest "could not reach cloud help"; demo continues offline | No ANR, M1 still passes |

## C. Device-permission denials

| # | Case | How to reproduce | Expected |
|---|---|---|---|
| C1 | CALL_PHONE denied | Deny in Settings → "call my son" | Fuzzy match + voice confirm still run; ACTION_DIAL opens instead of direct call |
| C2 | SEND_SMS denied | Deny → confirm an SMS | Nothing sent; spoken "message could not go out, check signal/settings" |
| C3 | Exact alarms denied (Android 14 default) | Revoke "Alarms & reminders" | Inexact alarm + Room record kept; spoken "may come a few minutes late" |
| C4 | Notification access off | Revoke listener → "read my messages" | Spoken setup prompt; route to `NotificationAccess.settingsIntent()` |
| C5 | POST_NOTIFICATIONS denied (13+) | Deny → fire a reminder | TTS hook still speaks; no status-bar notification; no crash |

## D. SOS (demo script step 7)

| # | Case | How to reproduce | Expected |
|---|---|---|---|
| D1 | SOS online, location fix | Press SOS | Call to primary + location SMS to ALL emergency contacts; last-SOS recorded for caregiver view |
| D2 | SOS, no location | Location off | Call + "location not available, please call back" SMS — texts still go out |
| D3 | SOS, zero emergency contacts | Fresh DB → SOS | No call/text; spoken "no emergency contact saved yet, add one in Settings" |
| D4 | 3x power press | Triple-press within 3 s | `SosTrigger` fires once; stray presses outside the window ignored |

## E. Demo-night checklist (PLAN 7)

- [ ] A1–A5 pass on airplane + dead-hotspot the night before.
- [ ] B1/B2 verified against mocks (never retry on 402 / quota 429).
- [ ] Cloud budget checked (`GET /api/v1/models/access`, credit balance); one request pre-warmed.
- [ ] Fallback video recorded (venue network may die; demo front-loads offline steps 1–2).
- [ ] `adb push`'d models present so local legs never wait on download.

## F. Venue hotspot + metered path (Track D — onboarding + router)

Venue Wi-Fi is a captive portal until login, a throttled hotspot after.
Onboarding must never burn mobile data silently on the ~1.8 GB full default set.

| # | Case | Steps | Expected spoken behavior |
|---|---|---|---|
| F1 | Captive-portal Wi-Fi (connected, no internet) | Join venue Wi-Fi before login → ask "Aaj Bangalore ka mausam kaisa hai?" | Router treats as offline (A4 behaviour); says weather needs internet, offers nothing fake |
| F2 | Onboarding on mobile data | Fresh install, Wi-Fi off → reach model step | Step says no Wi-Fi found; Download stays gated until "Use mobile data" is tapped — never auto-starts |
| F3 | Hotspot drops mid-download | Start download on Wi-Fi → disable hotspot at ~50% → re-enable | Failed rows name the file + "Try again"; resume continues from `.part` bytes, not from zero |
| F4 | Skip-for-now | Tap "Skip for now" on the model step | Onboarding finishes; first cloud-needing question offline says help is limited until the voice finishes downloading |

## G. Thermal throttle (PLAN 7)

Log SoC temperature; if throttled, drop LLM to 0.8B and TTS to Piper.
Spoken lines stay warm — the phone explains itself, never a code.

| # | Case | Steps | Expected spoken behavior |
|---|---|---|---|
| G1 | Throttle during a long session | Heat-soak the phone (10 min continuous talk) → ask a normal local question | Answers, slightly slower; says "I am slowing down a little so the phone can cool" once, not every turn |
| G2 | Throttle + cloud need | Throttled → "is paracetamol safe with BP medicine?" | Still escalates (safety beats speed); Piper voice acceptable while hot |
| G3 | Recovery | Let the phone cool → ask again | Full-quality Kokoro voice back, no announcement needed |

## H. Low-RAM tier (PLAN 7 — free RAM < 5 GB at load)

| # | Case | Steps | Expected spoken behavior |
|---|---|---|---|
| H1 | Low-RAM boot | Fill RAM (or use the 6 GB mid-range) → cold-start the app | Loads Qwen3.5-0.8B instead of 2B; answers shorter but in the same voice — nothing spoken about tiers |
| H2 | OOM during load | Force-kill while LLM maps → ask a hard question | Switches to Cloud mode (demo script step 8 path); says "I need internet for hard questions right now" |
| H3 | Low-RAM onboarding set | Fresh install on the 6 GB phone → model step | Default set offered; 0.8B LLM row appears so the fallback is on disk before it is needed |

## I. Onboarding download interruptions (Track D)

| # | Case | Steps | Expected spoken behavior |
|---|---|---|---|
| I1 | Airplane during download | Start download → airplane ON | Remaining rows fail with plain reasons; retry buttons reappear; no crash, no silent stall |
| I2 | Checksum mismatch | Corrupt one `.part` (or first fetch of a bad mirror) | Row shows "arrived damaged, downloaded again by itself"; only on a second mismatch does it ask to try later |
| I3 | adb-push fast-path | `push_models.sh` before first launch → open onboarding | Rows for present files read "ready, already on this phone"; only missing files download |
