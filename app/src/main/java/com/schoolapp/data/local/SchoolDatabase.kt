package com.schoolapp.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.schoolapp.data.local.dao.AssessmentCycleDao
import com.schoolapp.data.local.dao.AssessmentDao
import com.schoolapp.data.local.dao.LocalDataDao
import com.schoolapp.data.local.dao.ResultSnapshotDao
import com.schoolapp.data.local.dao.ScoreDao
import com.schoolapp.data.local.dao.SectionDao
import com.schoolapp.data.local.dao.StudentDao
import com.schoolapp.data.local.dao.SubjectDao
import com.schoolapp.data.local.dao.TeacherAssignmentDao
import com.schoolapp.data.local.dao.UserDao
import com.schoolapp.data.local.entity.AssessmentCycleEntity
import com.schoolapp.data.local.entity.AssessmentEntity
import com.schoolapp.data.local.entity.ResultSnapshotEntity
import com.schoolapp.data.local.entity.ScoreEntity
import com.schoolapp.data.local.entity.SectionEntity
import com.schoolapp.data.local.entity.StudentEntity
import com.schoolapp.data.local.entity.SubjectEntity
import com.schoolapp.data.local.entity.TeacherAssignmentEntity
import com.schoolapp.data.local.entity.UserEntity

@Database(
    entities = [
        UserEntity::class,
        SectionEntity::class,
        SubjectEntity::class,
        StudentEntity::class,
        AssessmentCycleEntity::class,
        AssessmentEntity::class,
        ScoreEntity::class,
        ResultSnapshotEntity::class,
        TeacherAssignmentEntity::class,
    ],
    version = 1,
    exportSchema = false,
)
@TypeConverters(AppTypeConverters::class)
abstract class SchoolDatabase : RoomDatabase() {
    abstract fun userDao(): UserDao
    abstract fun sectionDao(): SectionDao
    abstract fun subjectDao(): SubjectDao
    abstract fun studentDao(): StudentDao
    abstract fun assessmentCycleDao(): AssessmentCycleDao
    abstract fun assessmentDao(): AssessmentDao
    abstract fun scoreDao(): ScoreDao
    abstract fun resultSnapshotDao(): ResultSnapshotDao
    abstract fun teacherAssignmentDao(): TeacherAssignmentDao
    abstract fun localDataDao(): LocalDataDao

    companion object {
        @Volatile
        private var INSTANCE: SchoolDatabase? = null

        fun get(context: Context): SchoolDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    SchoolDatabase::class.java,
                    "school_app.db",
                ).fallbackToDestructiveMigration().build().also { INSTANCE = it }
            }
    }
}
