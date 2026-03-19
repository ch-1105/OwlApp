package com.phoneclaw.app.ui.apps

import com.phoneclaw.app.MainDispatcherRule
import com.phoneclaw.app.data.db.AuthorizedAppEntity
import com.phoneclaw.app.scanner.AppScanner
import com.phoneclaw.app.scanner.AuthorizationManager
import com.phoneclaw.app.scanner.InstalledApp
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AppsViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun initialLoad_mergesAuthorizedStateIntoDisplayedApps() = runTest {
        val authorizationManager = FakeAuthorizationManager(
            mutableMapOf(
                "com.example.clock" to AuthorizedAppEntity(
                    packageName = "com.example.clock",
                    appName = "Clock",
                    authorized = true,
                    authorizedAt = 1L,
                ),
            ),
        )
        val viewModel = AppsViewModel(
            appScanner = FakeAppScanner(
                listOf(
                    installedApp(packageName = "com.example.clock", appName = "Clock"),
                    installedApp(packageName = "com.example.notes", appName = "Notes"),
                ),
            ),
            authorizationManager = authorizationManager,
            workDispatcher = mainDispatcherRule.dispatcher,
        )

        advanceUntilIdle()

        val apps = viewModel.uiState.value.apps
        assertFalse(viewModel.uiState.value.isLoading)
        assertEquals(listOf("com.example.clock", "com.example.notes"), apps.map { it.packageName })
        assertTrue(apps.first { it.packageName == "com.example.clock" }.authorized)
        assertFalse(apps.first { it.packageName == "com.example.notes" }.authorized)
    }

    @Test
    fun searchAndAuthorizationToggle_updateVisibleState() = runTest {
        val authorizationManager = FakeAuthorizationManager()
        val viewModel = AppsViewModel(
            appScanner = FakeAppScanner(
                listOf(
                    installedApp(packageName = "com.example.clock", appName = "Clock"),
                    installedApp(packageName = "com.example.notes", appName = "Notes"),
                ),
            ),
            authorizationManager = authorizationManager,
            workDispatcher = mainDispatcherRule.dispatcher,
        )

        advanceUntilIdle()
        viewModel.onSearchQueryChange("note")

        assertEquals(listOf("com.example.notes"), viewModel.uiState.value.apps.map { it.packageName })

        val notesApp = viewModel.uiState.value.apps.single()
        viewModel.onAuthorizationToggle(notesApp, authorized = true)
        advanceUntilIdle()

        assertTrue(authorizationManager.isAuthorized("com.example.notes"))
        assertTrue(viewModel.uiState.value.apps.single().authorized)
    }
}

private class FakeAppScanner(
    private val apps: List<InstalledApp>,
) : AppScanner {
    override fun scanInstalledApps(): List<InstalledApp> = apps
}

private class FakeAuthorizationManager(
    private val appsByPackage: MutableMap<String, AuthorizedAppEntity> = mutableMapOf(),
) : AuthorizationManager {
    override fun getAuthorizedApps(): List<AuthorizedAppEntity> {
        return appsByPackage.values.filter { it.authorized }.sortedBy { it.appName }
    }

    override fun authorizeApp(packageName: String, appName: String) {
        appsByPackage[packageName] = AuthorizedAppEntity(
            packageName = packageName,
            appName = appName,
            authorized = true,
            authorizedAt = 1L,
        )
    }

    override fun revokeApp(packageName: String) {
        val existing = appsByPackage[packageName] ?: return
        appsByPackage[packageName] = existing.copy(
            authorized = false,
            authorizedAt = 2L,
        )
    }

    override fun isAuthorized(packageName: String): Boolean {
        return appsByPackage[packageName]?.authorized == true
    }
}

private fun installedApp(
    packageName: String,
    appName: String,
): InstalledApp {
    return InstalledApp(
        packageName = packageName,
        appName = appName,
        versionName = "1.0.0",
        versionCode = 1L,
        isSystemApp = false,
        iconDrawable = null,
    )
}
