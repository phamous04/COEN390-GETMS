package com.example.app.data.local

import com.example.app.ble.EmgPacket
import com.example.app.ble.ImuPacket
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.util.UUID
import java.util.zip.CRC32

class InvalidRecordingFile(message: String) : IOException(message)

/** Version 1: big-endian length + payload + CRC32 frames. Each packet is fsynced.
 * Only a torn final frame in an unsealed recording may be removed during recovery.
 */
class RecordingFileStore(
    private val directory: File,
    private val syncDirectory: (File) -> Unit = {},
) {
    data class Footer(val end: Long, val elapsed: Long, val status: RecordingStatus, val error: String?)
    data class Inspection(val footer: Footer?, val lastTime: Long, val elapsed: Long, val validBytes: Long)

    fun fileFor(id: String): File {
        require(UUID.fromString(id).toString() == id) { "Invalid recording ID" }
        return File(directory, "$id.getms")
    }

    fun linkedFile(recording: RecordingEntity): File {
        val expected = fileFor(recording.id).canonicalFile
        if (recording.localFilePath != expected.path) throw InvalidRecordingFile("Recording file link is invalid")
        if (!expected.isFile) throw InvalidRecordingFile("Local sensor file is missing")
        return expected
    }

    fun create(recording: RecordingEntity): Writer {
        val creatingDirectory = !directory.isDirectory
        check(directory.isDirectory || directory.mkdirs()) { "Cannot create recording directory" }
        if (creatingDirectory) directory.parentFile?.let(syncDirectory)
        val file = fileFor(recording.id)
        if (!file.createNewFile()) throw IOException("Recording file already exists")
        return Writer(RandomAccessFile(file, "rw")).also { writer ->
            try {
                writer.frame {
                    writeByte(1); writeUTF("GETMS"); writeInt(1)
                    writeUTF(recording.id); writeUTF(recording.userId.orEmpty()); writeUTF(recording.sessionId)
                    writeUTF(recording.exercise); writeUTF(recording.arm); writeLong(recording.startedAtMillis)
                }
                syncDirectory(directory)
            } catch (e: Exception) { writer.close(); throw e }
        }
    }

    fun inspect(recording: RecordingEntity, allowPartial: Boolean = false): Inspection {
        val file = linkedFile(recording)
        if (file.length() > MAX_FILE_BYTES) throw InvalidRecordingFile("Recording exceeds the upload size limit")
        RandomAccessFile(file, "r").use { input ->
            var header = false
            var footer: Footer? = null
            var valid = 0L
            var lastTime = recording.startedAtMillis
            var elapsed = 0L
            while (input.filePointer < input.length()) {
                if (footer != null) throw InvalidRecordingFile("Data found after recording footer")
                if (input.length() - input.filePointer < 4) {
                    if (allowPartial && header) break
                    throw InvalidRecordingFile("Incomplete frame length")
                }
                val length = input.readInt()
                if (length !in 1..MAX_FRAME_BYTES) throw InvalidRecordingFile("Invalid frame length")
                if (input.length() - input.filePointer < length + 4L) {
                    if (allowPartial && header) break
                    throw InvalidRecordingFile("Incomplete sensor frame")
                }
                val bytes = ByteArray(length)
                input.readFully(bytes)
                if (input.readInt() != CRC32().apply { update(bytes) }.value.toInt()) {
                    throw InvalidRecordingFile("Sensor frame checksum failed")
                }
                try {
                    DataInputStream(ByteArrayInputStream(bytes)).use { frame ->
                        when (frame.readUnsignedByte()) {
                            1 -> {
                                if (header || frame.readUTF() != "GETMS" || frame.readInt() != 1 ||
                                    frame.readUTF() != recording.id || frame.readUTF() != recording.userId.orEmpty() ||
                                    frame.readUTF() != recording.sessionId || frame.readUTF() != recording.exercise ||
                                    frame.readUTF() != recording.arm || frame.readLong() != recording.startedAtMillis
                                ) throw InvalidRecordingFile("Recording header does not match metadata")
                                header = true
                            }
                            2, 3 -> {
                                if (!header) throw InvalidRecordingFile("Missing recording header")
                                // Type remains in bytes[0]; timestamps use receipt wall time and monotonic elapsed time.
                                lastTime = frame.readLong()
                                val nextElapsed = frame.readLong()
                                if (nextElapsed < elapsed) throw InvalidRecordingFile("Invalid packet time")
                                elapsed = nextElapsed
                                frame.readInt() // BLE sequence
                                frame.readLong() // BLE packet t0Us
                                val count = frame.readInt()
                                if (count !in 1..1024) throw InvalidRecordingFile("Invalid sample count")
                                repeat(count) {
                                    frame.readLong() // sensor timestampUs
                                    if (bytes[0].toInt() == 2) frame.readInt() else repeat(4) {
                                        if (!frame.readFloat().isFinite()) throw InvalidRecordingFile("Invalid IMU value")
                                    }
                                }
                            }
                            4 -> {
                                if (!header) throw InvalidRecordingFile("Missing recording header")
                                val end = frame.readLong()
                                val duration = frame.readLong()
                                val status = RecordingStatus.valueOf(frame.readUTF())
                                if (duration < elapsed || status !in setOf(RecordingStatus.COMPLETED, RecordingStatus.INTERRUPTED)) {
                                    throw InvalidRecordingFile("Invalid recording footer")
                                }
                                footer = Footer(end, duration, status, frame.readUTF().ifEmpty { null })
                            }
                            else -> throw InvalidRecordingFile("Unsupported sensor frame")
                        }
                        if (frame.available() != 0) throw InvalidRecordingFile("Unexpected frame data")
                    }
                } catch (e: InvalidRecordingFile) { throw e
                } catch (e: Exception) { throw InvalidRecordingFile("Malformed sensor frame: ${e.javaClass.simpleName}") }
                valid = input.filePointer
            }
            if (!header) throw InvalidRecordingFile("Recording header is missing or incomplete")
            if (!allowPartial && footer == null) throw InvalidRecordingFile("Recording file was not safely completed")
            return Inspection(footer, lastTime, elapsed, valid)
        }
    }

    fun recover(recording: RecordingEntity): Footer {
        val inspection = inspect(recording, allowPartial = true)
        inspection.footer?.let { return it }
        val footer = Footer(inspection.lastTime, inspection.elapsed, RecordingStatus.INTERRUPTED,
            "Recording interrupted; recovered complete sensor packets")
        Writer(RandomAccessFile(linkedFile(recording), "rw").apply {
            setLength(inspection.validBytes)
            seek(inspection.validBytes)
        }).use { it.finish(footer) }
        return footer
    }

    fun deleteUploaded(recording: RecordingEntity) {
        check(recording.uploadStatus == UploadStatus.UPLOADED)
        val file = fileFor(recording.id)
        if (recording.localFilePath != file.canonicalPath) return
        if (file.exists() && !file.delete()) throw IOException("Could not clean up uploaded recording")
    }

    class Writer internal constructor(private val file: RandomAccessFile) : AutoCloseable {
        internal fun frame(payload: DataOutputStream.() -> Unit) {
            val bytes = ByteArrayOutputStream().also { buffer -> DataOutputStream(buffer).use(payload) }.toByteArray()
            require(bytes.size <= MAX_FRAME_BYTES)
            if (file.length() + bytes.size + 8 > MAX_FILE_BYTES - 4096 && bytes[0].toInt() != 4) {
                throw IOException("Recording reached the 50 MB storage limit; start another session")
            }
            file.writeInt(bytes.size); file.write(bytes)
            file.writeInt(CRC32().apply { update(bytes) }.value.toInt())
            file.fd.sync()
        }

        fun append(packet: EmgPacket, receivedAt: Long, elapsed: Long) {
            require(packet.samples.size in 1..1024) { "Invalid EMG packet size" }
            frame {
                writeByte(2); writeLong(receivedAt); writeLong(elapsed)
                writeInt(packet.sequence); writeLong(packet.t0Us); writeInt(packet.samples.size)
                packet.samples.forEach { writeLong(it.timestampUs); writeInt(it.channelA) }
            }
        }

        fun append(packet: ImuPacket, receivedAt: Long, elapsed: Long) {
            require(packet.samples.size in 1..1024) { "Invalid IMU packet size" }
            require(packet.samples.all { sample -> with(sample.quaternion) {
                qw.isFinite() && qx.isFinite() && qy.isFinite() && qz.isFinite()
            } }) { "Invalid IMU quaternion" }
            frame {
                writeByte(3); writeLong(receivedAt); writeLong(elapsed)
                writeInt(packet.sequence); writeLong(packet.t0Us); writeInt(packet.samples.size)
                packet.samples.forEach { sample ->
                    writeLong(sample.timestampUs)
                    with(sample.quaternion) { writeFloat(qw); writeFloat(qx); writeFloat(qy); writeFloat(qz) }
                }
            }
        }

        fun finish(footer: Footer) = frame {
            writeByte(4); writeLong(footer.end); writeLong(footer.elapsed)
            writeUTF(footer.status.name); writeUTF(footer.error.orEmpty().take(1000))
        }

        override fun close() = file.close()
    }

    companion object {
        private const val MAX_FRAME_BYTES = 64 * 1024
        private const val MAX_FILE_BYTES = 50L * 1024 * 1024
    }
}
