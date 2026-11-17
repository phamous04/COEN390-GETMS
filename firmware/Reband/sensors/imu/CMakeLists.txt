idf_component_register(SRCS "imu.c"
                       INCLUDE_DIRS "."
                       PRIV_REQUIRES esp_driver_i2c esp_timer reband_config)
