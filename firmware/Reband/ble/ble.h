#pragma once

#include <stdint.h>
#include "esp_err.h"
#include "getms_ble_spec.h"

// Called from the NimBLE host task when the app writes START / STOP. on_stop
// also runs when the phone disconnects while streaming.
typedef struct {
    void (*on_start)(void);
    void (*on_stop)(void);
} ble_callbacks_t;

// Starts the BLE stack, registers the GETMS GATT service and begins
// advertising. Requires nvs_flash_init() first.
esp_err_t ble_init(const ble_callbacks_t *callbacks, uint16_t fw_version);

// Sends one EMG packet (GETMS_EMG_SAMPLES_PER_PACKET samples of channel A).
// Returns ESP_ERR_INVALID_STATE if not streaming or the app has not enabled
// notifications, ESP_ERR_NO_MEM / ESP_FAIL if the stack could not queue it.
esp_err_t ble_send_emg(uint16_t seq, uint32_t t0_us, const int16_t *ch_a);

// Sets and clears bits in the Status error flags (GETMS_ERR_*).
void ble_set_error_flags(uint8_t set, uint8_t clear);
