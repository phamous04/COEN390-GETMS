// ReBand session.
//
// One task owns all device state. A periodic esp_timer wakes it at
// IMU_SAMPLE_RATE_HZ; each tick it applies queued Control commands, reads both
// IMUs (one 14-byte burst each), updates the tilt filters and then runs what
// the state needs: calibration capture or rep detection. Control writes are
// only queued on the NimBLE host task, so BLE never waits on I2C or on a
// calibration hold, and the sampling path never sleeps.
//
// States (GETMS_STATE_*):
//   IDLE --CAL 1--> CALIBRATING --CAL 2, CAL 3--> READY --START--> RECORDING
//   RECORDING --STOP / TARGET_REPS--> STOPPED --START--> RECORDING
//   any --invalid command, sensor fault, failed calibration step--> ERROR
//   ERROR --CAL 1--> CALIBRATING, or --START--> RECORDING if still calibrated

#include <math.h>
#include <stdarg.h>
#include <stdio.h>
#include <string.h>
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "freertos/queue.h"
#include "esp_timer.h"
#include "reband_config.h"
#include "session.h"
#include "imu.h"
#include "ble.h"
#include "motion.h"
#include "reps.h"

#define SAMPLE_PERIOD_US (1000000 / IMU_SAMPLE_RATE_HZ)
#define TASK_PRIO        (configMAX_PRIORITIES - 6) // just below the EMG sampler
#define TASK_CORE        1                          // off the BLE core
#define CMD_QUEUE_LEN    8
#define REPLAY_PER_TICK  2 // GET_REPS pacing: 200 events/s
#define ENV_WINDOW       (EMG_ENV_WINDOW_MS * GETMS_EMG_SAMPLE_RATE_HZ / 1000)

#define DBG(...) do { if (REBAND_DEBUG) printf(__VA_ARGS__); } while (0)

typedef struct {
    uint8_t data[GETMS_CMD_MAX_LEN];
    uint16_t len;
} command_t;

static const char *const s_imu_name[IMU_COUNT] = {"IMU 1", "IMU 2"};
static const char *const s_imu_place[IMU_COUNT] = {"upper arm", "forearm"};
static const uint8_t s_imu_addr[IMU_COUNT] = {IMU_ADDR_UPPER, IMU_ADDR_FORE};
static const char *const s_state_name[] = {"idle", "calibrating", "ready", "recording", "stopped", "error"};

static QueueHandle_t s_cmd_queue;
static TaskHandle_t s_task;
static esp_timer_handle_t s_timer;

// ---- Device state (session task only, s_state also read by the BLE callback) ----
static volatile uint8_t s_state = GETMS_STATE_IDLE;
static uint8_t s_error_code;
static uint8_t s_error_flags;
static uint16_t s_fw_version;

// ---- Sensors ----
static imu_sample_t s_imu[IMU_COUNT];
static tilt_t s_tilt[IMU_COUNT];
static uint8_t s_read_fails[IMU_COUNT];
static bool s_imu_fault; // reported; cleared by a successful re-probe (CALIBRATE step 1)
static int8_t s_imu_shown[IMU_COUNT] = {-1, -1}; // presence last printed, -1 = not yet
static int64_t s_last_us;
static volatile bool s_connected;

// EMG envelope: written by the stream task, read here (32-bit, atomic).
static volatile float s_env_mv;
static volatile uint32_t s_emg_sat_count;

// ---- Calibration ----
static uint8_t s_cal_active; // step being captured, 0 = none
static uint8_t s_cal_done;   // last completed step
static bool s_calibrated;    // all steps done and still valid
static vec3_t s_g0[IMU_COUNT];
static arm_model_t s_arm;
static float s_rom_ref, s_baseline_mv, s_mvc_mv;

static struct {
    uint32_t n, target;
    vec3_t gyro_sum[IMU_COUNT], gyro_sq[IMU_COUNT], acc_sum[IMU_COUNT];
    float acc_n_sum[IMU_COUNT], acc_n_sq[IMU_COUNT];
    float env_sum, env_peak;
} s_cap;

// ---- Recording ----
static rep_detector_t s_reps;
static biquad_t s_angle_lpf;
static bool s_lpf_primed;
static int64_t s_start_us;
static uint32_t s_last_sat;
static uint16_t s_rep_count, s_clean_count;

