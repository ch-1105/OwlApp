package com.phoneclaw.app.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "tasks")
data class TaskEntity(
    @PrimaryKey val taskId: String,
    val sessionId: String,
    val userMessage: String,
    val state: String,
    val actionSpecJson: String? = null,
    val executionResultJson: String? = null,
    val planningTraceJson: String? = null,
    val errorMessage: String? = null,
    val createdAt: Long,
    val updatedAt: Long,
)
