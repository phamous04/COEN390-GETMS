// GETMS / Reband firmware entry point.
//
// Boot flow:
//   1. NVS      - required by the BLE controller (PHY calibration data).
//   2. EMG      - ADC on GPIO34 + sampling task, idle until START.
//   3. BLE      - GATT server up, advertising as GETMS-XXXX.
//   4. stream   - task that forwards EMG blocks to BLE notifications.
// After that everything is event driven: the app connects, enables EMG
// notifications, writes START, and the device streams until STOP or disconnect.
//
// Bench test for the three MyoWare outputs: test/emg_test.c (see src/CMakeLists.txt).

#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "esp_log.h"
#include "esp_timer.h"
#include "nvs_flash.h"
#include "emg.h"
#include "ble.h"

#define FW_VERSION_MAJOR 0
#define FW_VERSION_MINOR 1
#define STATS_PERIOD_S   5

_Static_assert(EMG_SAMPLES_PER_BLOCK == GETMS_EMG_SAMPLES_PER_PACKET, "EMG block must fill one BLE packet");

static const char *TAG = "main";

static void on_start(void)
{
    ESP_ERROR_CHECK(emg_start(GETMS_EMG_SAMPLE_RATE_HZ));
}

static void on_stop(void)
{
    emg_stop();
}

// Forwards EMG blocks to BLE and prints a one-line summary while streaming.
static void stream_task(void *arg)
{
    emg_block_t block;
    uint32_t sent = 0, lost = 0;
    int64_t next_stats = 0;

    for (;;) {
        if (emg_read_block(&block, pdMS_TO_TICKS(500))) {
            if (ble_send_emg(block.seq, block.t0_us, block.mv) == ESP_OK) {
                sent++;
            } else {
                lost++;
            }
        }

        int64_t now = esp_timer_get_time();
        if (!emg_is_running()) {
            sent = lost = 0;
            next_stats = now + STATS_PERIOD_S * 1000000LL;
            continue;
        }
        if (now >= next_stats) {
            lost += emg_take_dropped();
            ESP_LOGI(TAG, "last %ds: %lu pkt sent, %lu lost | env %d mV",
                     STATS_PERIOD_S, (unsigned long)sent, (unsigned long)lost, emg_last_mv());
            sent = lost = 0;
            next_stats = now + STATS_PERIOD_S * 1000000LL;
        }
    }
}

void app_main(void)
{
    // Everything else logs at WARN (sdkconfig), only our own tags log INFO.
    esp_log_level_set("main", ESP_LOG_INFO);
    esp_log_level_set("emg", ESP_LOG_INFO);
    esp_log_level_set("ble", ESP_LOG_INFO);

    ESP_LOGI(TAG, "GETMS firmware v%d.%d", FW_VERSION_MAJOR, FW_VERSION_MINOR);

    esp_err_t err = nvs_flash_init();
    if (err == ESP_ERR_NVS_NO_FREE_PAGES || err == ESP_ERR_NVS_NEW_VERSION_FOUND) {
        ESP_ERROR_CHECK(nvs_flash_erase());
        err = nvs_flash_init();
    }
    ESP_ERROR_CHECK(err);

    ESP_ERROR_CHECK(emg_init());
    ESP_LOGI(TAG, "EMG: GPIO34 (MyoWare ENV), %d Hz, %d samples/packet, ADC %s",
             GETMS_EMG_SAMPLE_RATE_HZ, GETMS_EMG_SAMPLES_PER_PACKET,
             emg_is_calibrated() ? "calibrated" : "NOT calibrated (mV approximate)");

    const ble_callbacks_t callbacks = {.on_start = on_start, .on_stop = on_stop};
    ESP_ERROR_CHECK(ble_init(&callbacks, (FW_VERSION_MAJOR << 8) | FW_VERSION_MINOR));
    if (!emg_is_calibrated()) {
        ble_set_error_flags(GETMS_ERR_ADC_UNCALIBRATED, 0);
    }

    xTaskCreate(stream_task, "stream", 4096, NULL, 5, NULL);
}