// Last REP_RING_SIZE rep events; rep_index i lives in slot (i - 1) % REP_RING_SIZE.
static uint8_t s_ring[REP_RING_SIZE][GETMS_REP_EVENT_LEN];
static uint16_t s_replay_next, s_replay_end;

// ---- Helpers --------------------------------------------------------------------

static void put_u16(uint8_t *p, uint16_t v)
{
    p[0] = v & 0xff;
    p[1] = v >> 8;
}

static void put_u32(uint8_t *p, uint32_t v)
{
    for (int i = 0; i < 4; i++) {
        p[i] = (v >> (8 * i)) & 0xff;
    }
}

static uint16_t sat_u16(float v)
{
    if (!(v > 0.0f)) return 0; // also NaN
    return v >= 65535.0f ? 65535 : (uint16_t)lroundf(v);
}

static uint8_t sat_u8(float v)
{
    if (!(v > 0.0f)) return 0;
    return v >= 255.0f ? 255 : (uint8_t)lroundf(v);
}

static float activation_pct(float env_mv)
{
    float span = s_mvc_mv - s_baseline_mv;
    if (span <= 0.0f) return 0.0f;
    float pct = (env_mv - s_baseline_mv) / span * 100.0f;
    return pct > 0.0f ? pct : 0.0f;
}

// ---- Status ---------------------------------------------------------------------

static void publish_status(void)
{
    uint8_t b[GETMS_STATUS_LEN];
    b[0] = s_state;
    b[1] = s_error_flags;
    put_u16(b + 2, s_fw_version);
    b[4] = s_error_code;
    b[5] = s_cal_active;
    b[6] = s_cal_done;
    b[7] = TARGET_REPS > 255 ? 255 : TARGET_REPS;
    put_u16(b + 8, sat_u16(s_rom_ref * 10.0f));
    put_u16(b + 10, sat_u16(s_mvc_mv));
    put_u16(b + 12, sat_u16(s_baseline_mv));
    put_u16(b + 14, s_rep_count);
    put_u16(b + 16, s_clean_count);
    ble_set_status(b);
}

static void enter_state(uint8_t state)
{
    s_state = state;
    if (state != GETMS_STATE_ERROR) {
        s_error_code = GETMS_ERROR_NONE;
    }
    publish_status();
}

static void set_error(uint8_t code)
{
    ble_set_streaming(false); // ends a recording, if any
    s_cal_active = 0;
    s_error_code = code;
    enter_state(GETMS_STATE_ERROR);
}

// Prints "ERROR: <reason>" and enters the ERROR state.
static void fail(uint8_t code, const char *fmt, ...)
{
    va_list ap;
    printf("ERROR: ");
    va_start(ap, fmt);
    vprintf(fmt, ap);
    va_end(ap);
    printf("\n");
    set_error(code);
}

static void clear_calibration(void)
{
    s_calibrated = false;
    s_cal_active = s_cal_done = 0;
    s_rom_ref = s_baseline_mv = s_mvc_mv = 0.0f;
}

// ---- Sensors --------------------------------------------------------------------

// Probes both IMUs. Prints "IMU n connected" / "ERROR: IMU n not found" when a
// sensor's presence changes (so retries stay quiet); enters ERROR if one is missing.
static bool probe_imus(void)
{
    imu_init();
    s_error_flags &= ~(GETMS_ERR_IMU1_MISSING | GETMS_ERR_IMU2_MISSING);
    uint8_t code = GETMS_ERROR_NONE;
    bool changed = false;
    for (int i = 0; i < IMU_COUNT; i++) {
        s_read_fails[i] = 0;
        memset(&s_tilt[i], 0, sizeof(s_tilt[i]));
        bool present = imu_present((imu_id_t)i);
        if (present != s_imu_shown[i]) {
            changed = true;
            s_imu_shown[i] = present;
            if (present) {
                printf("%s connected (%s, 0x%02X)\n", s_imu_name[i], s_imu_place[i], s_imu_addr[i]);
            } else {
                printf("ERROR: %s not found at 0x%02X (%s)\n", s_imu_name[i], s_imu_addr[i],
                       esp_err_to_name(imu_last_error((imu_id_t)i)));
            }
        }
        if (!present) {
            s_error_flags |= i == IMU_UPPER ? GETMS_ERR_IMU1_MISSING : GETMS_ERR_IMU2_MISSING;
            if (code == GETMS_ERROR_NONE) {
                code = i == IMU_UPPER ? GETMS_ERROR_IMU1_MISSING : GETMS_ERROR_IMU2_MISSING;
            }
        }
    }
    s_imu_fault = code != GETMS_ERROR_NONE;
    if (s_imu_fault && changed) {
        // One line showing what is on the bus, to tell wiring from addressing problems.
        uint8_t found[8];
        int n = imu_scan_bus(found, 8);
        printf("   I2C scan (SDA %d, SCL %d):", IMU_I2C_SDA_GPIO, IMU_I2C_SCL_GPIO);
        for (int k = 0; k < n && k < 8; k++) {
            printf(" 0x%02X", found[k]);
        }
        printf(n ? "\n" : " no devices\n");
    }
    if (s_imu_fault) {
        clear_calibration();
        set_error(code);
    }
    return !s_imu_fault;
}

