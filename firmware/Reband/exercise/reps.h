#pragma once

// Bicep-curl rep detector: hysteresis state machine on the low-passed elbow
// angle, validity checks and the clean-rep (form) check. Pure logic, fed one
// sample at a time at IMU_SAMPLE_RATE_HZ.
//
//   EXTENDED --(angle > low + hyst)--> FLEXING --(angle >= high)--> PEAK
//   PEAK --(angle < peak - drop)--> EXTENDING --(angle < low)--> rep complete
//   FLEXING --(angle < low)--> EXTENDED (partial curl, ignored)

#include <stdbool.h>
#include <stdint.h>
#include "motion.h"

// Form-check failure reasons.
#define REP_REASON_TWIST (1u << 0)
#define REP_REASON_SWING (1u << 1)
#define REP_REASON_PLANE (1u << 2)

typedef struct {
    uint32_t t_ms;        // since START
    float dt_s;           // interval since the previous sample
    float elbow_deg;      // low-passed elbow angle
    vec3_t g_upper;       // upper-arm gravity direction (swing)
    float twist_rate_dps; // forearm rotation rate about its long axis
    float plane_rate_dps; // forearm rotation rate out of the hinge plane
    float act_pct;        // EMG activation, % MVC
    bool gap;             // interval before this sample was too long
    uint32_t gap_ms;
    bool saturated;       // EMG or IMU saturated on this sample
} rep_input_t;

typedef struct {
    uint32_t start_ms, duration_ms;
    float rom_deg;
    float peak_act, mean_act; // % MVC
    float twist_deg, swing_deg, plane_deg;
    bool clean, low_conf, missing, saturated;
    uint8_t reasons; // REP_REASON_* when not clean
} rep_result_t;

typedef enum { REP_PH_EXTENDED, REP_PH_FLEXING, REP_PH_PEAK, REP_PH_EXTENDING } rep_phase_t;

typedef enum { REP_NONE = 0, REP_COUNTED = 1, REP_REJECTED = -1 } rep_event_t;

typedef struct {
    float rom_ref, low_deg, high_deg;
    rep_phase_t phase;
    float min_before, peak;
    uint32_t start_ms;
    float act_sum, act_peak, act_flex_peak;
    uint32_t act_n;
    float twist, twist_min, twist_max;
    float plane, plane_min, plane_max;
    float swing_max;
    vec3_t g_upper_start;
    uint32_t max_gap_ms;
    bool gap, saturated;
    const char *reject_why; // set when REP_REJECTED is returned
} rep_detector_t;

void reps_init(rep_detector_t *d, float rom_ref_deg);
rep_event_t reps_update(rep_detector_t *d, const rep_input_t *in, rep_result_t *out);
