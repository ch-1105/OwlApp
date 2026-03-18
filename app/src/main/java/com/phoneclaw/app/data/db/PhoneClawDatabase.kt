package com.phoneclaw.app.data.db

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [
        TaskEntity::class,
        SkillEntity::class,
        AuthorizedAppEntity::class,
    ],
    version = 1,
    exportSchema = false,
)
abstract class PhoneClawDatabase : RoomDatabase() {
    abstract fun taskDao(): TaskDao
    abstract fun skillDao(): SkillDao
    abstract fun authorizedAppDao(): AuthorizedAppDao
}
