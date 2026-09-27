# Design Document — Upper Limb Rehabilitation Tracker

## 0. Project Summary

A single wearable EMG + IMU sensor unit captures muscle activation and limb motion while a patient performs prescribed exercises (e.g. arm raises). The same device is worn on the unaffected arm for one recording, then switched to the affected arm for a second recording, within the same session/visit. Data streams over BLE to a **native Android app** (Kotlin + Jetpack Compose — required by the course project brief), which processes it in real time and syncs it to Firebase. The system extracts clinically meaningful features per recording — range of motion, speed, smoothness, fatigue — and compares the paired affected/unaffected recordings to compute bilateral asymmetry. Results are presented through two in-app views: a simple, motivating progress view for the patient, and a detailed, drillable analytics dashboard for the clinician.

The project brief requires an Android app combined with sensors/actuators and basic data analytics — this design satisfies all three: the ESP32 sensor unit, the Android BLE/processing app, and the analytics/dashboard layer.

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
                  (single unit, worn on
                   one arm at a time)
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
                     timing, coordination)
                             ↓
                    ┌──────────────────┐
                    │  Patient Metrics  │
                    │  (Firestore)      │
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

### Testing protocol — single device, two recordings per session

1. Device worn on **one arm** (order alternated/randomized session-to-session — see §10 protocol note).
2. Patient performs the prescribed exercise → app records and processes that arm's data as one "recording."
3. Short rest, device moved to the **other arm**.
4. Patient repeats the same exercise → second recording.
5. App pairs the two recordings (same session, same exercise, opposite limbs) and computes asymmetry metrics between them.

### Where each stage lives

| Stage | Location | Why |
|---|---|---|
| Sampling + basic filtering | Firmware (ESP32) | Reduce BLE payload, keep noise off the wire |
| Rep segmentation, real-time feedback | Android app (Kotlin) | Needed live for the rep counter and in-session feedback |
| Full feature extraction (smoothness, fatigue) | Android app at end of each recording, or a Cloud Function | Heavier computation (FFT for fatigue, SPARC for smoothness); fine to run once per recording rather than continuously |
| Asymmetry calculation | Android app or Cloud Function, once both recordings in a session exist | Pairs the two same-session recordings and applies the asymmetry formula (§6) |
| Storage / cross-session aggregation | Firestore + Cloud Storage | Structured metrics in Firestore, raw signal traces in Cloud Storage (too large/unstructured for Firestore docs) |
| Dashboards | Android app, role-gated (patient vs clinician view) | One codebase, satisfies the "Android app" project requirement directly |

---

## 2. Hardware

### Sensors / components (single unit)

- **MyoWare 2.0 EMG** ×2 — flexor + extensor channel.
- **IMU**: BNO055 (9-DOF, onboard sensor fusion).
- **ESP32** (dual-core).
- **TP4056** — LiPo charge management.
- **MAX17048** (or similar) — battery fuel gauge.

### Single-device tradeoff

Halves the BOM and firmware/enclosure work versus the earlier two-unit design. The cost: the two arms are never recorded *simultaneously*, so asymmetry is computed from two separate same-visit recordings rather than one synchronized stream. For this project's exercises (short, controlled reps like arm raises), that's a reasonable tradeoff — see the protocol note in §10 for how to keep the comparison fair.

### Power / mechanical

- LiPo sized against duty cycle — EMG at 500–1000 Hz + IMU + active BLE is the main draw.
- Physical on/off switch + low-battery cutoff.
- Elastic strap + gel electrodes designed for **quick, repeatable placement on either arm**, since the whole protocol depends on the strap going on consistently each time it's moved. Motion artifact from a loose or inconsistently placed strap is the most common EMG failure mode — worth extra design attention given it's now moved mid-session.

---

## 3. Embedded Software

### Framework

Arduino for MVP → migrate sampling-critical tasks to FreeRTOS tasks/queues as timing needs tighten.

### Core responsibilities

