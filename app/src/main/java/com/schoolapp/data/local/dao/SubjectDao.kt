package com.schoolapp.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.schoolapp.data.local.entity.SubjectEntity

@Dao
interface SubjectDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(subject: SubjectEntity)

    @Query("SELECT * FROM subjects WHERE grade = :grade ORDER BY name")
    suspend fun getByGrade(grade: Int): List<SubjectEntity>

    @Query("SELECT * FROM subjects WHERE subjectId = :subjectId LIMIT 1")
    suspend fun getById(subjectId: String): SubjectEntity?

    @Query("DELETE FROM subjects WHERE subjectId = :subjectId")
    suspend fun deleteById(subjectId: String)

    // Inbound sync (data/sync/RoomInboundLocal). Ids compare with SQLite's BINARY collation,
    // the same byte order Firestore uses for document ids.

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(rows: List<SubjectEntity>)

    @Query("SELECT * FROM subjects")
    suspend fun getAllRows(): List<SubjectEntity>

    @Query("SELECT * FROM subjects WHERE subjectId IN (:ids)")
    suspend fun getByIds(ids: List<String>): List<SubjectEntity>

    @Query(
        "SELECT * FROM subjects WHERE (:afterId IS NULL OR subjectId > :afterId) " +
            "AND (:throughId IS NULL OR subjectId <= :throughId)",
    )
    suspend fun getInIdRange(afterId: String?, throughId: String?): List<SubjectEntity>

    @Query("DELETE FROM subjects WHERE subjectId IN (:ids)")
    suspend fun deleteAllByIds(ids: List<String>)
}