static void on_read_fail(int i)
{
    if (s_read_fails[i] < UINT8_MAX) {
        s_read_fails[i]++;
    }
    if (s_read_fails[i] == IMU_MAX_READ_FAILS && !s_imu_fault) {
        s_imu_fault = true;
        s_imu_shown[i] = -1; // the next probe reports it either way
        clear_calibration();
        fail(GETMS_ERROR_IMU_READ, "%s read failed", s_imu_name[i]);
    }
}

// ---- Calibration ------------------------------------------------------------------

static void cal_cue(uint8_t step)
{
    if (step == GETMS_CAL_STEP_RELAX) {
        printf("Doing calibration ...\n");
        printf("   Put your arm in relax position for bicep curl\n");
    } else if (step == GETMS_CAL_STEP_CURL) {
        printf("   Put arm in curled position\n");
    } else {
        printf("   Squeeze as hard as you can\n");
    }
}

// Starts capturing a step. print_cue is false in standalone mode, which
// prints the cue earlier, before the time given to get into position.
static void begin_cal_step(uint8_t step, bool print_cue)
{
    if (step == GETMS_CAL_STEP_RELAX) {
        // New calibration every time the band goes on: re-probe, drop the old one.
        clear_calibration();
        if (!probe_imus()) {
            return;
        }
    }
    if (print_cue) {
        cal_cue(step);
    }
    memset(&s_cap, 0, sizeof(s_cap));
    uint32_t ms = step == GETMS_CAL_STEP_MVC ? CAL_MVC_MS : CAL_HOLD_MS;
    s_cap.target = ms * IMU_SAMPLE_RATE_HZ / 1000;
    s_cal_active = step;
    enter_state(GETMS_STATE_CALIBRATING);
}

static void cal_fail(uint8_t code, const char *why)
{
    clear_calibration();
    fail(code, "calibration failed, %s", why);
}

// True if both IMUs stayed within the given movement limits during the
// capture. On failure, prints which sensor moved and by how much (to tune the limits).
static bool held_still(float gyro_std_max, float accel_std_max)
{
    float inv = 1.0f / (float)s_cap.n;
    for (int i = 0; i < IMU_COUNT; i++) {
        vec3_t m = v3_scale(s_cap.gyro_sum[i], inv);
        vec3_t sq = v3_scale(s_cap.gyro_sq[i], inv);
        float gyro_std = sqrtf(fmaxf(fmaxf(sq.x - m.x * m.x, fmaxf(sq.y - m.y * m.y, sq.z - m.z * m.z)), 0.0f));
        float am = s_cap.acc_n_sum[i] * inv;
        float accel_std = sqrtf(fmaxf(s_cap.acc_n_sq[i] * inv - am * am, 0.0f));
        DBG("cal: %s gyro std %.2f dps, accel std %.3f g\n", s_imu_name[i], gyro_std, accel_std);
        if (gyro_std > gyro_std_max || accel_std > accel_std_max) {
            printf("   %s moved: gyro %.1f deg/s (max %.1f), accel %.3f g (max %.3f)\n",
                   s_imu_name[i], gyro_std, gyro_std_max, accel_std, accel_std_max);
            return false;
        }
    }
    return true;
}

