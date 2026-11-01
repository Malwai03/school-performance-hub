package com.schoolapp.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.schoolapp.data.local.entity.ResultSnapshotEntity

@Dao
interface ResultSnapshotDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(snapshot: ResultSnapshotEntity)

    @Query("SELECT * FROM result_snapshots WHERE studentId = :studentId ORDER BY cycleId")
    suspend fun getStudentTrend(studentId: String): List<ResultSnapshotEntity>

    @Query("SELECT * FROM result_snapshots WHERE sectionId = :sectionId AND cycleId = :cycleId")
    suspend fun getSectionResults(sectionId: String, cycleId: String): List<ResultSnapshotEntity>

    @Query("SELECT * FROM result_snapshots WHERE sectionId = :sectionId")
    suspend fun getBySection(sectionId: String): List<ResultSnapshotEntity>

    @Query("DELETE FROM result_snapshots WHERE snapshotId IN (:snapshotIds)")
    suspend fun deleteByIds(snapshotIds: List<String>)

    @Query("DELETE FROM result_snapshots WHERE studentId = :studentId")
    suspend fun deleteByStudent(studentId: String)

    @Query("DELETE FROM result_snapshots WHERE sectionId = :sectionId")
    suspend fun deleteBySection(sectionId: String)
}
