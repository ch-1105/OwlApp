package com.phoneclaw.app.ui.apps

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.phoneclaw.app.scanner.AppScanner
import com.phoneclaw.app.scanner.AuthorizationManager
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class AppsUiState(
    val searchQuery: String = "",
    val apps: List<AppDisplayItem> = emptyList(),
    val isLoading: Boolean = true,
)

data class AppDisplayItem(
    val packageName: String,
    val appName: String,
    val versionName: String?,
    val isSystemApp: Boolean,
    val authorized: Boolean,
)

class AppsViewModel(
    private val appScanner: AppScanner,
    private val authorizationManager: AuthorizationManager,
    private val workDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {
    private val _uiState = MutableStateFlow(AppsUiState())
    val uiState: StateFlow<AppsUiState> = _uiState.asStateFlow()

    private var allApps: List<AppDisplayItem> = emptyList()

    init {
        refreshApps()
    }

    fun onSearchQueryChange(value: String) {
        _uiState.update { current ->
            current.copy(
                searchQuery = value,
                apps = filterApps(allApps, value),
            )
        }
    }

    fun onAuthorizationToggle(app: AppDisplayItem, authorized: Boolean) {
        viewModelScope.launch {
            _uiState.update { current -> current.copy(isLoading = true) }
            withContext(workDispatcher) {
                if (authorized) {
                    authorizationManager.authorizeApp(app.packageName, app.appName)
                } else {
                    authorizationManager.revokeApp(app.packageName)
                }
            }
            loadApps()
        }
    }

    fun refreshApps() {
        viewModelScope.launch {
            _uiState.update { current -> current.copy(isLoading = true) }
            loadApps()
        }
    }

    private suspend fun loadApps() {
        val loadedApps = withContext(workDispatcher) {
            val authorizedPackages = authorizationManager.getAuthorizedApps()
                .asSequence()
                .filter { it.authorized }
                .map { it.packageName }
                .toSet()

            appScanner.scanInstalledApps().map { installedApp ->
                AppDisplayItem(
                    packageName = installedApp.packageName,
                    appName = installedApp.appName,
                    versionName = installedApp.versionName,
                    isSystemApp = installedApp.isSystemApp,
                    authorized = installedApp.packageName in authorizedPackages,
                )
            }
        }

        allApps = loadedApps
        _uiState.update { current ->
            current.copy(
                apps = filterApps(loadedApps, current.searchQuery),
                isLoading = false,
            )
        }
    }
}

private fun filterApps(apps: List<AppDisplayItem>, query: String): List<AppDisplayItem> {
    val normalizedQuery = query.trim().lowercase()
    if (normalizedQuery.isBlank()) {
        return apps
    }

    return apps.filter { app ->
        app.appName.lowercase().contains(normalizedQuery) ||
            app.packageName.lowercase().contains(normalizedQuery)
    }
}

class AppsViewModelFactory(
    private val appScanner: AppScanner,
    private val authorizationManager: AuthorizationManager,
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(AppsViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return AppsViewModel(appScanner, authorizationManager) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
    }
}
