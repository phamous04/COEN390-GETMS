# This file was automatically generated for projects
# without default 'CMakeLists.txt' file.

FILE(GLOB_RECURSE app_sources ${CMAKE_SOURCE_DIR}/src/*.*)

# Bench test (prints ENV/RECT/RAW over serial). To run it instead of the BLE
# firmware, uncomment the line below and call emg_test_run() from app_main
# in place of the normal boot flow (both use ADC1).
# list(APPEND app_sources ${CMAKE_SOURCE_DIR}/test/emg_test.c)

idf_component_register(SRCS ${app_sources}
                       PRIV_REQUIRES nvs_flash esp_timer emg ble esp_adc)
