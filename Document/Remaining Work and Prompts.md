# GETMS — Remaining Work & Implementation Prompts

_Snapshot of `main` @ `4e174d9`. Branches `Matthew`, `Sara's-Branch`, `Eddy---BLE-App-Side` contain nothing beyond `main`._

---

## 1. Where we are

| Area | Status on `main` |
|---|---|
| BLE (app) | ✅ Scan / connect / MTU / notifications / START-STOP / packet parser + seq-gap metrics (`ble/`) |
| Live debug screen | ✅ `BleSensorScreen` shows live EMG A/B + pitch traces (custom `Canvas`) |
| Auth | ✅ Supabase email/password sign-in, sign-up, sign-out, session restore (`ProfileViewModel`) |
| Local DB | ⚠️ Room `recordings` table exists, but **nothing ever inserts into it** |
| Supabase backend | ⚠️ Only `profiles` table + private `recordings` bucket. No sessions/recordings/reps tables, no clinician RLS. App has **no Postgrest/Storage client** (only `auth-kt`) |
| Navigation | ⚠️ Home → Sensor / History / Profile placeholders. No auth gate, no role routing |
| Calibration | ❌ Not started (needed before the real exercise) |
| Exercise flow, metrics, review, clinician view | ❌ Not started |
| BLE disconnection handling | ⚠️ State goes to `DISCONNECTED` and streaming flag resets. No reconnect, no pause, no partial-data save |
| Firmware | ❌ `main.c` only runs an EMG bench test. `ble.c`, `imu.c`, `emg.c` are **empty**, so the app has no real device to talk to |

### Architectural blocker to fix first
`SensorViewModel` creates its own `BleManager` and calls `close()` in `onCleared()`. The view model is
scoped to the Sensor screen, so **navigating to any other screen drops the BLE connection**. A multi-screen
exercise flow (placement → calibration → tutorial → sets → review) needs one app-wide BLE/session owner.

### Root causes of the "no history after restart" bug
1. No code path calls `RecordingDao.upsert(...)`, so Room stays empty.
2. No remote tables or Postgrest/Storage dependency, so nothing is uploaded and there's nothing to download.
3. Sign-out runs `clearAll()` (intended), but sign-in never pulls the user's history back from Supabase.
4. `RecordingEntity` has no `userId`, and `HistoryScreen` only shows a count.

---

## 2. Remaining work (suggested order)

| # | Item | Depends on |
|---|---|---|
| P0 | App-wide BLE/session owner + calibration (prerequisite) | — |
| P1 | Data layer: Room schema + Supabase schema + sync (**fixes history bug**) | — |
| P2 | Exercise entry: intro, SVG placement guide, 2-frame tutorial, "I'm ready" | P0 |
| P3 | Real exercise: 2 sets × 5 reps with live rep counter + rest between sets | P0, P2 |
| P4 | BLE disconnection safety net | P0, P1, P3 |
| P5 | Exercise analysis: effort (force-proxy) curve, velocity, stability + comparison chart | P3 |
| P6 | End-of-exercise review (patient-friendly) | P1, P5 |
| P7 | Clinician detailed view | P1, P5 |

**Target patient flow**

```text
Home → Select exercise → Intro (what we measure) → Placement guide (SVG: electrodes, IMU, strap)
     → Connect → Calibration → Tutorial (2-frame animation) → [I'm ready]
     → Set 1 (5 reps) → Rest → Set 2 (5 reps) → Processing → Review → Saved/synced
```

### Decisions the team should confirm
1. **Reference arm.** The grey "correct arm" line needs data from the unaffected arm. Do we record both arms
   every session (design doc §6.3), or record a baseline of the unaffected arm once and reuse it?
   The prompts below assume both arms per session, falling back to the latest unaffected recording.
2. **"Force"** EMG measures muscle *activation* (%MVC), not force. The UI should call it **"Muscle effort"**,
   and the report should describe it as a force proxy.
