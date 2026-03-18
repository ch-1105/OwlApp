package com.phoneclaw.app.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

const val PHONECLAW_DATABASE_NAME = "phoneclaw.db"

val PHONECLAW_DB_MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "ALTER TABLE skills ADD COLUMN bindingsJson TEXT NOT NULL DEFAULT '[]'",
        )
    }
}

@Database(
    entities = [
        TaskEntity::class,
        SkillEntity::class,
        AuthorizedAppEntity::class,
    ],
    version = 2,
    exportSchema = false,
)
abstract class PhoneClawDatabase : RoomDatabase() {
    abstract fun taskDao(): TaskDao
    abstract fun skillDao(): SkillDao
    abstract fun authorizedAppDao(): AuthorizedAppDao
}
