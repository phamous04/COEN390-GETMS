#include "motion.h"
#include "reband_config.h"

void tilt_reset(tilt_t *t, vec3_t accel_g)
{
    t->g = v3_normalize(accel_g);
    t->valid = v3_norm(t->g) > 0.5f;
}

vec3_t tilt_update(tilt_t *t, vec3_t accel_g, vec3_t gyro_dps, float dt_s)
{
    vec3_t rate = v3_sub(gyro_dps, t->bias);
    if (!t->valid) {
        tilt_reset(t, accel_g);
        return rate;
    }
    // A direction fixed in the world turns the other way in the sensor frame: dg/dt = -w x g.
    vec3_t w = v3_scale(rate, DEG2RAD);
    vec3_t g = v3_normalize(v3_sub(t->g, v3_scale(v3_cross(w, t->g), dt_s)));
    float a = v3_norm(accel_g);
    if (fabsf(a - 1.0f) < FILTER_ACCEL_TRUST_G) {
        g = v3_add(v3_scale(g, FILTER_ALPHA), v3_scale(accel_g, (1.0f - FILTER_ALPHA) / a));
    }
    t->g = v3_normalize(g);
    return rate;
}

float angle_about_deg(vec3_t from, vec3_t to, vec3_t axis)
{
    vec3_t p0 = v3_sub(from, v3_scale(axis, v3_dot(from, axis)));
    vec3_t p1 = v3_sub(to, v3_scale(axis, v3_dot(to, axis)));
    return atan2f(v3_dot(axis, v3_cross(p0, p1)), v3_dot(p0, p1)) * RAD2DEG;
}

float angle_between_deg(vec3_t a, vec3_t b)
{
    float c = v3_dot(v3_normalize(a), v3_normalize(b));
    c = c > 1.0f ? 1.0f : (c < -1.0f ? -1.0f : c);
    return acosf(c) * RAD2DEG;
}

bool arm_model_setup(arm_model_t *m, vec3_t g0_upper, vec3_t g0_fore, vec3_t g1_fore)
{
    vec3_t h = v3_cross(g0_fore, g1_fore);
    if (v3_norm(h) < 0.05f) { // poses within ~3 deg of each other (or of opposite)
        return false;
    }
    m->g0_upper = g0_upper;
    m->g0_fore = g0_fore;
    m->hinge_fore = v3_normalize(h);

    // Upper arm: configured mounting axis, pointed the same way as the measured
    // forearm axis (both IMUs are mounted the same way round).
    vec3_t cfg = v3_normalize(v3(IMU_HINGE_AXIS_X, IMU_HINGE_AXIS_Y, IMU_HINGE_AXIS_Z));
    float side = v3_dot(m->hinge_fore, cfg) >= 0.0f ? 1.0f : -1.0f;
    m->hinge_upper = v3_scale(cfg, side * IMU_UPPER_HINGE_SIGN);

    // Relaxed forearm hangs straight down, so gravity lies along it (and is
    // already perpendicular to the hinge axis by construction).
    m->long_fore = v3_normalize(g0_fore);
    m->normal_fore = v3_cross(m->hinge_fore, m->long_fore);
    return true;
}

float arm_elbow_deg(const arm_model_t *m, vec3_t g_upper, vec3_t g_fore)
{
    return angle_about_deg(m->g0_fore, g_fore, m->hinge_fore) -
           angle_about_deg(m->g0_upper, g_upper, m->hinge_upper);
}

void biquad_lowpass(biquad_t *f, float cutoff_hz, float sample_hz)
{
    const float q = 0.70710678f;
    float w0 = 2.0f * (float)M_PI * cutoff_hz / sample_hz;
    float cw = cosf(w0);
    float alpha = sinf(w0) / (2.0f * q);
    float a0 = 1.0f + alpha;
    f->b0 = (1.0f - cw) / 2.0f / a0;
    f->b1 = (1.0f - cw) / a0;
    f->b2 = f->b0;
    f->a1 = -2.0f * cw / a0;
    f->a2 = (1.0f - alpha) / a0;
    f->z1 = f->z2 = 0.0f;
}

void biquad_prime(biquad_t *f, float x)
{
    f->z1 = x * (1.0f - f->b0);
    f->z2 = x * (f->b2 - f->a2);
}

float biquad_run(biquad_t *f, float x)
{
    float y = f->b0 * x + f->z1;
    f->z1 = f->b1 * x - f->a1 * y + f->z2;
    f->z2 = f->b2 * x - f->a2 * y;
    return y;
}