3. **Charts.** The design doc says Vico, but we already draw with a custom `Canvas`. The prompts reuse `Canvas` to avoid a new dependency.
4. **Design doc drift.** The doc says Firebase, but we use Supabase + Room. It also says minSdk 26, but the app is set to 24. Update §6.1, §10, and §11.

---

## 3. Implementation prompts

Every prompt assumes this context. Paste it before the specific prompt:

> **Shared context:** Android app in `Android_app/` (Kotlin, Jetpack Compose Material3, single activity,
> Navigation Compose in `navigation/AppNavHost.kt`, `AndroidViewModel` + `StateFlow`, Room in `data/local`,
> Supabase-kt in `data/remote`, no DI framework). BLE is in `ble/` (`BleManager`, `PacketParser`, models in
> `BleModels.kt`). EMG: 2 channels @ 1000 Hz (A = anterior deltoid, B = posterior deltoid), 20 samples/packet.
> IMU: quaternions @ 100 Hz, 5 samples/packet. Exercise = shoulder flexion (arm raise). Follow existing code
> style, keep changes focused, add unit tests for pure logic, and make sure `./gradlew testDebugUnitTest assembleDebug` passes.
> Reference: `Document/Final Design Doc.md` §6–§8.

### P0 — App-wide session owner + calibration (prerequisite)

```text
Goal: Make the BLE connection survive navigation, then add per-limb calibration.

1. Move BleManager ownership out of SensorViewModel into an app-scoped singleton (e.g. a
   `GetmsApp : Application` holding `bleManager` and a new `ExerciseSessionController`), registered in
   AndroidManifest. SensorViewModel and all new screens read from it; only an explicit "Disconnect" or
   app shutdown closes BLE. Remove `bleManager.close()` from SensorViewModel.onCleared.
2. Create `session/ExerciseSessionController` holding a StateFlow<SessionState> state machine:
   Idle → Intro → Placement → Connect → Calibrating → Tutorial → Ready → InSet(setIndex) → RestBetweenSets
   → Processing → Review → Done, plus Interrupted (used by P4). Pure Kotlin, unit-testable.
3. Calibration screen (design doc §6.4): 5 s rest → 3 × 3 s maximum contraction (MVC) → 3 s neutral pose
   (arm relaxed at side). Show a countdown, a progress bar, and simple instructions for each step. Compute and store a
   `Calibration(restMean[2], restSd[2], mvcPeak[2], q0: Quaternion)`:
   - EMG envelope = RMS over 100 ms windows of the raw samples. restMean/restSd come from the rest window,
     and mvcPeak = max envelope over the 3 MVC trials, per channel.
   - q0 = normalized average quaternion of the neutral-pose window.
   Reject the calibration with a clear retry message if the IMU moves > 5°/s during rest or neutral pose, or if
   mvcPeak < 3 × restMean on either channel (electrode problem).
4. Angle: add `relativeFlexionDeg(q, q0)` using q_rel = q0⁻¹ ⊗ q (§7.1) instead of the absolute pitch.
Tests: envelope RMS on a synthetic sine, calibration rejection rules, q_rel = identity when q == q0.
Done when: you can connect on the Sensor screen, navigate to calibration and back, and the stream never drops.
```

### P1 — Fix local + Supabase storage (history survives restart)

