#pragma once

// BLE interface contract (Final Design Doc §5). The Android app must use the
// same values; keep the two in sync when anything here changes.
//
//   GETMS Service  6e400000-0000-4000-8000-00805f9b34fb
//   EMG Data       6e400001-0000-4000-8000-00805f9b34fb  Notify
//   IMU Data       6e400002-0000-4000-8000-00805f9b34fb  Notify (not exposed yet, IMU is next sprint)
//   Status         6e400003-0000-4000-8000-00805f9b34fb  Read, Notify
//   Control        6e400004-0000-4000-8000-00805f9b34fb  Write
//
// Device advertises as "GETMS-XXXX" (last 4 hex digits of the BLE MAC), with
// the service UUID in the advertising packet and the name in the scan response.

#define GETMS_UUID_SERVICE "6e400000-0000-4000-8000-00805f9b34fb"
#define GETMS_UUID_EMG     "6e400001-0000-4000-8000-00805f9b34fb"
#define GETMS_UUID_IMU     "6e400002-0000-4000-8000-00805f9b34fb"
#define GETMS_UUID_STATUS  "6e400003-0000-4000-8000-00805f9b34fb"
#define GETMS_UUID_CONTROL "6e400004-0000-4000-8000-00805f9b34fb"

// Control characteristic: one byte.
#define GETMS_CMD_START 0x01
#define GETMS_CMD_STOP  0x02

// ATT application errors returned when a Control write is rejected.
#define GETMS_ATT_ERR_MTU_TOO_SMALL 0x80 // negotiate MTU >= GETMS_EMG_PACKET_LEN + 3 first
#define GETMS_ATT_ERR_UNKNOWN_CMD   0x81

// Status characteristic, 4 bytes: state u8 | error flags u8 | fw version u16 (major << 8 | minor).
#define GETMS_STATE_IDLE      0x00
#define GETMS_STATE_STREAMING 0x01

#define GETMS_ERR_ADC_UNCALIBRATED (1u << 0) // ADC eFuse calibration missing, mV values approximate
#define GETMS_ERR_MTU_TOO_SMALL    (1u << 1) // last START refused because MTU was too small

// EMG packet, little-endian, 86 bytes:
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
