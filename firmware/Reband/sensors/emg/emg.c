// EMG sampling: a periodic esp_timer wakes a high-priority task once per
// sample period; the task reads the ADC and groups samples into blocks that
// are handed to the BLE side through a queue.
//
// ADC1 only: ADC2 is unusable while the radio is on.

#include "emg.h"
#include "freertos/task.h"
#include "freertos/queue.h"
#include "esp_adc/adc_oneshot.h"
#include "esp_adc/adc_cali.h"
#include "esp_adc/adc_cali_scheme.h"
#include "esp_timer.h"
#include "esp_check.h"
#include "esp_log.h"

#define EMG_ADC_UNIT    ADC_UNIT_1
#define EMG_ADC_CHANNEL ADC_CHANNEL_6  // GPIO34 <- MyoWare ENV
#define EMG_ADC_ATTEN   ADC_ATTEN_DB_12 // widest input range, sensor swings 0-3.3 V
#define EMG_OVERSAMPLE  4               // averaged reads per sample, takes the edge off ADC noise
#define EMG_QUEUE_LEN   10              // 200 ms of slack at 50 blocks/s
#define EMG_TASK_PRIO   (configMAX_PRIORITIES - 5)
#define EMG_TASK_CORE   1               // keep sampling off the BLE core (0)

static const char *TAG = "emg";

static adc_oneshot_unit_handle_t s_adc;
static adc_cali_handle_t s_cali;
static esp_timer_handle_t s_timer;
static TaskHandle_t s_task;
static QueueHandle_t s_queue;

static volatile bool s_running;
static volatile uint32_t s_generation; // bumped on every start so the task drops stale partial blocks
static volatile int64_t s_start_us;
static volatile uint32_t s_dropped;
static volatile int16_t s_last_mv;

static void timer_cb(void *arg)
{
    xTaskNotifyGive(s_task);
}

static int16_t read_mv(void)
{
    int sum = 0, n = 0;
    for (int i = 0; i < EMG_OVERSAMPLE; i++) {
        int v;
        if (adc_oneshot_read(s_adc, EMG_ADC_CHANNEL, &v) == ESP_OK) {
            sum += v;
            n++;
        }
    }
    if (n == 0) {
        return s_last_mv;
    }
    int raw = sum / n;
    int mv = raw * 3300 / 4095;
    if (s_cali) {
        adc_cali_raw_to_voltage(s_cali, raw, &mv);
    }
    return (int16_t)mv;
}

static void sample_task(void *arg)
{
    emg_block_t block;
    size_t n = 0;
    uint16_t seq = 0;
    uint32_t generation = 0;

    for (;;) {
        ulTaskNotifyTake(pdTRUE, portMAX_DELAY);
        if (!s_running) {
            continue;
        }
        if (generation != s_generation) {
            generation = s_generation;
            n = 0;
            seq = 0;
        }

        if (n == 0) {
            block.t0_us = (uint32_t)(esp_timer_get_time() - s_start_us);
        }
        block.mv[n++] = read_mv();
        s_last_mv = block.mv[n - 1];

        if (n == EMG_SAMPLES_PER_BLOCK) {
            block.seq = seq++;
            if (xQueueSend(s_queue, &block, 0) != pdTRUE) {
                s_dropped++;
            }
            n = 0;
        }
    }
}

esp_err_t emg_init(void)
{
    adc_oneshot_unit_init_cfg_t unit_cfg = {.unit_id = EMG_ADC_UNIT};
    ESP_RETURN_ON_ERROR(adc_oneshot_new_unit(&unit_cfg, &s_adc), TAG, "ADC unit init failed");

    adc_oneshot_chan_cfg_t chan_cfg = {
        .atten = EMG_ADC_ATTEN,
        .bitwidth = ADC_BITWIDTH_12,
    };
    ESP_RETURN_ON_ERROR(adc_oneshot_config_channel(s_adc, EMG_ADC_CHANNEL, &chan_cfg), TAG, "ADC channel config failed");

    adc_cali_line_fitting_config_t cali_cfg = {
        .unit_id = EMG_ADC_UNIT,
        .atten = EMG_ADC_ATTEN,
        .bitwidth = ADC_BITWIDTH_12,
    };
    if (adc_cali_create_scheme_line_fitting(&cali_cfg, &s_cali) != ESP_OK) {
        s_cali = NULL;
    }

    s_queue = xQueueCreate(EMG_QUEUE_LEN, sizeof(emg_block_t));
    if (s_queue == NULL) {
        return ESP_ERR_NO_MEM;
    }
    if (xTaskCreatePinnedToCore(sample_task, "emg_sample", 3072, NULL, EMG_TASK_PRIO, &s_task, EMG_TASK_CORE) != pdPASS) {
        return ESP_ERR_NO_MEM;
    }

    const esp_timer_create_args_t timer_args = {
        .callback = timer_cb,
        .name = "emg_sample",
    };
    return esp_timer_create(&timer_args, &s_timer);
}

bool emg_is_calibrated(void)
{
    return s_cali != NULL;
}

esp_err_t emg_start(uint32_t sample_rate_hz)
{
    if (s_running) {
        return ESP_OK;
    }
    xQueueReset(s_queue);
    s_dropped = 0;
    s_start_us = esp_timer_get_time();
    s_generation++;
    s_running = true;
    return esp_timer_start_periodic(s_timer, 1000000 / sample_rate_hz);
}

void emg_stop(void)
{
    if (!s_running) {
        return;
    }
    s_running = false;
    esp_timer_stop(s_timer);
    xQueueReset(s_queue);
}

bool emg_is_running(void)
{
    return s_running;
}

bool emg_read_block(emg_block_t *out, TickType_t wait)
{
    return xQueueReceive(s_queue, out, wait) == pdTRUE;
}

uint32_t emg_take_dropped(void)
{
    uint32_t n = s_dropped;
    s_dropped -= n;
    return n;
}

int16_t emg_last_mv(void)
{
    return s_last_mv;
}
