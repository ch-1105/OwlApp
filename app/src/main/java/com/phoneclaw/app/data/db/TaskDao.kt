package com.phoneclaw.app.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update

@Dao
interface TaskDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(task: TaskEntity)

    @Update
    suspend fun update(task: TaskEntity)

    @Query("SELECT * FROM tasks WHERE taskId = :taskId")
    suspend fun getById(taskId: String): TaskEntity?

    @Query("SELECT * FROM tasks ORDER BY createdAt DESC")
    suspend fun getAll(): List<TaskEntity>

    @Query("DELETE FROM tasks WHERE taskId = :taskId")
    suspend fun deleteById(taskId: String)
}