```text
Goal: The patient sees their full exercise history after an app restart, a reinstall, or a sign-out/sign-in.
Room is the single source of truth for the UI, and Supabase is the durable copy.

Current bugs: nothing inserts into Room. Supabase has only `profiles` + bucket `recordings`. The app only
depends on supabase `auth-kt`. Sign-out wipes Room (keep that), but sign-in never pulls remote data.

1. Supabase (`supabase/setup.sql`, keep idempotent, add a new section, and update supabase/README.md):
   - `profiles`: add `clinician_id uuid null references profiles(id)` and `affected_side text check (left|right)`.
   - `invites(code text pk, clinician_id uuid)`, readable by authenticated users via a single-row lookup.
   - `exercise_sessions(id uuid pk, user_id, clinician_id, exercise_id text, started_at, ended_at,
     status text check (in_progress|complete|interrupted), sets_planned int, reps_per_set int)`.
   - `recordings(id uuid pk, session_id fk, user_id, clinician_id, limb text (affected|unaffected),
     side text, started_at, ended_at, calibration jsonb, metrics jsonb, raw_trace_path text,
     raw_trace_status text (pending|uploaded), app_version, fw_version)`.
   - `reps(recording_id fk, set_index int, rep_index int, metrics jsonb, primary key(recording_id,set_index,rep_index))`.
   - RLS: the patient can select/insert/update their own rows (`user_id = auth.uid()`). The clinician can SELECT
     rows where `clinician_id = auth.uid()`. Add a storage SELECT policy so the linked clinician can read the
     patient's folder. Add the index `(user_id, exercise_id, started_at)`.
2. Android deps (libs.versions.toml + app/build.gradle.kts): supabase `postgrest-kt`, `storage-kt`,
   kotlinx-serialization plugin + json, `androidx.work:work-runtime-ktx`.
3. Room: version 2 with an explicit Migration(1,2). **No destructive fallback**, because it would wipe history.
   Entities: SessionEntity, RecordingEntity (+ userId, sessionId, limb, metricsJson, calibrationJson,
   rawTracePath, syncState: PENDING|SYNCED|FAILED, updatedAt), RepEntity. DAOs filter by userId.
4. Repository `data/HistoryRepository`:
   - `saveLocal(...)` writes Room in one transaction, then enqueues a unique WorkManager `SyncWorker`
     (NetworkType.CONNECTED, exponential backoff).
   - `SyncWorker` upserts PENDING rows to Postgrest, uploads the raw trace file to
     `recordings/<uid>/<sessionId>/<recordingId>.bin`, and marks rows SYNCED.
   - `pullRemote(userId)` runs after sign-in and on app start when signed in. It fetches sessions, recordings,
     and reps, then upserts them into Room without overwriting local PENDING rows.
5. HistoryScreen: list sessions by date with the exercise name, the reps done, and a sync chip
   (Synced / Waiting for network / Failed - retry). Include empty and loading states. Tapping a session opens the review (P6).
Tests: migration test (Room MigrationTestHelper), DAO user filtering, and a pull-merge rule that never
overwrites PENDING.
Done when: you record → kill the app → reopen and history is there; you sign out → sign in and history is restored
from Supabase; you record offline → go online and it syncs automatically.
```

### P2 — Enter exercise: intro, placement SVG, tutorial animation

```text
Goal: Before recording, the patient learns what's measured, how to put the sensors on, and what the movement looks like.

1. Exercise intro screen: exercise name, 2 sets × 5 reps, an estimated duration, and three plain-language cards
   for what we measure: "Muscle effort" (EMG), "Speed of movement", "Stability/smoothness". Include a "Next" button.
2. Placement guide screen. Create vector art as Android VectorDrawables in res/drawable. Author them as SVG,
   then import them through Android Studio Vector Asset, or write the XML directly:
   - `placement_emg.xml`: upper arm with Ch A electrodes on the anterior deltoid, Ch B on the posterior deltoid,
     and the reference electrode on a bony landmark (elbow/acromion), labelled A / B / REF.
   - `placement_imu.xml`: the IMU box on the lateral upper arm with an arrow showing the x-axis along the humerus.
   - `placement_strap.xml`: the elastic strap holding the unit, snug but not tight.
   Show them as a 3-step pager with a short caption each, "Back/Next", and step dots. The side shown
   (left/right) mirrors the arm being tested, using `Modifier.graphicsLayer(scaleX = -1f)`.
   All art must use theme colors (tint), work in dark mode, and have contentDescription text.
3. Then go to Connect (reuse the existing scan/connect UI if not connected) → Calibration (P0).
4. Tutorial screen after calibration: a 2-frame animation of the arm raise, made of two vector drawables
   (`tutorial_arm_down.xml`, `tutorial_arm_up.xml`) alternated every ~1 s with `Crossfade` driven by a
   `LaunchedEffect` loop. Add a caption like "Raise your arm forward to shoulder height, then lower slowly" and a large
   "I'm ready" button that starts Set 1 (P3). Respect the system "remove animations" setting by showing both frames side by side.
Done when: the full path Intro → Placement (3 steps) → Connect → Calibration → Tutorial → Ready works with Back
navigation and keeps the BLE connection alive.
```

