#include <stdio.h>
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "esp_adc/adc_oneshot.h"
#include "esp_adc/adc_cali.h"
#include "esp_adc/adc_cali_scheme.h"
#include "esp_timer.h"
#include "esp_log.h"

// Bench test: print the three MyoWare 2.0 analog outputs on the serial monitor.
// These are the sensor's amplified outputs, not the electrode leads: the
// Cable Shield leads (MID/END/REF) carry millivolt skin signals that only the
// sensor's amplifier can read, so they never go to the ESP32.
//
// All inputs are on ADC1: ADC2 is unusable while the radio is on.
#define EMG_ADC_UNIT    ADC_UNIT_1
#define EMG_ADC_ATTEN   ADC_ATTEN_DB_12 // widest input range, sensor swings 0-3.3 V
#define OVERSAMPLE      16
#define PRINT_PERIOD_MS 100 // 10 Hz, slower serial output for easier monitoring

typedef struct {
    const char *name;
    adc_channel_t channel;
} emg_input_t;

static const emg_input_t inputs[] = {
    {"env", ADC_CHANNEL_6},  // GPIO34 <- ENV  (envelope)
    {"rect", ADC_CHANNEL_7}, // GPIO35 <- RECT (rectified)
    {"raw", ADC_CHANNEL_4},  // GPIO32 <- RAW  (centred on VIN/2)
};
#define NUM_INPUTS (sizeof(inputs) / sizeof(inputs[0]))

static const char *TAG = "emg_test";

void emg_test_run(void)
{
    adc_oneshot_unit_handle_t adc;
    adc_oneshot_unit_init_cfg_t unit_cfg = {.unit_id = EMG_ADC_UNIT};
    ESP_ERROR_CHECK(adc_oneshot_new_unit(&unit_cfg, &adc));

    adc_oneshot_chan_cfg_t chan_cfg = {
        .atten = EMG_ADC_ATTEN,
        .bitwidth = ADC_BITWIDTH_12,
    };
    for (size_t i = 0; i < NUM_INPUTS; i++) {
        ESP_ERROR_CHECK(adc_oneshot_config_channel(adc, inputs[i].channel, &chan_cfg));
    }

    adc_cali_handle_t cali = NULL;
    adc_cali_line_fitting_config_t cali_cfg = {
        .unit_id = EMG_ADC_UNIT,
        .atten = EMG_ADC_ATTEN,
        .bitwidth = ADC_BITWIDTH_12,
    };
    if (adc_cali_create_scheme_line_fitting(&cali_cfg, &cali) != ESP_OK) {
        ESP_LOGW(TAG, "ADC calibration unavailable, millivolts are approximate");
    }

    TickType_t last_wake = xTaskGetTickCount();
    while (1) {
        printf("time_ms: %lld", (long long)(esp_timer_get_time() / 1000));
        for (size_t i = 0; i < NUM_INPUTS; i++) {
            int sum = 0;
            for (int n = 0; n < OVERSAMPLE; n++) {
                int sample;
                ESP_ERROR_CHECK(adc_oneshot_read(adc, inputs[i].channel, &sample));
                sum += sample;
            }
            int raw = sum / OVERSAMPLE;
            int mv = raw * 3300 / 4095;
            if (cali) {
                adc_cali_raw_to_voltage(cali, raw, &mv);
            }
            printf(", %s: %d mV", inputs[i].name, mv);
        }
        printf("\n");
        vTaskDelayUntil(&last_wake, pdMS_TO_TICKS(PRINT_PERIOD_MS));
    }
}
