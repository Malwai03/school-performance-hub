package com.schoolapp.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.schoolapp.data.local.entity.TeacherAssignmentEntity

@Dao
interface TeacherAssignmentDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(assignment: TeacherAssignmentEntity)

    @Query("SELECT * FROM teacher_assignments WHERE teacherUid = :teacherUid")
    suspend fun getByTeacher(teacherUid: String): List<TeacherAssignmentEntity>

    @Query("SELECT * FROM teacher_assignments WHERE sectionId = :sectionId AND subjectId = :subjectId LIMIT 1")
    suspend fun getAssignment(sectionId: String, subjectId: String): TeacherAssignmentEntity?
}
