package com.example.app

import com.example.app.ble.DeviceState
import com.example.app.ui.exercise.AfterSet
import com.example.app.ui.exercise.SetProgress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Set transitions of the Bicep Curl flow (2 sets with a rest in between), without a wearable. */
class SetProgressTest {

    @Test
    fun targetReachedInFirstSetLeadsToRestAndTheNextSet() {
        val (sets, after) = SetProgress().setStopped(reps = 5, clean = 4, targetReps = 5, stoppedByUser = false)
        assertEquals(AfterSet.REST, after)
        assertEquals(2, sets.currentSet)
        assertEquals(1, sets.setsCompleted)
        assertEquals(5, sets.repCount)
        assertEquals(4, sets.cleanCount)
    }

    @Test
    fun stopPressedEarlyInFirstSetAsksBeforeTheNextSet() {
        val (sets, after) = SetProgress().setStopped(reps = 3, clean = 3, targetReps = 5, stoppedByUser = true)
        assertEquals(AfterSet.CONFIRM_NEXT_SET, after)
        // Either answer keeps the reps: the next set would be set 2, ending now saves 3 reps in 1 set.
        assertEquals(SetProgress(currentSet = 2, setsCompleted = 1, repCount = 3, cleanCount = 3), sets)
    }

    @Test
    fun stopIsNotEarlyAtTheTargetOrWithoutATarget() {
        assertEquals(AfterSet.REST, SetProgress().setStopped(5, 5, targetReps = 5, stoppedByUser = true).second)
        assertEquals(AfterSet.REST, SetProgress().setStopped(8, 6, targetReps = 0, stoppedByUser = true).second)
    }

    @Test
    fun lastSetFinishesWithTotalsAcrossSets() {
        val afterFirst = SetProgress().setStopped(5, 4, targetReps = 5, stoppedByUser = false).first
        // Stop pressed early in the last set: nothing left to ask, the session is saved.
        val (sets, after) = afterFirst.setStopped(reps = 3, clean = 2, targetReps = 5, stoppedByUser = true)
        assertEquals(AfterSet.FINISH, after)
        assertEquals(SetProgress(currentSet = 2, setsCompleted = 2, repCount = 8, cleanCount = 6), sets)
        assertFalse(sets.hasNextSet)
    }

    @Test
    fun setWithoutRepsIsNotCompleted() {
        val (sets, after) = SetProgress().setStopped(reps = 0, clean = 0, targetReps = 5, stoppedByUser = true)
        assertEquals(AfterSet.CONFIRM_NEXT_SET, after)
        assertEquals(0, sets.setsCompleted)
        assertEquals(0, sets.repCount)
    }

    @Test
    fun interruptedSetKeepsItsRepsButIsNotCompleted() {
        val afterFirst = SetProgress().setStopped(5, 5, targetReps = 5, stoppedByUser = false).first
        val sets = afterFirst.setInterrupted(reps = 2, clean = 1)
        assertEquals(SetProgress(currentSet = 2, setsCompleted = 1, repCount = 7, cleanCount = 6), sets)
    }

    @Test
    fun nextSetStartsWithoutCalibrationOnlyFromReadyOrStopped() {
        assertTrue(SetProgress.canStartWithoutCalibration(DeviceState.STOPPED))
        assertTrue(SetProgress.canStartWithoutCalibration(DeviceState.READY))
        for (state in listOf(DeviceState.IDLE, DeviceState.CALIBRATING, DeviceState.RECORDING, DeviceState.ERROR, DeviceState.UNKNOWN, null)) {
            assertFalse("$state", SetProgress.canStartWithoutCalibration(state))
        }
    }
}
