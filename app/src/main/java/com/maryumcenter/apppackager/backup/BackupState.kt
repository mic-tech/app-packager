package com.maryumcenter.apppackager.backup

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class BackupProgress(
    val currentIndex: Int,
    val total: Int,
    val label: String,
    val fileName: String,
    val bytesDone: Long,
    val bytesTotal: Long,
) {
    val fraction: Float
        get() = if (bytesTotal <= 0) 0f else (bytesDone.toFloat() / bytesTotal).coerceIn(0f, 1f)
}

data class BackupOutcome(
    val name: String,
    val detail: String,
    val ok: Boolean,
)

/**
 * Progress lives here rather than in a bound-service connection: the work
 * outlives the Activity, and the UI only ever reads it.
 */
object BackupState {
    private val _progress = MutableStateFlow<BackupProgress?>(null)
    val progress: StateFlow<BackupProgress?> = _progress.asStateFlow()

    private val _lastRun = MutableStateFlow<List<BackupOutcome>?>(null)
    val lastRun: StateFlow<List<BackupOutcome>?> = _lastRun.asStateFlow()

    internal fun setProgress(value: BackupProgress?) {
        _progress.value = value
    }

    internal fun beginRun() {
        _lastRun.value = null
    }

    internal fun addOutcome(outcome: BackupOutcome) {
        _lastRun.update { (it ?: emptyList()) + outcome }
    }

    fun clearLastRun() {
        _lastRun.value = null
    }
}
