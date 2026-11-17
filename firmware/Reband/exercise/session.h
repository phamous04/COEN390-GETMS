#pragma once

#include <stdbool.h>
#include <stdint.h>
#include "esp_err.h"
#include "emg.h"

// ReBand session: device state machine, 100 Hz IMU sampling, calibration,
// rep detection and the rep ring buffer. Call after emg_init(), before ble_init().
esp_err_t session_init(uint16_t fw_version);

// BLE callbacks (NimBLE host task): commands are queued, never run here.
void session_command(const uint8_t *data, uint16_t len);
void session_on_connection(bool connected);

// Every EMG block (stream task): updates the envelope used for calibration and reps.
void session_feed_emg(const emg_block_t *block);
