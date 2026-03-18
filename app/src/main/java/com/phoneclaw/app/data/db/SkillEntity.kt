package com.phoneclaw.app.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "skills")
data class SkillEntity(
    @PrimaryKey val skillId: String,
    val manifestJson: String,
    val bindingsJson: String = "[]",
    val source: String,
    val enabled: Boolean,
    val reviewStatus: String,
    val learnedAt: Long? = null,
    val appVersion: String? = null,
    val createdAt: Long,
    val updatedAt: Long,
)
