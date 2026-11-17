# Offline recordings

[RecordingRepository](app/src/main/java/com/example/app/data/RecordingRepository.kt) owns Room,
files and upload state. [RecordingManager](app/src/main/java/com/example/app/data/RecordingManager.kt)
owns capture in an application coroutine scope. The chart's lossy SharedFlows are not used for
persistence. Choose an exercise and arm, start, then save. History shows the current account's
recording/upload status, persisted errors and attempt counts.

## Durability and recovery

The UUID and owner are inserted into Room before collection. The private
`files/recordings/<UUID>.getms` header uses the same identity. Each packet is checksummed and synced
to disk. A bounded 256-packet queue keeps BLE callbacks responsive; if storage cannot keep up,
collection stops with an explicit interruption error. Only the chart's last 50 values and that
bounded queue are held in memory. Sudden process/power loss can discard packets still in the queue;
all complete durable frames are recovered.

Saving drains the queue, writes/syncs a footer, closes/validates the file, then updates Room.
Leaving the foreground (including configuration changes), leaving the screen, disconnecting or
signing out stops and saves collection. Continuous background BLE recording is not provided.
If the OS kills the process without callbacks, the row remains `RECORDING`. Next startup validates
all complete frames, trims only an incomplete trailing frame, appends an `INTERRUPTED` footer and
uses the last durable packet's end time/duration. An existing complete footer wins if the process
died between file completion and the Room commit.

A bad checksum, mismatched identity, missing file or invalid format becomes `CORRUPTED` / `FAILED`
with a persisted error. Corrupt files are retained for diagnosis. Recording creation and workers
wait for one startup recovery pass, so recovery never touches an active writer/upload.

Files survive app restarts, reboots and network loss. Uninstall/clear-storage removes them.
Recordings and their database are excluded together from cloud backup/device transfer to keep
sensor data local and prevent inconsistent metadata/file restores. Sign-out retains pending data;
account changes never reassign/upload another user's recordings. The v1→v2 migration preserves
legacy rows: synced rows become `UPLOADED`; unsynced rows become `CORRUPTED` / `FAILED` because v1
stored neither owner nor file. Legacy rows remain unowned, hidden from account history and never uploaded.

## Upload flow

`PENDING → UPLOADING → UPLOADED`, or `UPLOADING → FAILED → UPLOADING` on retry.
Room atomically claims uploads and persists attempt counts, timestamps and errors. WorkManager
uses unique work per UUID, a CONNECTED constraint and exponential backoff starting at 30 seconds.
Cancellation becomes `FAILED`; startup resets rows left `UPLOADING` by process death. A persistent
15-minute reconciliation job closes the Room-commit/enqueue crash window and survives reboot.
Sign-in/session restoration also schedules eligible rows for the current owner. Invalid files
fail permanently; network/server/auth failures retain files and retry. Work requests contain no tokens.

The uploader uses the existing persisted Supabase auth session, refreshes expiring tokens and
streams the file to the private bucket. The deterministic `<user>/<UUID>.getms` path and Storage
upsert prevent duplicate objects after a lost response. On existing projects, apply the owner-scoped UPDATE policy in
[the recording upload migration](../supabase/migrations/20261118000000_recording_upload_upserts.sql)
before deployment; rerunning the baseline setup would replace the clinician signup trigger function.
The archive contains the remote recording's
metadata; no separate server recordings table is introduced.

A successful server response is committed as `UPLOADED` before deletion. A crash before deletion
leaves an uploaded local copy that the next startup cleans up.

## Archive format, version 1

[RecordingFileStore](app/src/main/java/com/example/app/data/local/RecordingFileStore.kt) defines
big-endian frames: `int32 payloadLength`, payload, `int32 CRC32(payload)`. Payloads are limited to
64 KiB; strings use Java DataOutput's modified UTF-8 (`writeUTF`). Files fit the bucket's 50 MiB
limit with footer space reserved; reaching that limit interrupts collection and retains data.

| Type byte | Remaining payload fields, in order |
| --- | --- |
| 1: header | UTF GETMS, int32 version 1, UTF UUID, UTF owner ID, UTF session ID, UTF exercise, UTF arm, int64 start wall time |
| 2: EMG | int64 receipt wall time, int64 monotonic elapsed, int32 BLE sequence, int64 BLE t0Us, int32 count, then per sample: int64 timestampUs, int32 channelA mV |
| 3: IMU | Same packet fields, then per sample: int64 timestampUs, four float32 quaternion values (w,x,y,z) |
| 4: footer | int64 end wall time, int64 elapsed, UTF recording status, UTF interruption reason (empty for normal completion) |

Wall times/durations are milliseconds. Elapsed time uses Android's monotonic clock; sensor timestamps
remain microseconds. Exactly one header/footer are required for upload; data after a footer is invalid.

## Validation

From this directory:

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug
.\gradlew.bat :app:connectedDebugAndroidTest
```

Connected tests require an attached Android device/emulator. Coverage includes every torn-tail offset,
checksum corruption, missing files, process recovery, account isolation, retry/errors, cancellation,
confirmed cleanup, stuck uploads, real Room reopening/migration and WorkManager network constraints.
Live BLE and real server round trips require a wearable and configured Supabase development project.

## Files changed

| Area | Files |
| --- | --- |
| Metadata and migration | [RecordingEntity](app/src/main/java/com/example/app/data/local/RecordingEntity.kt), [RecordingDao](app/src/main/java/com/example/app/data/local/RecordingDao.kt), [AppDatabase](app/src/main/java/com/example/app/data/local/AppDatabase.kt), exported v2 Room schema |
| Durable files and state | [RecordingFileStore](app/src/main/java/com/example/app/data/local/RecordingFileStore.kt), [RecordingRepository](app/src/main/java/com/example/app/data/RecordingRepository.kt) |
| Capture and startup | [RecordingManager](app/src/main/java/com/example/app/data/RecordingManager.kt), [RecordingApplication](app/src/main/java/com/example/app/RecordingApplication.kt), BLE packet hook, activity lifecycle, manifest |
| Upload | [RecordingUploadWorker](app/src/main/java/com/example/app/data/RecordingUploadWorker.kt), [SupabaseRecordingUploader](app/src/main/java/com/example/app/data/remote/SupabaseRecordingUploader.kt), backend Storage policy |
| UI and account isolation | Sensor view model/screen, history view model/screen, sign-out retention |
| Supporting configuration | WorkManager/OkHttp and test dependencies, schema test assets, backup exclusions, validation task, Kotlin generated-file exclusion |
| Tests | RecordingFileStoreTest, RecordingRepositoryTest, SupabaseRecordingUploaderTest, RecordingPersistenceTest, RecordingWorkTest |
