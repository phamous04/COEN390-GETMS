#pragma once

// BLE interface contract. The Android app must use the same values; the
// byte-level description for the app side is BLE_PROTOCOL.md (next to
// platformio.ini). Keep the two in sync. All multi-byte fields are little-endian.
//
//   GETMS Service  6e400000-0000-4000-8000-00805f9b34fb
//   EMG Data       6e400001-0000-4000-8000-00805f9b34fb  Notify (while RECORDING)
//   IMU Data       6e400002-0000-4000-8000-00805f9b34fb  reserved, not exposed
//   Status         6e400003-0000-4000-8000-00805f9b34fb  Read, Notify
//   Control        6e400004-0000-4000-8000-00805f9b34fb  Write
//   Rep Event      6e400005-0000-4000-8000-00805f9b34fb  Notify
//
// Device advertises as "GETMS-XXXX" (last 4 hex digits of the BLE MAC), with
// the service UUID in the advertising packet and the name in the scan response.

#define GETMS_UUID_SERVICE "6e400000-0000-4000-8000-00805f9b34fb"
#define GETMS_UUID_EMG     "6e400001-0000-4000-8000-00805f9b34fb"
#define GETMS_UUID_IMU     "6e400002-0000-4000-8000-00805f9b34fb"
#define GETMS_UUID_STATUS  "6e400003-0000-4000-8000-00805f9b34fb"
#define GETMS_UUID_CONTROL "6e400004-0000-4000-8000-00805f9b34fb"
#define GETMS_UUID_REP     "6e400005-0000-4000-8000-00805f9b34fb"

// ---- Control (write) ----------------------------------------------------------
// Byte 0 is the opcode, parameters follow. A command that is unknown, has the
// wrong length or is not allowed in the current state moves the device to
// GETMS_STATE_ERROR (Status notification with an error code).
#define GETMS_CMD_CALIBRATE 0x01 // + step u8 (GETMS_CAL_STEP_*), 2 bytes
#define GETMS_CMD_START     0x02 // 1 byte, needs a completed calibration
#define GETMS_CMD_STOP      0x03 // 1 byte, ends recording or cancels calibration
#define GETMS_CMD_GET_STATE 0x04 // 1 byte, answered with a Status notification
#define GETMS_CMD_GET_REPS  0x05 // 1 byte, or 3 with from_index u16: replays stored Rep Events
#define GETMS_CMD_MAX_LEN   3

// Calibration steps, sent in order. The app shows the cue first, then sends the
// step; the device captures immediately (CAL_HOLD_MS / CAL_MVC_MS).
#define GETMS_CAL_STEP_RELAX 1 // arm hanging straight down, relaxed and still
#define GETMS_CAL_STEP_CURL  2 // arm fully curled, still
#define GETMS_CAL_STEP_MVC   3 // squeeze the biceps as hard as possible

// ---- Status (read, notify) -----------------------------------------------------
//  0 state        u8   GETMS_STATE_*
//  1 error_flags  u8   GETMS_ERR_* (ongoing conditions)
//  2 fw_version   u16  major << 8 | minor
//  4 error_code   u8   GETMS_ERROR_*: why the device is in ERROR, 0 otherwise
//  5 cal_active   u8   calibration step being captured right now, 0 = none
//  6 cal_done     u8   last calibration step completed, 0..3
//  7 target_reps  u8   exercise ends by itself after this many reps, 0 = unlimited
//  8 rom_ref_x10  u16  calibrated range of motion, degrees x10 (set by step 2)
// 10 mvc_mv       u16  MVC envelope peak, mV (set by step 3)
// 12 baseline_mv  u16  resting EMG envelope, mV (set by step 1)
// 14 rep_count    u16  reps counted since START
// 16 clean_count  u16  of those, reps that passed the form check
#define GETMS_STATUS_LEN 18

#define GETMS_STATE_IDLE        0x00
#define GETMS_STATE_CALIBRATING 0x01
#define GETMS_STATE_READY       0x02 // calibrated, ready to measure and count reps
#define GETMS_STATE_RECORDING   0x03
#define GETMS_STATE_STOPPED     0x04 // after STOP or TARGET_REPS; START again for another set
#define GETMS_STATE_ERROR       0x05

