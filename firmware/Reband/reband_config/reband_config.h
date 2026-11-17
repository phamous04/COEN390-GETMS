#pragma once

// ReBand tunables: rates, ranges and thresholds for the bicep-curl logic.
// Everything that may need tuning on real arms lives here.

// ---- Serial output -------------------------------------------------------------
#define REBAND_DEBUG 0 // 1 = verbose ESP_LOG output, EMG link stats, live angle/activation lines
#define TARGET_REPS  5 // counted reps (clean or not) until "Exercise complete"; 0 = unlimited

// ---- Standalone test (no phone) ---------------------------------------------------
// If no phone connects within STANDALONE_WAIT_S after boot, the device runs the
// calibration and the exercise by itself with cues on the serial monitor.
// A phone connecting at any point takes over.
#define STANDALONE_TEST    1
#define STANDALONE_WAIT_S  10 // wait for a BLE connection first
#define STANDALONE_PREP_S  3  // time to get into each calibration position
#define STANDALONE_RETRY_S 5  // pause before retrying a failed calibration

// ---- IMUs: two MPU-6050 on one I2C bus --------------------------------------------
// "IMU 1" = upper arm (AD0 high), "IMU 2" = forearm (AD0 low) in serial messages.
#define IMU_I2C_PORT       0
#define IMU_I2C_SDA_GPIO   21
#define IMU_I2C_SCL_GPIO   22
#define IMU_I2C_FREQ_HZ    100000 // 400000 also fits; 100 kHz is more tolerant of long wires (both reads ~3 ms)
#define IMU_I2C_TIMEOUT_MS 20     // the driver waits in FreeRTOS ticks (10 ms here): must be >= 2 ticks, never < 1 tick
#define IMU_ADDR_UPPER     0x69 // AD0 wired to 3V3
#define IMU_ADDR_FORE      0x68 // AD0 not connected (or GND)

#define IMU_ACCEL_FS_SEL   1   // 0..3 = ±2/4/8/16 g    -> ±4 g
#define IMU_GYRO_FS_SEL    1   // 0..3 = ±250/500/1000/2000 °/s -> ±500 °/s
#define IMU_DLPF_CFG       3   // on-chip low-pass, 3 = 44 Hz
#define IMU_SAMPLE_RATE_HZ 100
#define IMU_MAX_READ_FAILS 10   // consecutive failed reads (100 ms) before the ERROR state
#define IMU_GAP_FACTOR     1.5f // a sample interval above this x period is a missed sample

// Mounting: both IMUs are strapped the same way round, with this chip axis
// pointing out of the side of the arm (perpendicular to the plane of the curl).
// The forearm's exact hinge axis is measured during calibration; this one is
// used for the upper arm and to match the two frames.
#define IMU_HINGE_AXIS_X     0.0f
#define IMU_HINGE_AXIS_Y     0.0f
#define IMU_HINGE_AXIS_Z     1.0f
#define IMU_UPPER_HINGE_SIGN 1.0f // -1 if the upper-arm IMU is mounted flipped relative to the forearm one

// ---- Orientation filter ----------------------------------------------------------
#define FILTER_ALPHA         0.98f // complementary filter: gyro weight
#define FILTER_ACCEL_TRUST_G 0.25f // ignore the accelerometer while | |a| - 1 g | is above this
#define ANGLE_LPF_HZ         5.0f  // elbow angle low-pass before rep detection

// ---- EMG (MyoWare ENV output, already rectified and smoothed by the sensor) -----------
#define EMG_ENV_WINDOW_MS 150  // moving-average window on top of the ENV signal
#define EMG_SATURATION_MV 3200 // samples at or above this count as ADC saturation

// ---- Calibration -----------------------------------------------------------------
#define CAL_HOLD_MS            2500  // relax and curl holds
#define CAL_MVC_MS             3000  // maximal contraction window
#define CAL_STILL_GYRO_STD_DPS 4.0f // per-axis gyro std above this = arm moved
#define CAL_STILL_ACCEL_STD_G  0.05f // accel magnitude std above this = arm moved
#define CAL_CURL_GYRO_STD_DPS  12.0f // looser limits for the curled hold: the working muscle shakes,
#define CAL_CURL_ACCEL_STD_G   0.15f // and only the mean gravity direction is used from it
#define CAL_MIN_ROM_DEG        60.0f // relax -> curl must cover at least this
#define CAL_MIN_MVC_DELTA_MV   100   // MVC peak must exceed the resting baseline by this

// ---- Rep detection (fractions are of ROM_ref, the calibrated range of motion) -----------
#define REP_LOW_FRAC           0.20f  // rep starts above / ends below this angle
#define REP_HIGH_FRAC          0.65f  // and must reach this angle
#define REP_HYST_DEG           3.0f   // hysteresis on the low threshold
#define REP_PEAK_DROP_DEG      8.0f   // drop from the peak that starts the extending phase
#define REP_MIN_ROM_FRAC       0.50f  // minimum ROM for a rep to count
#define REP_LOWCONF_ROM_FRAC   0.70f  // counted, but flagged low-confidence below this
#define REP_MIN_DURATION_MS    800
#define REP_MAX_DURATION_MS    10000
#define REP_MIN_ACTIVATION_PCT 10.0f  // EMG must exceed this %MVC during flexion
#define REP_MAX_GAP_MS         50     // one sample gap longer than this invalidates the rep
#define REP_RING_SIZE          32     // reps kept for GET_REPS

// ---- Clean-rep (form) check ---------------------------------------------------------
#define FORM_MAX_TWIST_DEG 30.0f // forearm rotation about its long axis
#define FORM_MAX_SWING_DEG 15.0f // upper-arm tilt change (shoulder / body momentum)
#define FORM_MAX_PLANE_DEG 25.0f // forearm rotation out of the hinge plane