### P3 — Real exercise: 2 sets × 5 reps

```text
Goal: Record the actual exercise after calibration, with live feedback.

1. Live rep counter (`processing/realtime/RepCounter`): a causal low-pass (2nd-order Butterworth 6 Hz, or a
   one-euro filter) on the relative flexion angle from P0. A rep is counted when the angle rises above
   `startThreshold + minPeakProminenceDeg (20°)` and then returns below `startThreshold + 10°`, with ≥ 1.0 s
   between reps. Emit rep boundaries (start/peak/end sample timestamps). Latency must be ≤ 300 ms after the peak.
2. Exercise screen: big "Set 1 of 2", a rep counter "3 / 5", a live angle trace with a target band, and a
   "Stop" button. After 5 reps, auto-end the set → 30 s rest countdown (skippable) → Set 2 → Processing.
3. Recording buffer: for the recording, store every EMG and IMU packet exactly as received plus the rep
   boundaries and set index. Append to a file in `filesDir/raw/<sessionId>/<recordingId>.bin` while
   recording (one JSON header line with rates, scales, calibration, and fw/app version, then the packets). Flush
   every ~2 s, which P4 relies on.
4. On completion, save it through HistoryRepository (P1) with status complete and trigger the analysis (P5).
5. If both arms are recorded per session (team decision), after the first arm show an "arm switch" step:
   move the strap (show the placement SVGs again) → recalibrate → tutorial skipped → sets.
Tests: RepCounter on synthetic sinusoidal angle data (exactly 5 reps, noise robustness, no double-count at the peak).
Done when: 2 × 5 reps on the device produce exactly 10 detected reps and a raw file + Room rows.
```

### P4 — BLE disconnection safety net

```text
Goal: A device power-off, a dead battery, Bluetooth being turned off, moving out of range, or the app being
killed during an exercise never loses completed reps and never leaves the user stuck.

1. Detection, in ExerciseSessionController: ConnectionState → DISCONNECTED, **or** no EMG/IMU packet for
   > 1 s while in a set (watchdog), **or** BluetoothAdapter ACTION_STATE_CHANGED → OFF.
2. On detection: freeze the set (stop the rep counter, keep completed reps, discard the partial rep), flush
   the raw file, set the session state to Interrupted, and show a non-dismissable dialog: "Sensor disconnected - check
   it's on and nearby" with a live reconnect status.
3. Auto-reconnect to the last device address (persist it in DataStore or SharedPreferences) with backoff
   1 s, 2 s, 4 s… up to 30 s total. On success, re-enable notifications, send START, and offer
   "Resume set (n reps left)" / "Restart set". If the strap may have moved (more than 2 min disconnected), require recalibration.
4. Give up after 30 s: offer "Save what I did" (status interrupted, partial metrics) or "Discard".
   Never silently drop data.
5. App killed or crashed mid-session: on the next launch, detect an `exercise_sessions` row with status
   in_progress, then offer "Resume / Save partial / Discard".
6. Run recording in a foreground service (`foregroundServiceType="connectedDevice"`, notification
   "Recording exercise…") so a screen-off or app switch doesn't kill it. Add the FOREGROUND_SERVICE,
   FOREGROUND_SERVICE_CONNECTED_DEVICE and POST_NOTIFICATIONS (API 33+) permissions.
7. Log every interruption (time, duration, cause, packets lost) into the recording metadata so the clinician
   can see it.
Tests: a controller unit test with a fake BLE flow: packets stop → Interrupted → reconnect → resume keeps
the rep count, and timeout → save partial.
Done when: you unplug the ESP32 mid-set → the dialog appears ≤ 1.5 s → plug it back in → resume, and the final rep count is correct.
```

