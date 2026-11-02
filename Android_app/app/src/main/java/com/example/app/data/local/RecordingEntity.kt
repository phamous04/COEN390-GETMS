package com.example.app.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

/** Local copy of a recording; `synced` is flipped once it is uploaded to Supabase. */
@Entity(tableName = "recordings")
data class RecordingEntity(
    @PrimaryKey val id: String,
    val exercise: String,
    val arm: String,
    val startedAtMillis: Long,
    val synced: Boolean = false,
)
