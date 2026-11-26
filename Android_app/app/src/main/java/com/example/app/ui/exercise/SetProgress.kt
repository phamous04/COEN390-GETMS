package com.example.app.ui.exercise

import com.example.app.ble.DeviceState

/** Exercising-arm sets per session. Constant for now. */
const val SETS_PLANNED = 2

/** Rest between two sets, in seconds. The patient may skip it. */
const val REST_SECONDS = 30

/** What the flow does once the device has reported STOPPED for a set. */
enum class AfterSet {
    /** Rest, then START the next set. */
    REST,
    /** Stop was pressed before the set's target: ask "Continue to set N" / "End session". */
    CONFIRM_NEXT_SET,
    /** That was the last planned set: save the session. */
    FINISH,
}

/**
 * Set bookkeeping of one session. The firmware restarts rep_index, rep_count and clean_count at
 * every START, so the per-set numbers are added up here. Pure (no Android, no BLE), so the set
 * transitions are unit-tested without a wearable.
 */
data class SetProgress(
    val setsPlanned: Int = SETS_PLANNED,
    /** The set being prepared or recorded, 1-based. Reps are stored under this index. */
    val currentSet: Int = 1,
    /** Sets the device ended in STOPPED with at least one counted rep. */
    val setsCompleted: Int = 0,
    /** Device rep_count / clean_count summed over the sets that ended. */
    val repCount: Int = 0,
    val cleanCount: Int = 0,
) {
    val hasNextSet: Boolean get() = currentSet < setsPlanned

    /**
     * The device reported STOPPED for [currentSet] with [reps] counted ([clean] of them clean):
     * its target was reached, or Stop was pressed ([stoppedByUser]). [targetReps] 0 = unlimited.
     */
    fun setStopped(reps: Int, clean: Int, targetReps: Int, stoppedByUser: Boolean): Pair<SetProgress, AfterSet> {
        val closed = copy(
            setsCompleted = setsCompleted + if (reps > 0) 1 else 0,
            repCount = repCount + reps,
            cleanCount = cleanCount + clean,
        )
        if (!hasNextSet) return closed to AfterSet.FINISH
        val after = if (stoppedByUser && targetReps > 0 && reps < targetReps) AfterSet.CONFIRM_NEXT_SET else AfterSet.REST
        return closed.copy(currentSet = currentSet + 1) to after
    }

    /** [currentSet] ended without the device reaching STOPPED: its reps count, the set does not. */
    fun setInterrupted(reps: Int, clean: Int): SetProgress =
        copy(repCount = repCount + reps, cleanCount = cleanCount + clean)

    companion object {
        /**
         * START is accepted from READY and STOPPED while the device is still calibrated
         * (BLE_PROTOCOL.md), so the next set needs no recalibration. In any other state the
         * exercising arm is calibrated again first.
         */
        fun canStartWithoutCalibration(state: DeviceState?): Boolean =
            state == DeviceState.READY || state == DeviceState.STOPPED
    }
}
