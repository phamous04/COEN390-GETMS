#pragma once

#include <stdbool.h>
#include <stdint.h>
#include "esp_err.h"
#include "freertos/FreeRTOS.h"

// One MyoWare 2.0 output on GPIO34 (ADC1_CH6). The board's slider picks which
// signal that pin carries; we use ENV (the board's rectified and smoothed envelope).

#define EMG_SAMPLES_PER_BLOCK 20

typedef struct {
    uint16_t seq;   // block counter since emg_start(); still increments when a block is dropped
    uint32_t t0_us; // time of mv[0], microseconds since emg_start()
    int16_t mv[EMG_SAMPLES_PER_BLOCK];
} emg_block_t;

// Configures the ADC and the sampling task. Does not sample until emg_start().
esp_err_t emg_init(void);

// True if the ADC has eFuse calibration (otherwise mV values are approximate).
bool emg_is_calibrated(void);

// Starts sampling at sample_rate_hz; resets seq and the time base.
esp_err_t emg_start(uint32_t sample_rate_hz);
void emg_stop(void);
bool emg_is_running(void);

// Waits up to `wait` for the next full block.
bool emg_read_block(emg_block_t *out, TickType_t wait);

// Blocks dropped because nobody read them in time, since the last call.
uint32_t emg_take_dropped(void);

// Most recent sample, for logging.
int16_t emg_last_mv(void);