### P5 — Exercise analysis (effort, velocity, stability) + comparison chart

```text
Goal: Turn a finished recording into per-rep and per-set metrics plus an averaged movement profile,
compared against the reference (unaffected/"correct") arm.

Pure-Kotlin module `processing/features/`, runs on Dispatchers.Default, ≤ 3 s for a recording.
1. Signals: zero-phase 4th-order Butterworth 6 Hz on the angle (forward-backward). Velocity and
   acceleration come from central differences. The EMG envelope is 100 ms RMS, normalized to %MVC with the calibration.
2. Per rep: ROM (°), peak and mean angular velocity (°/s), rep duration (s), mean %MVC Ch A (muscle effort),
   CCI between A and B, and smoothness (SPARC on the speed profile, or the number of velocity peaks as a fallback).
   Steadiness = RMS (°) of the angle minus its 1 Hz low-pass during the rep (tremor/shake).
3. Per set and per recording: the mean of the per-rep metrics, CV of ROM and peak velocity (consistency),
   reps done/planned, and a fatigue indicator (EMG median-frequency slope across reps, optional).
4. Stability score 0–100 for patients: combine steadiness, smoothness, and consistency with documented
   weights. Keep the raw components for clinicians.
5. Averaged effort curve ("average force graph"): time-normalize each rep to 0–100 % of the movement
   (101 points), then compute the ensemble mean and SD of %MVC. Do the same for the angle and velocity.
6. Asymmetry vs the reference arm: AI = |aff − unaff| / ((|aff| + |unaff|)/2) × 100, plus "% of healthy arm"
   = aff/unaff × 100 for patient wording. The reference is the unaffected-arm recording from the same session,
   or else the latest one for this exercise.
7. Reusable composable `ComparisonChart(current, reference, band?, yLabel)` on `Canvas`:
   the current arm in `MaterialTheme.colorScheme.primary` (2.5 dp, on top), the reference arm as a
   grey line (`onSurfaceVariant` at ~50 % alpha, behind), an optional ±SD band in the primary color at 15 % alpha, a grey
   chart background (`surfaceVariant`), axis labels, and a legend "Your [left] arm" / "Your [right] arm (reference)".
   Show it for Effort (%MVC), Angle (°), and Velocity (°/s) vs "% of movement".
8. Store metrics JSON in Room/Supabase (P1 schema), along with the averaged curves (101 points each, small).
Tests: ROM/velocity on a synthetic sine of known amplitude and frequency, SPARC is smoother for a minimum-jerk
profile than for a noisy one, AI edge cases (both 0 → 0), and time-normalization length = 101.
```

### P6 — End-of-exercise review (patient-friendly)

```text
Goal: After processing, show a simple, encouraging summary with no jargon (no "SPARC", "%MVC", "CCI").

Screen `ReviewScreen(sessionId)`, also reachable from History:
1. Header: "Great job!", the exercise, the date, and "10 / 10 reps".
2. Three big cards with a value, a plain label, a traffic-light or star rating, and the change vs the last session (↑/↓):
   - Range of motion: "You raised your arm 82° (↑ 5° since last time)"
   - Speed: "Average speed 64°/s" + "steady pace" / "a bit rushed" based on the CV
   - Stability: "Stability 78/100 - smooth movement"
3. "Compared with your other arm": one line like "Your left arm reached 85 % of your right arm's range",
   plus the ComparisonChart (P5) for effort, with a toggle for Angle / Speed.
4. Set 1 vs Set 2 mini comparison (a fatigue hint, e.g. "Set 2 was slower, normal when muscles tire").
5. Sync status chip + "Done" (back to Home). If interrupted (P4), show a gentle banner: "Session was
   interrupted - we saved 7 reps".
Accessibility: large text, ≥ 48 dp touch targets, color is never the only signal (icons + text), TalkBack labels.
Loading, empty (no reference arm yet → hide the comparison and explain), and error states.
```

### P7 — Clinician detailed view

