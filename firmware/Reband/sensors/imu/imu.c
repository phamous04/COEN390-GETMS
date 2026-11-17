// MPU-6050 driver on the ESP-IDF i2c_master API. Both sensors share the bus,
// told apart by AD0 (0x68 / 0x69).

#include "imu.h"
#include "driver/i2c_master.h"
#include "esp_timer.h"
#include "reband_config.h"

#define REG_SMPLRT_DIV   0x19
#define REG_CONFIG       0x1A
#define REG_GYRO_CONFIG  0x1B
#define REG_ACCEL_CONFIG 0x1C
#define REG_ACCEL_XOUT_H 0x3B
#define REG_PWR_MGMT_1   0x6B
#define REG_WHO_AM_I     0x75

#define IMU_PROBE_TIMEOUT_MS 50 // probing is not in the sampling path, be generous

static i2c_master_bus_handle_t s_bus;
static i2c_master_dev_handle_t s_dev[IMU_COUNT];
static bool s_present[IMU_COUNT];
static esp_err_t s_last_err[IMU_COUNT] = {ESP_ERR_INVALID_STATE, ESP_ERR_INVALID_STATE};
static const uint8_t s_addr[IMU_COUNT] = {IMU_ADDR_UPPER, IMU_ADDR_FORE};
static float s_accel_lsb; // LSB per g
static float s_gyro_lsb;  // LSB per deg/s

static esp_err_t write_reg(i2c_master_dev_handle_t dev, uint8_t reg, uint8_t val)
{
    uint8_t buf[2] = {reg, val};
    return i2c_master_transmit(dev, buf, sizeof(buf), IMU_I2C_TIMEOUT_MS);
}

static esp_err_t read_regs(i2c_master_dev_handle_t dev, uint8_t reg, uint8_t *buf, size_t len)
{
    return i2c_master_transmit_receive(dev, &reg, 1, buf, len, IMU_I2C_TIMEOUT_MS);
}

static esp_err_t configure(imu_id_t id)
{
    if (s_dev[id] == NULL) {
        i2c_device_config_t cfg = {
            .dev_addr_length = I2C_ADDR_BIT_LEN_7,
            .device_address = s_addr[id],
            .scl_speed_hz = IMU_I2C_FREQ_HZ,
        };
        esp_err_t err = i2c_master_bus_add_device(s_bus, &cfg, &s_dev[id]);
        if (err != ESP_OK) {
            return err;
        }
    }
    i2c_master_dev_handle_t d = s_dev[id];
    uint8_t who; // 0x68 on genuine parts, clones differ: only check that it answers
    esp_err_t err = read_regs(d, REG_WHO_AM_I, &who, 1);
    if (err == ESP_OK) err = write_reg(d, REG_PWR_MGMT_1, 0x00); // wake from sleep
    if (err == ESP_OK) err = write_reg(d, REG_SMPLRT_DIV, 1000 / IMU_SAMPLE_RATE_HZ - 1); // 1 kHz base with DLPF on
    if (err == ESP_OK) err = write_reg(d, REG_CONFIG, IMU_DLPF_CFG);
    if (err == ESP_OK) err = write_reg(d, REG_GYRO_CONFIG, IMU_GYRO_FS_SEL << 3);
    if (err == ESP_OK) err = write_reg(d, REG_ACCEL_CONFIG, IMU_ACCEL_FS_SEL << 3);
    return err;
}

esp_err_t imu_init(void)
{
    if (s_bus == NULL) {
        i2c_master_bus_config_t bus_cfg = {
            .i2c_port = IMU_I2C_PORT,
            .sda_io_num = IMU_I2C_SDA_GPIO,
            .scl_io_num = IMU_I2C_SCL_GPIO,
            .clk_source = I2C_CLK_SRC_DEFAULT,
            .glitch_ignore_cnt = 7,
            .flags.enable_internal_pullup = true, // breakouts have their own, this is a fallback
        };
        esp_err_t err = i2c_new_master_bus(&bus_cfg, &s_bus);
        if (err != ESP_OK) {
            s_bus = NULL;
            for (int i = 0; i < IMU_COUNT; i++) {
                s_present[i] = false;
                s_last_err[i] = err;
            }
            return ESP_ERR_NOT_FOUND;
        }
        s_accel_lsb = 16384.0f / (float)(1 << IMU_ACCEL_FS_SEL);
        s_gyro_lsb = 131.0f / (float)(1 << IMU_GYRO_FS_SEL);
    }

    esp_err_t result = ESP_OK;
    for (int i = 0; i < IMU_COUNT; i++) {
        esp_err_t err = i2c_master_probe(s_bus, s_addr[i], IMU_PROBE_TIMEOUT_MS);
        if (err == ESP_OK) {
            err = configure((imu_id_t)i);
        }
        s_last_err[i] = err;
        s_present[i] = err == ESP_OK;
        if (!s_present[i]) {
            result = ESP_ERR_NOT_FOUND;
        }
    }
    return result;
}

bool imu_present(imu_id_t id)
{
    return s_present[id];
}

esp_err_t imu_last_error(imu_id_t id)
{
    return s_last_err[id];
}

int imu_scan_bus(uint8_t *found, int max)
{
    if (s_bus == NULL) {
        return 0;
    }
    int n = 0;
    for (uint16_t addr = 0x08; addr < 0x78; addr++) {
        if (i2c_master_probe(s_bus, addr, IMU_PROBE_TIMEOUT_MS) == ESP_OK) {
            if (n < max) {
                found[n] = (uint8_t)addr;
            }
            n++;
        }
    }
    return n;
}

esp_err_t imu_read(imu_id_t id, imu_sample_t *out)
{
    if (!s_present[id]) {
        return ESP_ERR_INVALID_STATE;
    }
    uint8_t raw[14]; // accel xyz, temperature, gyro xyz; big-endian
    esp_err_t err = read_regs(s_dev[id], REG_ACCEL_XOUT_H, raw, sizeof(raw));
    out->t_us = esp_timer_get_time();
    if (err != ESP_OK) {
        return err;
    }
    bool sat = false;
    for (int i = 0; i < 3; i++) {
        int16_t a = (int16_t)((raw[2 * i] << 8) | raw[2 * i + 1]);
        int16_t g = (int16_t)((raw[8 + 2 * i] << 8) | raw[9 + 2 * i]);
        sat |= a == INT16_MAX || a == INT16_MIN || g == INT16_MAX || g == INT16_MIN;
        out->accel[i] = a / s_accel_lsb;
        out->gyro[i] = g / s_gyro_lsb;
    }
    out->saturated = sat;
    return ESP_OK;
}
