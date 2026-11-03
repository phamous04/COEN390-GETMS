#pragma once

#include "esp_err.h"

// MPU-6050 placeholder, implemented next sprint (Final Design Doc §4):
// I2C @ 400 kHz, 100 Hz, accel ±4 g, gyro ±500 °/s, gyro bias at boot,
// Madgwick 6-DOF -> quaternion, streamed on the IMU Data characteristic.
// Not called from app_main yet.

esp_err_t imu_init(void);