1. **Sensor sampling task** — EMG at 500–1000 Hz, IMU at 50–100 Hz, fixed-rate timer.
2. **On-device signal conditioning** — rectify + smooth (RMS envelope) each EMG channel; raw IMU passed through onboard BNO055 fusion.
3. **BLE GATT service** — characteristics for EMG channel(s), IMU orientation (quaternion), battery, calibration command, device status.
4. **Calibration routine** — capture rest baseline and max-contraction (MVC) baseline per channel on command; run **each time the device is placed on a new arm**, since electrode position and muscle baseline differ per limb. Store in flash and send to the app for cloud storage per user, per limb, per recording.
5. **Timestamping** — attach a monotonic device timestamp to each sample (useful for rep segmentation and signal alignment even within a single recording).
6. **Scheduler** — sampling, processing, and BLE notification as separate tasks/queues so BLE traffic doesn't stall sampling.
7. **Power management** — sleep/low-power BLE intervals when idle.

---

## 4. Android App (Kotlin, Jetpack Compose)

Fully native — a hard requirement of the project brief, not just an implementation preference.

### Recommended libraries

| Need | Library | Why |
|---|---|---|
| BLE | **Kable** | Coroutines/Flow-native BLE library — connection management is simpler here now with a single device, but Kable is still the cleaner choice over raw `BluetoothGatt` |
| Charts | **Vico** | Compose-native charting for the trend/bar charts in the dashboards (§7) |
| Backend client | **Firebase Android SDK** (Auth, Firestore, Cloud Storage KTX modules) | Official Kotlin-friendly client for all three services under one project |

### Architecture

```text
Android App (Kotlin, Jetpack Compose)
│
├── Screens (Compose)
│   ├── Auth
│   ├── Device connection (single unit)
│   ├── Exercise selection
│   ├── Limb selection + "switch arm" prompt mid-session
│   ├── Live session view (rep counter, live signal feedback)
│   ├── Patient progress view
│   ├── Clinician dashboard (Patient → Exercise → Session → Metrics → Raw signals)
│   └── Settings
│
├── BLE Layer (Kotlin, via Kable)
│   └── Single GATT connection, exposed as a Kotlin Flow, tagged with
│       whichever limb the app currently has selected
│
├── Signal Processing Layer (Kotlin)
│   ├── Real-time: light filtering + rep counting for live feedback
│   └── End-of-recording: full feature extraction (§6), batched
│
└── Firebase Layer (Kotlin)
    └── Auth, Firestore (metrics), Cloud Storage (raw traces), sync
```

### Live session flow

1. Clinician/patient selects exercise (e.g. "Arm raise ×10") and which limb goes first (app should prompt/track this — see §10 protocol note on alternating order).
2. App connects to the device, confirms signal quality, runs calibration for the current limb.
3. Guided countdown → patient performs reps → app segments reps in real time, shows live rep counter and signal feedback.
4. On completion: app runs full feature extraction, saves the recording (tagged with limb + session ID).
5. App prompts to move the device to the other arm, re-calibrates, and repeats steps 2–4 for the second limb.
6. Once both recordings exist for the session, app computes and stores the asymmetry metrics between them.

---

## 5. Signal Processing

### IMU pipeline

1. Orientation already fused on-device (BNO055) → quaternion stream.
2. Convert to joint angle of interest (e.g. shoulder flexion angle) via a fixed reference frame calibrated at the start of each recording (patient holds neutral "rest" pose on that arm).
3. Low-pass filter (e.g. 4th-order Butterworth, ~5–10 Hz cutoff) to remove sensor noise before feature extraction.
4. Differentiate filtered angle → angular velocity → angular acceleration, for speed/accel features.

### EMG pipeline

1. On-device: rectify + RMS envelope (already done in firmware).
2. On phone: normalize envelope against that recording's calibration MVC → % MVC activation.
3. Onset detection: threshold crossing at baseline mean + 3×SD, sustained for a minimum duration.

### Rep segmentation

Peak detection on the filtered joint-angle signal: local maxima with a minimum prominence and minimum inter-peak interval (tuned per exercise type) mark rep boundaries. EMG onset can refine the start-of-rep timestamp.

---

## 6. Feature Extraction — What Gets Analyzed

### Per recording (i.e. per arm, per session)

