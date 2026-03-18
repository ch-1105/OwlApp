package com.phoneclaw.app.store

import com.phoneclaw.app.contracts.SkillManifest
import com.phoneclaw.app.skills.RegisteredSkillAction
import com.phoneclaw.app.skills.SkillActionBinding

const val SKILL_SOURCE_BUILTIN = "builtin"
const val SKILL_SOURCE_LEARNED = "learned"
const val SKILL_SOURCE_IMPORTED = "imported"

const val SKILL_REVIEW_APPROVED = "approved"
const val SKILL_REVIEW_PENDING = "pending"
const val SKILL_REVIEW_REJECTED = "rejected"

data class SkillRecord(
    val manifest: SkillManifest,
    val bindings: List<SkillActionBinding>,
    val source: String,
    val enabled: Boolean,
    val reviewStatus: String,
    val learnedAt: Long? = null,
    val appVersion: String? = null,
    val createdAt: Long,
    val updatedAt: Long,
) {
    val skillId: String
        get() = manifest.skillId
}

interface SkillStore {
    fun loadAllEnabledActions(): List<RegisteredSkillAction>

    fun saveLearnedSkill(manifest: SkillManifest, bindings: List<SkillActionBinding>)

    fun updateReviewStatus(skillId: String, status: String)

    fun setSkillEnabled(skillId: String, enabled: Boolean)

    fun loadAllSkills(): List<SkillRecord>
}
