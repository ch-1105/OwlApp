package com.phoneclaw.app.learner

data class ExplorationBudget(
    val maxSteps: Int = 30,
    val maxDepth: Int = 4,
    val maxDurationMs: Long = 120_000L,
)

data class ExplorationProgress(
    val pagesDiscovered: Int,
    val currentPageName: String,
    val transitionsRecorded: Int,
    val stepsUsed: Int,
    val stepsTotal: Int,
    val status: ExplorationStatus,
)

enum class ExplorationStatus {
    LAUNCHING,
    EXPLORING,
    GENERATING,
    COMPLETED,
    FAILED,
}

data class ExplorationOutcome(
    val appPackage: String,
    val appName: String,
    val pagesDiscovered: Int,
    val transitionsRecorded: Int,
    val drafts: List<LearnedSkillDraft>,
)

fun interface AppLauncher {
    fun launch(appPackage: String): Boolean
}

interface ExplorationAgent {
    suspend fun explore(
        appPackage: String,
        appName: String,
        budget: ExplorationBudget = ExplorationBudget(),
        onProgress: (ExplorationProgress) -> Unit = {},
    ): ExplorationOutcome
}
