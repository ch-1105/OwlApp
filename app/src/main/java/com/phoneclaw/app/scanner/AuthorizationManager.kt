package com.phoneclaw.app.scanner

import com.phoneclaw.app.data.db.AuthorizedAppEntity

interface AuthorizationManager {
    fun getAuthorizedApps(): List<AuthorizedAppEntity>

    fun authorizeApp(packageName: String, appName: String)

    fun revokeApp(packageName: String)

    fun isAuthorized(packageName: String): Boolean
}
