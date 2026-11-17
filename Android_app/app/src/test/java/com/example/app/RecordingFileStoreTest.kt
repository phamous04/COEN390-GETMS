package com.example.app

import com.example.app.ble.EmgPacket
import com.example.app.ble.EmgSample
import com.example.app.ble.ImuPacket
import com.example.app.ble.ImuSample
import com.example.app.ble.Quaternion
import com.example.app.data.local.InvalidRecordingFile
import com.example.app.data.local.RecordingEntity
import com.example.app.data.local.RecordingFileStore
import com.example.app.data.local.RecordingStatus
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.RandomAccessFile
import java.util.UUID

class RecordingFileStoreTest {
    @get:Rule val temporary = TemporaryFolder()
    private val store get() = RecordingFileStore(temporary.root)
    private fun recording(): RecordingEntity {
        val id = UUID.randomUUID().toString()
        return RecordingEntity(id, "Shoulder flexion", "Left", 1000, userId = "owner",
            localFilePath = store.fileFor(id).canonicalPath)
    }
    private val packet = EmgPacket(1, 10, listOf(EmgSample(450, 10), EmgSample(500, 20)))

    @Test fun createsLinkedVersionedFileWithBothSensorTypesAndFooter() {
        val recording = recording()
        store.create(recording).use {
            it.append(packet, 1100, 100)
            it.append(ImuPacket(2, 20, listOf(ImuSample(Quaternion(1f, 0f, 0f, 0f), 20))), 1200, 200)
            it.finish(RecordingFileStore.Footer(1300, 300, RecordingStatus.COMPLETED, null))
        }
        assertTrue(store.linkedFile(recording).name.startsWith(recording.id))
        assertEquals(300L, store.inspect(recording).footer!!.elapsed)
        assertEquals(200L, store.inspect(recording).elapsed)
    }

    @Test fun recoversEveryPossibleTornTailOfLastPacket() {
        val original = recording()
        var headerSize = 0L
        store.create(original).use {
            headerSize = store.linkedFile(original).length()
            it.append(packet, 1100, 100)
        }
        val originalBytes = store.linkedFile(original).readBytes()
        for (cut in headerSize.toInt() until originalBytes.size) {
            store.linkedFile(original).writeBytes(originalBytes.copyOf(cut))
            val footer = store.recover(original)
            assertEquals(RecordingStatus.INTERRUPTED, footer.status)
            assertEquals(0L, footer.elapsed)
            assertNotNull(store.inspect(original).footer)
        }
    }

    @Test fun processDeathPreservesCompletePacketsAndUsesLastDurableTime() {
        val recording = recording()
        store.create(recording).use { it.append(packet, 1100, 100) }
        val freshStore = RecordingFileStore(temporary.root)
        val footer = freshStore.recover(recording)
        assertEquals(1100L, footer.end)
        assertEquals(100L, footer.elapsed)
        assertEquals(RecordingStatus.INTERRUPTED, freshStore.inspect(recording).footer!!.status)
    }

    @Test fun crcCorruptionIsReportedAndFileIsPreserved() {
        val recording = recording()
        store.create(recording).use { it.append(packet, 1100, 100) }
        val file = store.linkedFile(recording)
        RandomAccessFile(file, "rw").use { it.seek(it.length() - 1); it.writeByte(123) }
        val bytes = file.readBytes()
        assertThrows(InvalidRecordingFile::class.java) { store.recover(recording) }
        assertArrayEquals(bytes, file.readBytes())
    }

    @Test fun missingMismatchedAndUnsealedFilesAreExplicitErrors() {
        val recording = recording()
        assertThrows(InvalidRecordingFile::class.java) { store.inspect(recording) }
        store.create(recording).close()
        assertThrows(InvalidRecordingFile::class.java) { store.inspect(recording) }
        assertThrows(InvalidRecordingFile::class.java) { store.inspect(recording.copy(exercise = "Other"), true) }
        assertThrows(InvalidRecordingFile::class.java) { store.inspect(recording.copy(localFilePath = "/elsewhere")) }
    }

    @Test fun completeFooterSurvivesCrashBeforeRoomCommit() {
        val recording = recording()
        store.create(recording).use { it.finish(RecordingFileStore.Footer(1200, 200, RecordingStatus.COMPLETED, null)) }
        assertEquals(RecordingStatus.COMPLETED, store.recover(recording).status)
    }

    @Test fun invalidSensorPacketDoesNotCorruptAlreadyDurableSamples() {
        val recording = recording()
        store.create(recording).use {
            it.append(packet, 1100, 100)
            assertThrows(IllegalArgumentException::class.java) {
                it.append(ImuPacket(2, 20, listOf(ImuSample(Quaternion(Float.NaN, 0f, 0f, 0f), 20))), 1200, 200)
            }
        }
        assertEquals(100L, store.recover(recording).elapsed)
        assertNotNull(store.inspect(recording).footer)
    }
}
