#pragma once

#include <stdbool.h>
#include <stdint.h>
#include "esp_err.h"

// Two MPU-6050 on one I2C bus (pins, rate and ranges in reband_config.h).

typedef enum {
    IMU_UPPER = 0, // upper arm, 0x69, "IMU 1"
    IMU_FORE = 1,  // forearm,   0x68, "IMU 2"
    IMU_COUNT = 2,
} imu_id_t;

typedef struct {
    float accel[3]; // g
    float gyro[3];  // deg/s, raw (bias not removed)
    int64_t t_us;   // esp_timer time of the read
    bool saturated; // an axis sat at full scale
} imu_sample_t;

// Creates the bus on first call, then probes and configures both sensors.
// Safe to call again to re-probe. Returns ESP_ERR_NOT_FOUND if a sensor is
// missing (see imu_present) and never aborts.
esp_err_t imu_init(void);

bool imu_present(imu_id_t id);

// Why the last imu_init() did not find this sensor (ESP_OK if it was found).
esp_err_t imu_last_error(imu_id_t id);

// Probes every 7-bit address; writes up to max answering addresses to found.
// Returns how many answered (for diagnosing wiring).
int imu_scan_bus(uint8_t *found, int max);

// One 14-byte burst read from ACCEL_XOUT_H. Fails fast (IMU_I2C_TIMEOUT_MS).
esp_err_t imu_read(imu_id_t id, imu_sample_t *out);
