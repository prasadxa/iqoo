# Speaker Echo Policy

The default is speaker-safe half-duplex. Microphone input is suppressed during
assistant output and for 400 ms after actual playback ends or is flushed.
Loudness is not evidence of a human interrupt. Hardware AEC, when available in
the cloud session, is supplementary and never enables an energy bypass.

## Root Causes

- Local `EchoController` admitted RMS above 0.08 during TTS. The cloud path
  admitted levels above 0.72 after scaling RMS by 0.12. Loud speaker output
  satisfies both thresholds.
- The local TTS worker cleared its gate when `AudioTrack.write` returned.
  Writes queue samples; they do not wait for the speaker to finish.
- `AudioPlayer` could report drain repeatedly, and failed to rearm its listener
  for a new sentence on a track that remained in `PLAYSTATE_PLAYING`.
- Cloud treated `AgentAudioDone` plus an empty application queue as silence,
  called `AudioTrack.stop` (which can drain asynchronously), and accepted every
  `UserStartedSpeaking` event as an immediate flush.
- Local re-listen retained raw speaker echo in its ring even when VAD rejected it.

## Ownership And Timing

`EchoController.ttsPlaying` is the producer hold. `playbackPlaying` is the
independent hardware hold. Both must be clear before the echo tail starts.
Repeated false notifications do not restart the tail. Callers poll
`AudioPlayer.isPlaying` until the playback head reaches the written frame count;
neither an empty queue nor the platform play-state flag proves drain.

Capture checks the gate before and after each blocking read so a frame spanning
the tail boundary is still discarded. Local re-listen stores silence for these
frames. Cloud sends same-length zero PCM, preserving the server's audio clock
without transmitting the speaker. Cloud ignores server speech-start events
while output or its tail is protected, including gaps between PCM packets.

Explicit talk-tap interruption remains available in local and cloud modes. It
invalidates in-flight writes and flushes immediately. In cloud mode the existing
talk action routes through `CloudVoiceBus`; it does not open a second local mic.
There is no client-cancel API in `VoiceAgentSocket`, so tapped cloud replies are
discarded until the next `AgentStartedSpeaking` event. The remote generator may
continue until server VAD hears the next user input.

## Fallback Audit And Limitations

The inspected tree has no Android `TextToSpeech` or `UtteranceProgressListener`
implementation. Real synthesis uses Kokoro/Piper or cloud PCM through
`AudioPlayer`; the fake orchestrator only changes state. Cloud-unavailable
announcements are routed to `orchestrator.speak`. Cloud capture is stopped before
publishing those requests. If platform TTS is added, its queued/start/done/error/
stop lifecycle must hold the same echo gate before `speak`, not just on a late
`onStart` callback, and must stop on a talk tap. Do not infer its completion from
the PCM player's state.

Voice barge-in during speaker output is intentionally unavailable, even with
headphones, until a separately verified route-aware full-duplex policy exists.
The 400 ms tail is conservative, not an acoustic guarantee for every device or
room. Hardware playback/capture latency, high-volume speaker coupling, Bluetooth,
and live server ordering still require device testing. A missing cloud
`AgentAudioDone` keeps capture closed rather than failing open; tapping or
stopping the session recovers. No UI labels or preferences were changed.

## Verification

Run sequentially (no parallel broad builds):

```sh
./gradlew :core-audio:testDebugUnitTest :core-pipeline:testDebugUnitTest --no-parallel --max-workers=2
./gradlew :app:testDebugUnitTest --no-parallel --max-workers=2
```

Regression coverage includes full-scale echo, post-playback tail boundaries,
producer/hardware hold independence, sentence rearming, write-versus-drain,
immediate flush, cancelled/failed writes, capture spanning an echo tail, the
re-listen ring, and cancellation at playback start. Android device boundaries
are mocked; unit tests do not establish real acoustic cancellation performance.
