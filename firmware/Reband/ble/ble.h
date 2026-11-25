#pragma once

#include <stdbool.h>
#include <stdint.h>
#include "esp_err.h"
#include "getms_ble_spec.h"

// Both callbacks run on the NimBLE host task and must return quickly.
typedef struct {
    // Raw Control write (data[0] = opcode). len is the real write length; at
    // most GETMS_CMD_MAX_LEN bytes are copied into data.
    void (*on_command)(const uint8_t *data, uint16_t len);
    void (*on_connection)(bool connected);
} ble_callbacks_t;

// Starts the BLE stack, registers the GETMS GATT service and begins
// advertising. Requires nvs_flash_init() first.
esp_err_t ble_init(const ble_callbacks_t *callbacks);

// Stores the Status value (GETMS_STATUS_LEN bytes) for reads and notifies it.
void ble_set_status(const uint8_t *status);

// EMG packets are only sent while streaming is on (RECORDING).
void ble_set_streaming(bool on);

// ATT MTU of the current connection, 0 if not connected.
uint16_t ble_mtu(void);

// Sends one EMG packet (GETMS_EMG_SAMPLES_PER_PACKET samples of channel A).
// ESP_ERR_INVALID_STATE if not streaming, not connected or not subscribed,
// ESP_ERR_NO_MEM / ESP_FAIL if the stack could not queue it.
esp_err_t ble_send_emg(uint16_t seq, uint32_t t0_us, const int16_t *ch_a);

// Sends one Rep Event (GETMS_REP_EVENT_LEN bytes). Same return codes.
esp_err_t ble_send_rep(const uint8_t *event);