static void finish_cal_step(void)
{
    uint8_t step = s_cal_active;
    float inv = 1.0f / (float)s_cap.n;
    s_cal_active = 0;

    switch (step) {
    case GETMS_CAL_STEP_RELAX:
        if (!held_still(CAL_STILL_GYRO_STD_DPS, CAL_STILL_ACCEL_STD_G)) {
            cal_fail(GETMS_ERROR_CAL_MOVED, "arm moved");
            return;
        }
        for (int i = 0; i < IMU_COUNT; i++) {
            // Gyro bias, zero pose (gravity direction) and a fresh filter start.
            s_tilt[i].bias = v3_scale(s_cap.gyro_sum[i], inv);
            s_g0[i] = v3_normalize(v3_scale(s_cap.acc_sum[i], inv));
            tilt_reset(&s_tilt[i], s_g0[i]);
        }
        s_baseline_mv = s_cap.env_sum * inv;
        DBG("cal: baseline %.0f mV\n", s_baseline_mv);
        break;

    case GETMS_CAL_STEP_CURL: {
        // Looser: a held curl shakes, and only the mean gravity direction is used here.
        if (!held_still(CAL_CURL_GYRO_STD_DPS, CAL_CURL_ACCEL_STD_G)) {
            cal_fail(GETMS_ERROR_CAL_MOVED, "arm moved");
            return;
        }
        vec3_t g1_upper = v3_normalize(v3_scale(s_cap.acc_sum[IMU_UPPER], inv));
        vec3_t g1_fore = v3_normalize(v3_scale(s_cap.acc_sum[IMU_FORE], inv));
        float rom = 0.0f;
        if (arm_model_setup(&s_arm, s_g0[IMU_UPPER], s_g0[IMU_FORE], g1_fore)) {
            rom = arm_elbow_deg(&s_arm, g1_upper, g1_fore);
        }
        DBG("cal: ROM_ref %.1f deg\n", rom);
        if (rom < CAL_MIN_ROM_DEG) {
            // Tilt of each board between the two holds: tells a short curl from a
            // swapped board or an upper arm that came forward with the forearm.
            printf("   elbow %.0f deg (min %.0f) | upper arm tilted %.0f deg, forearm %.0f deg\n",
                   rom, CAL_MIN_ROM_DEG, angle_between_deg(s_g0[IMU_UPPER], g1_upper),
                   angle_between_deg(s_g0[IMU_FORE], g1_fore));
            cal_fail(GETMS_ERROR_CAL_ROM_TOO_SMALL, "range of motion too small");
            return;
        }
        s_rom_ref = rom;
        break;
    }

    case GETMS_CAL_STEP_MVC:
        DBG("cal: MVC peak %.0f mV\n", s_cap.env_peak);
        if (s_cap.env_peak - s_baseline_mv < CAL_MIN_MVC_DELTA_MV) {
            cal_fail(GETMS_ERROR_CAL_MVC_TOO_LOW, "MVC not above baseline");
            return;
        }
        s_mvc_mv = s_cap.env_peak;
        s_cal_done = step;
        s_calibrated = true;
        printf("Calibration complete\n");
        enter_state(GETMS_STATE_READY); // carries ROM_ref, MVC and baseline
        return;
    }
    s_cal_done = step;
    publish_status(); // still CALIBRATING; cal_done tells the app to show the next cue
}

static void cal_capture(void)
{
    for (int i = 0; i < IMU_COUNT; i++) {
        vec3_t g = v3_from(s_imu[i].gyro);
        vec3_t a = v3_from(s_imu[i].accel);
        s_cap.gyro_sum[i] = v3_add(s_cap.gyro_sum[i], g);
        s_cap.gyro_sq[i] = v3_add(s_cap.gyro_sq[i], v3(g.x * g.x, g.y * g.y, g.z * g.z));
        s_cap.acc_sum[i] = v3_add(s_cap.acc_sum[i], a);
        float n = v3_norm(a);
        s_cap.acc_n_sum[i] += n;
        s_cap.acc_n_sq[i] += n * n;
    }
    float env = s_env_mv;
    s_cap.env_sum += env;
    s_cap.env_peak = fmaxf(s_cap.env_peak, env);
    if (++s_cap.n >= s_cap.target) {
        finish_cal_step();
    }
}

// ---- Recording --------------------------------------------------------------------

