idf_component_register(SRCS "emg.c"
                       INCLUDE_DIRS "."
                       REQUIRES freertos esp_common
                       PRIV_REQUIRES esp_adc esp_timer)
