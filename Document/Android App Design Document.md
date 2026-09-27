# Design Document — Upper Limb Rehabilitation Tracker

## 0. Project Summary

A wearable EMG + IMU sensor system captures muscle activation and limb motion while a patient performs prescribed exercises (e.g. arm raises). Data streams over BLE to a **native Android app** (Kotlin + Jetpack Compose — required by the course project brief), which processes it in real time and syncs it to a Supabase (Postgres) backend. The system extracts clinically meaningful features per session — range of motion, speed, smoothness, fatigue, bilateral asymmetry — and presents them through two in-app views: a simple, motivating progress view for the patient, and a detailed, drillable analytics dashboard for the clinician.

The project brief requires an Android app combined with sensors/actuators and basic data analytics — this design satisfies all three: the ESP32 sensor units, the Android BLE/processing app, and the analytics/dashboard layer.

There is no 3D visualization component — this was considered and dropped; the live session view shows real-time numeric/graphical feedback (rep counter, live signal trace) instead of a rendered arm model.

---

## 1. System Architecture

```text
                          PATIENT
                             │
                      ┌──────┴──────┐
                      │             │
                    EMG            IMU
                      │             │
                      └──────┬──────┘
                             ↓
                      Microcontroller
                   (sampling + on-device
                    filtering, per limb)
                             ↓
                        Bluetooth (BLE)
                             ↓
                        Android App
                    (Kotlin / Jetpack Compose)
                             ↓
                     Signal Processing
                (filtering, rep segmentation,
                    onset/offset detection)
                             ↓
                    Feature Extraction
              (ROM, speed, smoothness, fatigue,
                 timing, coordination, asymmetry)
                             ↓
                    ┌──────────────────┐
                    │  Patient Metrics  │
                    │  (Supabase/       │
                    │   Postgres)       │
                    └──────────────────┘
                             ↓
                  ┌──────────┴──────────┐
                  ↓                     ↓
           Patient View            Doctor Dashboard
        (simple progress,        (drillable: Patient →
         motivational)            Exercise → Session →
                                   Metrics → Raw signals)
              (both are screens within the same Android app)
```

### Where each stage lives

| Stage | Location | Why |
|---|---|---|
| Sampling + basic filtering | Firmware (ESP32) | Reduce BLE payload, keep noise off the wire |
| Rep segmentation, real-time feedback | Android app (Kotlin) | Needed live for the rep counter and in-session feedback |
| Full feature extraction (smoothness, fatigue, asymmetry) | Android app at session end, or a Supabase Edge Function | Heavier computation (FFT for fatigue, SPARC for smoothness); fine to run once per session rather than continuously |
| Storage / cross-session aggregation | Supabase Postgres + Storage | Structured metrics as relational tables (queryable with SQL), raw signal traces in Supabase Storage (too large/unstructured for table rows) |
| Dashboards | Android app, role-gated (patient vs clinician view) | One codebase, satisfies the "Android app" project requirement directly |

---

## 2. Hardware

### Sensors / components (per limb)

- **MyoWare 2.0 EMG** ×2 per limb — flexor + extensor channel.
- **IMU**: BNO055 (9-DOF, onboard sensor fusion), one per limb.
- **ESP32** (dual-core) — one per limb.
- **TP4056** — LiPo charge management, per unit.
- **MAX17048** (or similar) — battery fuel gauge, per unit.

### Bilateral sensing — key decision

Asymmetry metrics require simultaneous data from **both** arms. Recommended: **two independent sensor units** (affected + unaffected arm), each its own ESP32/BLE peripheral, both connected to the same phone session and time-synchronized (shared session start timestamp, or a sync pulse at exercise start).

> Alternative considered: a single unit with sensors run to both arms via cabling. Rejected — restricts patient movement and complicates the strap/enclosure design for little benefit over two independent wireless units.

### Power / mechanical

- LiPo sized against duty cycle — EMG at 500–1000 Hz + IMU + active BLE is the main draw.
- Physical on/off switch + low-battery cutoff.
- Elastic strap + gel electrodes for consistent skin contact; motion artifact from a loose strap is the most common EMG failure mode — design for it early.

---

## 3. Embedded Software

### Framework

Arduino for MVP → migrate sampling-critical tasks to FreeRTOS tasks/queues as timing needs tighten.

### Core responsibilities (per unit)

