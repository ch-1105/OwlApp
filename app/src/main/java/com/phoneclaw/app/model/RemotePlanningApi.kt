package com.phoneclaw.app.model

import com.phoneclaw.app.BuildConfig
import com.phoneclaw.app.contracts.ModelErrorKind
import com.phoneclaw.app.contracts.ModelRequest
import com.phoneclaw.app.contracts.ModelResponse
import com.phoneclaw.app.contracts.PlannedActionPayload
import com.phoneclaw.app.contracts.RiskLevel
import com.phoneclaw.app.skills.SkillRegistry
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

enum class ModelApiStyle {
    PHONECLAW_GATEWAY,
    OPENAI_CHAT_COMPLETIONS,
}


private const val TASK_TYPE_SUMMARIZE_WEB_CONTENT = "summarize_web_content"

data class CloudModelConfig(
    val provider: String,
    val baseUrl: String,
    val apiStyle: ModelApiStyle,
    val apiKey: String,
    val modelId: String,
    val connectTimeoutSeconds: Long,
    val readTimeoutSeconds: Long,
) {
    val remoteEnabled: Boolean
        get() = provider.lowercase() != "stub" && baseUrl.isNotBlank() && modelId.isNotBlank()
}

object BuildConfigCloudModelConfig {
    fun fromBuildConfig(): CloudModelConfig {
        return CloudModelConfig(
            provider = BuildConfig.PHONECLAW_MODEL_PROVIDER.ifBlank { "stub" },
            baseUrl = BuildConfig.PHONECLAW_MODEL_BASE_URL.trim(),
            apiStyle = BuildConfig.PHONECLAW_MODEL_API_STYLE.toModelApiStyle(),
            apiKey = BuildConfig.PHONECLAW_MODEL_API_KEY.trim(),
            modelId = BuildConfig.PHONECLAW_MODEL_ID.trim(),
            connectTimeoutSeconds = BuildConfig.PHONECLAW_MODEL_CONNECT_TIMEOUT_SECONDS.toLong(),
            readTimeoutSeconds = BuildConfig.PHONECLAW_MODEL_READ_TIMEOUT_SECONDS.toLong(),
        )
    }
}

class HttpCloudModelAdapter(
    private val config: CloudModelConfig,
    private val skillRegistry: SkillRegistry,
) : CloudModelAdapter {
    private val client = OkHttpClient.Builder()
        .connectTimeout(config.connectTimeoutSeconds, TimeUnit.SECONDS)
        .readTimeout(config.readTimeoutSeconds, TimeUnit.SECONDS)
        .build()

    private val planningSystemPrompt: String = buildPlanningSystemPrompt(skillRegistry)
    private val summarySystemPrompt: String = buildSummarySystemPrompt()

    override suspend fun planAction(request: ModelRequest): ModelResponse {
        if (!config.remoteEnabled || !request.allowCloud) {
            return ModelResponse(
                requestId = request.requestId,
                provider = config.provider.ifBlank { "stub" },
                modelId = config.modelId,
                outputText = "Remote cloud planning is disabled.",
                error = "Remote cloud planning is not configured for this build.",
                errorKind = ModelErrorKind.DISABLED,
            )
        }

        return withContext(Dispatchers.IO) {
            val endpoint = config.endpointPath()
            val requestBody = config.buildRequestBody(request, planningSystemPrompt, summarySystemPrompt).toString()

            val requestBuilder = Request.Builder()
                .url(endpoint)
                .addHeader("Accept", "application/json")
                .addHeader("Content-Type", "application/json")

            if (config.apiKey.isNotBlank()) {
                requestBuilder.addHeader("Authorization", "Bearer ${config.apiKey}")
            }

            val httpRequest = requestBuilder
                .post(requestBody.toRequestBody("application/json; charset=utf-8".toMediaType()))
                .build()

            try {
                client.newCall(httpRequest).execute().use { response ->
                    val responseBody = response.body?.string().orEmpty()
                    val parsed = config.parseSuccessResponse(responseBody, request)

                    if (!response.isSuccessful) {
                        return@withContext ModelResponse(
                            requestId = request.requestId,
                            provider = config.provider,
                            modelId = config.modelId,
                            outputText = parsed?.outputText.orEmpty(),
                            error = responseBody.parseApiErrorMessageOrNull()
                                ?: parsed?.error
                                ?: "Remote planning failed with HTTP ${response.code}.",
                            errorKind = parsed?.errorKind
                                ?: if (response.code in 400..499) ModelErrorKind.PROVIDER_REFUSED else ModelErrorKind.PROVIDER_FAILURE,
                        )
                    }

                    if (parsed == null) {
                        return@withContext ModelResponse(
                            requestId = request.requestId,
                            provider = config.provider,
                            modelId = config.modelId,
                            outputText = responseBody,
                            error = "Remote provider returned an invalid JSON payload.",
                            errorKind = ModelErrorKind.INVALID_RESPONSE,
                        )
                    }

                    ModelResponse(
                        requestId = parsed.requestId.ifBlank { request.requestId },
                        provider = parsed.provider.ifBlank { config.provider },
                        modelId = parsed.modelId.ifBlank { config.modelId },
                        outputText = parsed.outputText,
                        plannedAction = parsed.action,
                        error = parsed.error,
                        errorKind = parsed.errorKind,
                    )
                }
            } catch (error: IOException) {
                ModelResponse(
                    requestId = request.requestId,
                    provider = config.provider,
                    modelId = config.modelId,
                    outputText = "",
                    error = error.message ?: "Network request failed.",
                    errorKind = ModelErrorKind.NETWORK,
                )
            } catch (error: JSONException) {
                ModelResponse(
                    requestId = request.requestId,
                    provider = config.provider,
                    modelId = config.modelId,
                    outputText = "",
                    error = error.message ?: "Response validation failed.",
                    errorKind = ModelErrorKind.INVALID_RESPONSE,
                )
            } catch (error: IllegalArgumentException) {
                ModelResponse(
                    requestId = request.requestId,
                    provider = config.provider,
                    modelId = config.modelId,
                    outputText = "",
                    error = error.message ?: "Response validation failed.",
                    errorKind = ModelErrorKind.INVALID_RESPONSE,
                )
            }
        }
    }
}

