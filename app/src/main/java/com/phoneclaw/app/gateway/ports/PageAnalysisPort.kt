package com.phoneclaw.app.gateway.ports

import com.phoneclaw.app.contracts.PageSpec
import com.phoneclaw.app.explorer.PageTreeSnapshot

data class PageAnalysisResult(
    val suggestedPageSpec: PageSpec,
    val clickableElements: List<ClickableElementSuggestion>,
    val navigationHints: List<String>,
)

data class ClickableElementSuggestion(
    val resourceId: String?,
    val text: String?,
    val contentDescription: String?,
    val suggestedActionName: String,
    val suggestedDescription: String,
)

interface PageAnalysisPort {
    suspend fun analyzePage(
        appPackage: String,
        pageTree: PageTreeSnapshot,
        screenshot: ByteArray? = null,
    ): PageAnalysisResult
}
