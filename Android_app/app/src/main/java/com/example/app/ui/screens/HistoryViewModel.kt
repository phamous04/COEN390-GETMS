package com.example.app.ui.screens

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.app.data.local.AppDatabase
import com.example.app.data.local.RecordingEntity
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn

class HistoryViewModel(app: Application) : AndroidViewModel(app) {
    val recordings: StateFlow<List<RecordingEntity>> =
        AppDatabase.get(app).recordingDao().observeAll()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
}
