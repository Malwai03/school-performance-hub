package com.schoolapp.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.schoolapp.data.local.entity.ScoreEntity

@Dao
interface ScoreDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(score: ScoreEntity)

    @Query("SELECT * FROM scores WHERE assessmentId = :assessmentId ORDER BY rowid DESC")
    suspend fun getByAssessment(assessmentId: String): List<ScoreEntity>

    @Query("SELECT * FROM scores WHERE assessmentId = :assessmentId AND studentId = :studentId ORDER BY rowid DESC")
    suspend fun getByAssessmentAndStudent(assessmentId: String, studentId: String): List<ScoreEntity>

    @Query("SELECT * FROM scores WHERE studentId = :studentId")
    suspend fun getByStudent(studentId: String): List<ScoreEntity>

    @Query("SELECT * FROM scores WHERE synced = 0")
    suspend fun getUnsynced(): List<ScoreEntity>

    @Query("SELECT COUNT(*) FROM scores WHERE synced = 0")
    suspend fun countUnsynced(): Int

    @Query("DELETE FROM scores WHERE studentId = :studentId")
    suspend fun deleteByStudent(studentId: String)

    @Query("DELETE FROM scores WHERE assessmentId IN (:assessmentIds)")
    suspend fun deleteByAssessmentIds(assessmentIds: List<String>)

    @Query("DELETE FROM scores WHERE assessmentId = :assessmentId AND studentId = :studentId AND scoreId != :keepScoreId")
    suspend fun deleteDuplicates(assessmentId: String, studentId: String, keepScoreId: String)

    @Query("UPDATE scores SET synced = 1 WHERE scoreId = :scoreId")
    suspend fun markSynced(scoreId: String)
}
