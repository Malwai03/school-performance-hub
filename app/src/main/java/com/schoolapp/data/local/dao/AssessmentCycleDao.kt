package com.schoolapp.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.schoolapp.data.local.entity.AssessmentCycleEntity

@Dao
interface AssessmentCycleDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(cycle: AssessmentCycleEntity)

    @Query("SELECT * FROM assessment_cycles ORDER BY startDateIso DESC")
    suspend fun getAll(): List<AssessmentCycleEntity>

    @Query("SELECT * FROM assessment_cycles WHERE cycleId = :cycleId LIMIT 1")
    suspend fun getById(cycleId: String): AssessmentCycleEntity?

    @Query("DELETE FROM assessment_cycles WHERE cycleId = :cycleId")
    suspend fun deleteById(cycleId: String)
}
