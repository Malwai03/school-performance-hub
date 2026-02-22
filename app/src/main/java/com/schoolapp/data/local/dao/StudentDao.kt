package com.schoolapp.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.schoolapp.data.local.entity.StudentEntity

@Dao
interface StudentDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(student: StudentEntity)

    @Query("SELECT * FROM students WHERE sectionId = :sectionId AND active = 1 ORDER BY rollNumber")
    suspend fun getBySection(sectionId: String): List<StudentEntity>

    @Query("SELECT * FROM students WHERE sectionId = :sectionId ORDER BY rollNumber")
    suspend fun getBySectionAll(sectionId: String): List<StudentEntity>

    @Query("SELECT * FROM students WHERE studentId = :studentId LIMIT 1")
    suspend fun getById(studentId: String): StudentEntity?

    @Query("DELETE FROM students WHERE studentId = :studentId")
    suspend fun deleteById(studentId: String)

    @Query("SELECT COUNT(*) FROM students")
    suspend fun countAll(): Int
}
