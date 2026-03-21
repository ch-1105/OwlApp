package com.phoneclaw.app.learner

import com.phoneclaw.app.contracts.PageGraph
import com.phoneclaw.app.contracts.SkillManifest
import com.phoneclaw.app.explorer.PageTreeSnapshot
import com.phoneclaw.app.gateway.ports.PageAnalysisResult
import com.phoneclaw.app.skills.SkillActionBinding

data class LearnedSkillDraft(
    val manifest: SkillManifest,
    val pageGraph: PageGraph,
    val bindings: List<SkillActionBinding>,
    val evidence: List<LearningEvidence>,
)

data class LearningEvidence(
    val pageId: String,
    val snapshotJson: String,
    val screenshotPath: String?,
    val screenshotBytes: ByteArray? = null,
    val arrivedBy: ExplorationTransition? = null,
    val capturedAt: Long,
)

data class PageLearningInput(
    val pageTree: PageTreeSnapshot,
    val analysis: PageAnalysisResult,
    val screenshotPath: String? = null,
    val screenshotBytes: ByteArray? = null,
    val arrivedBy: ExplorationTransition? = null,
    val capturedAt: Long,
)

interface SkillLearner {
    suspend fun generateSkillDraft(
        appPackage: String,
        appName: String,
        pages: List<PageLearningInput>,
    ): LearnedSkillDraft
}
