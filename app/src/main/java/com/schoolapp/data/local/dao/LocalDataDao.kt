package com.schoolapp.data.local.dao

import androidx.room.Dao
import androidx.room.Query

@Dao
interface LocalDataDao {
    /** Rows across every table; 0 means the local database holds nobody's data. */
    @Query(
        """
        SELECT (SELECT COUNT(*) FROM users)
            + (SELECT COUNT(*) FROM sections)
            + (SELECT COUNT(*) FROM subjects)
            + (SELECT COUNT(*) FROM students)
            + (SELECT COUNT(*) FROM assessment_cycles)
            + (SELECT COUNT(*) FROM assessments)
            + (SELECT COUNT(*) FROM scores)
            + (SELECT COUNT(*) FROM result_snapshots)
            + (SELECT COUNT(*) FROM teacher_assignments)
        """,
    )
    suspend fun countAllRows(): Int
}
