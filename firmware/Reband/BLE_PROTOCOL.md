# ReBand BLE protocol (firmware v0.2)

Source of truth: `ble/getms_ble_spec.h`. All multi-byte fields are **little-endian**.

| Characteristic | UUID | Props |
|---|---|---|
| Service | `6e400000-0000-4000-8000-00805f9b34fb` | |
| EMG Data | `6e400001-…` | Notify (only while RECORDING) |
| IMU Data | `6e400002-…` | reserved, not exposed |
| Status | `6e400003-…` | Read, Notify |
| Control | `6e400004-…` | Write |
| **Rep Event** (new) | `6e400005-…` | Notify |

Enable notifications on EMG, Status and Rep Event. Request MTU 247 before START (START is refused below 89).

## Control (write)

| Opcode | Bytes | Meaning | Allowed in |
|---|---|---|---|
| `0x01` CALIBRATE | `01 step` | step 1 = relax, 2 = curl, 3 = MVC | step 1: any state but RECORDING. Steps 2 and 3: CALIBRATING after the previous step finished |
| `0x02` START | `02` | start counting reps | READY, STOPPED, or ERROR if still calibrated |
| `0x03` STOP | `03` | end recording (→ STOPPED) or cancel calibration (→ IDLE) | RECORDING, CALIBRATING |
| `0x04` GET_STATE | `04` | device answers with a Status notification | any |
| `0x05` GET_REPS | `05` or `05 lo hi` | replay stored Rep Events from `from_index` (default 1) | any |

Anything else (unknown opcode, wrong length, not allowed now) moves the device to **ERROR** with an `error_code`.

### Calibration flow (the app shows the cues)
1. Show "Relax your arm, hanging straight down". When ready, send `01 01`. The device captures for 2.5 s (keep still).
2. Wait for Status with `cal_done = 1` (or ERROR). Show "Curl your arm fully and hold", then send `01 02` (2.5 s capture).
3. Wait for `cal_done = 2`. Show "Squeeze as hard as you can", then send `01 03` (3 s capture).
4. Status `state = READY` carries `rom_ref`, `mvc_mv` and `baseline_mv`.

During a capture, `cal_active` holds the step being captured. Calibration is the same for both arms: the app records which arm it is and recalibrates after the switch.

## Status (18 bytes)

| Off | Type | Field |
|---|---|---|
| 0 | u8 | state: 0 idle, 1 calibrating, 2 ready, 3 recording, 4 stopped, 5 error |
| 1 | u8 | error_flags: b0 ADC uncalibrated, b1 MTU too small, b2 IMU 1 (upper arm, 0x69) missing, b3 IMU 2 (forearm, 0x68) missing |
| 2 | u16 | fw_version (major << 8 \| minor) |
| 4 | u8 | error_code: 0 none, 1 invalid command, 2 invalid state, 3 IMU 1 missing, 4 IMU 2 missing, 5 IMU read failed, 6 cal: arm moved, 7 cal: ROM too small, 8 cal: MVC too low, 9 MTU too small |
| 5 | u8 | cal_active: step being captured, 0 = none |
| 6 | u8 | cal_done: last completed step (0–3) |
| 7 | u8 | target_reps (0 = unlimited) |
| 8 | u16 | rom_ref_x10 (degrees × 10) |
| 10 | u16 | mvc_mv |
| 12 | u16 | baseline_mv |
| 14 | u16 | rep_count since START |
| 16 | u16 | clean_count since START |

Status is notified on every state change, every calibration step, every rep, and in reply to GET_STATE.

## Rep Event (19 bytes, one per counted rep)

| Off | Type | Field |
|---|---|---|
| 0 | u8 | type_version = `0x11` |
| 1 | u16 | rep_index (1, 2, 3, … since START) |
| 3 | u32 | start_ms since START |
| 7 | u16 | duration_ms |
| 9 | u16 | rom_x10 (degrees × 10) |
| 11 | u16 | peak_activation_x10 (% MVC × 10) |
| 13 | u16 | mean_activation_x10 (% MVC × 10) |
| 15 | u8 | forearm twist, degrees (max 255) |
| 16 | u8 | upper-arm swing, degrees (max 255) |
| 17 | u8 | plane deviation, degrees (max 255) |
| 18 | u8 | flags: b0 clean, b1 low confidence, b2 missing samples, b3 saturation, b4 not clean: twist, b5 not clean: swing, b6 not clean: plane, b7 replayed by GET_REPS |

The device keeps the last 32 reps. After a reconnect, send `05` + (last rep_index you have + 1) to get the missed ones back. **Recording continues while BLE is disconnected.**

## EMG Data (unchanged, 86 bytes)
`seq u16 | t0_us u32 | 20 × (chA i16, chB i16)`. chA is the MyoWare ENV output in mV, chB is always 0. seq and t0_us restart at every START.