| Metric | Definition |
|---|---|
| Range of motion (ROM) | max(angle) − min(angle) within a rep window, in degrees |
| Movement speed | peak (or mean) angular/linear velocity during a rep |
| Acceleration | peak or RMS angular acceleration during a rep |
| Movement smoothness | Spectral Arc Length (SPARC) on the velocity profile |
| Repetition count | output of rep segmentation, vs. prescribed count (e.g. 8/10) |
| Movement consistency (variability) | coefficient of variation, CV = SD/mean × 100%, across reps in the recording |
| Time to complete | total duration for the prescribed rep count |
| Muscle activation level | RMS amplitude during the activation window, normalized to %MVC from that recording's calibration |
| Activation timing | onset latency: time from movement onset (IMU) to EMG onset (threshold crossing) |
| Muscle fatigue | median frequency (MDF) of the EMG power spectrum (FFT per rep); a declining MDF trend across the recording is the classic EMG fatigue indicator |
| Muscle coordination | co-contraction index between flexor and extensor channels — overlap of simultaneous activation, normalized |

### Asymmetry — computed from paired recordings

Since the two limbs are no longer recorded simultaneously, asymmetry is computed by **pairing the affected and unaffected recordings from the same session** and comparing each metric:

```
A = |X_affected − X_unaffected| / ((X_affected + X_unaffected) / 2)
```

Tracked per session, per metric, using the two paired recordings from that visit. A decreasing trend over the rehab program is the key clinical signal.

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
- **Detailed analysis** — "Why did this metric change?" Drill into a specific session/recording and the raw signal behind a metric.

### Drill-down hierarchy

```
Patient → Exercise → Session → Metrics → Raw signals
```

- **Patient**: list of assigned patients, each with a progress summary.
- **Exercise**: which prescribed exercises this patient is doing (arm raise, etc.).
- **Session**: chronological list of sessions for that exercise; each session contains two recordings (affected + unaffected) plus the computed asymmetry.
- **Metrics**: full metric table/trend charts for the selected session, per limb and the asymmetry values (as in §6 table).
- **Raw signals**: EMG/IMU waveform viewer, selectable per recording (affected or unaffected) — for when a clinician needs to see *why* a metric looks off (e.g. a noisy rep, a strap slip, or which arm was tested first that day).

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

Implementation: bar/line charts per metric using Vico (Compose-native), driven by Firestore queries.

### Patient view

Simpler and motivational — streak count, personal bests, a couple of headline trend charts (ROM, symmetry) without the full clinical metric set.

---

## 8. Backend — Firebase

### Firestore collections

**Users**
```json
{ "userId": "123", "role": "patient", "name": "John Doe", "clinicianId": "c1" }
```

**Calibrations** *(per user, per limb, per recording)*
```json
{
  "userId": "123",
  "limb": "affected",
  "channel": "flexor",
  "baselineRest": 120,
  "baselineMVC": 3400,
  "recordedAt": "timestamp"
}
```

**Exercises**
```json
{ "exerciseId": "ex_armraise", "name": "Arm Raise", "prescribedReps": 10 }
```

**Sessions** *(one per visit; groups two recordings)*
```json
{
  "sessionId": "abc123",
  "userId": "123",
  "exerciseId": "ex_armraise",
  "date": "timestamp",
  "firstLimbTested": "unaffected"
}
```

**Recordings** *(one per arm per session)*
```json
{
  "recordingId": "rec_1",
  "sessionId": "abc123",
  "limb": "unaffected",
  "startTime": "timestamp",
  "endTime": "timestamp",
  "repsCompleted": 10,
  "rawTraceRef": "gs://.../rec_1.bin",
  "metrics": {
    "rom": 88, "speed": 0.66, "smoothness": 0.78,
    "emgActivation": 0.41, "variability": 0.07, "fatigueMdf": 124
  }
}
```

**Session Asymmetry** *(computed once both recordings exist)*
```json
{
  "sessionId": "abc123",
  "rom": 0.083,
  "speed": 0.079,
  "emgActivation": 0.05
}
```

**Clinicians**
```json
{ "clinicianId": "c1", "name": "Dr. Smith", "patientIds": ["123", "124"] }
```

### Querying trend data

A chart like "ROM trend for patient X, arm-raise exercise, across sessions" (§7) queries `Sessions` filtered by `userId` + `exerciseId` ordered by `date`, then reads the matching `Recordings` (filtered by `sessionId` + `limb`) and `Session Asymmetry` docs — chained client-side queries in Kotlin. Fine at this project's scale.

