// GETMS / ReBand firmware entry point.
//
// Boot flow:
//   1. NVS      - required by the BLE controller (PHY calibration data).
//   2. EMG      - ADC on GPIO34 (MyoWare ENV), sampled at 1 kHz from boot: the
//                 envelope is needed for calibration as well as recording.
//   3. session  - both IMUs on I2C, 100 Hz sampling task, device state machine.
//   4. BLE      - GATT server up, advertising as GETMS-XXXX.
//   5. stream   - task that feeds EMG blocks to the session and, while
//                 recording, to BLE notifications.
// Everything after that is driven by Control commands (ble/getms_ble_spec.h).
//
// Bench test for the three MyoWare outputs: test/emg_test.c (see src/CMakeLists.txt).

#include <stdio.h>
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "esp_log.h"
#include "esp_timer.h"
#include "nvs_flash.h"
#include "reband_config.h"
#include "emg.h"
#include "ble.h"
#include "session.h"

#define FW_VERSION_MAJOR 0
#define FW_VERSION_MINOR 2
#define STATS_PERIOD_S   5

_Static_assert(EMG_SAMPLES_PER_BLOCK == GETMS_EMG_SAMPLES_PER_PACKET, "EMG block must fill one BLE packet");

// Forwards EMG blocks to the session (envelope) and to BLE while recording.
static void stream_task(void *arg)
{
    emg_block_t block;
    uint32_t sent = 0, lost = 0;
    int64_t next_stats = esp_timer_get_time() + STATS_PERIOD_S * 1000000LL;

    for (;;) {
        if (emg_read_block(&block, pdMS_TO_TICKS(500))) {
            session_feed_emg(&block);
            esp_err_t err = ble_send_emg(block.seq, block.t0_us, block.mv);
            if (err == ESP_OK) {
                sent++;
            } else if (err != ESP_ERR_INVALID_STATE) { // INVALID_STATE: not recording / not connected
                lost++;
            }
        }

        if (REBAND_DEBUG) {
            int64_t now = esp_timer_get_time();
            if (now >= next_stats) {
                lost += emg_take_dropped();
                printf("EMG last %ds: %lu pkt sent, %lu lost | env %d mV\n",
                       STATS_PERIOD_S, (unsigned long)sent, (unsigned long)lost, emg_last_mv());
                sent = lost = 0;
                next_stats = now + STATS_PERIOD_S * 1000000LL;
            }
        }
    }
}

static void setup_logging(void)
{
#if REBAND_DEBUG
    esp_log_level_set("emg", ESP_LOG_INFO);
    esp_log_level_set("ble", ESP_LOG_INFO);
#else
    // Serial shows only the status lines printed by the session; errors use "ERROR:".
    esp_log_level_set("*", ESP_LOG_ERROR);
    esp_log_level_set("i2c.master", ESP_LOG_NONE); // a missing IMU is reported once, not per read
#endif
}

void app_main(void)
{
    setup_logging();
    printf("Starting up ...\n");

    esp_err_t err = nvs_flash_init();
    if (err == ESP_ERR_NVS_NO_FREE_PAGES || err == ESP_ERR_NVS_NEW_VERSION_FOUND) {
        ESP_ERROR_CHECK(nvs_flash_erase());
        err = nvs_flash_init();
    }
    ESP_ERROR_CHECK(err);

    ESP_ERROR_CHECK(emg_init());
    ESP_ERROR_CHECK(emg_start(GETMS_EMG_SAMPLE_RATE_HZ));

    ESP_ERROR_CHECK(session_init((FW_VERSION_MAJOR << 8) | FW_VERSION_MINOR));

    const ble_callbacks_t callbacks = {
        .on_command = session_command,
        .on_connection = session_on_connection,
    };
    ESP_ERROR_CHECK(ble_init(&callbacks));

    xTaskCreate(stream_task, "stream", 4096, NULL, 5, NULL);
}
