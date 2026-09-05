# Read-Only Health Connect

Entry point: `com.aasra.companion.ui.health.HealthScreen(onBack: () -> Unit)`.
The navigation owner supplies the existing localized context and `AasraTheme`.
No navigation, service, voice, or pipeline code is changed by this integration.

## Data and Permissions

- Uses stable `androidx.health.connect:connect-client:1.1.0`, not the current 1.2 alpha.
- `HealthConnectClient.getSdkStatus` gates `getOrCreate`. Missing/outdated provider offers Google Play installation; unavailable devices/profiles and provider errors have separate states.
- `PermissionController.createRequestPermissionResultContract` launches only from the explicit permission button. Requests only missing `HealthPermission.getReadPermission` permissions for `StepsRecord` and `HeartRateRecord`.
- `getGrantedPermissions` is checked before each metric and again before publishing the result. Partial consent works; permission loss clears values. Only previous-grant booleans are saved, to distinguish never-granted from revoked consent across launches.
- Today's steps use `aggregate(AggregateRequest(StepsRecord.COUNT_TOTAL, ...))`, not a sum of raw records. Health Connect applies source priorities. Totals can include phone steps and must not be described as watch-only.
- Heart rate uses paginated `readRecords(ReadRecordsRequest(HeartRateRecord::class, ...))` for the last 24 hours and selects the latest *sample* in that window, not the latest record start. Null and empty pagination tokens both terminate.
- Source IDs are the actual `DataOrigin.packageName` values. These may identify apps or platform-generated sources, not watch models.
- `Metadata.lastModifiedTime` is shown as the Health Connect record update time. It is NOT a verified watch sync time. Step timestamps come from matching-source raw records; missing metadata is explicitly unavailable. Sample measurement time, record update time, and app check time are separate.
- The 15-minute age label is display freshness only. No heart-rate thresholds, diagnosis, alert, or emergency action exists.

## Lifecycle and Privacy

`repeatOnLifecycle(RESUMED)` checks on entry and after a 30-second delay, or after an explicit refresh. Requests are serialized. Leaving the screen, covering it with another activity/dialog, or backgrounding the app cancels the loop and clears its in-memory snapshot. Returning always checks grants again. There is no background permission, worker, service, sensor listener, Bluetooth pairing, or claimed live stream.

Health values are not persisted, logged, transmitted over the network, or handed to the assistant pipeline. The companion app and Health Connect retain their own original records. The install link contains only the provider package ID. Permission rationale is available through the pre-14 rationale intent and the protected Android 14+ permission-usage alias, plus an in-app button. English and Hindi privacy strings explain this exact scope.

Before a Play release, the app owner must complete the Health Connect/health-app declarations and publish a privacy policy matching the in-app policy. This change does not claim Play approval or supply a hosted policy.

## Noise and realme Limitation

Compatibility is **not verified** for a particular Noise or realme watch/app/region. The official companion app must write the requested record types to Health Connect and the user must enable that sharing. Google Fit support or ordinary Bluetooth pairing is not proof that Health Connect receives records. Polling cannot force the watch or its companion to sync. There is deliberately no invented brand SDK or BLE protocol. If the companion cannot write these records, this integration cannot retrieve them.

## Verification

Pure JVM tests cover never-granted versus revoked access, valid zero steps versus absent data, the freshness boundary and clock mismatch, sample-window boundaries, empty series, out-of-order samples, and update-time tie breaking.

Device instrumentation belongs to the main agent. Still verify actual provider install/settings navigation, both OS permission-rationale paths, partial grant/denial/revocation, populated and empty providers, pause/resume cancellation, narrow layouts, Hindi, and large system font scaling on a device. No device tests were run for this change.

## Official References

- SDK releases: https://developer.android.com/jetpack/androidx/releases/health-connect
- SDK availability, permission launcher, manifest/rationale setup: https://developer.android.com/health-and-fitness/health-connect/get-started
- Foreground reads, pagination, read windows, on-device steps: https://developer.android.com/health-and-fitness/health-connect/read-data
- Aggregation and source priority: https://developer.android.com/health-and-fitness/health-connect/aggregate-data
- Official Android sample: https://github.com/android/health-samples/tree/main/health-connect

The documentation was consulted for this implementation. No official source was found establishing blanket Noise/realme compatibility; the UI intentionally does not claim it.
