package com.schoolapp.core.model

enum class UserRole { ADMIN, TEACHER, STUDENT }

enum class ScoreStatus { PRESENT, ABSENT, EXEMPT }

enum class PassStatus { PASS, FAIL, INCOMPLETE }

enum class RiskLevel { LOW, MEDIUM, HIGH }

enum class AdminDestination { DASHBOARD, ROSTER, GRADEBOOK, SETUP }

enum class StudentProfileTab { OVERVIEW, SUBJECTS, MARKS_ENTRY, TRENDS }

data class SchoolUser(
    val uid: String,
    val schoolId: String,
    val role: UserRole,
    val displayName: String,
    val code: String,
    val active: Boolean,
)

data class GradeSection(
    val sectionId: String,
    val schoolId: String,
    val grade: Int,
    val sectionName: String,
)

data class Subject(
    val subjectId: String,
    val schoolId: String,
    val grade: Int,
    val name: String,
    val passThresholdPct: Double = 33.0,
    val mandatory: Boolean = true,
)

data class Student(
    val studentId: String,
    val schoolId: String,
    val sectionId: String,
    val rollNumber: Int,
    val fullName: String,
    val active: Boolean = true,
)

data class AssessmentCycle(
    val cycleId: String,
    val schoolId: String,
    val name: String,
    val startDateIso: String,
    val endDateIso: String,
    val published: Boolean,
)

data class Assessment(
    val assessmentId: String,
    val cycleId: String,
    val sectionId: String,
    val subjectId: String,
    val title: String,
    val maxMarks: Double,
    val weight: Double = 1.0,
)

data class ScoreEntry(
    val scoreId: String,
    val assessmentId: String,
    val studentId: String,
    val obtainedMarks: Double,
    val status: ScoreStatus,
)

data class BulkScorePatch(
    val assessmentId: String,
    val rows: List<ScoreEntry>,
)

data class PublishReadiness(
    val ready: Boolean,
    val missingEntries: Int,
    val totalRequiredEntries: Int,
    val message: String,
)

data class SubjectResult(
    val subjectId: String,
    val percentage: Double,
    val complete: Boolean,
)

data class ResultSnapshot(
    val snapshotId: String,
    val cycleId: String,
    val sectionId: String,
    val studentId: String,
    val subjectPercentages: Map<String, Double>,
    val overallPercentage: Double?,
    val passStatus: PassStatus,
    val riskLevel: RiskLevel,
)

data class AcademicContext(
    val grade: Int?,
    val sectionId: String?,
    val cycleId: String?,
    val subjectId: String?,
    val assessmentId: String?,
)

data class TeacherAssignment(
    val assignmentId: String,
    val schoolId: String,
    val sectionId: String,
    val subjectId: String,
    val teacherUid: String,
)

data class SectionSummary(
    val cycleId: String,
    val sectionId: String,
    val averagePct: Double,
    val passCount: Int,
    val failCount: Int,
    val incompleteCount: Int,
    val topperStudentId: String?,
    val riskDistribution: Map<RiskLevel, Int>,
)