```text
Goal: A clinician can inspect every detail of a linked patient's sessions.

Prereqs: P1 schema + RLS (clinician SELECT where clinician_id = auth.uid()), and invite-code linking
(the patient enters a code in Profile, which sets profiles.clinician_id through a SECURITY DEFINER RPC `link_clinician(code)`).

1. Role routing: after sign-in, read `profiles.role` and send patients to Patient Home and clinicians to Clinician Home.
2. Clinician Home: a patient list (pseudonym, affected side, last session date, a trend arrow for ROM).
3. Patient detail: session list + trend charts across sessions (ROM, peak velocity, stability, effort, AI).
   The affected arm is in the primary color, and the unaffected arm is grey.
4. Session detail:
   - Per arm / per set / per rep table: ROM, peak and mean velocity, duration, %MVC A, CCI, SPARC, steadiness,
     MDF; aggregates with CV; asymmetry index per metric ("lower is better").
   - Calibration values (rest mean/SD, MVC peaks), app/fw version, packet loss %, and the interruption log (P4).
   - Averaged curves (ComparisonChart) with ±SD bands.
   - Raw signal viewer: download the raw trace from Storage on demand (cache it locally), parse it with PacketParser,
     and plot the angle, EMG A/B envelopes, and velocity vs time with rep boundaries and set markers. Support pinch-zoom/pan
     and channel toggles.
   - "Export CSV" (per-rep metrics), shared through the Android share sheet.
5. Read-only: clinicians cannot edit patient data. Optional: a clinician can set the prescription
   (sets × reps, rest time) per patient in an `exercise_prescriptions` table that the patient flow reads.
Tests: RLS checks in SQL (patient B cannot read patient A, and an unlinked clinician cannot read either), and a
ViewModel test for the drill-down state.
```

---

## 4. Other missing features (vs design doc and demo readiness)

**Critical path**
- **Firmware is essentially empty.** It needs the IMU driver + Madgwick fusion, EMG sampling at 1 kHz on ADC1
  with band-pass/notch, the BLE GATT server matching `GattConstants` (EMG/IMU/Status/Control), the packet format
  §5.3, and the IDLE/STREAMING states. Without it, none of the app flow can be demoed with real data.
  As a stopgap, add a **fake BLE source** in the app (synthetic sine angle + noise EMG) so P0–P7 can be built and
  tested in parallel.
- **Signal-quality check (FR-4)** before calibration: rest noise, saturation, IMU stationary → pass/fail per channel.
- **Auth gate + role-based home (FR-1).** The app currently opens Home with debug buttons for everyone.
- **Patient ↔ clinician linking via invite code (FR-2).**

**Design-doc features not covered above**
- First-limb proposal (alternate each session) + override reason (FR-8).
- Arm-switch screen with a rest countdown + recalibration (FR-7), if both arms are recorded.
- Exercise catalogue / prescription from the backend instead of hard-coded values.
- Patient home dashboard (FR-11): streak, personal bests, ROM + asymmetry trends, last-session card.
- Settings: forget device, rest duration, sign out, debug logging. Move the current Sensor debug screen under it.
- Loading, empty, and error states on every screen (§6.4). Add a permission rationale screen + denial handling.

**Quality / validation**
- Tests: only `PacketParserTest` exists. Add DSP/metric unit tests, Room migration tests, RLS tests, and Compose UI tests for the flow.
- Validation for the report: ROM vs goniometer (≤ 5° RMS, n ≥ 30 reps), rep-detection accuracy (≥ 95 % on
  ≥ 10 labelled recordings), packet loss ≤ 1 % at 2 m, reconnect ≤ 10 s, processing ≤ 3 s.
- Hardware: enclosure + strap, electrode landmark photos (they can be used as references for the SVGs), power decision.

**Housekeeping**
- Update the design doc (Firebase → Supabase, Room, minSdk 24, clinician RLS, new flow with sets/tutorial).
- Supabase email-confirmation deep link (or keep confirmation off for the demo).
- Account/data deletion for the end of term (§11).
- Rename package `com.example.app` and the app label before the demo. Remove `Android_app/test.txt`.
