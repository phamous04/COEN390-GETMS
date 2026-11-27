package com.example.app

import com.example.app.ui.exercise.Arm
import com.example.app.ui.exercise.BackAction
import com.example.app.ui.exercise.FlowStep
import com.example.app.ui.exercise.backAction
import com.example.app.ui.exercise.exerciseSummary
import com.example.app.ui.exercise.placementArm
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Back navigation and arm selection of the placement guide and tutorial steps (pure logic). */
class GuideStepsTest {

    @Test
    fun backPagesThroughTheHealthyArmGuideThenReturnsToSetup() {
        assertEquals(BackAction.PREVIOUS_GUIDE_PAGE, backAction(FlowStep.PLACEMENT, guidePage = 2))
        assertEquals(BackAction.PREVIOUS_GUIDE_PAGE, backAction(FlowStep.PLACEMENT, guidePage = 1))
        assertEquals(BackAction.BACK_TO_SETUP, backAction(FlowStep.PLACEMENT, guidePage = 0))
    }

    @Test
    fun backPagesThroughTheExercisingArmGuideThenAsksBeforeLeaving() {
        assertEquals(BackAction.PREVIOUS_GUIDE_PAGE, backAction(FlowStep.SWITCH_ARM, guidePage = 1))
        assertEquals(BackAction.CONFIRM_LEAVE, backAction(FlowStep.SWITCH_ARM, guidePage = 0))
    }

    @Test
    fun guidePageLeftBehindDoesNotAffectLaterSteps() {
        // The pager still sits on its last page after "Done, calibrate".
        for (step in listOf(FlowStep.REFERENCE_CALIBRATION, FlowStep.TUTORIAL, FlowStep.REFERENCE_RECORD, FlowStep.EXERCISE_CALIBRATION)) {
            assertEquals(step.name, BackAction.CONFIRM_LEAVE, backAction(step, guidePage = 2))
        }
        assertEquals(BackAction.IGNORE, backAction(FlowStep.RECORDING, guidePage = 2))
    }

    @Test
    fun backOnTheExistingStepsIsUnchanged() {
        val expected = mapOf(
            FlowStep.SETUP to BackAction.EXIT,
            FlowStep.REFERENCE_CALIBRATION to BackAction.CONFIRM_LEAVE,
            FlowStep.REFERENCE_RECORD to BackAction.CONFIRM_LEAVE,
            FlowStep.REFERENCE_REVIEW to BackAction.CONFIRM_LEAVE,
            FlowStep.EXERCISE_CALIBRATION to BackAction.CONFIRM_LEAVE,
            FlowStep.READY to BackAction.CONFIRM_LEAVE,
            FlowStep.RECORDING to BackAction.IGNORE,
            FlowStep.FINISHING to BackAction.IGNORE,
            FlowStep.SUMMARY to BackAction.EXIT,
        )
        for ((step, action) in expected) assertEquals(step.name, action, backAction(step, guidePage = 0))
    }

    @Test
    fun guideShowsTheHealthyArmFirstAndTheExercisingArmAfterTheSwitch() {
        assertEquals(Arm.RIGHT, placementArm(FlowStep.PLACEMENT, exercisingArm = Arm.LEFT))
        assertEquals(Arm.LEFT, placementArm(FlowStep.SWITCH_ARM, exercisingArm = Arm.LEFT))
        assertEquals(Arm.LEFT, placementArm(FlowStep.PLACEMENT, exercisingArm = Arm.RIGHT))
        assertNull(placementArm(FlowStep.PLACEMENT, exercisingArm = null))
        assertNull(placementArm(FlowStep.TUTORIAL, exercisingArm = Arm.LEFT))
    }

    @Test
    fun introLineNamesSetsRepsAndDuration() {
        assertEquals("Bicep Curl · 2 sets × 5 reps · ~5 min", exerciseSummary(sets = 2, repsPerSet = 5, minutes = 5))
        assertEquals("Bicep Curl · 1 set × 1 rep · ~3 min", exerciseSummary(sets = 1, repsPerSet = 1, minutes = 3))
    }
}
