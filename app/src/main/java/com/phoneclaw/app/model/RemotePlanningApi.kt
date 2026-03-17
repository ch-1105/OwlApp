package com.phoneclaw.app.model

import com.phoneclaw.app.BuildConfig
import com.phoneclaw.app.contracts.ModelErrorKind
import com.phoneclaw.app.contracts.ModelRequest
import com.phoneclaw.app.contracts.ModelResponse
import com.phoneclaw.app.contracts.PlannedActionPayload
import com.phoneclaw.app.contracts.RiskLevel
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
) : CloudModelAdapter {
    private val client = OkHttpClient.Builder()
        .connectTimeout(config.connectTimeoutSeconds, TimeUnit.SECONDS)
        .readTimeout(config.readTimeoutSeconds, TimeUnit.SECONDS)
        .build()

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
            val requestBody = config.buildRequestBody(request).toString()

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
                    val parsed = config.parseSuccessResponse(responseBody, request.requestId)

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
        return if (remoteResponse.errorKind == ModelErrorKind.DISABLED) {
            fallbackAdapter.planAction(request)
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

private fun CloudModelConfig.buildRequestBody(request: ModelRequest): JSONObject {
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
                        .put("content", buildPlanningSystemPrompt()),
                )
                put(
                    JSONObject()
                        .put("role", "user")
                        .put("content", request.inputMessages.joinToString("\n")),
                )
            })
            .put("temperature", 0)
            .put("stream", false)
            .put("max_tokens", 512)
    }
}

private fun CloudModelConfig.parseSuccessResponse(
    responseBody: String,
    fallbackRequestId: String,
): ParsedRemoteResponse? {
    return when (apiStyle) {
        ModelApiStyle.PHONECLAW_GATEWAY -> responseBody.parseGatewayResponseOrNull()
        ModelApiStyle.OPENAI_CHAT_COMPLETIONS -> responseBody.parseOpenAiChatResponseOrNull(
            provider = provider,
            fallbackModelId = modelId,
            fallbackRequestId = fallbackRequestId,
        )
    }
}

private fun buildPlanningSystemPrompt(): String {
    return """
        You are the planning model for PhoneClaw.
        Return only a JSON object with no markdown and no explanation.
        The only supported action in this build is open_system_settings.
        If the user is asking to open Android system settings, return:
        {
          "action": {
            "action_id": "open_system_settings",
            "skill_id": "system.settings",
            "intent_summary": "Open Android system settings",
            "params": {},
            "risk_level": "SAFE",
            "requires_confirmation": false,
            "executor_type": "intent",
            "expected_outcome": "System settings becomes foreground"
          }
        }
        Otherwise return:
        {
          "error": "Only open_system_settings is supported in this build.",
          "error_kind": "PROVIDER_REFUSED"
        }
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
): ParsedRemoteResponse? {
    if (isBlank()) return null

    return runCatching {
        val root = JSONObject(this)
        val content = root.extractAssistantContent()
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
