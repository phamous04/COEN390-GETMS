package com.example.app.ui.exercise

/**
 * The session as the intro card describes it. Keep these in step with the flow: the firmware ends
 * a set by itself at TARGET_REPS (5).
 */
const val INTRO_SETS = 2
const val INTRO_REPS_PER_SET = 5
const val INTRO_MINUTES = 5

/** "Bicep Curl · 2 sets × 5 reps · ~5 min" */
fun exerciseSummary(sets: Int, repsPerSet: Int, minutes: Int): String =
    "Bicep Curl · $sets ${if (sets == 1) "set" else "sets"} × " +
        "$repsPerSet ${if (repsPerSet == 1) "rep" else "reps"} · ~$minutes min"

/**
 * The arm the placement guide is shown for: the healthy arm before its calibration, the
 * exercising arm after the switch. Null on every other step.
 */
fun placementArm(step: FlowStep, exercisingArm: Arm?): Arm? = when (step) {
    FlowStep.PLACEMENT -> exercisingArm?.other
    FlowStep.SWITCH_ARM -> exercisingArm
    else -> null
}

/** What Back (system back or the arrow in the title) does on a step of the flow. */
enum class BackAction {
    /** Recording or saving: Stop has to be pressed first (or the session ended while disconnected). */
    IGNORE,
    /** Placement guide: one page back. */
    PREVIOUS_GUIDE_PAGE,
    /** Nothing is calibrated or stored yet: back to the arm selection. */
    BACK_TO_SETUP,
    /** Nothing to lose: leave the flow. */
    EXIT,
    /** The calibration and reference done so far would be discarded: ask first. */
    CONFIRM_LEAVE,
}

/** [guidePage] is the placement guide's current page. It only counts on the steps that show the guide. */
fun backAction(step: FlowStep, guidePage: Int): BackAction = when (step) {
    FlowStep.RECORDING, FlowStep.FINISHING -> BackAction.IGNORE
    FlowStep.SETUP, FlowStep.SUMMARY -> BackAction.EXIT
    FlowStep.PLACEMENT -> if (guidePage > 0) BackAction.PREVIOUS_GUIDE_PAGE else BackAction.BACK_TO_SETUP
    FlowStep.SWITCH_ARM -> if (guidePage > 0) BackAction.PREVIOUS_GUIDE_PAGE else BackAction.CONFIRM_LEAVE
    else -> BackAction.CONFIRM_LEAVE
}
