package com.example.app.ui.screens

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.app.data.local.AppDatabase
import com.example.app.data.local.RecordingEntity
import com.example.app.data.remote.AccountRepository
import com.example.app.data.remote.SupabaseProvider
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn

@OptIn(ExperimentalCoroutinesApi::class)
class HistoryViewModel(app: Application) : AndroidViewModel(app) {
    val recordings: StateFlow<List<RecordingEntity>> =
        flow {
            if (!SupabaseProvider.configured) emit(emptyList()) else {
                SupabaseProvider.auth.awaitInitialization()
                emitAll(AccountRepository().sessionStatus.map { AccountRepository().userId }
                    .distinctUntilChanged().flatMapLatest { userId ->
                        if (userId == null) flowOf(emptyList())
                        else AppDatabase.get(app).recordingDao().observeForUser(userId)
                    })
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
}
