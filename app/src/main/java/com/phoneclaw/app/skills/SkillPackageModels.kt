package com.phoneclaw.app.skills

import com.phoneclaw.app.contracts.SkillManifest

data class SkillActionBinding(
    val actionId: String,
    val intentAction: String,
)

data class SkillPackageDefinition(
    val manifest: SkillManifest,
    val bindings: List<SkillActionBinding>,
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

    return SkillPackageDefinition(
        manifest = manifest,
        bindings = bindings,
    )
}
