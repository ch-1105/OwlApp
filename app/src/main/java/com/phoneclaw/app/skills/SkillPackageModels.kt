package com.phoneclaw.app.skills

import com.phoneclaw.app.contracts.PageGraph
import com.phoneclaw.app.contracts.SkillManifest
import com.phoneclaw.app.learner.LearningEvidence

data class SkillActionBinding(
    val actionId: String,
    val intentAction: String,
)

data class SkillPackageDefinition(
    val manifest: SkillManifest,
    val bindings: List<SkillActionBinding>,
    val pageGraph: PageGraph? = null,
    val evidence: List<LearningEvidence> = emptyList(),
) {
    fun toRegisteredActions(): List<RegisteredSkillAction> {
        val actionMap = manifest.actions.associateBy { it.actionId }
        return bindings.map { binding ->
            RegisteredSkillAction(
                skill = manifest,
                action = actionMap.getValue(binding.actionId),
                intentAction = binding.intentAction,
            )
        }
    }
}

internal fun validateSkillPackage(
    manifest: SkillManifest,
    bindings: List<SkillActionBinding>,
    source: String,
    pageGraph: PageGraph? = null,
    evidence: List<LearningEvidence> = emptyList(),
): SkillPackageDefinition {
    val actionMap = manifest.actions.associateBy { it.actionId }
    val bindingIds = bindings.map { it.actionId }
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

    validatePageGraph(manifest, pageGraph, source)
    validateEvidence(pageGraph, evidence, source)

    return SkillPackageDefinition(
        manifest = manifest,
        bindings = bindings,
        pageGraph = pageGraph,
        evidence = evidence,
    )
}

private fun validatePageGraph(
    manifest: SkillManifest,
    pageGraph: PageGraph?,
    source: String,
) {
    if (pageGraph == null) {
        return
    }

    val manifestAppPackage = manifest.appPackage
    if (manifestAppPackage != null) {
        require(pageGraph.appPackage == manifestAppPackage) {
            "$source has page graph app package ${pageGraph.appPackage}, expected $manifestAppPackage."
        }
    }

    val pageIds = pageGraph.pages.map { it.pageId }
    val duplicatePageIds = pageIds.groupBy { it }
        .filterValues { it.size > 1 }
        .keys
    require(duplicatePageIds.isEmpty()) {
        "$source declares duplicate page ids: ${duplicatePageIds.sorted().joinToString(", ")}."
    }

    val actionIds = manifest.actions.map { it.actionId }.toSet()
    require(pageGraph.pages.all { page -> page.availableActions.all { it in actionIds } }) {
        "$source page graph declares page actions that are missing from the manifest."
    }

    require(
        pageGraph.transitions.all { transition ->
            transition.fromPageId in pageIds &&
                transition.toPageId in pageIds &&
                transition.triggerActionId in actionIds
        },
    ) {
        "$source page graph contains transitions that reference missing pages or actions."
    }
}

private fun validateEvidence(
    pageGraph: PageGraph?,
    evidence: List<LearningEvidence>,
    source: String,
) {
    if (evidence.isEmpty()) {
        return
    }

    require(pageGraph != null) {
        "$source declares evidence without a page graph."
    }

    val pageIds = pageGraph.pages.map { it.pageId }.toSet()
    require(evidence.all { item -> item.pageId in pageIds }) {
        "$source evidence references pages that are missing from the page graph."
    }

    require(
        evidence.all { item ->
            val transition = item.arrivedBy ?: return@all true
            transition.fromPageId in pageIds && transition.toPageId in pageIds
        },
    ) {
        "$source evidence contains transitions that reference missing pages."
    }
}
