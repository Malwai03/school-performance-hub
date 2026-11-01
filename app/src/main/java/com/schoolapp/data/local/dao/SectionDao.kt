package com.schoolapp.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.schoolapp.data.local.entity.SectionEntity

@Dao
interface SectionDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(section: SectionEntity)

    @Query("SELECT * FROM sections ORDER BY grade ASC, sectionName ASC")
    suspend fun getAll(): List<SectionEntity>

    @Query("SELECT * FROM sections WHERE sectionId = :sectionId LIMIT 1")
    suspend fun getById(sectionId: String): SectionEntity?

    @Query("DELETE FROM sections WHERE sectionId = :sectionId")
    suspend fun deleteById(sectionId: String)
}
