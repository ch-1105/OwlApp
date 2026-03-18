package com.phoneclaw.app.skills

import java.io.File

fun bundledRegisteredActionsForTests(): List<RegisteredSkillAction> {
    val assetsRoot = sequenceOf(
        File("src/main/assets"),
        File("app/src/main/assets"),
    ).firstOrNull { it.exists() }
        ?: error("Could not locate app/src/main/assets for skill tests.")

    return JsonSkillLoader.fromDirectory(assetsRoot).loadRegisteredActions()
}

fun bundledSkillRegistryForTests(): StaticSkillRegistry {
    return StaticSkillRegistry(bundledRegisteredActionsForTests())
}
