cmake_minimum_required(VERSION 3.16.0)

# Project components: config, BLE server, sensor drivers and the exercise logic.
# APPEND, not set: PlatformIO registers src/ through this same variable.
list(APPEND EXTRA_COMPONENT_DIRS
    ${CMAKE_CURRENT_LIST_DIR}/reband_config
    ${CMAKE_CURRENT_LIST_DIR}/ble
    ${CMAKE_CURRENT_LIST_DIR}/sensors/emg
    ${CMAKE_CURRENT_LIST_DIR}/sensors/imu
    ${CMAKE_CURRENT_LIST_DIR}/exercise)

include($ENV{IDF_PATH}/tools/cmake/project.cmake)
project(Reband)