class FallbackCloudModelAdapter(
    private val remoteAdapter: CloudModelAdapter,
    private val fallbackAdapter: CloudModelAdapter,
    private val useRemote: Boolean,
) : CloudModelAdapter {
    override suspend fun planAction(request: ModelRequest): ModelResponse {
        if (!useRemote || !request.allowCloud) {
            return fallbackAdapter.planAction(request)
        }

        val remoteResponse = remoteAdapter.planAction(request)
        if (remoteResponse.plannedAction != null) {
            return remoteResponse
        }

        val shouldTryFallback = remoteResponse.error != null || remoteResponse.errorKind != null
        if (!shouldTryFallback) {
            return remoteResponse
        }

        val fallbackResponse = fallbackAdapter.planAction(request)
        return if (request.taskType == TASK_TYPE_SUMMARIZE_WEB_CONTENT && fallbackResponse.error == null) {
            fallbackResponse
        } else if (fallbackResponse.plannedAction != null) {
            fallbackResponse
        } else {
            remoteResponse
        }
    }
}

private data class ParsedRemoteResponse(
    val requestId: String,
    val provider: String,
    val modelId: String,
    val outputText: String,
    val action: PlannedActionPayload?,
    val error: String?,
    val errorKind: ModelErrorKind?,
)

private fun CloudModelConfig.endpointPath(): String {
    val base = baseUrl.trimEnd('/')
    return when (apiStyle) {
        ModelApiStyle.PHONECLAW_GATEWAY -> "$base/v1/plan-action"
        ModelApiStyle.OPENAI_CHAT_COMPLETIONS -> "$base/chat/completions"
    }
}

private fun CloudModelConfig.buildRequestBody(
    request: ModelRequest,
    planningSystemPrompt: String,
    summarySystemPrompt: String,
): JSONObject {
    return when (apiStyle) {
        ModelApiStyle.PHONECLAW_GATEWAY -> JSONObject()
            .put("request_id", request.requestId)
            .put("task_id", request.taskId)
            .put("task_type", request.taskType)
            .put("input_messages", JSONArray(request.inputMessages))
            .put("allow_cloud", request.allowCloud)
            .put("preferred_provider", request.preferredProvider)
            .put("model_id", modelId)

        ModelApiStyle.OPENAI_CHAT_COMPLETIONS -> JSONObject()
            .put("model", modelId)
            .put("messages", JSONArray().apply {
                put(
                    JSONObject()
                        .put("role", "system")
                        .put("content", if (request.taskType == TASK_TYPE_SUMMARIZE_WEB_CONTENT) summarySystemPrompt else planningSystemPrompt),
                )
                put(
                    JSONObject()
                        .put("role", "user")
                        .put("content", request.inputMessages.joinToString("\n")),
                )
            })
            .put("temperature", 0)
            .put("stream", false)
            .put("max_tokens", if (request.taskType == TASK_TYPE_SUMMARIZE_WEB_CONTENT) 768 else 512)
    }
}