1. **Sensor sampling task** — EMG at 500–1000 Hz, IMU at 50–100 Hz, fixed-rate timer.
2. **On-device signal conditioning** — rectify + smooth (RMS envelope) each EMG channel; raw IMU passed through onboard BNO055 fusion.
3. **BLE GATT service** — characteristics for EMG channel(s), IMU orientation (quaternion), battery, calibration command, device status, and a **unit identifier** (affected/unaffected) so the phone can tell the two streams apart.
4. **Calibration routine** — capture rest baseline and max-contraction (MVC) baseline per channel on command; store in flash and send to the app for cloud storage per user, per limb.
5. **Timestamping** — attach a monotonic device timestamp to each sample so the phone can align both limbs' streams after the fact, even if BLE delivery timing jitters.
6. **Scheduler** — sampling, processing, and BLE notification as separate tasks/queues so BLE traffic doesn't stall sampling.
7. **Power management** — sleep/low-power BLE intervals when idle.

---

## 4. Android App (Kotlin, Jetpack Compose)

Fully native — a hard requirement of the project brief, not just an implementation preference.

### Recommended libraries

| Need | Library | Why |
|---|---|---|
| BLE | **Kable** | Coroutines/Flow-native BLE library — makes managing two simultaneous GATT connections (one per limb) far cleaner than raw `BluetoothGatt` |
| Charts | **Vico** | Compose-native charting for the trend/bar charts in the dashboards (§7) — avoids wrapping a View-based library like MPAndroidChart |
| Backend client | **supabase-kt** | Official Kotlin client for Supabase — typed Postgrest queries, Auth, and Storage from the same library |

### Architecture

```text
Android App (Kotlin, Jetpack Compose)
│
├── Screens (Compose)
│   ├── Auth
│   ├── Device connection (pairs BOTH limb units)
│   ├── Exercise selection
│   ├── Live session view (rep counter, live signal feedback)
│   ├── Patient progress view
│   ├── Clinician dashboard (Patient → Exercise → Session → Metrics → Raw signals)
│   └── Settings
│
├── BLE Layer (Kotlin, via Kable)
│   └── Two concurrent GATT connections (affected + unaffected units),
│       each exposed as a Kotlin Flow tagged by limb
│
├── Signal Processing Layer (Kotlin)
│   ├── Real-time: light filtering + rep counting for live feedback
│   └── End-of-session: full feature extraction (§6), batched
│
└── Supabase Layer (Kotlin, via supabase-kt)
    └── Auth, Postgrest (metrics tables), Storage (raw traces), sync
```

### Live session flow

1. Clinician/patient selects exercise (e.g. "Arm raise ×10").
2. App connects to both sensor units, confirms signal quality (impedance/contact check if available, or a quick baseline read).
3. Guided countdown → patient performs reps → app segments reps in real time, shows live rep counter and signal feedback.
4. On completion: app runs full feature extraction, writes session + metrics rows to Supabase, uploads raw trace to Supabase Storage.

---

## 5. Signal Processing

### IMU pipeline

1. Orientation already fused on-device (BNO055) → quaternion stream.
2. Convert to joint angle of interest (e.g. shoulder flexion angle) via a fixed reference frame calibrated at session start (patient holds neutral "rest" pose).
3. Low-pass filter (e.g. 4th-order Butterworth, ~5–10 Hz cutoff) to remove sensor noise before feature extraction.
4. Differentiate filtered angle → angular velocity → angular acceleration, for speed/accel features.

### EMG pipeline

1. On-device: rectify + RMS envelope (already done in firmware).
2. On phone: normalize envelope against that channel's calibration MVC → % MVC activation.
3. Onset detection: threshold crossing at baseline mean + 3×SD, sustained for a minimum duration (avoids false triggers on noise spikes).

### Rep segmentation

Peak detection on the filtered joint-angle signal: local maxima with a minimum prominence and minimum inter-peak interval (tuned per exercise type) mark rep boundaries. EMG onset can refine the start-of-rep timestamp.

---

## 6. Feature Extraction — What Gets Analyzed

### From IMU, per session (and per rep, then aggregated)

| Metric | Definition |
|---|---|
| Range of motion (ROM) | max(angle) − min(angle) within a rep window, in degrees |
| Movement speed | peak (or mean) angular/linear velocity during a rep |
| Acceleration | peak or RMS angular acceleration during a rep |
| Movement smoothness | Spectral Arc Length (SPARC) on the velocity profile — standard rehab smoothness metric, robust to noise and duration; normalized jerk score as a simpler fallback |
| Repetition count | output of rep segmentation, vs. prescribed count (e.g. 8/10) |
| Movement consistency (variability) | coefficient of variation, CV = SD/mean × 100%, of ROM (or speed) across reps in a session — lower is more consistent |
| Time to complete | total duration for the prescribed rep count |

### From EMG, per session

