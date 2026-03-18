package com.phoneclaw.app.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update

@Dao
interface SkillDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(skill: SkillEntity)

    @Update
    suspend fun update(skill: SkillEntity)

    @Query("SELECT * FROM skills WHERE skillId = :skillId")
    suspend fun getById(skillId: String): SkillEntity?

    @Query("SELECT * FROM skills ORDER BY skillId ASC")
    suspend fun getAll(): List<SkillEntity>

    @Query("DELETE FROM skills WHERE skillId = :skillId")
    suspend fun deleteById(skillId: String)
}
