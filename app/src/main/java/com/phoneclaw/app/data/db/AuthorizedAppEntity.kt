package com.phoneclaw.app.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "authorized_apps")
data class AuthorizedAppEntity(
    @PrimaryKey val packageName: String,
    val appName: String,
    val authorized: Boolean,
    val authorizedAt: Long,
)
