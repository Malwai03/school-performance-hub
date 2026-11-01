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

    @Query("SELECT * FROM teacher_assignments WHERE assignmentId = :assignmentId LIMIT 1")
    suspend fun getById(assignmentId: String): TeacherAssignmentEntity?

    @Query("SELECT * FROM teacher_assignments WHERE sectionId = :sectionId")
    suspend fun getBySection(sectionId: String): List<TeacherAssignmentEntity>

    @Query("SELECT * FROM teacher_assignments WHERE subjectId = :subjectId")
    suspend fun getBySubject(subjectId: String): List<TeacherAssignmentEntity>

    @Query("DELETE FROM teacher_assignments WHERE assignmentId = :assignmentId")
    suspend fun deleteById(assignmentId: String)

    @Query("DELETE FROM teacher_assignments WHERE sectionId = :sectionId")
    suspend fun deleteBySection(sectionId: String)

    @Query("DELETE FROM teacher_assignments WHERE subjectId = :subjectId")
    suspend fun deleteBySubject(subjectId: String)

    // Inbound sync (data/sync/RoomInboundLocal). Ids compare with SQLite's BINARY collation,
    // the same byte order Firestore uses for document ids.

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(rows: List<TeacherAssignmentEntity>)

    @Query("SELECT * FROM teacher_assignments")
    suspend fun getAllRows(): List<TeacherAssignmentEntity>

    @Query("SELECT * FROM teacher_assignments WHERE assignmentId IN (:ids)")
    suspend fun getByIds(ids: List<String>): List<TeacherAssignmentEntity>

    @Query(
        "SELECT * FROM teacher_assignments WHERE (:afterId IS NULL OR assignmentId > :afterId) " +
            "AND (:throughId IS NULL OR assignmentId <= :throughId)",
    )
    suspend fun getInIdRange(afterId: String?, throughId: String?): List<TeacherAssignmentEntity>

    @Query("DELETE FROM teacher_assignments WHERE assignmentId IN (:ids)")
    suspend fun deleteAllByIds(ids: List<String>)
}