static void start_recording(void)
{
    s_rep_count = s_clean_count = 0;
    s_replay_next = 1;
    s_replay_end = 0;
    reps_init(&s_reps, s_rom_ref);
    biquad_lowpass(&s_angle_lpf, ANGLE_LPF_HZ, IMU_SAMPLE_RATE_HZ);
    s_lpf_primed = false;
    s_last_sat = s_emg_sat_count;

    // Restart the EMG time base so EMG packet seq / t0 count from START.
    emg_stop();
    emg_start(GETMS_EMG_SAMPLE_RATE_HZ);
    s_start_us = esp_timer_get_time();
    ble_set_streaming(true);

    printf("Starting exercise ...\n");
    enter_state(GETMS_STATE_RECORDING);
}

static void stop_recording(void)
{
    ble_set_streaming(false);
    enter_state(GETMS_STATE_STOPPED);
}

static void report_rep(const rep_result_t *r)
{
    s_rep_count++;
    if (r->clean) {
        s_clean_count++;
    }

    uint8_t flags = 0;
    if (r->clean) flags |= GETMS_REP_F_CLEAN;
    if (r->low_conf) flags |= GETMS_REP_F_LOW_CONF;
    if (r->missing) flags |= GETMS_REP_F_MISSING;
    if (r->saturated) flags |= GETMS_REP_F_SATURATED;
    if (r->reasons & REP_REASON_TWIST) flags |= GETMS_REP_F_TWIST;
    if (r->reasons & REP_REASON_SWING) flags |= GETMS_REP_F_SWING;
    if (r->reasons & REP_REASON_PLANE) flags |= GETMS_REP_F_PLANE;

    uint8_t *e = s_ring[(s_rep_count - 1) % REP_RING_SIZE];
    e[0] = GETMS_REP_TYPE_VERSION;
    put_u16(e + 1, s_rep_count);
    put_u32(e + 3, r->start_ms);
    put_u16(e + 7, sat_u16((float)r->duration_ms));
    put_u16(e + 9, sat_u16(r->rom_deg * 10.0f));
    put_u16(e + 11, sat_u16(r->peak_act * 10.0f));
    put_u16(e + 13, sat_u16(r->mean_act * 10.0f));
    e[15] = sat_u8(r->twist_deg);
    e[16] = sat_u8(r->swing_deg);
    e[17] = sat_u8(r->plane_deg);
    e[18] = flags;
    ble_send_rep(e); // if it is lost, the app gets it back with GET_REPS

    if (r->clean) {
        printf("Rep %u\n", s_rep_count);
    } else {
        char why[24] = "";
        if (r->reasons & REP_REASON_TWIST) strcat(why, "twist");
        if (r->reasons & REP_REASON_SWING) strcat(why, why[0] ? ", swing" : "swing");
        if (r->reasons & REP_REASON_PLANE) strcat(why, why[0] ? ", plane" : "plane");
        printf("Rep %u (not clean: %s)\n", s_rep_count, why);
    }
    DBG("  rom %.1f deg, %lu ms, act peak %.0f%% mean %.0f%%, twist %.0f swing %.0f plane %.0f deg\n",
        r->rom_deg, (unsigned long)r->duration_ms, r->peak_act, r->mean_act, r->twist_deg, r->swing_deg, r->plane_deg);

    if (TARGET_REPS > 0 && s_rep_count >= TARGET_REPS) {
        printf("Exercise complete\n");
        stop_recording(); // publishes the final counts
    } else {
        publish_status();
    }
}

