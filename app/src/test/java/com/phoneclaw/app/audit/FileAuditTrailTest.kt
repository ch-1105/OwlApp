package com.phoneclaw.app.audit

import com.phoneclaw.app.contracts.ExecutionResult
import com.phoneclaw.app.contracts.VerificationResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class FileAuditTrailTest {
    @Test
    fun recordTaskEvent_writesReadableAuditLine() {
        val auditDir = Files.createTempDirectory("phoneclaw-audit").toFile()

        try {
            val trail = FileAuditTrail(
                auditRootDir = auditDir,
                clock = Clock.fixed(Instant.parse("2026-03-18T01:02:03Z"), ZoneOffset.UTC),
            )

            trail.recordTaskEvent(
                taskId = "task-1",
                traceId = "trace-1",
                eventType = "state_changed",
                payload = mapOf(
                    "state" to "PLANNING",
                    "note" to "line1\nline2",
                    "quote" to "\"hello\"",
                ),
            )

            val lines = auditDir.resolve("task-1.log").readLines()

            assertEquals(1, lines.size)
            assertTrue(lines.single().contains("timestamp=\"2026-03-18T01:02:03Z\""))
            assertTrue(lines.single().contains("task_id=\"task-1\""))
            assertTrue(lines.single().contains("trace_id=\"trace-1\""))
            assertTrue(lines.single().contains("category=\"task_event\""))
            assertTrue(lines.single().contains("event_type=\"state_changed\""))
            assertTrue(lines.single().contains("note=\"line1\\nline2\""))
            assertTrue(lines.single().contains("quote=\"\\\"hello\\\"\""))
        } finally {
            auditDir.deleteRecursively()
        }
    }

    @Test
    fun recordExecutionTrace_truncatesLargePayloadValues() {
        val auditDir = Files.createTempDirectory("phoneclaw-audit").toFile()

        try {
            val trail = FileAuditTrail(
                auditRootDir = auditDir,
                clock = Clock.fixed(Instant.parse("2026-03-18T01:02:03Z"), ZoneOffset.UTC),
            )

            trail.recordExecutionTrace(
                taskId = "task-2",
                traceId = "trace-2",
                result = ExecutionResult(
                    requestId = "req-2",
                    taskId = "task-2",
                    actionId = "fetch_web_page_content",
                    status = "success",
                    resultSummary = "Fetched page content",
                    outputData = mapOf("page_content" to "a".repeat(2_100)),
                    verification = VerificationResult(
                        passed = true,
                        reason = "Fetched successfully",
                    ),
                ),
            )

            val line = auditDir.resolve("task-2.log").readText()

            assertTrue(line.contains("category=\"execution_trace\""))
            assertTrue(line.contains("output_page_content=\""))
            assertTrue(line.contains("...(truncated)"))
        } finally {
            auditDir.deleteRecursively()
        }
    }
}
