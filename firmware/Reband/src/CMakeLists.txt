# This file was automatically generated for projects
# without default 'CMakeLists.txt' file.

FILE(GLOB_RECURSE app_sources ${CMAKE_SOURCE_DIR}/src/*.*)

# Bench test called from app_main; test/ is not built by default.
list(APPEND app_sources ${CMAKE_SOURCE_DIR}/test/emg_test.c)

idf_component_register(SRCS ${app_sources} PRIV_REQUIRES esp_adc esp_timer)
