package com.phoneclaw.app.ui

import com.phoneclaw.app.MainDispatcherRule
import com.phoneclaw.app.contracts.TaskSnapshot
import com.phoneclaw.app.contracts.TaskState
import com.phoneclaw.app.gateway.Gateway
import com.phoneclaw.app.learner.ExplorationAgent
import com.phoneclaw.app.learner.ExplorationBudget
import com.phoneclaw.app.learner.ExplorationOutcome
import com.phoneclaw.app.learner.ExplorationProgress
import com.phoneclaw.app.scanner.AppScanner
import com.phoneclaw.app.scanner.InstalledApp
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MainViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun submit_explorationIntent_startsExploration() = runTest {
        val fakeAgent = FakeExplorationAgent()
        val fakeScanner = FakeAppScanner(listOf(
            fakeApp("com.tencent.mm", "微信"),
            fakeApp("com.example.settings", "设置"),
        ))

        val vm = MainViewModel(
            gateway = FakeGateway(),
            explorationAgent = fakeAgent,
            appScanner = fakeScanner,
            workDispatcher = mainDispatcherRule.dispatcher,
        )

        vm.onPromptChange("学习微信")
        vm.submit()
        advanceUntilIdle()

        assertEquals("com.tencent.mm", fakeAgent.lastExploredPackage)
        val messages = vm.uiState.value.messages
        assertTrue(messages.any { it.text.contains("微信") && it.text.contains("学习完成") })
    }

    @Test
    fun submit_explorationIntentWithBrackets_startsExploration() = runTest {
        val fakeAgent = FakeExplorationAgent()
        val fakeScanner = FakeAppScanner(listOf(
            fakeApp("com.example.app", "测试应用"),
        ))

        val vm = MainViewModel(
            gateway = FakeGateway(),
            explorationAgent = fakeAgent,
            appScanner = fakeScanner,
            workDispatcher = mainDispatcherRule.dispatcher,
        )

        vm.onPromptChange("探索「测试应用」")
        vm.submit()
        advanceUntilIdle()

        assertEquals("com.example.app", fakeAgent.lastExploredPackage)
    }

    @Test
    fun submit_noMatchingApp_fallsToGateway() = runTest {
        val fakeAgent = FakeExplorationAgent()
        val fakeScanner = FakeAppScanner(emptyList())
        val fakeGateway = FakeGateway()

        val vm = MainViewModel(
            gateway = fakeGateway,
            explorationAgent = fakeAgent,
            appScanner = fakeScanner,
            workDispatcher = mainDispatcherRule.dispatcher,
        )

        vm.onPromptChange("学习不存在的应用")
        vm.submit()
        advanceUntilIdle()

        assertEquals(null, fakeAgent.lastExploredPackage)
        assertTrue(fakeGateway.submitted)
    }

    @Test
    fun submit_nonExplorationMessage_goesToGateway() = runTest {
        val fakeAgent = FakeExplorationAgent()
        val fakeScanner = FakeAppScanner(listOf(fakeApp("com.test", "Test")))
        val fakeGateway = FakeGateway()

        val vm = MainViewModel(
            gateway = fakeGateway,
            explorationAgent = fakeAgent,
            appScanner = fakeScanner,
            workDispatcher = mainDispatcherRule.dispatcher,
        )

        vm.onPromptChange("打开系统设置")
        vm.submit()
        advanceUntilIdle()

        assertEquals(null, fakeAgent.lastExploredPackage)
        assertTrue(fakeGateway.submitted)
    }

    @Test
    fun submit_explorationFailure_postsErrorMessage() = runTest {
        val fakeAgent = FakeExplorationAgent(shouldFail = true)
        val fakeScanner = FakeAppScanner(listOf(fakeApp("com.test", "测试")))

        val vm = MainViewModel(
            gateway = FakeGateway(),
            explorationAgent = fakeAgent,
            appScanner = fakeScanner,
            workDispatcher = mainDispatcherRule.dispatcher,
        )

        vm.onPromptChange("学习测试")
        vm.submit()
        advanceUntilIdle()

        val messages = vm.uiState.value.messages
        assertTrue(messages.any { it.text.contains("失败") })
    }
}

private class FakeGateway : Gateway {
    var submitted = false

    override suspend fun submitUserMessage(userMessage: String): TaskSnapshot {
        submitted = true
        return TaskSnapshot(
            taskId = "fake-task",
            state = TaskState.SUCCEEDED,
            userMessage = userMessage,
        )
    }

    override suspend fun confirmAction(taskId: String, approved: Boolean): TaskSnapshot {
        return TaskSnapshot(
            taskId = taskId,
            state = if (approved) TaskState.SUCCEEDED else TaskState.CANCELLED,
            userMessage = "",
        )
    }
}

private class FakeExplorationAgent(
    private val shouldFail: Boolean = false,
) : ExplorationAgent {
    var lastExploredPackage: String? = null

    override suspend fun explore(
        appPackage: String,
        appName: String,
        budget: ExplorationBudget,
        onProgress: (ExplorationProgress) -> Unit,
    ): ExplorationOutcome {
        lastExploredPackage = appPackage
        if (shouldFail) throw RuntimeException("探索模拟失败")
        return ExplorationOutcome(
            appPackage = appPackage,
            appName = appName,
            pagesDiscovered = 3,
            transitionsRecorded = 2,
            drafts = emptyList(),
        )
    }
}

private class FakeAppScanner(private val apps: List<InstalledApp>) : AppScanner {
    override fun scanInstalledApps(): List<InstalledApp> = apps
}

private fun fakeApp(packageName: String, appName: String): InstalledApp {
    return InstalledApp(
        packageName = packageName,
        appName = appName,
        versionName = "1.0",
        versionCode = 1L,
        isSystemApp = false,
        iconDrawable = null,
    )
}
