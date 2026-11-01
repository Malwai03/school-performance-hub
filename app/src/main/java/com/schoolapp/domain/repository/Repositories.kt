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

interface AnalyticsRepository {
    suspend fun getStudentTrend(studentId: String): List<ResultSnapshot>
    suspend fun getSectionSummary(cycleId: String, sectionId: String): SectionSummary
}
