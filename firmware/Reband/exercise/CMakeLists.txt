idf_component_register(SRCS "motion.c" "reps.c" "session.c"
                       INCLUDE_DIRS "."
                       REQUIRES emg
                       PRIV_REQUIRES reband_config imu ble esp_timer)