static void record_sample(int64_t now_us, int64_t dt_us, float dt_s, vec3_t rate_fore)
{
    float raw = arm_elbow_deg(&s_arm, s_tilt[IMU_UPPER].g, s_tilt[IMU_FORE].g);
    if (!s_lpf_primed) {
        biquad_prime(&s_angle_lpf, raw);
        s_lpf_primed = true;
    }
    uint32_t sat = s_emg_sat_count;
    rep_input_t in = {
        .t_ms = (uint32_t)((now_us - s_start_us) / 1000),
        .dt_s = dt_s,
        .elbow_deg = biquad_run(&s_angle_lpf, raw),
        .g_upper = s_tilt[IMU_UPPER].g,
        .twist_rate_dps = v3_dot(rate_fore, s_arm.long_fore),
        .plane_rate_dps = v3_dot(rate_fore, s_arm.normal_fore),
        .act_pct = activation_pct(s_env_mv),
        .gap = dt_us > (int64_t)(IMU_GAP_FACTOR * SAMPLE_PERIOD_US),
        .gap_ms = (uint32_t)(dt_us / 1000),
        .saturated = s_imu[IMU_UPPER].saturated || s_imu[IMU_FORE].saturated || sat != s_last_sat,
    };
    s_last_sat = sat;

    if (REBAND_DEBUG && in.t_ms % 500 < 10) {
        printf("t=%lu ms angle=%.1f act=%.0f%%\n", (unsigned long)in.t_ms, in.elbow_deg, in.act_pct);
    }

    rep_result_t r;
    rep_event_t ev = reps_update(&s_reps, &in, &r);
    if (ev == REP_COUNTED) {
        report_rep(&r);
    } else if (ev == REP_REJECTED) {
        DBG("rep not counted: %s\n", s_reps.reject_why);
    }
}

// ---- GET_REPS replay --------------------------------------------------------------

static void start_replay(uint16_t from)
{
    uint16_t oldest = s_rep_count > REP_RING_SIZE ? s_rep_count - REP_RING_SIZE + 1 : 1;
    s_replay_next = from < oldest ? oldest : from;
    s_replay_end = s_rep_count;
}

static void replay_step(void)
{
    for (int k = 0; k < REPLAY_PER_TICK && s_replay_next <= s_replay_end; k++) {
        uint8_t e[GETMS_REP_EVENT_LEN];
        memcpy(e, s_ring[(s_replay_next - 1) % REP_RING_SIZE], sizeof(e));
        e[18] |= GETMS_REP_F_REPLAY;
        esp_err_t err = ble_send_rep(e);
        if (err == ESP_ERR_NO_MEM) {
            return; // stack busy, retry next tick
        }
        if (err != ESP_OK) {
            s_replay_end = 0; // disconnected or unsubscribed: drop the replay
            return;
        }
        s_replay_next++;
    }
}

// ---- Commands ---------------------------------------------------------------------

static void not_allowed(const char *what)
{
    fail(GETMS_ERROR_INVALID_STATE, "%s not allowed in %s", what, s_state_name[s_state]);
}

static void handle_command(const command_t *c)
{
    if (c->len == 0 || c->len > GETMS_CMD_MAX_LEN) {
        fail(GETMS_ERROR_INVALID_COMMAND, "invalid command length %u", c->len);
        return;
    }
    uint8_t op = c->data[0];
    switch (op) {
    case GETMS_CMD_CALIBRATE: {
        if (c->len != 2) break;
        uint8_t step = c->data[1];
        bool ok;
        if (step == GETMS_CAL_STEP_RELAX) {
            ok = s_state != GETMS_STATE_RECORDING;
        } else if (step == GETMS_CAL_STEP_CURL || step == GETMS_CAL_STEP_MVC) {
            ok = s_state == GETMS_STATE_CALIBRATING && s_cal_active == 0 && s_cal_done == step - 1;
        } else {
            fail(GETMS_ERROR_INVALID_COMMAND, "invalid calibration step %u", step);
            return;
        }
        if (!ok) {
            not_allowed("CALIBRATE");
            return;
        }
        begin_cal_step(step, true);
        return;
    }

    case GETMS_CMD_START:
        if (c->len != 1) break;
        if (!s_calibrated || s_imu_fault ||
            !(s_state == GETMS_STATE_READY || s_state == GETMS_STATE_STOPPED || s_state == GETMS_STATE_ERROR)) {
            not_allowed("START");
            return;
        }
        if (ble_mtu() < GETMS_EMG_PACKET_LEN + 3) {
            s_error_flags |= GETMS_ERR_MTU_TOO_SMALL;
            fail(GETMS_ERROR_MTU_TOO_SMALL, "MTU too small for EMG data");
            return;
        }
        s_error_flags &= ~GETMS_ERR_MTU_TOO_SMALL;
        start_recording();
        return;

    case GETMS_CMD_STOP:
        if (c->len != 1) break;
        if (s_state == GETMS_STATE_RECORDING) {
            printf("Exercise stopped\n");
            stop_recording();
        } else if (s_state == GETMS_STATE_CALIBRATING) {
            printf("Calibration cancelled\n");
            clear_calibration();
            enter_state(GETMS_STATE_IDLE);
        } else {
            not_allowed("STOP");
        }
        return;

    case GETMS_CMD_GET_STATE:
        if (c->len != 1) break;
        publish_status();
        return;

    case GETMS_CMD_GET_REPS:
        if (c->len != 1 && c->len != 3) break;
        start_replay(c->len == 3 ? (uint16_t)(c->data[1] | (c->data[2] << 8)) : 1);
        return;

    default:
        break;
    }
    fail(GETMS_ERROR_INVALID_COMMAND, "invalid command 0x%02x (%u bytes)", op, c->len);
}

