package com.phoneclaw.app.skills

import com.phoneclaw.app.gateway.ports.SkillRegistryPort
import com.phoneclaw.app.store.SkillStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking

class StoreBackedSkillRegistry(
    private val skillStore: SkillStore,
) : SkillRegistryPort {
    override fun allSkills() = snapshot().allSkills()

    override fun allActions() = snapshot().allActions()

    override fun findAction(actionId: String) = snapshot().findAction(actionId)

    override fun matchUserMessage(userMessage: String) = snapshot().matchUserMessage(userMessage)

    /**
     * SkillRegistryPort is synchronous by design (used in Gateway/Policy/Executor paths).
     * SkillStore is suspend (backed by Room). Bridge with runBlocking on IO dispatcher
     * to avoid blocking the caller's thread pool with DB I/O.
     */
    private fun snapshot(): StaticSkillRegistry {
        val actions = runBlocking(Dispatchers.IO) { skillStore.loadAllEnabledActions() }
        return StaticSkillRegistry(actions)
    }
}
