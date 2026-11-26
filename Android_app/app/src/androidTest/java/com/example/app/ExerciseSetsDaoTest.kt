package com.example.app

import android.content.Context
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.app.data.local.AppDatabase
import com.example.app.data.local.ExerciseRepEntity
import com.example.app.data.local.ExerciseSessionEntity
import com.example.app.data.local.SetType
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/** The firmware restarts rep_index at every START, so reps are keyed by set as well. */
@RunWith(AndroidJUnit4::class)
class ExerciseSetsDaoTest {
    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()

    @After fun close() = db.close()

    private fun rep(setIndex: Int, repIndex: Int, rom: Int) = ExerciseRepEntity(
        sessionId = "s1", setType = SetType.EXERCISE, setIndex = setIndex, repIndex = repIndex, startMs = 0,
        durationMs = 1500, romX10 = rom, peakActivationX10 = 400, meanActivationX10 = 200,
        twistDeg = 3, swingDeg = 2, planeDeg = 1, flags = 1,
    )

    @Test fun firstRepOfSetTwoDoesNotOverwriteFirstRepOfSetOne() = runBlocking {
        val dao = db.exerciseSessionDao()
        dao.upsertSession(
            ExerciseSessionEntity(
                id = "s1", patientId = "p1", exercisingArm = "right", referenceArm = "left",
                setsPlanned = 2, createdAtMillis = 1000, updatedAtMillis = 1000,
            ),
        )
        dao.insertReps(listOf(rep(setIndex = 1, repIndex = 1, rom = 900)))
        dao.insertReps(listOf(rep(setIndex = 2, repIndex = 1, rom = 700)))
        // A GET_REPS replay of a stored rep is still ignored, within its own set.
        dao.insertReps(listOf(rep(setIndex = 1, repIndex = 1, rom = 111), rep(setIndex = 2, repIndex = 1, rom = 222)))

        val saved = dao.observeSession("s1", "p1").first()!!
        assertEquals(
            mapOf(1 to listOf(900), 2 to listOf(700)),
            saved.exerciseSets.mapValues { (_, reps) -> reps.map { it.romX10 } },
        )
    }
}
