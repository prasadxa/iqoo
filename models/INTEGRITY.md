# Model Download Integrity

## Confirmed Cause

Audit: 2026-09-05 local time (HTTP responses dated 2026-09-04 UTC).
No GGUF or large archive payloads were downloaded. Evidence comes from the
Hugging Face tree API, resolver HEAD responses, and GitHub release metadata.
Only the two small STT vocabulary files were fetched to check their SHA-256.

For `Qwen3.5-0.8B-M-TS-Q4_K_M.gguf`, both the tree API's `lfs.oid` and the
resolver's `X-Linked-ETag` identify this file SHA-256:

```text
635788bdc1b0ba1335e47cca0159e531811c722ffad4e3c7b363c2b55ecc26c8
```

The old registry and desktop script instead used the Xet storage hash
(`xetHash` / `X-Xet-Hash`, also present in the CDN URL):

```text
4395c09631a6d2a4bbcf15359d61c8f1e38c418df81ed87781bfdcf8fad2052f
```

These are different identifiers. The latter is not the payload SHA-256, so a
correct 436,743,552-byte download could never pass the old checksum check.
The downloader fetched it twice, then left the second complete `.part` behind.
Every user retry sent `Range: bytes=436743552-`, received HTTP 416, and kept that
same file without checking or resetting it. This exact 416 response was also
confirmed upstream using HEAD only.

The 2B metadata was already correct: 1,073,069,440 bytes, SHA-256
`8d497863b95e392baf022258f864c34f4a28613df340c500bb647486c52657ae`.
The wrong-hash finding specifically applies to the low-memory 0.8B selection;
there were no device logs to establish which entry the reporting device chose.
The resume/cancellation bugs affected both selections.

Both Qwen resolver URLs are now pinned to the verified repository commits:

| Model | Revision |
| --- | --- |
| 0.8B | `fff685b81430bd58e703547bb6014f7b5d482f48` |
| 2B | `e42cdd7a10a99b70833c43bb1516a696c8075ea8` |

Reproduce the metadata and range checks without downloading the model:

```bash
curl -fsS 'https://huggingface.co/api/models/TheStageAI/Qwen3.5-0.8B-GGUF/tree/fff685b81430bd58e703547bb6014f7b5d482f48' \
  | jq '.[] | select(.path == "Qwen3.5-0.8B-M-TS-Q4_K_M.gguf") | {path,size,lfs,xetHash}'
curl -sSIL -H 'Range: bytes=436743552-' \
  'https://huggingface.co/TheStageAI/Qwen3.5-0.8B-GGUF/resolve/fff685b81430bd58e703547bb6014f7b5d482f48/Qwen3.5-0.8B-M-TS-Q4_K_M.gguf'
```

## Other Catalog Checks

- English STT encoder, decoder, joiner and Hindi model sizes/hashes match HF `lfs` metadata.
- English and Hindi vocabulary hashes match their downloaded small text payloads.
- Kokoro and both Piper archive sizes/hashes match GitHub release asset `size`/`digest`.
- Silero VAD, Whisper tiny and keyword spotter URLs resolve successfully and their sizes match. GitHub reports `digest: null` for these older assets and HEAD exposes no SHA-256. Their existing hashes were preserved, not claimed as independently reverified in this audit.
- A CDN ETag, Xet hash, or Git blob ID must not be substituted for a file SHA-256.

## Recovery Contract

- Runtime file names are unchanged. Existing valid installed files remain usable, including offline.
- A complete `.part` matching the corrected size/hash is promoted locally, without issuing Range or requiring Wi-Fi.
- Corrupt/oversized complete partials are removed. Partial 416 gets one clean restart, not an endless retry loop.
- A 206 response must have a valid matching Content-Range. A 200 response to Range is consumed as a fresh body, without making a second request.
- Exact size and SHA-256 are required before promotion. A known response-size mismatch fails before writing; an incomplete transfer remains resumable.
- Each checksum failure deletes its bad partial, including the final automatic retry. Rename failures retain the verified partial and never report Done.
- Coroutine cancellation closes the active HTTP call, preserves downloaded bytes, and propagates cancellation rather than reporting a recoverable network error. Hashing and extraction also check cancellation.
- Saved, verified archives can be installed without another transfer. Extracted archives retain the existing hash-specific installation-marker contract; this is not a per-member corruption audit of an already installed directory.
- `ModelPaths.isInstalled` now checks size AND SHA-256 for plain files. Same-size corruption is not readiness. ADB adoption also rejects corrupt plain-file sources.

## App UI Handoff

No app/UI files were edited for this fix.

1. In `OnboardingWizard.kt`, move the `ModelPaths.isInstalled` scan inside `LaunchedEffect` to `withContext(Dispatchers.IO)`, then apply its results to Compose state on Main. `LaunchedEffect` alone still runs on Main; hashing a GGUF there can freeze the UI. The ADB adoption call in `AasraApp.kt` already runs on IO.
2. Treat `onProgress(done == total)` as transfer completion only. Keep a verifying/installing state until `Result.Done`; never infer readiness from 100%, file existence, or file length.
3. Progress callbacks execute on IO. Marshal UI state updates to Main or expose progress through a thread-safe StateFlow. Progress may reset to zero for the single automatic checksum retry.
4. Let CancellationException propagate and cancel/join the running job before retrying the same entry. Do not run two downloaders against the same target concurrently. The downloader removes its ongoing notification on exit.
5. Preserve model files and `.part` files during app update. Do not clear app data: the fixed downloader can salvage the affected complete 0.8B partial. If the old desktop script already deleted a failed file, those bytes cannot be recovered.
6. Metadata/size errors should retain their diagnostic reason; retrying cannot repair a stale catalog. Keep the existing Result and CHECKSUM_PREFIX API, but do not describe all integrity errors as network damage.

## Verification

`./gradlew :models:testDebugUnitTest --console=plain`: 32 tests passed.
The initial test-first run had 15 failures; an additional ADB corruption test
failed before its fix. Tests use tiny real files, real OkHttp requests to
MockWebServer, and a mocked Android Context, not large model downloads.

Coverage includes full/corrupt `.part`, bounded 416 recovery, valid/invalid Range,
ignored Range, unknown-length and interrupted bodies, checksum retry cleanup,
stale size/hash metadata, offline reuse, rename failure, cancellation during
network IO and hashing, archive reuse, readiness, and ADB corruption rejection.

`bash -n scripts/download_models.sh` and scoped `git diff --check` also passed.
`:models:lintDebug` passed with 0 errors and 10 dependency-version/catalog warnings.
This does not establish on-device inference compatibility or exercise the app UI.
