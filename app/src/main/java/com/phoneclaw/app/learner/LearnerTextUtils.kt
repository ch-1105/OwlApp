package com.phoneclaw.app.learner

import com.phoneclaw.app.explorer.AccessibilityNodeSnapshot
import com.phoneclaw.app.explorer.PageTreeSnapshot
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/**
 * Shared text normalization and snapshot serialization utilities for the learning subsystem.
 */

private val WHITESPACE_RUN = Regex("\\s+")
private val NON_SLUG_CHARS = Regex("[^a-z0-9]+")

internal fun String.cleanText(maxLength: Int = 80): String {
    return trim()
        .replace(WHITESPACE_RUN, " ")
        .take(maxLength)
}

internal fun String.toActionSlug(maxLength: Int = 40): String {
    val cleaned = lowercase()
        .replace(NON_SLUG_CHARS, "_")
        .trim('_')
    if (cleaned.isBlank()) {
        return "generated_action"
    }
    return cleaned.take(maxLength)
}

internal fun String.toDisplayName(): String {
    val normalized = replace('_', ' ')
        .replace('-', ' ')
        .trim()
    if (normalized.isBlank()) {
        return "Generated Action"
    }
    return normalized.split(WHITESPACE_RUN).joinToString(" ") { word ->
        word.replaceFirstChar { char ->
            if (char.isLowerCase()) char.titlecase() else char.toString()
        }
    }
}

internal fun MutableSet<String>.makeUnique(baseName: String): String {
    if (add(baseName)) {
        return baseName
    }
    var suffix = 2
    while (true) {
        val candidate = "${baseName}_$suffix"
        if (add(candidate)) {
            return candidate
        }
        suffix += 1
    }
}

internal fun PageTreeSnapshot.toSnapshotJson(): String {
    return buildJsonObject {
        put("package_name", JsonPrimitive(packageName))
        put("activity_name", activityName.toJsonPrimitiveOrNull())
        put("timestamp", JsonPrimitive(timestamp))
        put("nodes", JsonArray(nodes.map { it.toSnapshotJsonElement() }))
    }.toString()
}

private fun AccessibilityNodeSnapshot.toSnapshotJsonElement(): kotlinx.serialization.json.JsonElement {
    return buildJsonObject {
        put("node_id", JsonPrimitive(nodeId))
        put("class_name", className.toJsonPrimitiveOrNull())
        put("text", text.toJsonPrimitiveOrNull())
        put("content_description", contentDescription.toJsonPrimitiveOrNull())
        put("resource_id", resourceId.toJsonPrimitiveOrNull())
        put("is_clickable", JsonPrimitive(isClickable))
        put("is_scrollable", JsonPrimitive(isScrollable))
        put("is_editable", JsonPrimitive(isEditable))
        put("bounds", JsonPrimitive(bounds))
        put("children", JsonArray(children.map { it.toSnapshotJsonElement() }))
    }
}

private fun String?.toJsonPrimitiveOrNull(): kotlinx.serialization.json.JsonElement {
    return if (this != null) JsonPrimitive(this) else JsonNull
}
