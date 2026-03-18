package com.phoneclaw.app.skills

import com.phoneclaw.app.contracts.RiskLevel
import com.phoneclaw.app.contracts.SkillActionManifest
import com.phoneclaw.app.contracts.SkillManifest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

internal fun parseSkillPackageDocument(jsonText: String, source: String): SkillPackageDefinition {
    val root = Json.parseToJsonElement(jsonText).jsonObject
    val manifest = root.requireObject("skill", source).toSkillManifest(source)
    val bindings = root.requireArray("action_bindings", source)
        .mapIndexed { index, bindingElement ->
            bindingElement.jsonObject.toSkillActionBinding("$source action_bindings[$index]")
        }

    return validateSkillPackage(
        manifest = manifest,
        bindings = bindings,
        source = source,
    )
}

internal fun parseSkillManifestJson(jsonText: String, source: String): SkillManifest {
    val jsonObject = Json.parseToJsonElement(jsonText).jsonObject
    return jsonObject.toSkillManifest(source)
}

internal fun parseSkillBindingsJson(jsonText: String, source: String): List<SkillActionBinding> {
    return Json.parseToJsonElement(jsonText)
        .jsonArray
        .mapIndexed { index, bindingElement ->
            bindingElement.jsonObject.toSkillActionBinding("$source[$index]")
        }
}

fun encodeSkillManifestJson(manifest: SkillManifest): String {
    return manifest.toJsonObject().toString()
}

fun encodeSkillBindingsJson(bindings: List<SkillActionBinding>): String {
    return JsonArray(bindings.map { it.toJsonObject() }).toString()
}

private fun JsonObject.toSkillManifest(source: String): SkillManifest {
    return SkillManifest(
        schemaVersion = requireString("schema_version", source),
        skillId = requireString("skill_id", source),
        skillVersion = requireString("skill_version", source),
        skillType = requireString("skill_type", source),
        displayName = requireString("display_name", source),
        owner = requireString("owner", source),
        platform = requireString("platform", source),
        appPackage = optionalString("app_package"),
        defaultRiskLevel = requireRiskLevel("default_risk_level", source),
        enabled = requireBoolean("enabled", source),
        actions = requireArray("actions", source).mapIndexed { index, actionElement ->
            actionElement.jsonObject.toSkillActionManifest("$source actions[$index]")
        },
    )
}

private fun JsonObject.toSkillActionManifest(source: String): SkillActionManifest {
    return SkillActionManifest(
        actionId = requireString("action_id", source),
        displayName = requireString("display_name", source),
        description = requireString("description", source),
        executorType = requireString("executor_type", source),
        riskLevel = requireRiskLevel("risk_level", source),
        requiresConfirmation = requireBoolean("requires_confirmation", source),
        expectedOutcome = requireString("expected_outcome", source),
        enabled = optionalBoolean("enabled") ?: true,
        exampleUtterances = optionalStringList("example_utterances"),
        matchKeywords = optionalStringList("match_keywords"),
    )
}

private fun JsonObject.toSkillActionBinding(source: String): SkillActionBinding {
    return SkillActionBinding(
        actionId = requireString("action_id", source),
        intentAction = optionalString("intent_action").orEmpty(),
    )
}

private fun SkillManifest.toJsonObject(): JsonObject {
    return buildJsonObject {
        put("schema_version", JsonPrimitive(schemaVersion))
        put("skill_id", JsonPrimitive(skillId))
        put("skill_version", JsonPrimitive(skillVersion))
        put("skill_type", JsonPrimitive(skillType))
        put("display_name", JsonPrimitive(displayName))
        put("owner", JsonPrimitive(owner))
        put("platform", JsonPrimitive(platform))
        if (appPackage != null) {
            put("app_package", JsonPrimitive(appPackage))
        }
        put("default_risk_level", JsonPrimitive(defaultRiskLevel.name.lowercase()))
        put("enabled", JsonPrimitive(enabled))
        put("actions", JsonArray(actions.map { it.toJsonObject() }))
    }
}

private fun SkillActionManifest.toJsonObject(): JsonObject {
    return buildJsonObject {
        put("action_id", JsonPrimitive(actionId))
        put("display_name", JsonPrimitive(displayName))
        put("description", JsonPrimitive(description))
        put("executor_type", JsonPrimitive(executorType))
        put("risk_level", JsonPrimitive(riskLevel.name.lowercase()))
        put("requires_confirmation", JsonPrimitive(requiresConfirmation))
        put("expected_outcome", JsonPrimitive(expectedOutcome))
        put("enabled", JsonPrimitive(enabled))
        put("example_utterances", JsonArray(exampleUtterances.map(::JsonPrimitive)))
        put("match_keywords", JsonArray(matchKeywords.map(::JsonPrimitive)))
    }
}

private fun SkillActionBinding.toJsonObject(): JsonObject {
    return buildJsonObject {
        put("action_id", JsonPrimitive(actionId))
        put("intent_action", JsonPrimitive(intentAction))
    }
}

private fun JsonObject.requireObject(fieldName: String, source: String): JsonObject {
    return this[fieldName]?.jsonObject
        ?: error("$source is missing object field `$fieldName`.")
}

private fun JsonObject.requireArray(fieldName: String, source: String): JsonArray {
    return this[fieldName]?.jsonArray
        ?: error("$source is missing array field `$fieldName`.")
}

private fun JsonObject.requireString(fieldName: String, source: String): String {
    return this[fieldName]?.jsonPrimitive?.contentOrNull
        ?: error("$source is missing string field `$fieldName`.")
}

private fun JsonObject.optionalString(fieldName: String): String? {
    return this[fieldName]?.jsonPrimitive?.contentOrNull
}

private fun JsonObject.requireBoolean(fieldName: String, source: String): Boolean {
    return this[fieldName]?.jsonPrimitive?.booleanOrNull
        ?: error("$source is missing boolean field `$fieldName`.")
}

private fun JsonObject.optionalBoolean(fieldName: String): Boolean? {
    return this[fieldName]?.jsonPrimitive?.booleanOrNull
}

private fun JsonObject.optionalStringList(fieldName: String): List<String> {
    return this[fieldName]
        ?.jsonArray
        ?.mapIndexed { index, element ->
            element.requireStringValue("$fieldName[$index]")
        }
        .orEmpty()
}

private fun JsonObject.requireRiskLevel(fieldName: String, source: String): RiskLevel {
    val rawValue = requireString(fieldName, source)
    return runCatching {
        RiskLevel.valueOf(rawValue.uppercase())
    }.getOrElse {
        error("$source has unsupported risk level `$rawValue` in `$fieldName`.")
    }
}

private fun JsonElement.requireStringValue(source: String): String {
    return jsonPrimitive.contentOrNull
        ?: error("$source must be a string value.")
}
