package com.phoneclaw.app.skills

import com.phoneclaw.app.gateway.ports.SkillRegistryPort
import com.phoneclaw.app.store.SkillStore

class StoreBackedSkillRegistry(
    private val skillStore: SkillStore,
) : SkillRegistryPort {
    override fun allSkills() = snapshot().allSkills()

    override fun allActions() = snapshot().allActions()

    override fun findAction(actionId: String) = snapshot().findAction(actionId)

    override fun matchUserMessage(userMessage: String) = snapshot().matchUserMessage(userMessage)

    private fun snapshot(): StaticSkillRegistry {
        return StaticSkillRegistry(skillStore.loadAllEnabledActions())
    }
}