| Metric | Definition |
|---|---|
| Muscle activation level | RMS amplitude during the activation window, normalized to %MVC from calibration |
| Activation timing | onset latency: time from movement onset (IMU) to EMG onset (threshold crossing) |
| Muscle fatigue | median frequency (MDF) of the EMG power spectrum (FFT per rep); a declining MDF trend across the session is the classic EMG fatigue indicator |
| Muscle coordination | co-contraction index between flexor and extensor channels — overlap of simultaneous activation, normalized |
| Affected vs. unaffected difference | see asymmetry index below, applied per metric |

### Asymmetry index

For any paired metric X (ROM, speed, EMG activation, etc.), computed between affected and unaffected limb:

```
A = |X_affected − X_unaffected| / ((X_affected + X_unaffected) / 2)
```

Tracked per session, per metric. A decreasing trend over the rehab program is the key clinical signal — it quantifies recovery of bilateral function, not just improvement of the affected limb in isolation.

### Example session-over-session table (illustrative)

| Metric | Session 1 | Session 10 |
|---|---:|---:|
| Range of motion | 62° | 81° |
| Movement speed | 0.42 m/s | 0.61 m/s |
| Movement smoothness | 0.54 | 0.72 |
| Repetitions | 8/10 | 10/10 |
| EMG activation | 0.31 mV | 0.39 mV |
| Movement variability | 18% | 9% |

---

## 7. Dashboards (both in-app, role-gated)

### Two levels of information

- **Doctor overview** — "How is the patient progressing?" High-level trend across sessions.
- **Detailed analysis** — "Why did this metric change?" Drill into a specific session and the raw signal behind a metric.

### Drill-down hierarchy

```
Patient → Exercise → Session → Metrics → Raw signals
```

- **Patient**: list of assigned patients, each with a progress summary.
- **Exercise**: which prescribed exercises this patient is doing (arm raise, etc.).
- **Session**: chronological list of sessions for that exercise, each with headline metrics.
- **Metrics**: full metric table/trend charts for the selected session (as in §6 table).
- **Raw signals**: EMG/IMU waveform viewer for that session — for when a clinician needs to see *why* a metric looks off (e.g. a noisy rep, a strap slip).

### Doctor dashboard — progress view (illustrative)

```
PATIENT: #024
Upper Limb Rehabilitation
──────────────────────────────

Range of Motion
Session 1   ███████████
Session 5   ██████████████
Session 10  █████████████████

Movement Speed
Session 1   ███████
Session 5   ███████████
Session 10  █████████████

Left/Right Symmetry
Session 1   █████
Session 5   █████████
Session 10  ███████████████
```

Implementation: bar/line charts per metric using Vico (Compose-native), driven directly by Postgrest queries against Supabase (e.g. `SELECT ... FROM session_metrics WHERE patient_id = ? AND exercise_id = ? ORDER BY session_date`).

### Patient view

Simpler and motivational — streak count, personal bests, a couple of headline trend charts (ROM, symmetry) without the full clinical metric set. Same data, reduced/reframed presentation.

---

## 8. Backend — Supabase (Postgres)

### Why Supabase over a NoSQL option (e.g. Firebase)

- The core product is trend/aggregate analytics over structured session data — "ROM trend for patient X across all sessions," "asymmetry trend by exercise" — these are natural SQL `GROUP BY`/`ORDER BY`/window-function queries, not a good fit for a document store.
- **Row-Level Security (RLS)**, written as SQL policies on each table, enforces "patient sees only their own data, clinician sees only assigned patients" at the database level — not just app-side logic.
- Auth, Postgrest (auto-generated REST API), and Storage (for raw traces) are all included, same convenience as Firebase.
- It's plain Postgres underneath — standard SQL, portable, no proprietary query language.
- Latency is a non-issue for this design: live feedback during a session runs entirely on-device from BLE data and never touches Supabase; Supabase is only hit once at session-end (write) and on dashboard load (read) — neither is latency-sensitive.

### Schema (tables)

**users**
```sql
id uuid primary key,
role text check (role in ('patient', 'clinician')),
name text,
clinician_id uuid references users(id)  -- null for clinicians
```

**calibrations**
```sql
id uuid primary key,
user_id uuid references users(id),
limb text check (limb in ('affected', 'unaffected')),
channel text check (channel in ('flexor', 'extensor')),
baseline_rest numeric,
baseline_mvc numeric,
recorded_at timestamptz
```

**exercises**
```sql
id uuid primary key,
name text,
prescribed_reps int
```

**sessions**
```sql
id uuid primary key,
user_id uuid references users(id),
exercise_id uuid references exercises(id),
start_time timestamptz,
end_time timestamptz,
reps_completed int,
raw_trace_path text  -- Supabase Storage object path
```

