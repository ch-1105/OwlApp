package com.phoneclaw.app.store

import com.phoneclaw.app.contracts.PageGraph
import com.phoneclaw.app.contracts.SkillManifest
import com.phoneclaw.app.data.db.SkillDao
import com.phoneclaw.app.data.db.SkillEntity
import com.phoneclaw.app.learner.LearningEvidence
import com.phoneclaw.app.skills.JsonSkillLoader
import com.phoneclaw.app.skills.RegisteredSkillAction
import com.phoneclaw.app.skills.SkillActionBinding
import com.phoneclaw.app.skills.SkillPackageDefinition
import com.phoneclaw.app.skills.encodeLearningEvidenceJson
import com.phoneclaw.app.skills.encodePageGraphJson
import com.phoneclaw.app.skills.encodeSkillBindingsJson
import com.phoneclaw.app.skills.encodeSkillManifestJson
import com.phoneclaw.app.skills.parseLearningEvidenceJson
import com.phoneclaw.app.skills.parsePageGraphJson
import com.phoneclaw.app.skills.parseSkillBindingsJson
import com.phoneclaw.app.skills.parseSkillManifestJson
import com.phoneclaw.app.skills.validateSkillPackage

class RoomSkillStore(
    private val skillDao: SkillDao,
    private val builtinLoader: JsonSkillLoader,
    private val clock: () -> Long = { System.currentTimeMillis() },
) : SkillStore {
    override suspend fun loadAllEnabledActions(): List<RegisteredSkillAction> {
        return loadAllSkills()
            .filter { record ->
                record.enabled && record.reviewStatus == SKILL_REVIEW_APPROVED
            }
            .flatMap { record ->
                record.toSkillPackageDefinition().toRegisteredActions()
            }
    }

    override suspend fun saveLearnedSkill(
        manifest: SkillManifest,
        bindings: List<SkillActionBinding>,
        pageGraph: PageGraph?,
        evidence: List<LearningEvidence>,
    ) {
        val now = clock()
        val existing = skillDao.getById(manifest.skillId)
        val createdAt = existing?.createdAt ?: now
        val learnedAt = existing?.learnedAt ?: now
        val enabled = existing?.enabled ?: manifest.enabled
        val reviewStatus = existing?.reviewStatus ?: SKILL_REVIEW_PENDING
        val persistedPageGraph = pageGraph ?: existing.toExistingPageGraph(manifest.skillId)
        val persistedEvidence = if (evidence.isNotEmpty()) {
            evidence
        } else {
            existing.toExistingEvidence(manifest.skillId)
        }
        val skillPackage = validateSkillPackage(
            manifest = manifest.copy(enabled = enabled),
            bindings = bindings,
            source = "learned:${manifest.skillId}",
            pageGraph = persistedPageGraph,
            evidence = persistedEvidence,
        )

        skillDao.insert(
            SkillEntity(
                skillId = manifest.skillId,
                manifestJson = encodeSkillManifestJson(skillPackage.manifest),
                bindingsJson = encodeSkillBindingsJson(skillPackage.bindings),
                pageGraphJson = skillPackage.pageGraph?.let(::encodePageGraphJson),
                evidenceJson = encodeLearningEvidenceJson(skillPackage.evidence),
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

    override suspend fun updateReviewStatus(skillId: String, status: String) {
        require(status in setOf(SKILL_REVIEW_APPROVED, SKILL_REVIEW_PENDING, SKILL_REVIEW_REJECTED)) {
            "Unsupported review status: $status"
        }

        val existing = requireNotNull(findSkillRecord(skillId)) {
            "Skill $skillId was not found."
        }

        persistRecord(existing.copy(reviewStatus = status, updatedAt = clock()))
    }

    override suspend fun setSkillEnabled(skillId: String, enabled: Boolean) {
        val existing = requireNotNull(findSkillRecord(skillId)) {
            "Skill $skillId was not found."
        }

        persistRecord(existing.copy(enabled = enabled, updatedAt = clock()))
    }

    override suspend fun loadAllSkills(): List<SkillRecord> {
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
                pageGraph = skillPackage.pageGraph,
                evidence = skillPackage.evidence,
                source = SKILL_SOURCE_BUILTIN,
                enabled = skillPackage.manifest.enabled,
                reviewStatus = SKILL_REVIEW_APPROVED,
                createdAt = 0L,
                updatedAt = 0L,
            )
        }
    }

    private suspend fun persistedRecords(): List<SkillRecord> {
        return skillDao.getAll().map { entity -> entity.toSkillRecord() }
    }

    private suspend fun findSkillRecord(skillId: String): SkillRecord? {
        return loadAllSkills().firstOrNull { it.skillId == skillId }
    }

    private suspend fun persistRecord(record: SkillRecord) {
        skillDao.insert(record.toEntity())
    }
}

private fun SkillEntity.toSkillRecord(): SkillRecord {
    val sourceRef = "db:$skillId"
    val manifest = parseSkillManifestJson(manifestJson, sourceRef)
    val bindings = parseSkillBindingsJson(bindingsJson, "$sourceRef bindings")
    val pageGraph = pageGraphJson?.takeIf { it.isNotBlank() }?.let { jsonText ->
        parsePageGraphJson(jsonText, "$sourceRef pageGraph")
    }
    val evidence = parseLearningEvidenceJson(evidenceJson, "$sourceRef evidence")
    val skillPackage = validateSkillPackage(
        manifest = manifest,
        bindings = bindings,
        source = sourceRef,
        pageGraph = pageGraph,
        evidence = evidence,
    )

    return SkillRecord(
        manifest = skillPackage.manifest.copy(enabled = enabled),
        bindings = skillPackage.bindings,
        pageGraph = skillPackage.pageGraph,
        evidence = skillPackage.evidence,
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
        pageGraphJson = pageGraph?.let(::encodePageGraphJson),
        evidenceJson = encodeLearningEvidenceJson(evidence),
        source = source,
        enabled = enabled,
        reviewStatus = reviewStatus,
        learnedAt = learnedAt,
        appVersion = appVersion,
        createdAt = createdAt,
        updatedAt = updatedAt,
    )
}

private fun SkillRecord.toSkillPackageDefinition(): SkillPackageDefinition {
    return SkillPackageDefinition(
        manifest = manifest.copy(enabled = enabled),
        bindings = bindings,
        pageGraph = pageGraph,
        evidence = evidence,
    )
}

private fun SkillEntity?.toExistingPageGraph(skillId: String): PageGraph? {
    val entity = this ?: return null
    val jsonText = entity.pageGraphJson?.takeIf { it.isNotBlank() } ?: return null
    return parsePageGraphJson(jsonText, "db:$skillId existing pageGraph")
}

private fun SkillEntity?.toExistingEvidence(skillId: String): List<LearningEvidence> {
    val entity = this ?: return emptyList()
    return parseLearningEvidenceJson(entity.evidenceJson, "db:$skillId existing evidence")
}