#define GETMS_ERR_ADC_UNCALIBRATED (1u << 0) // ADC eFuse calibration missing, mV values approximate
#define GETMS_ERR_MTU_TOO_SMALL    (1u << 1) // last START refused because MTU was too small
#define GETMS_ERR_IMU1_MISSING     (1u << 2) // upper-arm IMU (0x69) not answering
#define GETMS_ERR_IMU2_MISSING     (1u << 3) // forearm IMU (0x68) not answering

#define GETMS_ERROR_NONE             0
#define GETMS_ERROR_INVALID_COMMAND  1 // unknown opcode, bad length or bad parameter
#define GETMS_ERROR_INVALID_STATE    2 // command not allowed in the current state
#define GETMS_ERROR_IMU1_MISSING     3
#define GETMS_ERROR_IMU2_MISSING     4
#define GETMS_ERROR_IMU_READ         5 // an IMU stopped answering; recalibrate
#define GETMS_ERROR_CAL_MOVED        6 // movement during a still hold
#define GETMS_ERROR_CAL_ROM_TOO_SMALL 7
#define GETMS_ERROR_CAL_MVC_TOO_LOW  8 // MVC not above the resting baseline
#define GETMS_ERROR_MTU_TOO_SMALL    9 // negotiate MTU >= GETMS_EMG_PACKET_LEN + 3 before START

// ---- Rep Event (notify), one per counted rep ------------------------------------------
//  0 type_version     u8   GETMS_REP_TYPE_VERSION
//  1 rep_index        u16  running count of counted reps since START, from 1
//  3 start_ms         u32  rep start, ms since START
//  7 duration_ms      u16
//  9 rom_x10          u16  range of motion, degrees x10
// 11 peak_act_x10     u16  peak EMG activation, % MVC x10
// 13 mean_act_x10     u16  mean EMG activation over the rep, % MVC x10
// 15 twist_deg        u8   forearm twist, degrees, saturated at 255
// 16 swing_deg        u8   upper-arm swing, degrees, saturated at 255
// 17 plane_deg        u8   rotation out of the hinge plane, degrees, saturated at 255
// 18 flags            u8   GETMS_REP_F_*
#define GETMS_REP_EVENT_LEN    19
#define GETMS_REP_TYPE_VERSION 0x11 // high nibble type 1 = rep event, low nibble version 1

#define GETMS_REP_F_CLEAN     (1u << 0) // passed the form check, counts toward clean_count
#define GETMS_REP_F_LOW_CONF  (1u << 1) // short ROM or samples missing
#define GETMS_REP_F_MISSING   (1u << 2) // at least one IMU sample interval was late
#define GETMS_REP_F_SATURATED (1u << 3) // EMG ADC or an IMU axis hit full scale
#define GETMS_REP_F_TWIST     (1u << 4) // not clean: forearm twist
#define GETMS_REP_F_SWING     (1u << 5) // not clean: upper-arm swing
#define GETMS_REP_F_PLANE     (1u << 6) // not clean: out of plane
#define GETMS_REP_F_REPLAY    (1u << 7) // sent again in answer to GET_REPS

// ---- EMG packet (notify, while RECORDING), 86 bytes -------------------------------------
//   seq u16 | t0_us u32 | 20 x (chA i16, chB i16)
// seq   increments per packet from 0 at START; a gap means lost packets.
// t0_us time of the packet's first sample, microseconds since START.
//       Sample i is at t0_us + i * (1e6 / GETMS_EMG_SAMPLE_RATE_HZ).
// chA   MyoWare 2.0 ENV output on GPIO34, in millivolts (0-3300).
// chB   second EMG channel, always 0 until that sensor is added.
#define GETMS_EMG_SAMPLE_RATE_HZ     1000
#define GETMS_EMG_SAMPLES_PER_PACKET 20
#define GETMS_EMG_CHANNELS           2
#define GETMS_EMG_PACKET_LEN         (2 + 4 + GETMS_EMG_SAMPLES_PER_PACKET * GETMS_EMG_CHANNELS * 2)
