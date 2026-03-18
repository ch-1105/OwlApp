package com.phoneclaw.app.gateway.ports

interface SummaryPort {
    suspend fun summarize(taskId: String, userMessage: String, content: Map<String, String>): String?
}
