package com.phoneclaw.app.skills

import com.phoneclaw.app.contracts.PageGraph
import com.phoneclaw.app.contracts.PageMatchRule
import com.phoneclaw.app.contracts.PageSpec
import com.phoneclaw.app.contracts.PageTransition
import com.phoneclaw.app.contracts.RiskLevel
import com.phoneclaw.app.contracts.SkillActionManifest
import com.phoneclaw.app.contracts.SkillManifest
import com.phoneclaw.app.learner.ExplorationTransition
import com.phoneclaw.app.learner.LearningEvidence
import java.util.Base64
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
import kotlinx.serialization.json.longOrNull

internal fun parseSkillPackageDocument(jsonText: String, source: String): SkillPackageDefinition {
    val root = Json.parseToJsonElement(jsonText).jsonObject
    val manifest = root.requireObject("skill", source).toSkillManifest(source)
    val bindings = root.requireArray("action_bindings", source)
        .mapIndexed { index, bindingElement ->
            bindingElement.jsonObject.toSkillActionBinding("$source action_bindings[$index]")
        }
    val pageGraph = root.optionalObject("page_graph")?.toPageGraph("$source page_graph")
    val evidence = root.optionalArray("evidence")
        ?.mapIndexed { index, evidenceElement ->
            evidenceElement.jsonObject.toLearningEvidence("$source evidence[$index]")
        }
        .orEmpty()

    return validateSkillPackage(
        manifest = manifest,
        bindings = bindings,
        source = source,
        pageGraph = pageGraph,
        evidence = evidence,
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

internal fun parsePageGraphJson(jsonText: String, source: String): PageGraph {
    return Json.parseToJsonElement(jsonText).jsonObject.toPageGraph(source)
}

internal fun parseLearningEvidenceJson(jsonText: String, source: String): List<LearningEvidence> {
    return Json.parseToJsonElement(jsonText)
        .jsonArray
        .mapIndexed { index, evidenceElement ->
            evidenceElement.jsonObject.toLearningEvidence("$source[$index]")
        }
}

fun encodeSkillManifestJson(manifest: SkillManifest): String {
    return manifest.toJsonObject().toString()
}

fun encodeSkillBindingsJson(bindings: List<SkillActionBinding>): String {
    return JsonArray(bindings.map { it.toJsonObject() }).toString()
}

fun encodePageGraphJson(pageGraph: PageGraph): String {
    return pageGraph.toJsonObject().toString()
}

fun encodeLearningEvidenceJson(evidence: List<LearningEvidence>): String {
    return JsonArray(evidence.map { item -> item.toJsonObject() }).toString()
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

private fun JsonObject.toPageGraph(source: String): PageGraph {
    return PageGraph(
        schemaVersion = requireString("schema_version", source),
        appPackage = requireString("app_package", source),
        pages = requireArray("pages", source).mapIndexed { index, pageElement ->
            pageElement.jsonObject.toPageSpec("$source pages[$index]")
        },
        transitions = requireArray("transitions", source).mapIndexed { index, transitionElement ->
            transitionElement.jsonObject.toPageTransition("$source transitions[$index]")
        },
    )
}

private fun JsonObject.toPageSpec(source: String): PageSpec {
    return PageSpec(
        schemaVersion = requireString("schema_version", source),
        pageId = requireString("page_id", source),
        pageName = requireString("page_name", source),
        appPackage = requireString("app_package", source),
        activityName = optionalString("activity_name"),
        matchRules = requireArray("match_rules", source).mapIndexed { index, ruleElement ->
            ruleElement.jsonObject.toPageMatchRule("$source match_rules[$index]")
        },
        availableActions = requireArray("available_actions", source).mapIndexed { index, actionElement ->
            actionElement.requireStringValue("$source available_actions[$index]")
        },
        evidenceFields = optionalStringMap("evidence_fields"),
    )
}

private fun JsonObject.toPageMatchRule(source: String): PageMatchRule {
    return PageMatchRule(
        type = requireString("type", source),
        value = requireString("value", source),
    )
}

private fun JsonObject.toPageTransition(source: String): PageTransition {
    return PageTransition(
        fromPageId = requireString("from_page_id", source),
        toPageId = requireString("to_page_id", source),
        triggerActionId = requireString("trigger_action_id", source),
        triggerNodeDescription = requireString("trigger_node_description", source),
    )
}

private fun JsonObject.toLearningEvidence(source: String): LearningEvidence {
    val screenshotBase64 = optionalString("screenshot_base64")
    val screenshotBytes = screenshotBase64?.let { encodedBytes ->
        runCatching {
            Base64.getDecoder().decode(encodedBytes)
        }.getOrElse {
            error("$source has invalid screenshot_base64.")
        }
    }

    return LearningEvidence(
        pageId = requireString("page_id", source),
        snapshotJson = requireString("snapshot_json", source),
        screenshotPath = optionalString("screenshot_path"),
        screenshotBytes = screenshotBytes,
        arrivedBy = optionalObject("arrived_by")?.toExplorationTransition("$source arrived_by"),
        capturedAt = requireLong("captured_at", source),
    )
}

private fun JsonObject.toExplorationTransition(source: String): ExplorationTransition {
    return ExplorationTransition(
        fromPageId = requireString("from_page_id", source),
        toPageId = requireString("to_page_id", source),
        triggerNodeId = requireString("trigger_node_id", source),
        triggerNodeDescription = requireString("trigger_node_description", source),
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

private fun PageGraph.toJsonObject(): JsonObject {
    return buildJsonObject {
        put("schema_version", JsonPrimitive(schemaVersion))
        put("app_package", JsonPrimitive(appPackage))
        put("pages", JsonArray(pages.map { page -> page.toJsonObject() }))
        put("transitions", JsonArray(transitions.map { transition -> transition.toJsonObject() }))
    }
}

private fun PageSpec.toJsonObject(): JsonObject {
    return buildJsonObject {
        put("schema_version", JsonPrimitive(schemaVersion))
        put("page_id", JsonPrimitive(pageId))
        put("page_name", JsonPrimitive(pageName))
        put("app_package", JsonPrimitive(appPackage))
        if (activityName != null) {
            put("activity_name", JsonPrimitive(activityName))
        }
        put("match_rules", JsonArray(matchRules.map { rule -> rule.toJsonObject() }))
        put("available_actions", JsonArray(availableActions.map(::JsonPrimitive)))
        put(
            "evidence_fields",
            buildJsonObject {
                evidenceFields.forEach { (key, value) ->
                    put(key, JsonPrimitive(value))
                }
            },
        )
    }
}

private fun PageMatchRule.toJsonObject(): JsonObject {
    return buildJsonObject {
        put("type", JsonPrimitive(type))
        put("value", JsonPrimitive(value))
    }
}

private fun PageTransition.toJsonObject(): JsonObject {
    return buildJsonObject {
        put("from_page_id", JsonPrimitive(fromPageId))
        put("to_page_id", JsonPrimitive(toPageId))
        put("trigger_action_id", JsonPrimitive(triggerActionId))
        put("trigger_node_description", JsonPrimitive(triggerNodeDescription))
    }
}

private fun LearningEvidence.toJsonObject(): JsonObject {
    return buildJsonObject {
        put("page_id", JsonPrimitive(pageId))
        put("snapshot_json", JsonPrimitive(snapshotJson))
        put("captured_at", JsonPrimitive(capturedAt))
        if (screenshotPath != null) {
            put("screenshot_path", JsonPrimitive(screenshotPath))
        }
        if (screenshotBytes != null) {
            put("screenshot_base64", JsonPrimitive(Base64.getEncoder().encodeToString(screenshotBytes)))
        }
        if (arrivedBy != null) {
            put("arrived_by", arrivedBy.toJsonObject())
        }
    }
}

private fun ExplorationTransition.toJsonObject(): JsonObject {
    return buildJsonObject {
        put("from_page_id", JsonPrimitive(fromPageId))
        put("to_page_id", JsonPrimitive(toPageId))
        put("trigger_node_id", JsonPrimitive(triggerNodeId))
        put("trigger_node_description", JsonPrimitive(triggerNodeDescription))
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

private fun JsonObject.optionalObject(fieldName: String): JsonObject? {
    return this[fieldName]?.jsonObject
}

private fun JsonObject.optionalArray(fieldName: String): JsonArray? {
    return this[fieldName]?.jsonArray
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

private fun JsonObject.requireLong(fieldName: String, source: String): Long {
    return this[fieldName]?.jsonPrimitive?.longOrNull
        ?: error("$source is missing long field `$fieldName`.")
}

private fun JsonObject.optionalStringList(fieldName: String): List<String> {
    return this[fieldName]
        ?.jsonArray
        ?.mapIndexed { index, element ->
            element.requireStringValue("$fieldName[$index]")
        }
        .orEmpty()
}

private fun JsonObject.optionalStringMap(fieldName: String): Map<String, String> {
    return this[fieldName]
        ?.jsonObject
        ?.entries
        ?.associate { (key, value) ->
            key to value.requireStringValue("$fieldName.$key")
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
