package com.maryumcenter.apppackager.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.maryumcenter.apppackager.data.AppRepository
import com.maryumcenter.apppackager.data.InstalledApp
import com.maryumcenter.apppackager.data.Prefs
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.launch

class AppListViewModel(application: Application) : AndroidViewModel(application) {

    private val repo = AppRepository(application)
    private val prefs = Prefs(application)

    private val _apps = MutableStateFlow<List<InstalledApp>>(emptyList())
    private val _loading = MutableStateFlow(true)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    private val _selected = MutableStateFlow<Set<String>>(emptySet())
    val selected: StateFlow<Set<String>> = _selected.asStateFlow()

    private val _showSystem = MutableStateFlow(prefs.showSystemApps)
    val showSystem: StateFlow<Boolean> = _showSystem.asStateFlow()

    private val _includeObb = MutableStateFlow(prefs.includeObb)
    val includeObb: StateFlow<Boolean> = _includeObb.asStateFlow()

    private val _forceXapk = MutableStateFlow(prefs.forceXapk)
    val forceXapk: StateFlow<Boolean> = _forceXapk.asStateFlow()

    private val _destination = MutableStateFlow(prefs.destinationUri?.let(Uri::parse))
    val destination: StateFlow<Uri?> = _destination.asStateFlow()

    /** Search and the system-app switch are the only filters, applied in that order. */
    val visibleApps: StateFlow<List<InstalledApp>> =
        combine(_apps, _query, _showSystem) { apps, query, showSystem ->
            val needle = query.trim().lowercase()
            apps.asSequence()
                // An updated system app (e.g. a Play-updated browser) has a real
                // APK worth backing up, so it stays visible either way.
                .filter { showSystem || !it.isSystem || it.isUpdatedSystem }
                .filter {
                    needle.isEmpty() ||
                        it.label.lowercase().contains(needle) ||
                        it.packageName.lowercase().contains(needle)
                }
                .toList()
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Approximate download size of the current selection, for the bottom bar. */
    val selectedBytes: StateFlow<Long> =
        combine(_apps, _selected) { apps, selected ->
            apps.filter { it.packageName in selected }.sumOf { it.apkSize }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0L)

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            _loading.value = true
            _apps.value = repo.loadInstalledApps()
            // Drop selections for anything that disappeared while we were away.
            val known = _apps.value.mapTo(mutableSetOf()) { it.packageName }
            _selected.value = _selected.value.intersect(known)
            _loading.value = false
        }
    }

    fun setQuery(value: String) {
        _query.value = value
    }

    fun toggle(packageName: String) {
        _selected.value = _selected.value.let {
            if (packageName in it) it - packageName else it + packageName
        }
    }

    fun selectAllVisible() {
        _selected.value = _selected.value + visibleApps.value.map { it.packageName }
    }

    fun clearSelection() {
        _selected.value = emptySet()
    }

    fun setShowSystem(value: Boolean) {
        _showSystem.value = value
        prefs.showSystemApps = value
    }

    fun setIncludeObb(value: Boolean) {
        _includeObb.value = value
        prefs.includeObb = value
    }

    fun setForceXapk(value: Boolean) {
        _forceXapk.value = value
        prefs.forceXapk = value
    }

    fun setDestination(uri: Uri) {
        _destination.value = uri
        prefs.destinationUri = uri.toString()
    }

    /** Selection in the order shown, so the queue matches what the user sees. */
    fun selectedInOrder(): List<String> =
        visibleApps.value.map { it.packageName }.filter { it in _selected.value }
            .ifEmpty { _selected.value.toList() }
}
