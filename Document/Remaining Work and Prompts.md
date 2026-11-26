# ReBand (GETMS) — Remaining Work & Implementation Prompts

_Reviewed against `main` @ `d1488ba`. All feature branches are merged into `main`._

---

## 1. What is already done

| Area | Implemented | Where |
|---|---|---|
| Firmware | 2 IMUs (upper arm 0x69, forearm 0x68) + MyoWare EMG (ch A). Calibration, rep detection and form checks run **on the device**. BLE GATT server. Recording continues if BLE drops, with the last 32 reps replayable | `firmware/Reband/` (`exercise/session.c`, `reps.c`, `motion.c`), protocol in `firmware/Reband/BLE_PROTOCOL.md` |
| BLE (app) | App-wide `BleManager` singleton: scan/connect, MTU, Status / Rep Event / EMG notifications, CALIBRATE / START / STOP / GET_STATE / GET_REPS | `ble/` |
| Exercise flow | Choose the exercising arm → calibrate the healthy arm (relax / curl / MVC) → one reference curl → review → switch arm → calibrate the exercising arm → one set (firmware `TARGET_REPS = 5`) → save → summary | `ui/exercise/ExerciseViewModel.kt`, `ExerciseFlowScreen.kt` |
| Metrics | Per rep from the firmware: ROM, duration, peak / mean %MVC, twist / swing / plane deviation, clean + quality flags. Healthy-vs-exercising comparison table | `data/SessionMetrics.kt`, `ui/history/SessionViews.kt` |
| Local storage | Room v3: `exercise_sessions`, `exercise_reps`, `recordings` (raw EMG files with crash recovery + WorkManager upload) | `data/local/`, `data/RecordingManager.kt`, `Android_app/OFFLINE_RECORDINGS.md` |
| Supabase | Pairing / invite codes, verified clinicians, sessions + reps via RPCs (`save_exercise_session`, `get_exercise_sessions`), raw files in Storage (a clinician can read an assigned patient's folder) | `supabase/migrations/*` |
| Patient UI | Login / sign-up, Home with weekly activity, History list + session detail, Settings, device scan, My Clinician | `navigation/AppNavHost.kt`, `ui/screens`, `ui/history`, `ui/pairing` |
| Clinician UI | Dashboard, patients, invite code, requests, patient → sessions → session detail (read-only, from the server) | `ui/pairing/Clinician*` |
| Disconnects (partial) | Banner + "Reconnect wearable" (manual, through the scanner), GET_REPS replay after reconnect, "End session now" → saved as interrupted, command timeouts, reps written to Room as they arrive, an `in_progress` session left from a previous run → `interrupted` on next launch | `ExerciseViewModel.onConnectionLost`, `onStatus`, `finish` |

## 2. Gaps found in the requested items

| Requested | Current state | Gap |
|---|---|---|
| 2 sets × 5 reps after calibration | One set of 5 (firmware `TARGET_REPS`) | No set loop or rest. **`rep_index` restarts at every START**, but the rep primary key is `(session, set_type, rep_index)` in both Room and Supabase, so set 2 would overwrite set 1 |
| Intro: what data is analyzed | Setup card only says "one reference curl, then your set" | No explanation of the metrics |
| SVG placement (EMG, IMUs, strap) | Text-only `SwitchArmStep`. `res/drawable` has only launcher icons | No artwork, no placement step before the first calibration |
| 2-frame tutorial → "I'm ready" | None | New step |
| Average effort ("force") graph, velocity, stability | Table of averages only, no charts. The raw EMG stream is saved to a file for the exercising set only, **not for the reference curl**, and never analyzed in the app | EMG curve per rep, velocity, stability score, chart with the reference arm in grey |
| Patient-friendly end review | `SummaryStep` reuses the technical `SessionDetailContent` (same as the clinician view) | Simple patient view |
| Doctor detailed data | Same table + per-rep list | Per-set breakdown, charts, trends across sessions, raw EMG viewer, export |
| History after restart | Room + `restoreFromServer` exist | Bugs listed in P6 |
| BLE safety net | Partial (see above) | No auto-reconnect, no foreground service (raw capture stops when the app is backgrounded), no resume after a device power-off, no recovery after an app kill |

## 3. Suggested order

| # | Item | Depends on |
|---|---|---|
| P6 | Fix history persistence / sync | — |
| P1 | 2 sets × 5 reps (incl. DB + Supabase migration) | — |
| P2 | Intro + placement SVGs + tutorial | — |
| P7 | BLE disconnection safety net | P1 |
| P3 | Analysis: effort curve, velocity, stability + comparison chart | P1 |
| P4 | Patient-friendly review | P3 |
| P5 | Clinician detailed view | P3 |

**Target patient flow**
```text
Setup (choose arm + "what we measure") → Placement (SVG, healthy arm) → Calibrate healthy arm
→ Tutorial (2 frames) → [I'm ready] → Reference curl → Review → Switch arm (placement SVG, mirrored)
→ Calibrate exercising arm → Set 1 (5 reps) → Rest → Set 2 (5 reps) → Saving → Patient review
```

### Decisions to confirm
1. **"Force"**: The MyoWare ENV signal measures muscle *activation*, not force. The UI should call it **"Muscle effort (%MVC)"**.
2. **Velocity curve**: The firmware does not stream the elbow angle (the IMU characteristic is reserved). Without a firmware change, velocity is a per-rep average (2·ROM / duration). A velocity *curve* and smoothness need the optional firmware change in P3.
3. **Rest between sets**: 30 s, with skip allowed. Set 2 reuses the calibration if the device is still calibrated.
4. **Reference** is one healthy-arm curl, as today. The grey line is that curl's curve.

---

## 4. Implementation prompts

Paste this shared context before each prompt:

> **Shared context:** ReBand upper-limb rehab app. The exercise is a **bicep curl**. Android app in `Android_app/`
> (Kotlin, Compose Material3, `AndroidViewModel` + `StateFlow`, Room v3 in `data/local`, Supabase-kt with RPCs in
> `data/remote`, theme color `Teal` in `ui/theme/Color.kt`). The wearable does calibration, rep detection and
> form checks on-device. The protocol is in `firmware/Reband/BLE_PROTOCOL.md`: Status (18 B), Rep Event (19 B: ROM, duration,
> peak/mean %MVC, twist/swing/plane, flags), EMG (MyoWare ENV, ch A in mV, 1 kHz, `t0_us` since START), and
> commands CALIBRATE(1..3) / START / STOP / GET_STATE / GET_REPS. `rep_index`, `seq` and `t0_us` restart at every START.
> The flow lives in `ui/exercise/ExerciseViewModel.kt` (`FlowStep` state machine, one command in flight via `Pending`)
> and `ExerciseFlowScreen.kt`. Sessions/reps are persisted through `data/ExerciseSessionRepository.kt` and uploaded with
> `save_exercise_session` (`supabase/migrations/20261118000000_exercise_sessions.sql`). Raw EMG goes through
> `data/RecordingManager.kt` (see `Android_app/OFFLINE_RECORDINGS.md`). Follow the existing style, add new Supabase
> changes as a new migration file, export the Room schema, add focused unit tests, and run
> `.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug` from `Android_app/`.

### P1 — 2 sets × 5 reps

```text
Goal: After the exercising arm is calibrated, the patient does 2 sets of 5 reps with a rest in between.

1. Data model (do this first; today set 2 would overwrite set 1 because rep_index restarts at each START):
   - Room v4: add `setIndex INTEGER NOT NULL DEFAULT 1` to `exercise_reps`, change the PK to
     (sessionId, setType, setIndex, repIndex), and add `setsPlanned` / `setsCompleted` to `exercise_sessions`.
     Write an explicit Migration(3,4) that keeps all rows (SQLite needs a table rebuild to change a PK), add it to
     ALL_MIGRATIONS, export 4.json, and extend the migration androidTest.
   - Supabase: new migration `2026112x_exercise_sets.sql`. Add `set_index int not null default 1` to
     `exercise_reps`, change the PK to (session_id, set_type, set_index, rep_index), and add
     `sets_planned` / `sets_completed` to `exercise_sessions`. Update `save_exercise_session` and
     `get_exercise_sessions` to read and write them. Update `ExerciseJson` and `SessionDataTest`.
2. Flow (ExerciseViewModel):
   - State: `currentSet` (1..2), `setsPlanned = 2` (constant for now). Add FlowStep `REST_BETWEEN_SETS`.
   - Device STOPPED after set 1 (target reached or the user pressed Stop) → run the GET_REPS gap recovery for that
     set *before* the next START, because the device ring only holds reps since the last START → REST
     (30 s countdown, "Skip rest") → send START again. START is allowed from STOPPED while calibrated, so
     no recalibration is needed. If the device is not READY/STOPPED, go to EXERCISE_CALIBRATION.
   - Reset `lastExerciseRepIndex`, `exerciseRepsStored`, and the EMG seq counters for each set.
     Store reps with `setIndex = currentSet`.
   - Stop pressed early in set 1 → dialog "Continue to set 2" / "End session".
   - Finish after set 2. `repCount` / `cleanRepCount` are totals across sets.
   - Raw EMG: one RecordingManager capture per set (pass `sessionId`, and put the set in the `exercise` label or a new
     header field).
3. UI: "Set 1 of 2" above RepetitionCounter. Rest screen with a big countdown and "Next: Set 2 · 5 reps".
   Session detail and history group the reps by set.
Tests: the Migration(3,4) keeps rows; the set-2 rep 1 does not overwrite the set-1 rep 1 (DAO); JSON round-trip with set_index;
ViewModel set transition (if practical with a fake BleManager, otherwise extract a pure reducer).
```

### P2 — Exercise intro, SVG placement guide, 2-frame tutorial

```text
Goal: Before recording, the patient learns what is measured, how to wear the device, and what the movement looks like.

1. Intro (extend SetupStep): "Bicep Curl · 2 sets × 5 reps · ~5 min" plus three plain cards:
   "Muscle effort" (EMG sensor on the biceps), "Speed" (how fast you curl), "Stability" (how steady
   and in-line your arm stays). Keep the arm selection below.
2. Placement step (new FlowStep.PLACEMENT, after SETUP and before REFERENCE_CALIBRATION; reuse the same
   composable inside SWITCH_ARM for the exercising arm). Create vector artwork. Author it as SVG in
   `Android_app/art/` and convert it to VectorDrawable XML in `res/drawable/` with Android Studio > Vector Asset:
   - `placement_emg.xml`: MyoWare sensor on the belly of the biceps, electrodes in line with the muscle fibers,
     and the reference electrode placed as the MyoWare guide recommends.
   - `placement_imu.xml`: upper-arm IMU just above the elbow and forearm IMU just below the elbow, both facing
     the same way on each arm (the firmware checks the hinge axis).
   - `placement_strap.xml`: straps snug, not tight, with cables not pulling on the sensors.
   Show them as a 3-page pager (HorizontalPager) with a caption and dots, plus "Back/Next" and "Done, calibrate".
   Mirror the art for the left arm with Modifier.graphicsLayer(scaleX = -1f). Tint with theme colors
   (dark mode), and add contentDescriptions. Ask a teammate to check the art against the real hardware.
3. Tutorial step (new FlowStep.TUTORIAL, after the healthy-arm calibration completes and before REFERENCE_RECORD):
   `tutorial_curl_down.xml` (arm straight, elbow at side) and `tutorial_curl_up.xml` (fully curled),
   alternated every ~1 s with Crossfade driven by a LaunchedEffect loop. Caption: "Curl all the way up, then
   lower slowly. Keep your elbow by your side." Big "I'm ready" button → REFERENCE_RECORD. If the system
   animator scale is 0, show both frames side by side. Before the exercising-arm sets, READY shows a
   small replay of the same animation.
4. Update `restartCalibration` / `cancelFlow` / the back handling in ExerciseFlowScreen for the new steps.
Done when: the full path works with Back navigation, the BLE connection stays up, and the existing flow behavior is unchanged.
```

### P3 — Analysis: effort curve, velocity, stability + comparison chart

```text
Goal: After each set, compute per-rep and per-set analysis and draw the exercising arm against the healthy
reference: exercising arm in the main color (Teal), healthy arm as a grey line on a grey background.

Inputs: Rep Events (start_ms, duration_ms, ROM, %MVC, twist/swing/plane, flags), the EMG ENV stream
(ch A mV, 1 kHz, t0_us since START), and calibration baseline_mv / mvc_mv per arm.

1. Capture: keep an in-memory buffer of (t_us, chA) per set in ExerciseViewModel. At 1 kHz × ~60 s that is fine.
   **Also capture EMG during the reference curl** (today RecordingManager only runs for the exercising set).
   Start the reference raw capture as well so the clinician has it.
2. New pure module `data/analysis/` (unit-testable, runs on Dispatchers.Default):
   - %MVC(t) = clamp((env − baseline) / (mvc − baseline) × 100, 0, 150), smoothed with a 100 ms moving average.
   - Per rep: slice [start_ms, start_ms + duration_ms] (t_us/1000 since START), resample to 101 points (0–100 % of
     the movement). Per set: ensemble mean ± SD over the reps. Reference: the single reference curl's curve.
   - Velocity: mean angular velocity per rep = 2·ROM / duration (°/s; up + down) and tempo (duration).
     Optional, needs firmware: stream the elbow angle on the reserved IMU characteristic 6e400002 while RECORDING
     (`seq u16 | t0_us u32 | 10 × angle_x10 i16`, 100 Hz), update BLE_PROTOCOL.md + PacketParser, then add
     peak velocity, a velocity curve and smoothness (SPARC or number of velocity peaks).
   - Stability score 0–100 per rep and per set. Form penalties: twist/30°, swing/15°, plane/25° (the firmware
     FORM_MAX limits), each capped at 1. Consistency: CV of ROM and of duration across the set's reps. Also use
     the clean-rep ratio. Document the weights in KDoc and keep the components for clinicians.
   - Fatigue hint: set 2 vs set 1 change in mean ROM, duration and mean %MVC.
3. Persist: the 101-point curves (mean, SD, reference) + stability/velocity per set. Add a Room table
   `exercise_set_analysis` (sessionId, setType, setIndex, json) in a v5 migration, or a JSON column. Store them in
   Supabase as jsonb through save/get_exercise_sessions (new migration). Recompute is not possible
   later without the raw file, so store them.
4. `ui/components/ComparisonChart.kt` (Canvas, no new dependency): `surfaceVariant` background,
   reference line `onSurfaceVariant` at 50 % alpha drawn first, ±SD band in Teal at 15 % alpha, exercising line in
   Teal 2.5 dp on top, labelled axes ("% of movement", "% MVC"), legend "Left arm (today)" / "Right arm
   (healthy)", and a semantics contentDescription summarising the peak values. Reuse it for any curve.
Tests: a synthetic ENV signal gives the expected %MVC; the resample length is 101; mean/SD of identical reps → SD 0;
stability = 100 for perfect reps and decreases with twist/CV; mean velocity formula.
```

### P4 — Patient-friendly end-of-exercise review

```text
Goal: Replace the technical SummaryStep content with a simple, encouraging review. The current
SessionDetailContent becomes the clinician view (P5).

New `ui/history/PatientSessionReview.kt`, used by SummaryStep and by the patient History detail route:
1. Header: "Great work!", the date, and "10 / 10 reps · 2 sets". Show an interrupted banner if status = interrupted
   ("We saved 7 reps before the wearable disconnected").
2. Three big cards (value + plain sentence + icon + a color that is never the only signal):
   - Range: "You curled 118° — 92 % of your healthy arm" (+ "↑ 6° since last session" if a previous session exists).
   - Speed: "Steady pace · 2.4 s per rep" ("a bit fast" / "a bit slow" relative to the reference curl).
   - Stability: "Stability 81/100 — elbow stayed steady" (name the main issue if one exists: "try not to swing your upper arm").
3. "Muscle effort" ComparisonChart (P3): today's average curve vs the healthy arm in grey, with one sentence.
4. Set 1 vs Set 2: a small two-column comparison plus a fatigue hint if it applies.
5. Sync chip (Saved on phone / Synced) and buttons "Done" and "See details" (expands the existing technical view).
No jargon: no %MVC, no "plane deviation", no flags in the main view. Keep the existing "measurements, not a
diagnosis" note. Use large text, 48 dp targets and TalkBack labels. If there is no reference curve (older sessions), hide the chart.
```

### P5 — Clinician detailed view

```text
Goal: An assigned, verified clinician can inspect everything about a patient's sessions. The view stays read-only, and
the server checks access on every call (existing behaviour in ClinicianHistoryViewModel).

Build on the existing clinician routes (CLINICIAN_PATIENT, CLINICIAN_SESSION) and SessionDetailContent:
1. Patient detail: trends across sessions (Canvas line charts): ROM as % of the healthy arm, mean %MVC,
   mean velocity, stability score, clean-rep ratio, plus the number of sessions per week.
2. Session detail, per set:
   - Set table: reps, clean reps, mean/SD ROM, duration, velocity, peak/mean %MVC, stability with its components
     (twist, swing, plane, CV), and the set 1 → set 2 change.
   - Per-rep rows (existing RepRow) plus velocity and flags.
   - ComparisonChart with the ±SD band for each set and the reference curve.
   - The calibration values of both arms, firmware version, error flags, packet loss and interruption reason (already
     available; keep them).
3. Raw EMG viewer: the server currently has no recordings table, so the clinician cannot find which Storage
   file belongs to a session. Add `raw_recording_ids text[]` (or a small `session_recordings` table) to the
   session upload. Download `<patient>/<uuid>.getms` on demand (the storage policy
   `recordings_select_assigned_clinician` already allows it), parse it with a reader shared with RecordingFileStore,
   and plot the %MVC envelope over time with rep start/end markers and set separators. Support pinch-zoom/pan.
   Cache it in cacheDir only.
4. "Export CSV" (per-rep metrics of a session or of all sessions) through FileProvider + the share sheet.
Tests: CSV formatting, .getms reader on a sample file, and a JSON mapping test for the new fields.
```

### P6 — Fix local + Supabase storage (history missing after restart / re-login)

```text
Goal: A patient always sees their full session history after an app restart, sign-out/sign-in, reinstall,
or a period offline. First reproduce each scenario on a device, then fix every cause you confirm.

Suspects found in code review:
1. **Confirmed: same-process re-login skips the restore.** In AppNavHost.MainNavHost, `HistoryViewModel` /
   `ExerciseViewModel` are created with `viewModel(key = "history-$patientId")` at activity scope, so they
   survive sign-out. `HistoryViewModel.bind()` returns early when the id is unchanged, so after
   sign-out (which runs `clearSyncedLocalCopies()`) → sign-in as the same user, `restoreFromServer` never runs
   and synced sessions disappear until the process dies. Fix: trigger restore + sync on every transition to
   Authenticated (e.g. in AuthViewModel or RecordingServices, collecting `sessionStatus` in an app scope), not
   only on ViewModel creation.
2. `restoreFromServer` swallows every error and runs once. There's no UI state and no retry. Expose
   `RestoreState(Loading/Done/Failed)`, show "Couldn't load older sessions — Retry" in History, and add pull-to-refresh.
3. Reps are only restored when the session row was newly inserted. Always `insertReps` (IGNORE) so a session
   saved without reps gets them back.
4. Session uploads only retry at bind, finish and sign-out. Add a unique WorkManager job (CONNECTED constraint, exponential
   backoff) that runs `syncPending`, like RecordingUploadWorker. Show the per-session `synced` state in the history list
   ("Saved on phone" vs "Synced").
5. Server not migrated: if `save_exercise_session` / `get_exercise_sessions` are missing, uploads fail silently
   (Log.w only), so nothing can be restored after a reinstall. Surface the failure (Settings: "Sync status · last
   error · Sync now") and document in supabase/README.md that all files in supabase/migrations must be applied in order.
6. `HistoryViewModel.session(id)` returns `flowOf(null)` if called before `bind` (e.g. state restored into the
   detail route), which shows "Session not found". Make it `patientId.filterNotNull().flatMapLatest { … }`.
7. Raw sensor recordings (`recordings` table) are never restored after a reinstall: by design there is no
   server table. Either say so in the UI or list them from Storage.
Tests (Robolectric/instrumented where needed): sign-out → sign-in with the same user restores synced sessions;
restore adds missing reps; failed upload retried by the worker; session(id) before bind emits once bound.
Manual checklist: record → force-stop → reopen; record offline → reconnect network; sign-out/in; uninstall/reinstall.
```

### P7 — BLE disconnection safety net

```text
Goal: The wearable being turned off, a dead battery, phone Bluetooth turned off, the device going out of range, the
app being backgrounded, or the app being killed during calibration or a set must never lose recorded reps or leave the user stuck.

Already handled (keep): the device keeps recording while disconnected; GET_REPS replay on reconnect; the
"End session now" interrupted save; command timeouts; reps written to Room immediately; stale in_progress →
interrupted on next launch; device ERROR / unexpected state → interrupted.

Add:
1. Auto-reconnect: persist the last device address (SharedPreferences). When `servicesReady` drops during
   calibration, a set or rest, call `BleManager.reconnect()` with backoff (1, 2, 4, 8 s… up to 60 s) instead of
   requiring the scanner. Show a banner: "Reconnecting to ReBand… (attempt n)" with "Open scanner" as a fallback.
2. Device lost power: after a reconnect the device reports IDLE (no calibration, empty ring). Instead of
   finishing immediately, keep the reps already stored and offer "Recalibrate and finish set N (k reps left)"
   (EXERCISE_CALIBRATION → START, continue with the same setIndex and offset the rep indices) or
   "Save and end".
3. Give-up timer: if not reconnected after 60 s, show "Save what you did" / "Keep trying" / "Discard".
4. Foreground service (`foregroundServiceType="connectedDevice"`) from calibration until the session is saved,
   with an ongoing notification ("Set 1 · 3/5 reps"). Add the FOREGROUND_SERVICE,
   FOREGROUND_SERVICE_CONNECTED_DEVICE and POST_NOTIFICATIONS permissions (request the last one on API 33+). Let
   RecordingManager keep capturing while the service runs. Today `foregroundChanged(false)` stops the raw
   capture as soon as the screen turns off or the user switches apps. Keep the screen on during a set
   (FLAG_KEEP_SCREEN_ON).
5. App killed mid-set: on the next connect, if a session became `interrupted` in the last ~15 min and the
   device's Status shows RECORDING/STOPPED with rep_count > stored reps, offer "Recover reps from the wearable"
   (GET_REPS from 1, attach to that session, mark it for re-sync).
6. Phone Bluetooth off: make sure BleManager's adapter-state receiver triggers the same path, and prompt
   with ACTION_REQUEST_ENABLE.
7. Calibration interrupted: after a reconnect, read Status. If cal_done shows the device still holds a finished
   calibration (READY), skip recalibration instead of always restarting at step 1.
8. Log every interruption (start, duration, cause, reps recovered) in the session (`interruptions` JSON, Room +
   Supabase) for the clinician.
Tests: extract the reconnect/resume decisions into a pure reducer and unit test it: drop during a set → reconnect with
device RECORDING → replay; drop → device IDLE → resume offer; 60 s timeout → save prompt.
Manual: unplug the ESP32 mid-set, walk out of range, turn phone BT off, swipe the app away mid-set.
```

---

## 5. Other missing features / housekeeping

**Product**
- Patient progress over time: Home has weekly activity only. Add ROM % of healthy arm and stability trends, a streak, and personal bests.
- Clinician prescription: sets × reps × frequency per patient. `TARGET_REPS` is a firmware compile-time constant,
  so it needs a new SET_TARGET control opcode or an app-side STOP. Add clinician notes per session as well.
- Exercise reminders (notification), optional.
- Account deletion / end-of-term data wipe (design doc §11). Nothing implements it yet.

**Firmware / protocol**
- EMG channel B is always 0, so coordination (CCI) from the design doc can't be computed. Drop it from the doc.
- `STANDALONE_TEST 1` in `reband_config.h`: confirm it can't start a standalone calibration during the demo
  if the phone connects late (it waits 10 s).
- The optional angle stream (see P3) is needed for velocity curves and smoothness.
- Battery level is not reported (design doc §3.3 / NFR-6).

**Quality / validation (report)**
- ROM vs goniometer (≤ 5° RMS), rep detection accuracy (≥ 95 %), packet loss at 2 m, reconnect time, battery life.
- UI tests for the exercise flow and the clinician drill-down.

**Docs / cleanup**
- `Document/Final Design Doc.md` is outdated:
  - It describes Firebase, but the app uses Supabase RPCs, RLS and Storage.
  - It describes shoulder flexion, but the exercise is a bicep curl.
  - It describes 2 EMG channels, but only the MyoWare ENV channel is used.
  - It puts all processing in the app, but calibration, rep detection and form checks run in the firmware.
  - It doesn't describe the reference-curl flow.
- `firmware/Reband/Final Design Doc.md` is a duplicate copy. Keep one.
- Remove `Android_app/test.txt`. Consider untracking `Android_app/.idea/*`. Rename the `com.example.app` package before the demo.
