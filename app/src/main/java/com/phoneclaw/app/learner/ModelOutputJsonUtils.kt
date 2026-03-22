package com.phoneclaw.app.learner

import com.phoneclaw.app.contracts.RiskLevel
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Defensive JSON parsing helpers for model responses.
 *
 * These never throw — they return null or empty on any parse failure.
 * Used by both [AiPageAnalyzer] and [AiSkillLearner] to extract structured
 * data from model output that may be malformed.
 */

internal fun JsonObject.stringOrNull(key: String): String? {
    return runCatching {
        this[key]?.jsonPrimitive?.contentOrNull?.trim()
    }.getOrNull()?.takeIf { it.isNotBlank() }
}

internal fun JsonObject.booleanOrNull(key: String): Boolean? {
    return runCatching {
        this[key]?.jsonPrimitive?.booleanOrNull
    }.getOrNull()
}

internal fun JsonObject.riskLevelOrNull(key: String): RiskLevel? {
    val rawValue = stringOrNull(key) ?: return null
    return RiskLevel.entries.firstOrNull { it.name == rawValue.uppercase() }
}

internal fun JsonObject.stringList(key: String): List<String> {
    return arrayOrEmpty(key)
        .mapNotNull { element ->
            runCatching { element.jsonPrimitive.contentOrNull?.trim() }.getOrNull()
        }
        .filter { it.isNotBlank() }
}

internal fun JsonObject.arrayOrEmpty(key: String): List<JsonElement> {
    return runCatching {
        this[key]?.jsonArray?.toList()
    }.getOrNull().orEmpty()
}

internal fun JsonObject.objectOrNull(key: String): JsonObject? {
    return this[key].asObjectOrNull()
}

internal fun JsonElement?.asObjectOrNull(): JsonObject? {
    return runCatching { this?.jsonObject }.getOrNull()
}

internal fun JsonObject.toStringMap(): Map<String, String> {
    return entries.mapNotNull { (key, value) ->
        val text = runCatching {
            (value as? JsonPrimitive)?.contentOrNull?.trim()
        }.getOrNull() ?: return@mapNotNull null
        key to text
    }.toMap()
}