### Raw signal storage

Raw EMG/IMU traces are high-volume time series — stored as a binary/CSV blob per recording in Cloud Storage, referenced by `rawTraceRef`. The "raw signals" drill-down fetches and plots this on demand.

---

## 9. Security & Privacy

- Firebase Authentication; role-based access (patient can only see their own data; clinician can only see their assigned patients).
- Firestore security rules enforcing that isolation server-side.
- This is health-adjacent data tied to an identified patient — decide early whether to treat it under formal health-data handling (encryption at rest, retention policy, audit logging) even at MVP scale.

---

## 10. Testing Plan

### Protocol note — controlling for order effects

Since the same device tests both arms sequentially rather than simultaneously, **alternate or randomize which arm is tested first** across sessions, and log `firstLimbTested` (already in the schema above) so the order is always known. This prevents a systematic bias where, say, the second-tested arm always looks slightly better or worse due to warm-up or fatigue rather than actual rehab progress. Keep the rest period between the two recordings consistent session-to-session for the same reason.

### Test coverage

- **Unit**: rep segmentation accuracy, feature calculations (ROM, SPARC, MDF, asymmetry formula) against synthetic/known signals.
- **Integration**: BLE connection stability across the "switch arm mid-session" flow (disconnect/reconnect or re-scan handling), Firestore/Cloud Storage sync, calibration persistence per recording.
- **Validation**: compare app-computed ROM/speed against a reference (e.g. manual goniometer reading, video motion tracking) to establish measurement accuracy before trusting the clinical metrics.
- **UI**: drill-down navigation (Patient → Exercise → Session → Metrics → Raw signals), dashboard rendering correctness, the arm-switch prompt flow.
- **Performance**: BLE latency, session-end processing time, battery consumption.

---

## 11. Development Roadmap

1. **Project setup** — Android Studio project (Kotlin/Compose), Firebase project, GitHub repo, ESP32 firmware skeleton (single unit)
2. **Firmware core** — sampling, on-device conditioning, BLE GATT service, timestamping
3. **BLE connectivity (mobile)** — Kable-based connect/reconnect flow, including the mid-session arm-switch handoff
4. **Calibration system** — per-limb, per-recording MVC/rest calibration flow, Firestore integration
5. **Signal processing + rep segmentation** — real-time filtering, live rep counter and signal feedback
6. **Feature extraction engine** — ROM, speed, smoothness (SPARC), consistency, EMG activation, timing, fatigue (MDF), coordination
7. **Asymmetry computation** — pairing logic across a session's two recordings, asymmetry formula
8. **Data platform** — session/recording schema in Firestore, security rules, raw trace upload to Cloud Storage
9. **Patient view** — simplified progress dashboard (Vico charts)
10. **Clinician dashboard** — full drill-down (Patient → Exercise → Session → Metrics → Raw signals), trend charts
11. **Validation & testing** — accuracy validation against reference measurement, order-effect check, full test plan from §10

---

## 12. Open Questions / Next Steps

1. **Validation reference** — what ground truth will you validate ROM/speed against (goniometer, video, motion capture)?
2. **Regulatory posture** — consumer wellness/research tool vs. something aiming toward clinical validity?
3. **Exercise library scope** — is "arm raise" the only exercise for MVP, or should the pipeline generalize to multiple exercise types from the start?
4. **Strap/enclosure quick-swap design** — confirm the physical strap can be moved arm-to-arm quickly and consistently without a full re-fitting each time, since this now happens every session.
5. **Order randomization enforcement** — decide whether the app enforces/suggests alternating first-tested limb automatically, or leaves it to the clinician's judgment (with logging either way).

---

## Deliverables

- **Embedded**: ESP32 firmware, single unit (sampling, conditioning, BLE GATT, calibration, timestamping)
- **Android App**: Native app (Kotlin, Jetpack Compose, Kable for BLE, Vico for charts) — BLE, arm-switch flow, calibration, live session view, signal processing, feature extraction, asymmetry computation, patient view, clinician drill-down dashboard
- **Backend**: Firebase (Auth, Firestore schema in §8, Cloud Storage for raw traces, security rules)
- **Documentation**: this design doc, BLE characteristic spec, feature-extraction algorithm reference (formulas in §6), database schema, validation report
