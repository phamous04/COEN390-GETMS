package com.example.app.data

import com.example.app.ble.RepFlags
import com.example.app.data.local.ExerciseRepEntity
import com.example.app.data.local.SessionWithReps
import java.util.Locale
import kotlin.math.abs

/**
 * Comparison of the healthy-arm reference with the exercising arm, built only from measured
 * firmware values. No score or clinical interpretation is derived.
 *
 * Aggregation rule for the exercising arm: the arithmetic mean over **all counted reps** of
 * **every set** (clean or not), as stored from the Rep Events; the best (maximum) range of motion
 * is shown as well. The reference side is the single reference rep. Calibration rows compare the
 * values each arm's calibration reported.
 */
object SessionMetrics {

    enum class MetricUnit(val suffix: String) { DEGREES("°"), SECONDS(" s"), PERCENT_MVC("% MVC"), MILLIVOLTS(" mV") }

    data class ComparisonRow(
        val label: String,
        val unit: MetricUnit,
        /** Healthy-arm value, null when not recorded. */
        val reference: Double?,
        /** Exercising-arm value (mean of counted reps, or calibration value). */
        val exercising: Double?,
        val note: String? = null,
        /** Ratio exercising / reference is shown for magnitudes where it is meaningful. */
        val showPercentOfReference: Boolean = false,
    ) {
        val difference: Double? get() = if (reference != null && exercising != null) exercising - reference else null
        val percentOfReference: Double?
            get() = if (showPercentOfReference && reference != null && exercising != null && abs(reference) > 1e-9) {
                exercising / reference * 100.0
            } else null
    }

    data class SetSummary(
        val repCount: Int,
        val cleanCount: Int,
        val meanRomDeg: Double?,
        val maxRomDeg: Double?,
        val meanDurationS: Double?,
        val meanPeakActivationPct: Double?,
        val meanActivationPct: Double?,
        val meanTwistDeg: Double?,
        val meanSwingDeg: Double?,
        val meanPlaneDeg: Double?,
        val lowConfidenceReps: Int,
        val missingSampleReps: Int,
        val saturatedReps: Int,
    )

    fun summarize(reps: List<ExerciseRepEntity>): SetSummary {
        fun mean(f: (ExerciseRepEntity) -> Double) = if (reps.isEmpty()) null else reps.sumOf(f) / reps.size
        return SetSummary(
            repCount = reps.size,
            cleanCount = reps.count { it.flags and RepFlags.CLEAN != 0 },
            meanRomDeg = mean { it.romX10 / 10.0 },
            maxRomDeg = reps.maxOfOrNull { it.romX10 / 10.0 },
            meanDurationS = mean { it.durationMs / 1000.0 },
            meanPeakActivationPct = mean { it.peakActivationX10 / 10.0 },
            meanActivationPct = mean { it.meanActivationX10 / 10.0 },
            meanTwistDeg = mean { it.twistDeg.toDouble() },
            meanSwingDeg = mean { it.swingDeg.toDouble() },
            meanPlaneDeg = mean { it.planeDeg.toDouble() },
            lowConfidenceReps = reps.count { it.flags and RepFlags.LOW_CONFIDENCE != 0 },
            missingSampleReps = reps.count { it.flags and RepFlags.MISSING_SAMPLES != 0 },
            saturatedReps = reps.count { it.flags and RepFlags.SATURATED != 0 },
        )
    }

    fun comparison(data: SessionWithReps): List<ComparisonRow> {
        val s = data.session
        val ref = data.referenceRep
        val ex = summarize(data.exerciseReps)
        val activationNote = "Each arm is scaled to its own maximum squeeze from calibration."
        return listOf(
            ComparisonRow("Range of motion per rep", MetricUnit.DEGREES, ref?.romX10?.div(10.0), ex.meanRomDeg, "Exercising arm: mean of counted reps.", true),
            ComparisonRow("Best range of motion", MetricUnit.DEGREES, ref?.romX10?.div(10.0), ex.maxRomDeg, "Exercising arm: largest counted rep.", true),
            ComparisonRow("Calibrated range of motion", MetricUnit.DEGREES, s.referenceRomRefX10?.div(10.0), s.exerciseRomRefX10?.div(10.0), "Measured from the relaxed and curled calibration holds.", true),
            ComparisonRow("Rep duration", MetricUnit.SECONDS, ref?.durationMs?.div(1000.0), ex.meanDurationS, "Exercising arm: mean of counted reps.", true),
            ComparisonRow("Peak muscle activation", MetricUnit.PERCENT_MVC, ref?.peakActivationX10?.div(10.0), ex.meanPeakActivationPct, activationNote),
            ComparisonRow("Mean muscle activation", MetricUnit.PERCENT_MVC, ref?.meanActivationX10?.div(10.0), ex.meanActivationPct, activationNote),
            ComparisonRow("Forearm twist", MetricUnit.DEGREES, ref?.twistDeg?.toDouble(), ex.meanTwistDeg),
            ComparisonRow("Upper-arm swing", MetricUnit.DEGREES, ref?.swingDeg?.toDouble(), ex.meanSwingDeg),
            ComparisonRow("Out-of-plane movement", MetricUnit.DEGREES, ref?.planeDeg?.toDouble(), ex.meanPlaneDeg),
            ComparisonRow("Maximum squeeze (calibration)", MetricUnit.MILLIVOLTS, s.referenceMvcMv?.toDouble(), s.exerciseMvcMv?.toDouble(), "Raw EMG envelope; depends on sensor placement on each arm."),
            ComparisonRow("Resting muscle signal", MetricUnit.MILLIVOLTS, s.referenceBaselineMv?.toDouble(), s.exerciseBaselineMv?.toDouble(), "Raw EMG envelope; depends on sensor placement on each arm."),
        )
    }

    /** Plain-language quality notes for one rep's flags. */
    fun flagNotes(flags: Int): List<String> = buildList {
        if (flags and RepFlags.LOW_CONFIDENCE != 0) add("Low confidence (short range or missing samples)")
        if (flags and RepFlags.MISSING_SAMPLES != 0) add("Some motion samples arrived late")
        if (flags and RepFlags.SATURATED != 0) add("A sensor reached its limit")
        if (flags and RepFlags.NOT_CLEAN_TWIST != 0) add("Forearm twisted")
        if (flags and RepFlags.NOT_CLEAN_SWING != 0) add("Upper arm swung")
        if (flags and RepFlags.NOT_CLEAN_PLANE != 0) add("Moved out of the curl plane")
    }

    fun format(value: Double?, unit: MetricUnit): String {
        if (value == null) return "—"
        val number = when (unit) {
            MetricUnit.SECONDS -> String.format(Locale.getDefault(), "%.1f", value)
            MetricUnit.MILLIVOLTS -> String.format(Locale.getDefault(), "%.0f", value)
            else -> String.format(Locale.getDefault(), "%.1f", value)
        }
        return number + unit.suffix
    }

    fun formatSigned(value: Double?, unit: MetricUnit): String {
        if (value == null) return "—"
        val sign = if (value > 0) "+" else ""
        return sign + format(value, unit)
    }

    /** "MM:SS" (or "H:MM:SS" past an hour). */
    fun clock(ms: Long?): String {
        if (ms == null || ms < 0) return "—"
        val total = ms / 1000
        val h = total / 3600
        val m = (total % 3600) / 60
        val sec = total % 60
        return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, sec) else String.format(Locale.US, "%02d:%02d", m, sec)
    }
}
