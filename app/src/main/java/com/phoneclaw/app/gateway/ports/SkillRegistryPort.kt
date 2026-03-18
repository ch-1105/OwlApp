package com.phoneclaw.app.gateway.ports

import com.phoneclaw.app.contracts.SkillManifest
import com.phoneclaw.app.skills.RegisteredSkillAction

interface SkillRegistryPort {
    fun allSkills(): List<SkillManifest>
    fun allActions(): List<RegisteredSkillAction>
    fun findAction(actionId: String): RegisteredSkillAction?
    fun matchUserMessage(userMessage: String): RegisteredSkillAction?
}

