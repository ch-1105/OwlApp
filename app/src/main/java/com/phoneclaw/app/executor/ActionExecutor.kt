package com.phoneclaw.app.executor

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.phoneclaw.app.contracts.ExecutionRequest
import com.phoneclaw.app.contracts.ExecutionResult
import com.phoneclaw.app.contracts.VerificationResult
import com.phoneclaw.app.skills.SkillRegistry
import com.phoneclaw.app.web.extractHtmlTitle
import com.phoneclaw.app.web.extractReadableText
import com.phoneclaw.app.web.normalizeWebUrl
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

interface ActionExecutor {
    suspend fun execute(request: ExecutionRequest): ExecutionResult
}

class IntentActionExecutor(
    private val appContext: Context,
    private val skillRegistry: SkillRegistry,
) : ActionExecutor {
    private val httpClient = OkHttpClient.Builder()
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    override suspend fun execute(request: ExecutionRequest): ExecutionResult {
        val registeredAction = skillRegistry.findAction(request.actionSpec.actionId)
            ?: return failureResult(
                request = request,
                resultSummary = "Unsupported action",
                errorMessage = "Executor has no registry entry for ${request.actionSpec.actionId}.",
                verificationReason = "No registered action matched the requested action id.",
            )

        return when (registeredAction.action.executorType) {
            "intent" -> launchIntent(
                request = request,
                intentAction = registeredAction.intentAction,
                displayName = registeredAction.action.displayName,
            )

            "browser_intent" -> openBrowserUrl(
                request = request,
                displayName = registeredAction.action.displayName,
            )

            "web_fetch" -> fetchWebPage(
                request = request,
                displayName = registeredAction.action.displayName,
            )

            else -> failureResult(
                request = request,
                resultSummary = "Unsupported executor type",
                errorMessage = "Executor does not support ${registeredAction.action.executorType} in this milestone.",
                verificationReason = "Registered action requires an unsupported executor type.",
            )
        }
    }

    private fun launchIntent(
        request: ExecutionRequest,
        intentAction: String,
        displayName: String,
    ): ExecutionResult {
        return runCatching {
            val intent = Intent(intentAction).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            appContext.startActivity(intent)

            ExecutionResult(
                requestId = request.requestId,
                taskId = request.taskId,
                actionId = request.actionSpec.actionId,
                status = "success",
                resultSummary = "$displayName was launched.",
                verification = VerificationResult(
                    passed = true,
                    reason = "Intent dispatch completed without throwing.",
                ),
            )
        }.getOrElse { error ->
            failureResult(
                request = request,
                resultSummary = "$displayName launch failed.",
                errorMessage = error.message,
                verificationReason = "Intent dispatch raised an exception.",
            )
        }
    }

    private fun openBrowserUrl(
        request: ExecutionRequest,
        displayName: String,
    ): ExecutionResult {
        val normalizedUrl = request.actionSpec.params["url"]?.let(::normalizeWebUrl)
            ?: return failureResult(
                request = request,
                resultSummary = "$displayName failed.",
                errorMessage = "A valid http or https url is required for browser actions.",
                verificationReason = "The action parameters did not include a valid url.",
            )

        return runCatching {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(normalizedUrl)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            appContext.startActivity(intent)

            ExecutionResult(
                requestId = request.requestId,
                taskId = request.taskId,
                actionId = request.actionSpec.actionId,
                status = "success",
                resultSummary = "$displayName opened $normalizedUrl.",
                outputData = mapOf(
                    "opened_url" to normalizedUrl,
                ),
                verification = VerificationResult(
                    passed = true,
                    reason = "Browser intent dispatch completed without throwing.",
                ),
            )
        }.getOrElse { error ->
            failureResult(
                request = request,
                resultSummary = "$displayName failed.",
                errorMessage = error.message,
                verificationReason = "Browser intent dispatch raised an exception.",
            )
        }
    }

    private suspend fun fetchWebPage(
        request: ExecutionRequest,
        displayName: String,
    ): ExecutionResult {
        val normalizedUrl = request.actionSpec.params["url"]?.let(::normalizeWebUrl)
            ?: return failureResult(
                request = request,
                resultSummary = "$displayName failed.",
                errorMessage = "A valid http or https url is required for web fetch actions.",
                verificationReason = "The action parameters did not include a valid url.",
            )

        return withContext(Dispatchers.IO) {
            runCatching {
                val httpRequest = Request.Builder()
                    .url(normalizedUrl)
                    .header("User-Agent", "PhoneClaw/0.1")
                    .get()
                    .build()

                httpClient.newCall(httpRequest).execute().use { response ->
                    val responseBody = response.body?.string().orEmpty()
                    if (!response.isSuccessful) {
                        return@use failureResult(
                            request = request,
                            resultSummary = "$displayName failed.",
                            errorMessage = "HTTP ${response.code} while fetching $normalizedUrl.",
                            verificationReason = "The target page did not return a successful HTTP response.",
                        )
                    }

                    val title = extractHtmlTitle(responseBody)
                    val readableText = extractReadableText(responseBody).ifBlank {
                        "No readable text could be extracted from the page."
                    }

                    ExecutionResult(
                        requestId = request.requestId,
                        taskId = request.taskId,
                        actionId = request.actionSpec.actionId,
                        status = "success",
                        resultSummary = title?.let { "Fetched $it." } ?: "Fetched web page content from $normalizedUrl.",
                        outputData = buildMap {
                            put("page_url", normalizedUrl)
                            title?.let { put("page_title", it) }
                            put("page_content", readableText)
                        },
                        verification = VerificationResult(
                            passed = true,
                            reason = "HTTP fetch completed and content was parsed.",
                        ),
                    )
                }
            }.getOrElse { error ->
                failureResult(
                    request = request,
                    resultSummary = "$displayName failed.",
                    errorMessage = error.message,
                    verificationReason = "The page fetch raised an exception.",
                )
            }
        }
    }

    private fun failureResult(
        request: ExecutionRequest,
        resultSummary: String,
        errorMessage: String?,
        verificationReason: String,
    ): ExecutionResult {
        return ExecutionResult(
            requestId = request.requestId,
            taskId = request.taskId,
            actionId = request.actionSpec.actionId,
            status = "failed",
            resultSummary = resultSummary,
            errorMessage = errorMessage,
            verification = VerificationResult(
                passed = false,
                reason = verificationReason,
            ),
        )
    }
}
