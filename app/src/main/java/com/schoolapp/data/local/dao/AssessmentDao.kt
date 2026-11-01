package com.schoolapp.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.schoolapp.data.local.entity.AssessmentEntity

@Dao
interface AssessmentDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(assessment: AssessmentEntity)

    @Query("SELECT * FROM assessments WHERE sectionId = :sectionId AND cycleId = :cycleId")
    suspend fun getBySectionAndCycle(sectionId: String, cycleId: String): List<AssessmentEntity>

    @Query("SELECT * FROM assessments WHERE sectionId = :sectionId")
    suspend fun getBySection(sectionId: String): List<AssessmentEntity>

    @Query("SELECT * FROM assessments WHERE subjectId = :subjectId")
    suspend fun getBySubject(subjectId: String): List<AssessmentEntity>

    @Query("SELECT * FROM assessments WHERE assessmentId = :assessmentId LIMIT 1")
    suspend fun getById(assessmentId: String): AssessmentEntity?

    @Query("DELETE FROM assessments WHERE assessmentId = :assessmentId")
    suspend fun deleteById(assessmentId: String)

    @Query("DELETE FROM assessments WHERE sectionId = :sectionId")
    suspend fun deleteBySection(sectionId: String)

    @Query("DELETE FROM assessments WHERE subjectId = :subjectId")
    suspend fun deleteBySubject(subjectId: String)
}