**session_metrics**
```sql
id uuid primary key,
session_id uuid references sessions(id),
limb text check (limb in ('affected', 'unaffected')),
rom numeric,
speed numeric,
smoothness numeric,
emg_activation numeric,
variability numeric,
fatigue_mdf numeric
```

**session_asymmetry**
```sql
id uuid primary key,
session_id uuid references sessions(id),
metric text,       -- 'rom', 'speed', 'emg_activation', etc.
asymmetry_value numeric
```

A query like "ROM trend for patient X, arm-raise exercise, across sessions" is one SQL statement joining `sessions` → `session_metrics`, filtered and ordered — directly backing the chart in §7.

### Raw signal storage

Raw EMG/IMU traces are high-volume time series — stored as a binary/CSV blob per session in **Supabase Storage**, referenced by `raw_trace_path` in the `sessions` table. The "raw signals" drill-down level fetches and plots this on demand, not loaded by default.

---

## 9. Security & Privacy

- Supabase Auth; role stored on the `users` table.
- Row-Level Security policies enforcing patient/clinician data isolation directly in Postgres — e.g. a patient's `SELECT` policy restricts to `user_id = auth.uid()`, a clinician's restricts to `user_id IN (SELECT id FROM users WHERE clinician_id = auth.uid())`.
- Same RLS model extends to Storage bucket access for raw traces.
- This is health-adjacent data tied to an identified patient — decide early whether to treat it under formal health-data handling (encryption at rest, retention policy, audit logging) even at MVP scale.

---

## 10. Testing Plan

- **Unit**: rep segmentation accuracy, feature calculations (ROM, SPARC, MDF, asymmetry formula) against synthetic/known signals.
- **Integration**: dual-BLE-connection stability (Kable), Supabase sync (Postgrest writes, Storage uploads), calibration persistence.
- **Validation**: compare app-computed ROM/speed against a reference (e.g. manual goniometer reading, video motion tracking) to establish measurement accuracy before trusting the clinical metrics.
- **UI**: drill-down navigation (Patient → Exercise → Session → Metrics → Raw signals), dashboard rendering correctness, RLS policy correctness (patient cannot query another patient's data).
- **Performance**: BLE latency across two simultaneous connections, session-end processing time, battery consumption per unit.

---

## 11. Development Roadmap

1. **Project setup** — Android Studio project (Kotlin/Compose), Supabase project + schema (§8), GitHub repo, ESP32 firmware skeleton (×2 units)
2. **Firmware core** — sampling, on-device conditioning, BLE GATT service, per-unit identification, timestamping
3. **Dual BLE connectivity (mobile)** — Kable-based connect/manage both limb units concurrently, tagged streams
4. **Calibration system** — per-limb, per-channel MVC/rest calibration flow, Supabase integration
5. **Signal processing + rep segmentation** — real-time filtering, live rep counter and signal feedback
6. **Feature extraction engine** — ROM, speed, smoothness (SPARC), consistency, EMG activation, timing, fatigue (MDF), coordination, asymmetry index
7. **Data platform** — session/metrics tables in Supabase, RLS policies, raw trace upload to Storage
8. **Patient view** — simplified progress dashboard (Vico charts)
9. **Clinician dashboard** — full drill-down (Patient → Exercise → Session → Metrics → Raw signals), trend charts
10. **Validation & testing** — accuracy validation against reference measurement, full test plan from §10

---

## 12. Open Questions / Next Steps

1. **Validation reference** — what ground truth will you validate ROM/speed against (goniometer, video, motion capture)? Needed to claim the metrics are clinically meaningful, not just internally consistent.
2. **Regulatory posture** — consumer wellness/research tool vs. something aiming toward clinical validity? Affects data handling rigor and whether metric accuracy needs formal validation.
3. **Exercise library scope** — is "arm raise" the only exercise for MVP, or is the rep-segmentation/feature pipeline meant to generalize to multiple exercise types from the start? Generalizing early costs more design time but avoids a rewrite later.
4. **Two-unit BOM/cost** — confirm budget accommodates two full sensor units (EMG×2 + IMU + ESP32 each) rather than one.

---

## Deliverables

- **Embedded**: ESP32 firmware ×2 (sampling, conditioning, BLE GATT, calibration, timestamping)
- **Android App**: Native app (Kotlin, Jetpack Compose, Kable for BLE, Vico for charts) — dual BLE, calibration, live session view, signal processing, feature extraction, patient view, clinician drill-down dashboard
- **Backend**: Supabase (Auth, Postgres schema in §8, Storage for raw traces, RLS policies)
- **Documentation**: this design doc, BLE characteristic spec, feature-extraction algorithm reference (formulas in §6), database schema, validation report
