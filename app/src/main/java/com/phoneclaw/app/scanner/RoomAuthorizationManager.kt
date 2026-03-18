package com.phoneclaw.app.scanner

import com.phoneclaw.app.data.db.AuthorizedAppDao
import com.phoneclaw.app.data.db.AuthorizedAppEntity
import kotlinx.coroutines.runBlocking

class RoomAuthorizationManager(
    private val authorizedAppDao: AuthorizedAppDao,
    private val clock: () -> Long = { System.currentTimeMillis() },
) : AuthorizationManager {
    override fun getAuthorizedApps(): List<AuthorizedAppEntity> {
        return runBlocking { authorizedAppDao.getAll() }
            .filter { it.authorized }
    }

    override fun authorizeApp(packageName: String, appName: String) {
        runBlocking {
            authorizedAppDao.insert(
                AuthorizedAppEntity(
                    packageName = packageName,
                    appName = appName,
                    authorized = true,
                    authorizedAt = clock(),
                ),
            )
        }
    }

    override fun revokeApp(packageName: String) {
        val existing = runBlocking { authorizedAppDao.getById(packageName) } ?: return
        runBlocking {
            authorizedAppDao.insert(
                existing.copy(
                    authorized = false,
                    authorizedAt = clock(),
                ),
            )
        }
    }

    override fun isAuthorized(packageName: String): Boolean {
        return runBlocking { authorizedAppDao.getById(packageName) }
            ?.authorized == true
    }
}
