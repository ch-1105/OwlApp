package com.phoneclaw.app.skills

import android.content.res.AssetManager
import com.phoneclaw.app.contracts.RiskLevel
import com.phoneclaw.app.contracts.SkillActionManifest
import com.phoneclaw.app.contracts.SkillManifest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File

private const val DEFAULT_SKILLS_DIRECTORY = "skills"

class JsonSkillLoader private constructor(
    private val listDocuments: (String) -> List<String>,
    private val readDocument: (String) -> String,
) {
    fun loadRegisteredActions(directory: String = DEFAULT_SKILLS_DIRECTORY): List<RegisteredSkillAction> {
        val documentNames = listDocuments(directory)
            .filter { it.endsWith(".json", ignoreCase = true) }
            .sorted()

        return documentNames.flatMap { documentName ->
            val documentPath = if (directory.isBlank()) documentName else "$directory/$documentName"
            parseSkillPackage(
                jsonText = readDocument(documentPath),
                source = documentPath,
            )
        }
    }

    companion object {
        fun fromAssets(assetManager: AssetManager): JsonSkillLoader {
            return JsonSkillLoader(
                listDocuments = { directory -> assetManager.list(directory)?.toList().orEmpty() },
                readDocument = { path ->
                    assetManager.open(path).bufferedReader().use { reader ->
                        reader.readText()
                    }
                },
            )
        }

        fun fromDirectory(rootDir: File): JsonSkillLoader {
            return JsonSkillLoader(
                listDocuments = { directory ->
                    val targetDir = if (directory.isBlank()) rootDir else File(rootDir, directory)
                    targetDir.listFiles()
                        ?.filter { it.isFile }
                        ?.map { it.name }
                        .orEmpty()
                },
                readDocument = { path ->
                    File(rootDir, path.replace('/', File.separatorChar)).readText()
                },
            )
        }
    }
}

private fun parseSkillPackage(jsonText: String, source: String): List<RegisteredSkillAction> {
    val root = Json.parseToJsonElement(jsonText).jsonObject
    val skill = root.requireObject("skill", source).toSkillManifest(source)
    val bindings = root.requireArray("action_bindings", source)
        .map { bindingElement -> bindingElement.jsonObject }

    val actionMap = skill.actions.associateBy { it.actionId }
    val bindingIds = bindings.map { binding -> binding.requireString("action_id", source) }
    val duplicateBindingIds = bindingIds.groupBy { it }
        .filterValues { it.size > 1 }
        .keys
    require(duplicateBindingIds.isEmpty()) {
        "$source declares duplicate action bindings: ${duplicateBindingIds.sorted().joinToString(", ")}."
    }

    val missingBindings = actionMap.keys - bindingIds.toSet()
    require(missingBindings.isEmpty()) {
        "$source is missing action bindings for: ${missingBindings.sorted().joinToString(", ")}."
    }

    val unknownBindings = bindingIds.toSet() - actionMap.keys
    require(unknownBindings.isEmpty()) {
        "$source declares bindings for unknown actions: ${unknownBindings.sorted().joinToString(", ")}."
    }

    return bindings.map { binding ->
        val actionId = binding.requireString("action_id", source)
        RegisteredSkillAction(
            skill = skill,
            action = actionMap.getValue(actionId),
            intentAction = binding.optionalString("intent_action").orEmpty(),
        )
    }
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
