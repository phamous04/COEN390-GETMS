# Design Document — Rehab Arm Tracker

## 0. Project Summary

A wearable EMG + IMU sensor unit streams muscle activation and limb motion data over BLE/WiFi to an Android app. The app renders a 3D "grasp and place" game in which the patient's real muscle signals and arm motion drive a virtual robotic arm, tasked with picking up objects and placing them into matching holes. The goal is guided, motivating, and measurable rehabilitation for patients with nerve damage or motor impairment, with data captured for the patient and their clinician to track recovery over time.

---

## 1. Hardware

### Sensors / components

- **MyoWare 2.0 EMG** — muscle activation. Confirm channel count needed: a single sensor gives one muscle group (e.g. forearm flexors); pinch/grasp realism likely wants 2 sensors (flexor + extensor) to detect both close and open intent, and to compute a co-contraction / release signal.
- **IMU** (e.g. MPU6050/BNO055) — wrist/forearm orientation and motion, used to control arm position/rotation in the 3D scene. A 9-DOF IMU (BNO055) with onboard sensor fusion saves significant CPU on the microcontroller vs raw 6-DOF fusion in firmware.
- **TP4056** — LiPo charge management. Add a battery fuel gauge (e.g. MAX17048) if the app should show battery %.

### Microcontroller

- **ESP32 (full)** vs **ESP32-C3 mini**: the C3 is single-core RISC-V — fine for BLE + basic sampling, but tighter on headroom if you add on-device filtering, dual EMG channels, and a scheduler. Standard ESP32 (dual-core Xtensa) gives you a dedicated core for sensor sampling/DSP and another for BLE/WiFi stack, which matters for keeping sampling jitter low. **Recommendation: prototype on ESP32, evaluate C3 later for size/battery savings once firmware is stable.**
- BLE is the better default link vs WiFi: lower power, lower latency for small payloads, no network/AP dependency for a wearable. Keep WiFi only if you want OTA updates or a future multi-device/server architecture.

### Misc / power

- LiPo battery — size against duty cycle (EMG @ ~500–1000 Hz + IMU + BLE radio active is the main draw).
- Consider a physical on/off switch and low-battery cutoff to protect the LiPo.
- Enclosure/strap: needs to hold EMG electrodes at consistent skin contact — motion artifact from a loose strap is a common EMG failure mode worth designing around early (e.g. elastic strap + gel electrodes).

---

## 2. Embedded Software

### Framework

- **Arduino**: faster to prototype, big library ecosystem (BLE, sensor libs), fine for MVP.
- **ESP-IDF + FreeRTOS**: better for precise timing/scheduling once you need reliable fixed-rate EMG sampling alongside BLE without jitter. **Recommendation: start on Arduino for MVP, port sampling-critical tasks to FreeRTOS tasks (Arduino-ESP32 actually runs on FreeRTOS under the hood, so you can adopt tasks/queues incrementally rather than doing a full ESP-IDF rewrite).**

### Core responsibilities

1. **Sensor sampling task** — fixed-rate timer (EMG typically 500–1000 Hz for usable envelope detection; IMU 50–100 Hz is usually enough for orientation).
2. **Signal processing** — rectify + smooth (moving average / RMS envelope) the raw EMG signal on-device rather than streaming raw noisy samples; this reduces BLE bandwidth and offloads filtering from the phone. Compute a normalized 0–1 "activation" value per channel using the calibration baseline (see below).
3. **Sensor fusion (if not handled by IMU chip)** — complementary/Kalman filter for orientation.
4. **BLE GATT service** — define characteristics for: EMG channel(s) (notify, e.g. 20–50 Hz post-processing rate — no need to send every raw sample), IMU orientation (quaternion or Euler, notify), battery level (read), calibration command (write), device status (read).
5. **Calibration routine** — capture rest baseline and max-contraction baseline per channel on command from the app, store in flash (or resend to app to store per-user — see Mobile section).
6. **Scheduler** — coordinate sampling, processing, and BLE notification intervals so BLE traffic doesn't stall sampling.
7. **Power management** — sleep/low-power BLE intervals when idle; wake on motion or app connect.

**Note:** the "will need something like FreeRTOS" instinct is right — the scheduler point above is exactly a multi-task FreeRTOS design (sampling task, processing task, BLE task, communicating via queues), not a single loop().

---

## 3. Mobile Software (Android)

### Engine choice

