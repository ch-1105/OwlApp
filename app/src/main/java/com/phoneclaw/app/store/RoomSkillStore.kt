package com.phoneclaw.app.store

import com.phoneclaw.app.contracts.SkillManifest
import com.phoneclaw.app.data.db.SkillDao
import com.phoneclaw.app.data.db.SkillEntity
import com.phoneclaw.app.skills.JsonSkillLoader
import com.phoneclaw.app.skills.RegisteredSkillAction
import com.phoneclaw.app.skills.SkillActionBinding
import com.phoneclaw.app.skills.SkillPackageDefinition
import com.phoneclaw.app.skills.encodeSkillBindingsJson
import com.phoneclaw.app.skills.encodeSkillManifestJson
import com.phoneclaw.app.skills.parseSkillBindingsJson
import com.phoneclaw.app.skills.parseSkillManifestJson
import com.phoneclaw.app.skills.validateSkillPackage
import kotlinx.coroutines.runBlocking

class RoomSkillStore(
    private val skillDao: SkillDao,
    private val builtinLoader: JsonSkillLoader,
    private val clock: () -> Long = { System.currentTimeMillis() },
) : SkillStore {
    override fun loadAllEnabledActions(): List<RegisteredSkillAction> {
        return loadAllSkills()
            .filter { record ->
                record.enabled && record.reviewStatus == SKILL_REVIEW_APPROVED
            }
            .flatMap { record ->
                SkillPackageDefinition(
                    manifest = record.manifest.copy(enabled = record.enabled),
                    bindings = record.bindings,
                ).toRegisteredActions()
            }
    }

    override fun saveLearnedSkill(manifest: SkillManifest, bindings: List<SkillActionBinding>) {
        validateSkillPackage(
            manifest = manifest,
            bindings = bindings,
            source = "learned:${manifest.skillId}",
        )

        val now = clock()
        val existing = runBlocking { skillDao.getById(manifest.skillId) }
        val createdAt = existing?.createdAt ?: now
        val learnedAt = existing?.learnedAt ?: now
        val enabled = existing?.enabled ?: manifest.enabled
        val reviewStatus = existing?.reviewStatus ?: SKILL_REVIEW_PENDING

        runBlocking {
            skillDao.insert(
                SkillEntity(
                    skillId = manifest.skillId,
                    manifestJson = encodeSkillManifestJson(manifest.copy(enabled = enabled)),
                    bindingsJson = encodeSkillBindingsJson(bindings),
                    source = SKILL_SOURCE_LEARNED,
                    enabled = enabled,
                    reviewStatus = reviewStatus,
                    learnedAt = learnedAt,
                    appVersion = existing?.appVersion,
                    createdAt = createdAt,
                    updatedAt = now,
                ),
            )
        }
    }

    override fun updateReviewStatus(skillId: String, status: String) {
        require(status in setOf(SKILL_REVIEW_APPROVED, SKILL_REVIEW_PENDING, SKILL_REVIEW_REJECTED)) {
            "Unsupported review status: $status"
        }

        val existing = requireNotNull(findSkillRecord(skillId)) {
            "Skill $skillId was not found."
        }

        persistRecord(existing.copy(reviewStatus = status, updatedAt = clock()))
    }

    override fun setSkillEnabled(skillId: String, enabled: Boolean) {
        val existing = requireNotNull(findSkillRecord(skillId)) {
            "Skill $skillId was not found."
        }

        persistRecord(existing.copy(enabled = enabled, updatedAt = clock()))
    }

    override fun loadAllSkills(): List<SkillRecord> {
        val merged = linkedMapOf<String, SkillRecord>()
        builtinRecords().forEach { record ->
            merged[record.skillId] = record
        }
        persistedRecords().forEach { record ->
            merged[record.skillId] = record
        }
        return merged.values.sortedBy { it.skillId }
    }

    private fun builtinRecords(): List<SkillRecord> {
        return builtinLoader.loadSkillPackages().map { skillPackage ->
            SkillRecord(
                manifest = skillPackage.manifest,
                bindings = skillPackage.bindings,
                source = SKILL_SOURCE_BUILTIN,
                enabled = skillPackage.manifest.enabled,
                reviewStatus = SKILL_REVIEW_APPROVED,
                createdAt = 0L,
                updatedAt = 0L,
            )
        }
    }

    private fun persistedRecords(): List<SkillRecord> {
        return runBlocking { skillDao.getAll() }
            .map { entity -> entity.toSkillRecord() }
    }

    private fun findSkillRecord(skillId: String): SkillRecord? {
        return loadAllSkills().firstOrNull { it.skillId == skillId }
    }

    private fun persistRecord(record: SkillRecord) {
        runBlocking {
            skillDao.insert(record.toEntity())
        }
    }
}

private fun SkillEntity.toSkillRecord(): SkillRecord {
    val sourceRef = "db:${skillId}"
    val manifest = parseSkillManifestJson(manifestJson, sourceRef)
    val bindings = parseSkillBindingsJson(bindingsJson, "$sourceRef bindings")
    validateSkillPackage(manifest, bindings, sourceRef)

    return SkillRecord(
        manifest = manifest.copy(enabled = enabled),
        bindings = bindings,
        source = this.source,
        enabled = enabled,
        reviewStatus = reviewStatus,
        learnedAt = learnedAt,
        appVersion = appVersion,
        createdAt = createdAt,
        updatedAt = updatedAt,
    )
}

private fun SkillRecord.toEntity(): SkillEntity {
    return SkillEntity(
        skillId = skillId,
        manifestJson = encodeSkillManifestJson(manifest.copy(enabled = enabled)),
        bindingsJson = encodeSkillBindingsJson(bindings),
        source = source,
        enabled = enabled,
        reviewStatus = reviewStatus,
        learnedAt = learnedAt,
        appVersion = appVersion,
        createdAt = createdAt,
        updatedAt = updatedAt,
    )
}