// ---- Standalone test ---------------------------------------------------------------
// Without a phone, runs the same calibration and exercise from the serial
// monitor: cue, STANDALONE_PREP_S to get into position, capture, next step.
// Failed steps retry from step 1; a phone connecting hands control to the app.

typedef enum {
    AUTO_OFF,
    AUTO_WAIT_BLE,  // waiting STANDALONE_WAIT_S for a phone
    AUTO_WAIT_IMUS, // re-probing until both IMUs answer
    AUTO_PREP,      // cue printed, user getting into position
    AUTO_CAPTURE,   // calibration step being captured
    AUTO_RECORDING,
    AUTO_RETRY,
    AUTO_DONE,
} auto_phase_t;

static auto_phase_t s_auto;
static int64_t s_auto_at; // the current wait ends at this esp_timer time
static uint8_t s_auto_step;

static void auto_prep(uint8_t step, int64_t now)
{
    cal_cue(step);
    s_auto_step = step;
    s_auto = AUTO_PREP;
    s_auto_at = now + STANDALONE_PREP_S * 1000000LL;
}

static void auto_retry(int64_t now)
{
    printf("   Retrying calibration in %d s\n", STANDALONE_RETRY_S);
    s_auto = AUTO_RETRY;
    s_auto_at = now + STANDALONE_RETRY_S * 1000000LL;
}

static void auto_run(int64_t now)
{
    if (s_auto == AUTO_OFF || s_auto == AUTO_DONE) {
        return;
    }
    if (s_connected) {
        if (s_auto != AUTO_WAIT_BLE) {
            printf("Standalone test stopped, app in control\n");
        }
        s_auto = AUTO_OFF;
        return;
    }
    if (now < s_auto_at) {
        return;
    }

    switch (s_auto) {
    case AUTO_WAIT_BLE:
        printf("No BLE connection, running standalone test\n");
        s_auto = AUTO_WAIT_IMUS;
        break;

    case AUTO_WAIT_IMUS:
        if (probe_imus()) {
            auto_prep(GETMS_CAL_STEP_RELAX, now);
        } else {
            s_auto_at = now + 2000000LL; // quiet retry; plugging a sensor in prints "IMU n connected"
        }
        break;

    case AUTO_PREP:
        printf(s_auto_step == GETMS_CAL_STEP_MVC ? "   Squeeze now ...\n" : "   Hold still ...\n");
        begin_cal_step(s_auto_step, false);
        s_auto = AUTO_CAPTURE;
        break;

    case AUTO_CAPTURE:
        if (s_state == GETMS_STATE_ERROR) {
            auto_retry(now);
        } else if (s_state == GETMS_STATE_READY) {
            start_recording();
            s_auto = AUTO_RECORDING;
        } else if (s_cal_active == 0) {
            auto_prep(s_cal_done + 1, now);
        }
        break;

    case AUTO_RECORDING:
        if (s_state == GETMS_STATE_ERROR) {
            auto_retry(now);
        } else if (s_state != GETMS_STATE_RECORDING) {
            printf("Reset the board to run the test again\n");
            s_auto = AUTO_DONE;
        }
        break;

    case AUTO_RETRY:
        s_auto = AUTO_WAIT_IMUS;
        break;

    default:
        break;
    }
}

// ---- Sampling task -----------------------------------------------------------------

