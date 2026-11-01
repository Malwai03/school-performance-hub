package com.schoolapp.domain.repository

import com.schoolapp.core.logic.UnsyncedMarksChoice
import com.schoolapp.core.model.Assessment
import com.schoolapp.core.model.AssessmentCycle
import com.schoolapp.core.model.BulkScorePatch
import com.schoolapp.core.model.GradeSection
import com.schoolapp.core.model.ManagedCredentialResult
import com.schoolapp.core.model.PublishReadiness
import com.schoolapp.core.model.ResultSnapshot
import com.schoolapp.core.model.SchoolUser
import com.schoolapp.core.model.ScoreEntry
import com.schoolapp.core.model.SectionSummary
import com.schoolapp.core.model.Student
import com.schoolapp.core.model.Subject
import com.schoolapp.core.model.TeacherAssignment
import com.schoolapp.core.model.UserRole

/**
 * Marks entered on this device have not reached the server, so going ahead would delete them.
 * [fromAnotherAccount]: they belong to an account other than the one signing in.
 */
class UnsyncedMarksException(
    val unsyncedMarks: Int,
    val fromAnotherAccount: Boolean,
) : IllegalStateException(
    if (fromAnotherAccount) {
        "This device has $unsyncedMarks marks from another account that were never uploaded."
    } else {
        "$unsyncedMarks marks have not been uploaded yet."
    },
)

sealed interface SignOutResult {
    data object SignedOut : SignOutResult

    /** Still signed in: these marks could not be uploaded. Ask the user, then call again with their choice. */
    data class UnsyncedMarks(val count: Int) : SignOutResult
}

interface AuthRepository {
    /**
     * Signs in and makes the local data belong to this account. If the device still holds
     * another account's unsynced marks this throws [UnsyncedMarksException] and signs nobody in,
     * unless [discardOtherAccountsUnsyncedMarks].
     */
    suspend fun signIn(
        loginCode: String,
        password: String,
        discardOtherAccountsUnsyncedMarks: Boolean = false,
    ): SchoolUser

    /**
     * Uploads this account's unsynced marks one last time, then deletes its local data and signs
     * out. If marks are still not uploaded, stays signed in and returns
     * [SignOutResult.UnsyncedMarks]; the user's answer comes back as [unsyncedMarks].
     */
    suspend fun signOut(unsyncedMarks: UnsyncedMarksChoice = UnsyncedMarksChoice.ASK): SignOutResult
    suspend fun currentUser(): SchoolUser?
    suspend fun changePassword(currentPassword: String, newPassword: String)
    suspend fun createManagedAccount(
        role: UserRole,
        loginCode: String,
        displayName: String,
        studentId: String? = null,
        sectionId: String? = null,
    ): ManagedCredentialResult
    suspend fun resetManagedPassword(loginCode: String): ManagedCredentialResult
}

interface MasterDataRepository {
    suspend fun upsertSection(section: GradeSection)
    suspend fun upsertSubject(subject: Subject)
    suspend fun upsertStudent(student: Student)
    suspend fun deleteSection(sectionId: String)
    suspend fun deleteSubject(subjectId: String)
    suspend fun deleteStudent(studentId: String)
    suspend fun upsertAssessmentCycle(cycle: AssessmentCycle)
    suspend fun upsertAssessment(assessment: Assessment)
    suspend fun assignTeacher(sectionId: String, subjectId: String, teacherUid: String)
    suspend fun getSections(): List<GradeSection>
    suspend fun getSubjectsBySection(sectionId: String): List<Subject>
    suspend fun getStudentsBySection(sectionId: String): List<Student>
    suspend fun getCycles(): List<AssessmentCycle>
    suspend fun getAssessments(sectionId: String, cycleId: String): List<Assessment>
    suspend fun getTeacherAssignments(teacherUid: String): List<TeacherAssignment>
}

interface MarksRepository {
    suspend fun upsertScore(score: ScoreEntry)
    suspend fun upsertScoresBulk(patch: BulkScorePatch)
    suspend fun getScoresByAssessment(assessmentId: String): List<ScoreEntry>
    suspend fun getScoreByAssessmentAndStudent(assessmentId: String, studentId: String): ScoreEntry?
    suspend fun bulkImport(fileUri: String, type: String, entityType: String)
    suspend fun validatePublishReadiness(cycleId: String, sectionId: String): PublishReadiness
    suspend fun publishCycle(cycleId: String, sectionId: String)
}

/** A student's results as stored on this device, after checking them with the server when possible. */
data class StudentTrend(
    val snapshots: List<ResultSnapshot>,
    /** The server could not be asked just now, so these may be out of date. */
    val serverUnreachable: Boolean = false,
)

interface AnalyticsRepository {
    /**
     * The student's results. With a real signed-in account they are checked with the server
     * first (new, corrected and withdrawn results); otherwise, or when the server cannot be
     * asked, the copy on this device is returned.
     */
    suspend fun getStudentTrend(studentId: String): StudentTrend
    suspend fun getSectionSummary(cycleId: String, sectionId: String): SectionSummary
}

/** What started a download from the school server into this device. */
enum class SyncTrigger {
    /** A screen opened (including right after sign-in): skipped if a run finished in the last hour. */
    APP_OPEN,

    /** Pull-to-refresh, the refresh button, or right after an import: always runs. */
    MANUAL,

    /** The background WorkManager job. */
    PERIODIC,
}

enum class SyncOutcome {
    /** Everything this account downloads is now up to date. */
    DONE,

    /** Stopped at the per-run bound: the rest comes with the next run. */
    PARTIAL,

    /** A run finished recently enough (automatic trigger only). */
    SKIPPED,

    /** Nothing to download from: demo session, no Firebase, nobody signed in, or data owned by another account. */
    UNAVAILABLE,

    /** The server could not be reached or refused: what is on this device is kept. */
    FAILED,

    /** The account changed during the run, which stopped before writing anything for it. */
    ACCOUNT_CHANGED,
}

data class SyncReport(
    val outcome: SyncOutcome,
    /** Rows stored from the server. */
    val downloaded: Int = 0,
    /** Synced rows removed because the server no longer has them (or they left this account's scope). */
    val removed: Int = 0,
    /** Server versions not applied because this device holds a newer, not yet uploaded change. */
    val keptLocal: Int = 0,
)

/**
 * Brings the signed-in account's share of its school (see core/logic/InboundScope) down from
 * Firestore into the local database. Uploads stay with their own paths.
 */
interface SyncRepository {
    suspend fun syncNow(trigger: SyncTrigger): SyncReport
}