private fun CloudModelConfig.parseSuccessResponse(
    responseBody: String,
    request: ModelRequest,
): ParsedRemoteResponse? {
    return when (apiStyle) {
        ModelApiStyle.PHONECLAW_GATEWAY -> responseBody.parseGatewayResponseOrNull()
        ModelApiStyle.OPENAI_CHAT_COMPLETIONS -> responseBody.parseOpenAiChatResponseOrNull(
            provider = provider,
            fallbackModelId = modelId,
            fallbackRequestId = request.requestId,
            taskType = request.taskType,
        )
    }
}

private fun buildPlanningSystemPrompt(skillRegistry: SkillRegistry): String {
    val supportedActions = skillRegistry.allActions().joinToString("\n") { action ->
        val requiredParams = if (action.action.actionId in setOf("open_web_url", "fetch_web_page_content")) {
            "url"
        } else {
            "none"
        }

        """
        - action_id: ${action.action.actionId}
          skill_id: ${action.skill.skillId}
          display_name: ${action.action.displayName}
          description: ${action.action.description}
          risk_level: ${action.action.riskLevel}
          requires_confirmation: ${action.action.requiresConfirmation}
          executor_type: ${action.action.executorType}
          expected_outcome: ${action.action.expectedOutcome}
          required_params: $requiredParams
          example_utterances: ${action.action.exampleUtterances.joinToString(" | ")}
        """.trimIndent()
    }

    return """
        You are the planning model for PhoneClaw.
        Return only a JSON object with no markdown and no explanation.
        Choose exactly one supported action when the user clearly asks for it.
        Supported actions in this build:
        $supportedActions

        Special rules:
        - If the user asks to open a website or URL, use `open_web_url`.
        - If the user asks to fetch, read, extract, or summarize webpage content, use `fetch_web_page_content`.
        - Browser actions must include `params.url`.
        - Preserve the exact URL when the user provides one.
        - If the user provides a bare domain like `openai.com`, normalize it to `https://openai.com`.
        - For non-browser actions, use an empty object for `params`.

        If the user request maps clearly to one supported action, return:
        {
          "action": {
            "action_id": "...",
            "skill_id": "...",
            "intent_summary": "...",
            "params": {},
            "risk_level": "SAFE",
            "requires_confirmation": false,
            "executor_type": "...",
            "expected_outcome": "..."
          }
        }

        For browser actions, `params` should look like:
        {
          "url": "https://example.com"
        }

        Use the exact action_id, skill_id, risk_level, requires_confirmation, executor_type, and expected_outcome from the supported action catalog.
        Generate intent_summary as a short English summary for the chosen action.
        If the request is not supported or ambiguous, return:
        {
          "error": "This build only supports the registered system and browser actions.",
          "error_kind": "PROVIDER_REFUSED"
        }
    """.trimIndent()
}

private fun buildSummarySystemPrompt(): String {
    return """
        You are the summarization model for PhoneClaw.
        Read the provided page content and answer the user request directly.
        Rules:
        - Reply in concise Chinese.
        - Focus on the user question first, then list key points from the page.
        - If page content is insufficient, say what is missing.
        - Return plain text only.
    """.trimIndent()
}
private fun String.parseGatewayResponseOrNull(): ParsedRemoteResponse? {
    if (isBlank()) return null
    return runCatching {
        val json = JSONObject(this)
        ParsedRemoteResponse(
            requestId = json.optString("request_id"),
            provider = json.optString("provider"),
            modelId = json.optString("model_id"),
            outputText = json.optString("output_text"),
            action = json.optJSONObject("action")?.toPlannedActionPayload(),
            error = json.optNullableString("error"),
            errorKind = json.optNullableString("error_kind").toModelErrorKind(),
        )
    }.getOrNull()
}