static void tick(void)
{
    command_t cmd;
    while (xQueueReceive(s_cmd_queue, &cmd, 0) == pdTRUE) {
        handle_command(&cmd);
    }
    replay_step();
    auto_run(esp_timer_get_time());

    bool ok = true;
    for (int i = 0; i < IMU_COUNT; i++) {
        if (imu_read((imu_id_t)i, &s_imu[i]) == ESP_OK) {
            s_read_fails[i] = 0;
        } else {
            on_read_fail(i);
            ok = false;
        }
    }
    if (!ok) {
        return; // a missed sample shows up as a long interval on the next good one
    }

    int64_t now = s_imu[IMU_FORE].t_us;
    int64_t dt_us = s_last_us ? now - s_last_us : SAMPLE_PERIOD_US;
    s_last_us = now;
    float dt_s = fminf((float)dt_us * 1e-6f, 0.1f); // bound integration after a stall

    vec3_t rate[IMU_COUNT];
    for (int i = 0; i < IMU_COUNT; i++) {
        rate[i] = tilt_update(&s_tilt[i], v3_from(s_imu[i].accel), v3_from(s_imu[i].gyro), dt_s);
    }

    if (s_state == GETMS_STATE_CALIBRATING && s_cal_active) {
        cal_capture();
    } else if (s_state == GETMS_STATE_RECORDING) {
        record_sample(now, dt_us, dt_s, rate[IMU_FORE]);
    }
}

static void timer_cb(void *arg)
{
    xTaskNotifyGive(s_task);
}

static void session_task(void *arg)
{
    for (;;) {
        ulTaskNotifyTake(pdTRUE, portMAX_DELAY); // overruns collapse into one tick
        tick();
    }
}

// ---- Public -----------------------------------------------------------------------

void session_command(const uint8_t *data, uint16_t len)
{
    if (s_cmd_queue == NULL) {
        return;
    }
    command_t c = {.len = len};
    memcpy(c.data, data, len < GETMS_CMD_MAX_LEN ? len : GETMS_CMD_MAX_LEN);
    if (xQueueSend(s_cmd_queue, &c, 0) != pdTRUE) {
        DBG("command queue full, dropped 0x%02x\n", c.data[0]);
    }
}

void session_on_connection(bool connected)
{
    s_connected = connected;
    if (connected) {
        printf("BLE Connected\n");
    } else if (s_state == GETMS_STATE_RECORDING) {
        printf("ERROR: BLE disconnected\n"); // recording continues, reps kept for GET_REPS
    } else {
        printf("BLE Disconnected\n");
    }
}

void session_feed_emg(const emg_block_t *block)
{
    static int16_t win[ENV_WINDOW];
    static int32_t sum;
    static int idx, filled;

    for (int i = 0; i < EMG_SAMPLES_PER_BLOCK; i++) {
        int16_t mv = block->mv[i];
        sum += mv - win[idx];
        win[idx] = mv;
        idx = (idx + 1) % ENV_WINDOW;
        if (filled < ENV_WINDOW) {
            filled++;
        }
        if (mv >= EMG_SATURATION_MV) {
            s_emg_sat_count++;
        }
    }
    s_env_mv = (float)sum / (float)filled;
}

esp_err_t session_init(uint16_t fw_version)
{
    s_fw_version = fw_version;
    if (!emg_is_calibrated()) {
        s_error_flags |= GETMS_ERR_ADC_UNCALIBRATED;
    }

    s_cmd_queue = xQueueCreate(CMD_QUEUE_LEN, sizeof(command_t));
    if (s_cmd_queue == NULL) {
        return ESP_ERR_NO_MEM;
    }

    if (probe_imus()) { // otherwise already in ERROR with the reason printed
        enter_state(GETMS_STATE_IDLE);
    }
    if (STANDALONE_TEST) {
        printf("Waiting for BLE connection (%d s) ...\n", STANDALONE_WAIT_S);
        s_auto = AUTO_WAIT_BLE;
        s_auto_at = esp_timer_get_time() + STANDALONE_WAIT_S * 1000000LL;
    }

    if (xTaskCreatePinnedToCore(session_task, "session", 4096, NULL, TASK_PRIO, &s_task, TASK_CORE) != pdPASS) {
        return ESP_ERR_NO_MEM;
    }
    const esp_timer_create_args_t timer_args = {
        .callback = timer_cb,
        .name = "imu_sample",
    };
    esp_err_t err = esp_timer_create(&timer_args, &s_timer);
    if (err != ESP_OK) {
        return err;
    }
    return esp_timer_start_periodic(s_timer, SAMPLE_PERIOD_US);
}
