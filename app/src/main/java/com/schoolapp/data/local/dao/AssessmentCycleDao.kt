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

    // Inbound sync (data/sync/RoomInboundLocal). Ids compare with SQLite's BINARY collation,
    // the same byte order Firestore uses for document ids.

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(rows: List<AssessmentCycleEntity>)

    @Query("SELECT * FROM assessment_cycles")
    suspend fun getAllRows(): List<AssessmentCycleEntity>

    @Query("SELECT * FROM assessment_cycles WHERE cycleId IN (:ids)")
    suspend fun getByIds(ids: List<String>): List<AssessmentCycleEntity>

    @Query(
        "SELECT * FROM assessment_cycles WHERE (:afterId IS NULL OR cycleId > :afterId) " +
            "AND (:throughId IS NULL OR cycleId <= :throughId)",
    )
    suspend fun getInIdRange(afterId: String?, throughId: String?): List<AssessmentCycleEntity>

    @Query("DELETE FROM assessment_cycles WHERE cycleId IN (:ids)")
    suspend fun deleteAllByIds(ids: List<String>)
}
