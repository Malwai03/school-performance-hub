package com.schoolapp.domain.repository

import com.schoolapp.core.model.Assessment
import com.schoolapp.core.model.AssessmentCycle
import com.schoolapp.core.model.BulkScorePatch
import com.schoolapp.core.model.GradeSection
import com.schoolapp.core.model.PublishReadiness
import com.schoolapp.core.model.ResultSnapshot
import com.schoolapp.core.model.SchoolUser
import com.schoolapp.core.model.ScoreEntry
import com.schoolapp.core.model.SectionSummary
import com.schoolapp.core.model.Student
import com.schoolapp.core.model.Subject

interface AuthRepository {
    suspend fun signIn(loginCode: String, password: String): SchoolUser
    suspend fun signOut()
    suspend fun currentUser(): SchoolUser?
}

interface MasterDataRepository {
    suspend fun upsertSection(section: GradeSection)
    suspend fun upsertSubject(subject: Subject)
    suspend fun upsertStudent(student: Student)
    suspend fun upsertAssessmentCycle(cycle: AssessmentCycle)
    suspend fun upsertAssessment(assessment: Assessment)
    suspend fun assignTeacher(sectionId: String, subjectId: String, teacherUid: String)
    suspend fun getSections(): List<GradeSection>
    suspend fun getSubjectsBySection(sectionId: String): List<Subject>
    suspend fun getStudentsBySection(sectionId: String): List<Student>
    suspend fun getCycles(): List<AssessmentCycle>
    suspend fun getAssessments(sectionId: String, cycleId: String): List<Assessment>
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
