package com.phoneclaw.app.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update

@Dao
interface AuthorizedAppDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(app: AuthorizedAppEntity)

    @Update
    suspend fun update(app: AuthorizedAppEntity)

    @Query("SELECT * FROM authorized_apps WHERE packageName = :packageName")
    suspend fun getById(packageName: String): AuthorizedAppEntity?

    @Query("SELECT * FROM authorized_apps ORDER BY appName ASC")
    suspend fun getAll(): List<AuthorizedAppEntity>

    @Query("DELETE FROM authorized_apps WHERE packageName = :packageName")
    suspend fun deleteById(packageName: String)
}