Given a real-time 3D game with physics-based object grasping, **Unity (C#) with the Android build target** is the strongest fit — mature physics engine, asset store, and BLE support via plugins (e.g. Android native BLE bridged into Unity, or a dedicated BLE asset). Native Android (Kotlin + OpenGL/Filament) is more control but far more work to hand-roll physics and grasp interactions.

### Connectivity

- **BLE GATT client** subscribing to the EMG/IMU notify characteristics.
- Low-latency handling: keep BLE connection interval short (e.g. 15–30 ms) for responsiveness; buffer incoming samples and interpolate/smooth on the render side so the arm doesn't feel jittery even if BLE packets arrive irregularly.
- Reconnection handling (patient repositioning, momentary signal loss) needs to be graceful — don't drop the game session on a brief disconnect.

### Game design

- **Control mapping**: IMU orientation → arm position/rotation; EMG activation level(s) → gripper open/close (and possibly grip _force_, which is clinically meaningful for grading rehab difficulty).
- **Levels**: object pickup-and-place tasks with increasing difficulty — smaller objects/holes, further reach, timed challenges, multi-step sequences (pick red block → place in red hole, etc.).
- **Object/hole variety**: different shapes and sizes to encourage varied grasp patterns (pinch vs full grasp), which maps to different rehab goals.
- **Feedback**: visual (highlight target, success animation), audio cue, and optionally phone haptics on successful grasp/placement — reinforcement matters a lot for rehab adherence.
- **Difficulty/assist scaling**: ability to adjust sensitivity/threshold per patient (someone with more limited activation shouldn't need "100%" to close the gripper) — this should read from the user's calibration profile, not be a fixed global threshold.
- **Session structure**: warm-up/calibration → timed or level-based session → cooldown/summary screen with results.

### Accounts & calibration

- Login (email or clinic-issued code) → profile.
- Calibration flow: guided "relax," then "squeeze as hard as you can" sequence per EMG channel, stored per user (not just per device) so profiles are portable across devices/sessions.
- Store baseline + threshold settings, dominant hand, session history reference.

---

## 4. Backend / Database

You'll want persistent storage beyond the device for accounts, calibration profiles, and session/analytics history — plan for a real backend rather than only on-device storage.

### Suggested stack

- **Option A (fastest to build):** Firebase (Auth + Firestore) — good fit for MVP, handles auth and real-time sync with minimal backend code.
- **Option B (more control/portable):** Supabase (Postgres + Auth) — relational schema is a natural fit for structured session/metric data and easier to run analytics queries against directly.

### Suggested schema (relational form)

- **users**(id, email, name, created_at, clinician_id nullable)
- **profiles**(id, user_id, dominant_hand, emg_channel_count, notes)
- **calibrations**(id, profile_id, channel, baseline_rest, baseline_max, threshold, recorded_at)
- **sessions**(id, user_id, started_at, ended_at, level_id, device_id)
- **session_events**(id, session_id, timestamp, event_type [grasp_start, grasp_success, grasp_fail, drop, etc.], object_id)
- **session_metrics**(id, session_id, metric_type [avg_activation, range_of_motion, success_rate, avg_reaction_time_ms, grip_hold_time], value)
- **levels**(id, name, difficulty, config_json)
- **clinicians**(id, name, email) — if you want clinician accounts that can view multiple patients

### Local-first consideration

Since this is a wearable/mobile game, consider caching a session locally (Unity local DB / SQLite) and syncing to the backend when connectivity allows, so a session isn't lost mid-play if network drops.

---

## 5. Data Analytics

This is a core value driver for a rehab product — the data is arguably as important as the game itself.

### Per-session metrics to capture

- Success rate (objects placed / attempted)
- Average and peak muscle activation per channel
- Range of motion (from IMU) achieved during session
- Reaction time (cue → grasp initiation)
- Grip hold duration / stability (activation variance while holding)
- Fatigue indicator (activation trend declining over session length)

### Longitudinal / trend analytics

- Progress charts per metric over days/weeks — the clinically useful view is _trend_, not single-session snapshots.
- Plateau or regression detection — flag when a patient's metrics stop improving or decline, useful for clinician follow-up.
- Adaptive difficulty suggestion — auto-recommend the next level/threshold based on recent performance.

### Views

- **Patient-facing dashboard**: simple, motivating (streaks, personal bests, progress-over-time chart).
- **Clinician-facing dashboard**: more clinical detail, multi-patient overview, exportable report (PDF) for medical records — this pairs well with the existing team focus on documents/reports if you want a "generate PT report" export feature.

### Privacy note

Since this stores health-adjacent data (rehab/muscle activity tied to an identified patient), it's worth deciding early whether you're treating this as regulated health data (HIPAA-type handling) even at MVP stage — affects backend choice, encryption at rest, and data retention policy.

---

## 6. Additional Feature Suggestions

- **Clinician web portal** — lets a PT assign specific levels/difficulty remotely and review patient progress without needing the phone.
- **Gamification** — achievement badges, streaks, difficulty milestones to support adherence (a known challenge in home rehab programs).
- **Remote alerts** — notify a clinician if a patient hasn't played in N days, or if performance regresses.
- **Multi-device profile portability** — since calibration is stored per-user in the backend, a patient could resume on a different phone/device.
- **Guided onboarding** — first-run tutorial teaching the grasp mechanic before Level 1.
- **OTA firmware updates over WiFi** — worth keeping WiFi capability on the ESP32 for this even if BLE is the primary game-time link.

---

## 7. Open Questions / Next Steps

1. Single-channel vs dual-channel EMG (flexor + extensor) — affects both hardware BOM and gripper-control realism.
2. Firebase vs Supabase — depends on team's comfort with SQL and how much reporting/analytics work you want to do directly on the data.
3. Unity BLE plugin selection and Android permission handling (BLE requires location permission on many Android versions — plan for the permission UX).
4. Regulatory posture — are you treating this as a consumer wellness app or edging toward a medical device / clinical tool? This affects data handling, EMG-based threshold tuning, and validation requirements.