private fun String.parseOpenAiChatResponseOrNull(
    provider: String,
    fallbackModelId: String,
    fallbackRequestId: String,
    taskType: String,
): ParsedRemoteResponse? {
    if (isBlank()) return null

    return runCatching {
        val root = JSONObject(this)
        val content = root.extractAssistantContent()

        if (taskType == TASK_TYPE_SUMMARIZE_WEB_CONTENT) {
            return@runCatching ParsedRemoteResponse(
                requestId = fallbackRequestId,
                provider = provider,
                modelId = root.optString("model").ifBlank { fallbackModelId },
                outputText = content,
                action = null,
                error = null,
                errorKind = null,
            )
        }

        val contentJson = content.extractJsonObjectOrNull()?.let(::JSONObject)
        ParsedRemoteResponse(
            requestId = fallbackRequestId,
            provider = provider,
            modelId = root.optString("model").ifBlank { fallbackModelId },
            outputText = content,
            action = contentJson?.optJSONObject("action")?.toPlannedActionPayload(),
            error = contentJson?.optNullableString("error")
                ?: if (contentJson == null) "Model did not return JSON content." else null,
            errorKind = contentJson?.optNullableString("error_kind").toModelErrorKind()
                ?: if (contentJson == null) ModelErrorKind.INVALID_RESPONSE else null,
        )
    }.getOrNull()
}

private fun String.parseApiErrorMessageOrNull(): String? {
    if (isBlank()) return null

    return runCatching {
        val root = JSONObject(this)
        when (val error = root.opt("error")) {
            is JSONObject -> error.optString("message").takeIf { it.isNotBlank() }
            is String -> error.takeIf { it.isNotBlank() }
            else -> null
        }
    }.getOrNull()
}

private fun JSONObject.extractAssistantContent(): String {
    val choice = optJSONArray("choices")?.optJSONObject(0) ?: return ""
    val message = choice.optJSONObject("message") ?: return ""
    val content = message.opt("content")

    return when (content) {
        is String -> content
        is JSONArray -> buildString {
            for (index in 0 until content.length()) {
                val part = content.optJSONObject(index) ?: continue
                val text = part.optString("text")
                if (text.isNotBlank()) {
                    append(text)
                }
            }
        }
        else -> ""
    }
}

private fun String.extractJsonObjectOrNull(): String? {
    val cleaned = trim()
        .removePrefix("```json")
        .removePrefix("```")
        .removeSuffix("```")
        .trim()

    if (cleaned.startsWith('{') && cleaned.endsWith('}')) {
        return cleaned
    }

    val start = cleaned.indexOf('{')
    val end = cleaned.lastIndexOf('}')
    return if (start >= 0 && end > start) {
        cleaned.substring(start, end + 1)
    } else {
        null
    }
}

private fun JSONObject.toPlannedActionPayload(): PlannedActionPayload {
    val paramsObject = optJSONObject("params")
    val params = buildMap {
        paramsObject?.keys()?.forEach { key ->
            put(key, paramsObject.optString(key))
        }
    }

    return PlannedActionPayload(
        actionId = getString("action_id"),
        skillId = getString("skill_id"),
        intentSummary = getString("intent_summary"),
        params = params,
        riskLevel = getString("risk_level").toRiskLevel(),
        requiresConfirmation = optBoolean("requires_confirmation", false),
        executorType = getString("executor_type"),
        expectedOutcome = getString("expected_outcome"),
    )
}

private fun JSONObject.optNullableString(key: String): String? {
    return if (isNull(key)) {
        null
    } else {
        optString(key).takeIf { it.isNotBlank() }
    }
}

private fun String.toRiskLevel(): RiskLevel {
    return RiskLevel.entries.firstOrNull { it.name == uppercase() }
        ?: throw IllegalArgumentException("Unsupported risk level: $this")
}

private fun String?.toModelErrorKind(): ModelErrorKind? {
    if (this.isNullOrBlank()) return null
    return ModelErrorKind.entries.firstOrNull { it.name == uppercase() } ?: ModelErrorKind.UNKNOWN
}

private fun String.toModelApiStyle(): ModelApiStyle {
    return when (lowercase()) {
        "phoneclaw-gateway", "gateway", "plan-action" -> ModelApiStyle.PHONECLAW_GATEWAY
        "openai-chat-completions", "openai-completions", "chat-completions" -> ModelApiStyle.OPENAI_CHAT_COMPLETIONS
        else -> throw IllegalArgumentException("Unsupported model API style: $this")
    }
}











