# Local TTS Contract

See [Safe Speech and Supported Controls](../cloud-callmissed/TTS_CAPABILITIES.md)
for the shared input contract, source links, security limits, speed semantics,
streaming audit, and limitations.

Both `SherpaTts.synthesize` and `PiperFallbackTts.synthesize` normalize input
before native generation. They send plain text and supported speaker/speed
arguments, never raw SSML or emotion controls. Invalid/empty input produces no
audio without invalidating a loaded voice. Preserve the same normalization
contract in both module-local `SpeechInput` copies and their tests.
