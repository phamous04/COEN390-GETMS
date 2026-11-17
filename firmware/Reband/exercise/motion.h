#pragma once

// Orientation math: 3-vectors, the per-IMU complementary tilt filter, the
// elbow-angle model and a biquad low-pass.

#include <math.h>
#include <stdbool.h>

#define DEG2RAD 0.017453293f
#define RAD2DEG 57.29578f

typedef struct {
    float x, y, z;
} vec3_t;

static inline vec3_t v3(float x, float y, float z) { return (vec3_t){x, y, z}; }
static inline vec3_t v3_from(const float *a) { return (vec3_t){a[0], a[1], a[2]}; }
static inline vec3_t v3_add(vec3_t a, vec3_t b) { return v3(a.x + b.x, a.y + b.y, a.z + b.z); }
static inline vec3_t v3_sub(vec3_t a, vec3_t b) { return v3(a.x - b.x, a.y - b.y, a.z - b.z); }
static inline vec3_t v3_scale(vec3_t a, float s) { return v3(a.x * s, a.y * s, a.z * s); }
static inline float v3_dot(vec3_t a, vec3_t b) { return a.x * b.x + a.y * b.y + a.z * b.z; }
static inline vec3_t v3_cross(vec3_t a, vec3_t b)
{
    return v3(a.y * b.z - a.z * b.y, a.z * b.x - a.x * b.z, a.x * b.y - a.y * b.x);
}
static inline float v3_norm(vec3_t a) { return sqrtf(v3_dot(a, a)); }
static inline vec3_t v3_normalize(vec3_t a)
{
    float n = v3_norm(a);
    return n > 1e-6f ? v3_scale(a, 1.0f / n) : v3(0, 0, 0);
}

// ---- Complementary tilt filter ----------------------------------------------------
// Tracks the gravity direction in the sensor frame: gyro-propagated, pulled
// towards the accelerometer with weight (1 - FILTER_ALPHA). No yaw, so no
// magnetometer is needed. All angles are derived from this vector.
typedef struct {
    vec3_t g;    // unit "up" direction (what the accelerometer reads at rest)
    vec3_t bias; // gyro bias, deg/s (set by calibration)
    bool valid;
} tilt_t;

void tilt_reset(tilt_t *t, vec3_t accel_g);
// Returns the bias-corrected gyro rate, deg/s.
vec3_t tilt_update(tilt_t *t, vec3_t accel_g, vec3_t gyro_dps, float dt_s);

// Signed angle (deg) from `from` to `to` about `axis` (unit), after projecting
// both onto the plane normal to the axis.
float angle_about_deg(vec3_t from, vec3_t to, vec3_t axis);
float angle_between_deg(vec3_t a, vec3_t b);

// ---- Elbow model (built during calibration) ----------------------------------------
// Relax pose = 0 deg. The forearm hinge axis is the axis that carries the
// forearm's gravity vector from the relax pose to the curl pose, so flexion is
// positive on either arm and strap rotation on the forearm does not matter.
typedef struct {
    vec3_t g0_upper, g0_fore;       // gravity in the relax pose (the zero pose)
    vec3_t hinge_upper, hinge_fore; // elbow hinge axis in each sensor frame
    vec3_t long_fore;               // forearm long axis (twist)
    vec3_t normal_fore;             // third forearm axis (rotation out of the hinge plane)
} arm_model_t;

// False if the two forearm poses are too close to define a hinge axis.
bool arm_model_setup(arm_model_t *m, vec3_t g0_upper, vec3_t g0_fore, vec3_t g1_fore);
// Elbow flexion (deg): forearm tilt minus upper-arm tilt about the hinge.
float arm_elbow_deg(const arm_model_t *m, vec3_t g_upper, vec3_t g_fore);

// ---- 2nd-order Butterworth low-pass ---------------------------------------------------
typedef struct {
    float b0, b1, b2, a1, a2;
    float z1, z2;
} biquad_t;

void biquad_lowpass(biquad_t *f, float cutoff_hz, float sample_hz);
void biquad_prime(biquad_t *f, float x); // start in steady state at x
float biquad_run(biquad_t *f, float x);
