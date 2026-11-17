#include <string.h>
#include "reps.h"
#include "reband_config.h"

void reps_init(rep_detector_t *d, float rom_ref_deg)
{
    memset(d, 0, sizeof(*d));
    d->rom_ref = rom_ref_deg;
    d->low_deg = REP_LOW_FRAC * rom_ref_deg;
    d->high_deg = REP_HIGH_FRAC * rom_ref_deg;
    d->phase = REP_PH_EXTENDED;
    d->min_before = 1e9f;
}

static void begin(rep_detector_t *d, const rep_input_t *in)
{
    d->phase = REP_PH_FLEXING;
    d->start_ms = in->t_ms;
    d->peak = in->elbow_deg;
    d->act_sum = d->act_peak = d->act_flex_peak = 0.0f;
    d->act_n = 0;
    d->twist = d->twist_min = d->twist_max = 0.0f;
    d->plane = d->plane_min = d->plane_max = 0.0f;
    d->swing_max = 0.0f;
    d->g_upper_start = in->g_upper;
    d->max_gap_ms = 0;
    d->gap = d->saturated = false;
}

static void accumulate(rep_detector_t *d, const rep_input_t *in)
{
    d->peak = fmaxf(d->peak, in->elbow_deg);

    d->act_sum += in->act_pct;
    d->act_n++;
    d->act_peak = fmaxf(d->act_peak, in->act_pct);
    if (d->phase == REP_PH_FLEXING || d->phase == REP_PH_PEAK) {
        d->act_flex_peak = fmaxf(d->act_flex_peak, in->act_pct);
    }

    d->twist += in->twist_rate_dps * in->dt_s;
    d->twist_min = fminf(d->twist_min, d->twist);
    d->twist_max = fmaxf(d->twist_max, d->twist);
    d->plane += in->plane_rate_dps * in->dt_s;
    d->plane_min = fminf(d->plane_min, d->plane);
    d->plane_max = fmaxf(d->plane_max, d->plane);
    d->swing_max = fmaxf(d->swing_max, angle_between_deg(d->g_upper_start, in->g_upper));

    if (in->gap) {
        d->gap = true;
        if (in->gap_ms > d->max_gap_ms) {
            d->max_gap_ms = in->gap_ms;
        }
    }
    d->saturated |= in->saturated;
}

static rep_event_t finish(rep_detector_t *d, const rep_input_t *in, rep_result_t *out)
{
    uint32_t duration = in->t_ms - d->start_ms;
    float rom = d->peak - d->min_before;
    d->phase = REP_PH_EXTENDED;
    d->min_before = in->elbow_deg;

    if (rom < REP_MIN_ROM_FRAC * d->rom_ref) {
        d->reject_why = "ROM too small";
        return REP_REJECTED;
    }
    if (duration < REP_MIN_DURATION_MS) {
        d->reject_why = "too fast";
        return REP_REJECTED;
    }
    if (duration > REP_MAX_DURATION_MS) {
        d->reject_why = "too slow";
        return REP_REJECTED;
    }
    if (d->act_flex_peak < REP_MIN_ACTIVATION_PCT) {
        d->reject_why = "no EMG activation";
        return REP_REJECTED;
    }
    if (d->max_gap_ms > REP_MAX_GAP_MS) {
        d->reject_why = "samples missing";
        return REP_REJECTED;
    }

    out->start_ms = d->start_ms;
    out->duration_ms = duration;
    out->rom_deg = rom;
    out->peak_act = d->act_peak;
    out->mean_act = d->act_n ? d->act_sum / (float)d->act_n : 0.0f;
    out->twist_deg = d->twist_max - d->twist_min;
    out->plane_deg = d->plane_max - d->plane_min;
    out->swing_deg = d->swing_max;

    out->reasons = 0;
    if (out->twist_deg > FORM_MAX_TWIST_DEG) out->reasons |= REP_REASON_TWIST;
    if (out->swing_deg > FORM_MAX_SWING_DEG) out->reasons |= REP_REASON_SWING;
    if (out->plane_deg > FORM_MAX_PLANE_DEG) out->reasons |= REP_REASON_PLANE;
    out->clean = out->reasons == 0;
    out->missing = d->gap;
    out->low_conf = d->gap || rom < REP_LOWCONF_ROM_FRAC * d->rom_ref;
    out->saturated = d->saturated;
    return REP_COUNTED;
}

rep_event_t reps_update(rep_detector_t *d, const rep_input_t *in, rep_result_t *out)
{
    float a = in->elbow_deg;
    switch (d->phase) {
    case REP_PH_EXTENDED:
        d->min_before = fminf(d->min_before, a);
        if (a > d->low_deg + REP_HYST_DEG) {
            begin(d, in);
            accumulate(d, in);
        }
        return REP_NONE;

    case REP_PH_FLEXING:
        accumulate(d, in);
        if (a >= d->high_deg) {
            d->phase = REP_PH_PEAK;
        } else if (a < d->low_deg) { // partial curl, never reached the high threshold
            d->phase = REP_PH_EXTENDED;
            d->min_before = a;
        }
        return REP_NONE;

    case REP_PH_PEAK:
        accumulate(d, in);
        if (a < d->peak - REP_PEAK_DROP_DEG) {
            d->phase = REP_PH_EXTENDING;
        }
        return REP_NONE;

    case REP_PH_EXTENDING:
        accumulate(d, in);
        if (a >= d->peak) { // went back up past the peak
            d->phase = REP_PH_PEAK;
        } else if (a < d->low_deg) {
            return finish(d, in, out);
        }
        return REP_NONE;
    }
    return REP_NONE;
}
