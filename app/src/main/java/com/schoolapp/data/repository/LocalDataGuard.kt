package com.schoolapp.data.repository

import com.schoolapp.core.logic.DemoLogin
import com.schoolapp.core.logic.LocalDataPolicy
import com.schoolapp.core.logic.SchoolScope
import com.schoolapp.core.model.Assessment
import com.schoolapp.core.model.AssessmentCycle
import com.schoolapp.core.model.GradeSection
import com.schoolapp.core.model.SchoolUser
import com.schoolapp.core.model.ScoreEntry
import com.schoolapp.core.model.ScoreStatus
import com.schoolapp.core.model.Student
import com.schoolapp.core.model.Subject
import com.schoolapp.core.model.TeacherAssignment
import com.schoolapp.core.model.UserRole
import com.schoolapp.data.local.SchoolDatabase
import com.schoolapp.data.local.entity.toEntity
import com.schoolapp.data.sync.DataStoreInboundState
import com.schoolapp.domain.repository.UnsyncedMarksException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Moves the local Room data between accounts according to [LocalDataPolicy]. Startup, sign-in
 * and sign-out all change the data owner under one lock so they cannot interleave (for example
 * the startup demo seed landing in a real account's freshly cleared database).
 *
 * Downloads from the server (inbound sync) write each page under the same lock, so a page can
 * never land in data that a sign-out or another account's sign-in has just cleared.
 */
internal class LocalDataGuard(
    private val db: SchoolDatabase,
    private val sessionStore: SessionStore,
    private val demoLoginEnabled: Boolean,
    private val syncState: DataStoreInboundState,
) {
    private val mutex = Mutex()

    /**
     * Goes up every time the local data is cleared or handed to another owner. A download
     * compares it before each write: one started before a clear never writes after it, even for
     * the same account signing straight back in.
     */
    @Volatile
    var generation: Long = 0
        private set

    suspend fun <T> withLock(block: suspend () -> T): T = mutex.withLock { block() }

    /**
     * Startup: drop a saved session that may not be restored, record an owner for data left by
     * older app versions, and seed the demo data in demo builds when the database is free.
     */
    suspend fun prepareAtStartup(firebaseUid: String?) = mutex.withLock {
        val sessionUid = sessionStore.getUserId()
        val kind = LocalDataPolicy.sessionKind(sessionUid, firebaseUid, demoLoginEnabled)
        if (sessionUid != null && kind == LocalDataPolicy.SessionKind.NONE) {
            sessionStore.clear()
        }
        val recorded = sessionStore.getDataOwner()
        val owner = LocalDataPolicy.ownerForUntrackedData(recorded, sessionUid, kind)
        if (owner != null && owner != recorded) {
            sessionStore.saveDataOwner(owner)
        }
        seedDemoIfFreeLocked()
    }

    /**
     * Makes the local data belong to [incomingOwner] before that account sees any of it. Another
     * owner's data is deleted; if it still holds unsynced marks this throws
     * [UnsyncedMarksException] instead, unless [discardConfirmed]. Call under [withLock].
     */
    suspend fun claimForLocked(incomingOwner: String, discardConfirmed: Boolean) {
        val action = LocalDataPolicy.onSignIn(
            currentOwner = sessionStore.getDataOwner(),
            incomingOwner = incomingOwner,
            hasLocalData = db.localDataDao().countAllRows() > 0,
            unsyncedMarks = db.scoreDao().countUnsynced(),
            discardConfirmed = discardConfirmed,
        )
        when (action) {
            LocalDataPolicy.SignInAction.Keep -> Unit
            LocalDataPolicy.SignInAction.Clear -> {
                db.clearAllTables()
                syncState.clear()
            }
            is LocalDataPolicy.SignInAction.ConfirmDiscard ->
                throw UnsyncedMarksException(action.unsyncedMarks, fromAnotherAccount = true)
        }
        generation++
        sessionStore.saveDataOwner(incomingOwner)
        // A sign-in always downloads when its first screen opens.
        syncState.markDue()
    }

    /** Deletes all local data and forgets its owner. Call under [withLock]. */
    suspend fun clearLocked() {
        db.clearAllTables()
        syncState.clear()
        generation++
        sessionStore.clearDataOwner()
    }

    /** Seeds the bundled demo data if this is a demo build and nobody else's data is here. Call under [withLock]. */
    suspend fun seedDemoIfFreeLocked() {
        val owner = sessionStore.getDataOwner()
        val hasLocalData = db.localDataDao().countAllRows() > 0
        if (!LocalDataPolicy.shouldSeedDemo(demoLoginEnabled, owner, hasLocalData)) return
        seedDemoData()
        sessionStore.saveDataOwner(LocalDataPolicy.DEMO_OWNER)
    }

    private suspend fun seedDemoData() {
        // Demo/seed data lives in the default school; demo logins are cached with it.
        val schoolId = SchoolScope.DEFAULT_SCHOOL_ID
        db.userDao().upsert(
            SchoolUser(DemoLogin.ADMIN_UID, schoolId, UserRole.ADMIN, "Principal Admin", DemoLogin.ADMIN_CODE, true).toEntity(),
        )
        db.userDao().upsert(
            SchoolUser(DemoLogin.TEACHER_UID, schoolId, UserRole.TEACHER, "Teacher Maya", DemoLogin.TEACHER_CODE, true).toEntity(),
        )
        db.userDao().upsert(
            SchoolUser(DemoLogin.STUDENT_UID, schoolId, UserRole.STUDENT, "Student Rohan", DemoLogin.STUDENT_CODE, true).toEntity(),
        )

        val section = GradeSection("g6_a", schoolId, 6, "A")
        db.sectionDao().upsert(section.toEntity())

        val subjects = listOf(
            Subject("sub_math_6", schoolId, 6, "Mathematics", 33.0, true),
            Subject("sub_science_6", schoolId, 6, "Science", 33.0, true),
            Subject("sub_english_6", schoolId, 6, "English", 33.0, true),
        )
        subjects.forEach { db.subjectDao().upsert(it.toEntity()) }

        val students = listOf(
            Student(DemoLogin.STUDENT_UID, schoolId, section.sectionId, 1, "Rohan Das", true),
            Student("student-2", schoolId, section.sectionId, 2, "Aisha Noor", true),
            Student("student-3", schoolId, section.sectionId, 3, "Karan Shah", true),
        )
        students.forEach { db.studentDao().upsert(it.toEntity()) }

        db.teacherAssignmentDao().upsert(
            TeacherAssignment(
                assignmentId = "assign-1",
                schoolId = schoolId,
                sectionId = section.sectionId,
                subjectId = "sub_math_6",
                teacherUid = DemoLogin.TEACHER_UID,
            ).toEntity(),
        )

        val cycle = AssessmentCycle(
            cycleId = "cycle_2026_ut1",
            schoolId = schoolId,
            name = "Unit Test - March",
            startDateIso = "2026-03-01",
            endDateIso = "2026-03-15",
            published = false,
        )
        db.assessmentCycleDao().upsert(cycle.toEntity())

        val assessments = listOf(
            Assessment("assess_math_ut1", cycle.cycleId, section.sectionId, "sub_math_6", "Math Unit Test", 100.0, 1.0),
            Assessment("assess_science_ut1", cycle.cycleId, section.sectionId, "sub_science_6", "Science Unit Test", 100.0, 1.0),
            Assessment("assess_english_ut1", cycle.cycleId, section.sectionId, "sub_english_6", "English Unit Test", 100.0, 1.0),
        )
        assessments.forEach { db.assessmentDao().upsert(it.toEntity()) }

        val demoScores = listOf(
            ScoreEntry("score_1", "assess_math_ut1", DemoLogin.STUDENT_UID, 78.0, ScoreStatus.PRESENT),
            ScoreEntry("score_2", "assess_science_ut1", DemoLogin.STUDENT_UID, 72.0, ScoreStatus.PRESENT),
            ScoreEntry("score_3", "assess_english_ut1", DemoLogin.STUDENT_UID, 85.0, ScoreStatus.PRESENT),
            ScoreEntry("score_4", "assess_math_ut1", "student-2", 91.0, ScoreStatus.PRESENT),
            ScoreEntry("score_5", "assess_science_ut1", "student-2", 88.0, ScoreStatus.PRESENT),
            ScoreEntry("score_6", "assess_english_ut1", "student-2", 84.0, ScoreStatus.PRESENT),
        )
        demoScores.forEach { db.scoreDao().upsert(it.toEntity(synced = true)) }
    }
}
