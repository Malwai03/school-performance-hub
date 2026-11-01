package com.schoolapp.ui.admin

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.schoolapp.core.logic.SchoolScope
import com.schoolapp.core.model.AcademicContext
import com.schoolapp.core.model.AdminDestination
import com.schoolapp.core.model.Assessment
import com.schoolapp.core.model.AssessmentCycle
import com.schoolapp.core.model.BulkScorePatch
import com.schoolapp.core.model.GradeSection
import com.schoolapp.core.model.ManagedCredentialResult
import com.schoolapp.core.model.ResultSnapshot
import com.schoolapp.core.model.ScoreEntry
import com.schoolapp.core.model.ScoreStatus
import com.schoolapp.core.model.SectionSummary
import com.schoolapp.core.model.Student
import com.schoolapp.core.model.StudentProfileTab
import com.schoolapp.core.model.Subject
import com.schoolapp.core.model.UserRole
import com.schoolapp.domain.repository.AnalyticsRepository
import com.schoolapp.domain.repository.AuthRepository
import com.schoolapp.domain.repository.MarksRepository
import com.schoolapp.domain.repository.MasterDataRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.random.Random

data class StudentAssessmentProfileRow(
    val assessment: Assessment,
    val subjectName: String,
)

data class LeaderboardRow(
    val studentId: String,
    val rollNumber: Int,
    val fullName: String,
    val averagePct: Double,
    val rank: Int,
    val hasScores: Boolean,
)

data class DashboardStudentRow(
    val studentId: String,
    val rollNumber: Int,
    val studentName: String,
    val examName: String,
    val subjectName: String,
    val marks: Double?,
    val percentage: Double?,
    val averagePct: Double?,
    val rank: Int?,
    val passStatus: String,
    val subjectAveragesSummary: String,
    val subjectMarks: List<DashboardSubjectMark>,
)

data class DashboardSubjectMark(
    val subjectName: String,
    val obtained: Double?,
    val max: Double,
    val percentage: Double?,
)

data class AdminDashboardState(
    val loading: Boolean = true,
    val selectedTab: Int = 0,
    val destination: AdminDestination = AdminDestination.DASHBOARD,
    val context: AcademicContext = AcademicContext(
        grade = null,
        sectionId = null,
        cycleId = null,
        subjectId = null,
        assessmentId = null,
    ),
    val totalStudents: Int = 0,
    val currentUserName: String = "",
    val currentUserCode: String = "",
    val currentUserRole: UserRole? = null,
    val schoolName: String = "",
    val passRatePct: Double = 0.0,
    val highRiskCount: Int = 0,
    val sparkline: List<Float> = emptyList(),
    val sections: List<GradeSection> = emptyList(),
    val cycles: List<AssessmentCycle> = emptyList(),
    val selectedCycleId: String? = null,
    val sectionSummaries: Map<String, SectionSummary> = emptyMap(),
    val studentsBySection: Map<String, List<Student>> = emptyMap(),
    val subjectsBySection: Map<String, List<Subject>> = emptyMap(),
    val assessmentsBySection: Map<String, List<Assessment>> = emptyMap(),
    val showAddStudentDialog: Boolean = false,
    val showCreateCycleDialog: Boolean = false,
    val showCreateSectionDialog: Boolean = false,
    val showCreateSubjectDialog: Boolean = false,
    val showCreateAssessmentDialog: Boolean = false,
    val selectedStudentProfileId: String? = null,
    val profileLoading: Boolean = false,
    val profileSaving: Boolean = false,
    val profileStudent: Student? = null,
    val profileSection: GradeSection? = null,
    val profileCycles: List<AssessmentCycle> = emptyList(),
    val profileSelectedCycleId: String? = null,
    val profileAssessments: List<StudentAssessmentProfileRow> = emptyList(),
    val profileScoreDrafts: Map<String, String> = emptyMap(),
    val profileStatusByAssessment: Map<String, ScoreStatus> = emptyMap(),
    val profileTrend: List<ResultSnapshot> = emptyList(),
    val profileSectionAverage: Double = 0.0,
    val profileSelectedTab: StudentProfileTab = StudentProfileTab.OVERVIEW,
    val gradebookStudents: List<Student> = emptyList(),
    val gradebookScoreDrafts: Map<String, String> = emptyMap(),
    val gradebookStatusByStudent: Map<String, ScoreStatus> = emptyMap(),
    val gradebookValidationByStudent: Map<String, String> = emptyMap(),
    val gradebookMaxMarks: Double? = null,
    val sectionLeaderboard: List<LeaderboardRow> = emptyList(),
    val studentAverageById: Map<String, Double> = emptyMap(),
    val studentRankById: Map<String, Int> = emptyMap(),
    val sectionAverageScorePct: Double = 0.0,
    val sectionPassPct: Double = 0.0,
    val sectionTopperStudentId: String? = null,
    val dashboardRows: List<DashboardStudentRow> = emptyList(),
    val dashboardSearchDone: Boolean = false,
    val passwordChangeLoading: Boolean = false,
    val passwordChangeMessage: String? = null,
    val passwordChangeError: Boolean = false,
    val managedCredentialLoading: Boolean = false,
    val managedCredentialResult: ManagedCredentialResult? = null,
    val managedCredentialMessage: String? = null,
    val managedCredentialError: Boolean = false,
    val gradebookSaving: Boolean = false,
    val publishReadinessMessage: String? = null,
    val publishReadinessError: Boolean = false,
    val error: String? = null,
    val info: String? = null,
)

class AdminViewModel(
    private val authRepository: AuthRepository,
    private val masterDataRepository: MasterDataRepository,
    private val analyticsRepository: AnalyticsRepository,
    private val marksRepository: MarksRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(AdminDashboardState())
    val uiState: StateFlow<AdminDashboardState> = _uiState.asStateFlow()

    init { refresh() }

    private fun destinationToTab(destination: AdminDestination): Int = when (destination) {
        AdminDestination.DASHBOARD -> 0
        AdminDestination.ROSTER -> 1
        AdminDestination.GRADEBOOK -> 2
        AdminDestination.SETUP -> 3
    }

    private fun tabToDestination(tab: Int): AdminDestination = when (tab) {
        1 -> AdminDestination.ROSTER
        2 -> AdminDestination.GRADEBOOK
        3 -> AdminDestination.SETUP
        else -> AdminDestination.DASHBOARD
    }

    fun navigateTo(destination: AdminDestination) {
        _uiState.value = _uiState.value.copy(
            destination = destination,
            selectedTab = destinationToTab(destination),
            error = null,
            info = null,
        )
        refresh()
    }

    fun selectTab(tab: Int) {
        navigateTo(tabToDestination(tab))
    }

    fun selectContextGrade(grade: Int) {
        _uiState.value = _uiState.value.copy(
            context = _uiState.value.context.copy(
                grade = grade,
                sectionId = null,
                subjectId = null,
                assessmentId = null,
            ),
            dashboardRows = emptyList(),
            dashboardSearchDone = false,
            error = null,
            info = null,
        )
        refresh()
    }

    fun selectContextSection(sectionId: String) {
        _uiState.value = _uiState.value.copy(
            context = _uiState.value.context.copy(
                sectionId = sectionId,
                subjectId = null,
                assessmentId = null,
            ),
            dashboardRows = emptyList(),
            dashboardSearchDone = false,
            error = null,
            info = null,
        )
        refresh()
    }

    fun selectContextCycle(cycleId: String) {
        _uiState.value = _uiState.value.copy(
            context = _uiState.value.context.copy(
                cycleId = cycleId,
                assessmentId = null,
            ),
            selectedCycleId = cycleId,
            dashboardRows = emptyList(),
            dashboardSearchDone = false,
            error = null,
            info = null,
        )
        refresh()
    }

    fun selectContextSubject(subjectId: String) {
        _uiState.value = _uiState.value.copy(
            context = _uiState.value.context.copy(
                subjectId = subjectId,
                assessmentId = null,
            ),
            dashboardRows = emptyList(),
            dashboardSearchDone = false,
            error = null,
            info = null,
        )
        refresh()
    }

    fun selectContextAssessment(assessmentId: String) {
        _uiState.value = _uiState.value.copy(
            context = _uiState.value.context.copy(assessmentId = assessmentId),
            dashboardRows = emptyList(),
            dashboardSearchDone = false,
            error = null,
            info = null,
        )
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            val previous = _uiState.value
            _uiState.value = previous.copy(loading = true, error = null, info = null)
            runCatching {
                val currentUser = authRepository.currentUser()
                val sections = masterDataRepository.getSections()
                val cycles = masterDataRepository.getCycles()
                val selectedCycleId = previous.context.cycleId ?: previous.selectedCycleId ?: cycles.firstOrNull()?.cycleId

                val studentsBySection = mutableMapOf<String, List<Student>>()
                val subjectsBySection = mutableMapOf<String, List<Subject>>()
                val assessmentsBySection = mutableMapOf<String, List<Assessment>>()
                var totalStudents = 0

                sections.forEach { section ->
                    val students = masterDataRepository.getStudentsBySection(section.sectionId)
                    val subjects = masterDataRepository.getSubjectsBySection(section.sectionId)
                    studentsBySection[section.sectionId] = students
                    subjectsBySection[section.sectionId] = subjects
                    totalStudents += students.size

                    if (selectedCycleId != null) {
                        val assessments = masterDataRepository.getAssessments(section.sectionId, selectedCycleId)
                        assessmentsBySection[section.sectionId] = assessments
                    }
                }

                val summaries = if (selectedCycleId != null) {
                    sections.mapNotNull { section ->
                        runCatching { analyticsRepository.getSectionSummary(selectedCycleId, section.sectionId) }.getOrNull()
                    }
                } else {
                    emptyList()
                }

                val totalEvaluated = summaries.sumOf { it.passCount + it.failCount + it.incompleteCount }
                val totalPassed = summaries.sumOf { it.passCount }
                val passRate = if (totalEvaluated == 0) 0.0 else (totalPassed.toDouble() / totalEvaluated) * 100.0
                val highRisk = summaries.sumOf { it.riskDistribution[com.schoolapp.core.model.RiskLevel.HIGH] ?: 0 }
                val spark = summaries.map { it.averagePct.toFloat() }

                val contextSeed = previous.context.copy(cycleId = selectedCycleId)
                val context = coerceContext(
                    sections = sections,
                    cycles = cycles,
                    subjectsBySection = subjectsBySection,
                    assessmentsBySection = assessmentsBySection,
                    context = contextSeed,
                )
                val contextChanged = context != previous.context

                val gradebookStudents = context.sectionId
                    ?.let { studentsBySection[it] }
                    .orEmpty()
                    .sortedBy { it.rollNumber }
                val selectedAssessment = context.sectionId
                    ?.let { sectionId -> assessmentsBySection[sectionId].orEmpty() }
                    .orEmpty()
                    .firstOrNull { it.assessmentId == context.assessmentId }
                val gradebookScores = selectedAssessment
                    ?.let { marksRepository.getScoresByAssessment(it.assessmentId) }
                    .orEmpty()
                    .associateBy { it.studentId }
                val gradebookDrafts = gradebookStudents.associate { student ->
                    val marks = gradebookScores[student.studentId]?.obtainedMarks
                    student.studentId to when {
                        marks == null -> ""
                        marks % 1.0 == 0.0 -> marks.toInt().toString()
                        else -> "%.2f".format(marks).trimEnd('0').trimEnd('.')
                    }
                }
                val gradebookStatus = gradebookStudents.associate { student ->
                    student.studentId to (gradebookScores[student.studentId]?.status ?: ScoreStatus.PRESENT)
                }
                val cycleAssessments = context.sectionId
                    ?.let { sectionId -> assessmentsBySection[sectionId].orEmpty() }
                    .orEmpty()
                val scoresByAssessmentId = cycleAssessments.associate { assessment ->
                    assessment.assessmentId to marksRepository.getScoresByAssessment(assessment.assessmentId).associateBy { it.studentId }
                }
                data class LeaderSeed(
                    val student: Student,
                    val averagePct: Double,
                    val hasScores: Boolean,
                )
                val rankedSeeds = gradebookStudents.map { student ->
                    var totalObtained = 0.0
                    var totalMax = 0.0
                    cycleAssessments.forEach { assessment ->
                        val score = scoresByAssessmentId[assessment.assessmentId]?.get(student.studentId)
                        if (score != null) {
                            totalMax += assessment.maxMarks
                            totalObtained += when (score.status) {
                                ScoreStatus.PRESENT -> score.obtainedMarks
                                ScoreStatus.ABSENT, ScoreStatus.EXEMPT -> 0.0
                            }
                        }
                    }
                    val hasScores = totalMax > 0.0
                    LeaderSeed(
                        student = student,
                        averagePct = if (hasScores) (totalObtained / totalMax) * 100.0 else 0.0,
                        hasScores = hasScores,
                    )
                }.sortedWith(
                    compareByDescending<LeaderSeed> { it.hasScores }
                        .thenByDescending { it.averagePct }
                        .thenBy { it.student.rollNumber },
                )

                var rankCursor = 0
                val leaderboard = rankedSeeds.map { seed ->
                    val rank = if (seed.hasScores) {
                        rankCursor += 1
                        rankCursor
                    } else {
                        0
                    }
                    LeaderboardRow(
                        studentId = seed.student.studentId,
                        rollNumber = seed.student.rollNumber,
                        fullName = seed.student.fullName,
                        averagePct = seed.averagePct,
                        rank = rank,
                        hasScores = seed.hasScores,
                    )
                }
                val scoredRows = leaderboard.filter { it.hasScores }
                val sectionAverageScorePct = scoredRows.map { it.averagePct }.average().takeIf { !it.isNaN() } ?: 0.0
                val sectionPassPct = if (scoredRows.isEmpty()) {
                    0.0
                } else {
                    (scoredRows.count { it.averagePct >= 33.0 }.toDouble() / scoredRows.size) * 100.0
                }
                val sectionTopperId = scoredRows.firstOrNull()?.studentId
                val averageById = leaderboard.associate { it.studentId to it.averagePct }
                val rankById = leaderboard.associate { it.studentId to it.rank }

                AdminDashboardState(
                    loading = false,
                    selectedTab = destinationToTab(previous.destination),
                    destination = previous.destination,
                    context = context,
                    totalStudents = totalStudents,
                    currentUserName = currentUser?.displayName.orEmpty(),
                    currentUserCode = currentUser?.code.orEmpty(),
                    currentUserRole = currentUser?.role,
                    schoolName = displaySchoolName(currentUser?.schoolId),
                    passRatePct = passRate,
                    highRiskCount = highRisk,
                    sparkline = spark.ifEmpty { listOf(62f, 66f, 71f, 69f, 74f, 79f) },
                    sections = sections,
                    cycles = cycles,
                    selectedCycleId = context.cycleId,
                    sectionSummaries = summaries.associateBy { it.sectionId },
                    studentsBySection = studentsBySection,
                    subjectsBySection = subjectsBySection,
                    assessmentsBySection = assessmentsBySection,
                    showAddStudentDialog = previous.showAddStudentDialog,
                    showCreateCycleDialog = previous.showCreateCycleDialog,
                    showCreateSectionDialog = previous.showCreateSectionDialog,
                    showCreateSubjectDialog = previous.showCreateSubjectDialog,
                    showCreateAssessmentDialog = previous.showCreateAssessmentDialog,
                    selectedStudentProfileId = previous.selectedStudentProfileId,
                    profileLoading = previous.profileLoading,
                    profileSaving = previous.profileSaving,
                    profileStudent = previous.profileStudent,
                    profileSection = previous.profileSection,
                    profileCycles = previous.profileCycles,
                    profileSelectedCycleId = previous.profileSelectedCycleId,
                    profileAssessments = previous.profileAssessments,
                    profileScoreDrafts = previous.profileScoreDrafts,
                    profileStatusByAssessment = previous.profileStatusByAssessment,
                    profileTrend = previous.profileTrend,
                    profileSectionAverage = previous.profileSectionAverage,
                    profileSelectedTab = previous.profileSelectedTab,
                    gradebookStudents = gradebookStudents,
                    gradebookScoreDrafts = gradebookDrafts,
                    gradebookStatusByStudent = gradebookStatus,
                    gradebookValidationByStudent = previous.gradebookValidationByStudent,
                    gradebookMaxMarks = selectedAssessment?.maxMarks,
                    sectionLeaderboard = leaderboard,
                    studentAverageById = averageById,
                    studentRankById = rankById,
                    sectionAverageScorePct = sectionAverageScorePct,
                    sectionPassPct = sectionPassPct,
                    sectionTopperStudentId = sectionTopperId,
                    dashboardRows = if (contextChanged) emptyList() else previous.dashboardRows,
                    dashboardSearchDone = if (contextChanged) false else previous.dashboardSearchDone,
                    passwordChangeLoading = previous.passwordChangeLoading,
                    passwordChangeMessage = previous.passwordChangeMessage,
                    passwordChangeError = previous.passwordChangeError,
                    managedCredentialLoading = previous.managedCredentialLoading,
                    managedCredentialResult = previous.managedCredentialResult,
                    managedCredentialMessage = previous.managedCredentialMessage,
                    managedCredentialError = previous.managedCredentialError,
                    gradebookSaving = false,
                    publishReadinessMessage = previous.publishReadinessMessage,
                    publishReadinessError = previous.publishReadinessError,
                    info = previous.info,
                )
            }.onSuccess { _uiState.value = it }
                .onFailure { _uiState.value = _uiState.value.copy(loading = false, error = it.message) }
        }
    }

    private fun coerceContext(
        sections: List<GradeSection>,
        cycles: List<AssessmentCycle>,
        subjectsBySection: Map<String, List<Subject>>,
        assessmentsBySection: Map<String, List<Assessment>>,
        context: AcademicContext,
    ): AcademicContext {
        val grades = sections.map { it.grade }.distinct().sorted()
        val resolvedGrade = context.grade?.takeIf { it in grades } ?: grades.firstOrNull()
        val sectionsForGrade = sections.filter { it.grade == resolvedGrade }
        val resolvedSection = context.sectionId
            ?.takeIf { sectionId -> sectionsForGrade.any { it.sectionId == sectionId } }
            ?: sectionsForGrade.firstOrNull()?.sectionId
        val resolvedCycle = context.cycleId
            ?.takeIf { cycleId -> cycles.any { it.cycleId == cycleId } }
            ?: cycles.firstOrNull()?.cycleId
        val resolvedSubjects = resolvedSection?.let { subjectsBySection[it] }.orEmpty()
        val resolvedSubject = context.subjectId
            ?.takeIf { subjectId -> resolvedSubjects.any { it.subjectId == subjectId } }
            ?: resolvedSubjects.firstOrNull()?.subjectId
        val resolvedAssessments = resolvedSection
            ?.let { assessmentsBySection[it] }
            .orEmpty()
            .let { list ->
                if (resolvedSubject == null) list else list.filter { it.subjectId == resolvedSubject }
            }
        val resolvedAssessment = context.assessmentId
            ?.takeIf { assessmentId -> resolvedAssessments.any { it.assessmentId == assessmentId } }
            ?: resolvedAssessments.firstOrNull()?.assessmentId

        return AcademicContext(
            grade = resolvedGrade,
            sectionId = resolvedSection,
            cycleId = resolvedCycle,
            subjectId = resolvedSubject,
            assessmentId = resolvedAssessment,
        )
    }

    fun selectCycle(cycleId: String) {
        selectContextCycle(cycleId)
    }

    fun clearPasswordChangeMessage() {
        _uiState.value = _uiState.value.copy(passwordChangeMessage = null, passwordChangeError = false)
    }

    fun clearManagedCredentialFeedback() {
        _uiState.value = _uiState.value.copy(
            managedCredentialMessage = null,
            managedCredentialError = false,
            managedCredentialResult = null,
        )
    }

    fun changeMyPassword(currentPassword: String, newPassword: String, confirmPassword: String) {
        val state = _uiState.value
        if (newPassword != confirmPassword) {
            _uiState.value = state.copy(
                passwordChangeMessage = "New password and confirm password do not match",
                passwordChangeError = true,
            )
            return
        }
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                passwordChangeLoading = true,
                passwordChangeMessage = null,
                passwordChangeError = false,
            )
            runCatching {
                authRepository.changePassword(currentPassword = currentPassword, newPassword = newPassword)
            }.onSuccess {
                _uiState.value = _uiState.value.copy(
                    passwordChangeLoading = false,
                    passwordChangeMessage = "Password changed successfully",
                    passwordChangeError = false,
                )
            }.onFailure { error ->
                _uiState.value = _uiState.value.copy(
                    passwordChangeLoading = false,
                    passwordChangeMessage = error.message ?: "Unable to change password",
                    passwordChangeError = true,
                )
            }
        }
    }

    fun createStudentLogin(studentId: String, loginCode: String) {
        val normalizedCode = loginCode.trim().lowercase()
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                managedCredentialLoading = true,
                managedCredentialMessage = null,
                managedCredentialError = false,
                managedCredentialResult = null,
            )
            runCatching {
                require(studentId.isNotBlank()) { "Select a student first" }
                require(normalizedCode.isNotBlank()) { "Login code is required" }
                require(normalizedCode.none { it.isWhitespace() }) { "Login code cannot contain spaces" }

                val student = _uiState.value.studentsBySection.values
                    .flatten()
                    .firstOrNull { it.studentId == studentId }
                    ?: error("Student not found")

                authRepository.createManagedAccount(
                    role = UserRole.STUDENT,
                    loginCode = normalizedCode,
                    displayName = student.fullName,
                    studentId = student.studentId,
                    sectionId = student.sectionId,
                )
            }.onSuccess { result ->
                _uiState.value = _uiState.value.copy(
                    managedCredentialLoading = false,
                    managedCredentialResult = result,
                    managedCredentialMessage = "Student login created. Share the temporary password.",
                    managedCredentialError = false,
                    info = "Student login created",
                    error = null,
                )
                refresh()
            }.onFailure { error ->
                _uiState.value = _uiState.value.copy(
                    managedCredentialLoading = false,
                    managedCredentialMessage = error.message ?: "Unable to create student login",
                    managedCredentialError = true,
                    managedCredentialResult = null,
                )
            }
        }
    }

    fun createTeacherLogin(displayName: String, loginCode: String, sectionId: String?) {
        val normalizedCode = loginCode.trim().lowercase()
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                managedCredentialLoading = true,
                managedCredentialMessage = null,
                managedCredentialError = false,
                managedCredentialResult = null,
            )
            runCatching {
                require(displayName.isNotBlank()) { "Teacher name is required" }
                require(normalizedCode.isNotBlank()) { "Login code is required" }
                require(normalizedCode.none { it.isWhitespace() }) { "Login code cannot contain spaces" }

                authRepository.createManagedAccount(
                    role = UserRole.TEACHER,
                    loginCode = normalizedCode,
                    displayName = displayName.trim(),
                    sectionId = sectionId?.takeIf { it.isNotBlank() },
                )
            }.onSuccess { result ->
                _uiState.value = _uiState.value.copy(
                    managedCredentialLoading = false,
                    managedCredentialResult = result,
                    managedCredentialMessage = "Teacher login created. Share the temporary password.",
                    managedCredentialError = false,
                    info = "Teacher login created",
                    error = null,
                )
                refresh()
            }.onFailure { error ->
                _uiState.value = _uiState.value.copy(
                    managedCredentialLoading = false,
                    managedCredentialMessage = error.message ?: "Unable to create teacher login",
                    managedCredentialError = true,
                    managedCredentialResult = null,
                )
            }
        }
    }

    fun resetManagedUserPassword(loginCode: String) {
        val normalizedCode = loginCode.trim().lowercase()
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                managedCredentialLoading = true,
                managedCredentialMessage = null,
                managedCredentialError = false,
                managedCredentialResult = null,
            )
            runCatching {
                require(normalizedCode.isNotBlank()) { "Login code is required" }
                require(normalizedCode.none { it.isWhitespace() }) { "Login code cannot contain spaces" }
                authRepository.resetManagedPassword(normalizedCode)
            }.onSuccess { result ->
                _uiState.value = _uiState.value.copy(
                    managedCredentialLoading = false,
                    managedCredentialResult = result,
                    managedCredentialMessage = "Password reset complete. Share the new temporary password.",
                    managedCredentialError = false,
                    info = "Password reset complete",
                    error = null,
                )
            }.onFailure { error ->
                _uiState.value = _uiState.value.copy(
                    managedCredentialLoading = false,
                    managedCredentialMessage = error.message ?: "Unable to reset password",
                    managedCredentialError = true,
                    managedCredentialResult = null,
                )
            }
        }
    }

    fun runDashboardSearch() {
        val state = _uiState.value
        val sectionId = state.context.sectionId
        val assessmentId = state.context.assessmentId
        if (sectionId == null || assessmentId == null) {
            _uiState.value = state.copy(error = "Select class, section, and exam first", dashboardSearchDone = false)
            return
        }
        viewModelScope.launch {
            runCatching {
                val cycleId = state.context.cycleId ?: state.selectedCycleId ?: error("Select cycle first")
                val students = masterDataRepository.getStudentsBySection(sectionId).sortedBy { it.rollNumber }
                val assessments = masterDataRepository.getAssessments(sectionId, cycleId)
                val assessment = assessments.firstOrNull { it.assessmentId == assessmentId } ?: error("Exam not found")
                val subjectMap = masterDataRepository.getSubjectsBySection(sectionId)
                    .associate { it.subjectId to it }
                val mandatorySubjectIds = subjectMap.values.filter { it.mandatory }.map { it.subjectId }.toSet()
                val scoreMapByAssessment = assessments.associate { exam ->
                    exam.assessmentId to marksRepository.getScoresByAssessment(exam.assessmentId).associateBy { it.studentId }
                }
                val selectedScoreMap = scoreMapByAssessment[assessmentId].orEmpty()
                val groupedBySubject = assessments.groupBy { it.subjectId }
                data class RowSeed(
                    val row: DashboardStudentRow,
                    val rankMetric: Double?,
                )
                val seededRows = students.map { student ->
                    val score = selectedScoreMap[student.studentId]
                    val marks = score?.let {
                        when (it.status) {
                            ScoreStatus.PRESENT -> it.obtainedMarks
                            ScoreStatus.ABSENT, ScoreStatus.EXEMPT -> 0.0
                        }
                    }
                    val mandatoryAssessments = assessments.filter { it.subjectId in mandatorySubjectIds }
                    val overallMax = mandatoryAssessments.sumOf { it.maxMarks }
                    val totalObtained = mandatoryAssessments.sumOf { exam ->
                        val examScore = scoreMapByAssessment[exam.assessmentId]?.get(student.studentId) ?: return@sumOf 0.0
                        when (examScore.status) {
                            ScoreStatus.PRESENT -> examScore.obtainedMarks
                            ScoreStatus.ABSENT, ScoreStatus.EXEMPT -> 0.0
                        }
                    }
                    val overallAverage = if (overallMax > 0.0) (totalObtained / overallMax) * 100.0 else null
                    val subjectMarks = groupedBySubject.entries
                        .sortedBy { subjectMap[it.key]?.name ?: it.key }
                        .map { (subjectId, exams) ->
                            val subjectName = subjectMap[subjectId]?.name ?: subjectId
                            val totalSubjectMax = exams.sumOf { it.maxMarks }
                            val hasAnyEntry = exams.any { scoreMapByAssessment[it.assessmentId]?.containsKey(student.studentId) == true }
                            val obtained = exams.sumOf { exam ->
                                val examScore = scoreMapByAssessment[exam.assessmentId]?.get(student.studentId) ?: return@sumOf 0.0
                                when (examScore.status) {
                                    ScoreStatus.PRESENT -> examScore.obtainedMarks
                                    ScoreStatus.ABSENT, ScoreStatus.EXEMPT -> 0.0
                                }
                            }
                            DashboardSubjectMark(
                                subjectName = subjectName,
                                obtained = if (hasAnyEntry) obtained else null,
                                max = totalSubjectMax,
                                percentage = if (hasAnyEntry && totalSubjectMax > 0.0) (obtained / totalSubjectMax) * 100.0 else null,
                            )
                        }
                    val subjectAverages = groupedBySubject.mapValues { (_, exams) ->
                        val percentages = exams.mapNotNull { exam ->
                            val examScore = scoreMapByAssessment[exam.assessmentId]?.get(student.studentId) ?: return@mapNotNull null
                            val obtained = when (examScore.status) {
                                ScoreStatus.PRESENT -> examScore.obtainedMarks
                                ScoreStatus.ABSENT, ScoreStatus.EXEMPT -> 0.0
                            }
                            (obtained / exam.maxMarks) * 100.0
                        }
                        if (percentages.isEmpty()) null else percentages.average()
                    }
                    val hasAnySubjectScore = subjectAverages.values.any { it != null }
                    val passStatus = when {
                        !hasAnySubjectScore -> "INCOMPLETE"
                        mandatorySubjectIds.any { subjectId ->
                            val avg = subjectAverages[subjectId] ?: return@any false
                            val threshold = subjectMap[subjectId]?.passThresholdPct ?: 33.0
                            avg < threshold
                        } -> "FAIL"
                        else -> "PASS"
                    }
                    val subjectSummary = groupedBySubject.entries.joinToString(" | ") { (subjectId, _) ->
                        val name = subjectMap[subjectId]?.name ?: subjectId
                        val avg = subjectAverages[subjectId]
                        "$name ${avg?.let { "${"%.1f".format(it)}%" } ?: "-"}"
                    }
                    RowSeed(
                        row = DashboardStudentRow(
                            studentId = student.studentId,
                            rollNumber = student.rollNumber,
                            studentName = student.fullName,
                            examName = assessment.title,
                            subjectName = subjectMap[assessment.subjectId]?.name ?: "Subject",
                            marks = marks,
                            percentage = marks?.let { (it / assessment.maxMarks) * 100.0 },
                            averagePct = overallAverage,
                            rank = null,
                            passStatus = passStatus,
                            subjectAveragesSummary = subjectSummary,
                            subjectMarks = subjectMarks,
                        ),
                        rankMetric = overallAverage,
                    )
                }
                val ranked = seededRows.sortedWith(
                    compareByDescending<RowSeed> { it.rankMetric != null }
                        .thenByDescending { it.rankMetric ?: 0.0 }
                        .thenBy { it.row.rollNumber },
                )
                var rankCursor = 0
                ranked.map { seed ->
                    val rank = if (seed.rankMetric != null) {
                        rankCursor += 1
                        rankCursor
                    } else {
                        null
                    }
                    seed.row.copy(rank = rank)
                }
            }.onSuccess { rows ->
                _uiState.value = _uiState.value.copy(
                    dashboardRows = rows,
                    dashboardSearchDone = true,
                    error = null,
                    info = null,
                )
            }.onFailure { error ->
                _uiState.value = _uiState.value.copy(
                    dashboardRows = emptyList(),
                    dashboardSearchDone = false,
                    error = error.message,
                )
            }
        }
    }

    fun openStudentProfile(studentId: String) {
        _uiState.value = _uiState.value.copy(
            selectedStudentProfileId = studentId,
            profileLoading = true,
            profileSelectedTab = StudentProfileTab.OVERVIEW,
            error = null,
            info = null,
        )
        loadStudentProfile(studentId, _uiState.value.profileSelectedCycleId)
    }

    fun closeStudentProfile() {
        _uiState.value = _uiState.value.copy(
            selectedStudentProfileId = null,
            profileLoading = false,
            profileSaving = false,
            profileStudent = null,
            profileSection = null,
            profileCycles = emptyList(),
            profileSelectedCycleId = null,
            profileAssessments = emptyList(),
            profileScoreDrafts = emptyMap(),
            profileStatusByAssessment = emptyMap(),
            profileTrend = emptyList(),
            profileSectionAverage = 0.0,
            profileSelectedTab = StudentProfileTab.OVERVIEW,
            error = null,
            info = null,
        )
    }

    fun selectStudentProfileTab(tab: StudentProfileTab) {
        _uiState.value = _uiState.value.copy(profileSelectedTab = tab)
    }

    fun selectProfileCycle(cycleId: String) {
        val studentId = _uiState.value.selectedStudentProfileId ?: return
        _uiState.value = _uiState.value.copy(profileSelectedCycleId = cycleId, profileLoading = true)
        loadStudentProfile(studentId, cycleId)
    }

    fun onProfileScoreChange(assessmentId: String, value: String) {
        _uiState.value = _uiState.value.copy(
            profileScoreDrafts = _uiState.value.profileScoreDrafts + (assessmentId to value),
            error = null,
            info = null,
        )
    }

    fun toggleProfileStatus(assessmentId: String) {
        val current = _uiState.value.profileStatusByAssessment[assessmentId] ?: ScoreStatus.PRESENT
        val next = when (current) {
            ScoreStatus.PRESENT -> ScoreStatus.ABSENT
            ScoreStatus.ABSENT -> ScoreStatus.EXEMPT
            ScoreStatus.EXEMPT -> ScoreStatus.PRESENT
        }
        _uiState.value = _uiState.value.copy(
            profileStatusByAssessment = _uiState.value.profileStatusByAssessment + (assessmentId to next),
            error = null,
            info = null,
        )
    }

    fun saveProfileScores() {
        val state = _uiState.value
        val student = state.profileStudent ?: return
        if (state.profileAssessments.isEmpty()) {
            _uiState.value = state.copy(error = "No assessments available for selected cycle", info = null)
            return
        }

        viewModelScope.launch {
            _uiState.value = state.copy(profileSaving = true, error = null, info = null)
            runCatching {
                state.profileAssessments.forEach { row ->
                    val assessment = row.assessment
                    val status = state.profileStatusByAssessment[assessment.assessmentId] ?: ScoreStatus.PRESENT
                    val raw = state.profileScoreDrafts[assessment.assessmentId].orEmpty().trim()
                    val marks = when (status) {
                        ScoreStatus.PRESENT -> {
                            if (raw.isEmpty()) return@forEach
                            val parsed = raw.toDoubleOrNull()
                                ?: throw IllegalArgumentException("Invalid marks for ${row.subjectName} - ${assessment.title}")
                            if (parsed < 0) throw IllegalArgumentException("Marks cannot be negative")
                            if (parsed > assessment.maxMarks) {
                                throw IllegalArgumentException("${assessment.title}: marks exceed max ${"%.0f".format(assessment.maxMarks)}")
                            }
                            parsed
                        }

                        ScoreStatus.ABSENT, ScoreStatus.EXEMPT -> 0.0
                    }

                    marksRepository.upsertScore(
                        com.schoolapp.core.model.ScoreEntry(
                            scoreId = "${assessment.assessmentId}_${student.studentId}",
                            assessmentId = assessment.assessmentId,
                            studentId = student.studentId,
                            obtainedMarks = marks,
                            status = status,
                        ),
                    )
                }
            }.onSuccess {
                _uiState.value = _uiState.value.copy(profileSaving = false, info = "Student marks updated")
                loadStudentProfile(student.studentId, _uiState.value.profileSelectedCycleId)
            }.onFailure {
                _uiState.value = _uiState.value.copy(profileSaving = false, error = it.message)
            }
        }
    }

    fun onGradebookScoreChange(studentId: String, value: String) {
        _uiState.value = _uiState.value.copy(
            gradebookScoreDrafts = _uiState.value.gradebookScoreDrafts + (studentId to value),
            gradebookValidationByStudent = _uiState.value.gradebookValidationByStudent - studentId,
            error = null,
            info = null,
        )
    }

    fun toggleGradebookStatus(studentId: String) {
        val current = _uiState.value.gradebookStatusByStudent[studentId] ?: ScoreStatus.PRESENT
        val next = when (current) {
            ScoreStatus.PRESENT -> ScoreStatus.ABSENT
            ScoreStatus.ABSENT -> ScoreStatus.EXEMPT
            ScoreStatus.EXEMPT -> ScoreStatus.PRESENT
        }
        _uiState.value = _uiState.value.copy(
            gradebookStatusByStudent = _uiState.value.gradebookStatusByStudent + (studentId to next),
            gradebookValidationByStudent = _uiState.value.gradebookValidationByStudent - studentId,
            error = null,
            info = null,
        )
    }

    fun applyBulkStatus(status: ScoreStatus) {
        val statuses = _uiState.value.gradebookStudents.associate { it.studentId to status }
        _uiState.value = _uiState.value.copy(
            gradebookStatusByStudent = statuses,
            error = null,
            info = null,
        )
    }

    fun applyBulkMarks(value: String) {
        val max = _uiState.value.gradebookMaxMarks
        val parsed = value.toDoubleOrNull()
        if (parsed == null || parsed < 0 || (max != null && parsed > max)) {
            _uiState.value = _uiState.value.copy(error = "Enter valid bulk marks")
            return
        }
        val updated = _uiState.value.gradebookStudents.associate { student ->
            student.studentId to if ((_uiState.value.gradebookStatusByStudent[student.studentId] ?: ScoreStatus.PRESENT) == ScoreStatus.PRESENT) {
                value
            } else {
                ""
            }
        }
        _uiState.value = _uiState.value.copy(
            gradebookScoreDrafts = updated,
            error = null,
            info = null,
        )
    }

    fun saveGradebookScores() {
        val state = _uiState.value
        val assessment = state.context.assessmentId
        if (assessment == null) {
            _uiState.value = state.copy(error = "Select assessment first")
            return
        }
        if (state.gradebookStudents.isEmpty()) {
            _uiState.value = state.copy(error = "No students found for selected section")
            return
        }

        viewModelScope.launch {
            val maxMarks = state.gradebookMaxMarks
            val validation = mutableMapOf<String, String>()
            val rows = mutableListOf<ScoreEntry>()

            state.gradebookStudents.forEach { student ->
                val status = state.gradebookStatusByStudent[student.studentId] ?: ScoreStatus.PRESENT
                val raw = state.gradebookScoreDrafts[student.studentId].orEmpty().trim()
                val marks = when (status) {
                    ScoreStatus.PRESENT -> {
                        if (raw.isEmpty()) {
                            validation[student.studentId] = "Marks required"
                            return@forEach
                        }
                        val parsed = raw.toDoubleOrNull()
                        if (parsed == null) {
                            validation[student.studentId] = "Invalid number"
                            return@forEach
                        }
                        if (parsed < 0) {
                            validation[student.studentId] = "Cannot be negative"
                            return@forEach
                        }
                        if (maxMarks != null && parsed > maxMarks) {
                            validation[student.studentId] = "Exceeds max ${"%.0f".format(maxMarks)}"
                            return@forEach
                        }
                        parsed
                    }

                    ScoreStatus.ABSENT, ScoreStatus.EXEMPT -> 0.0
                }

                rows += ScoreEntry(
                    scoreId = "${assessment}_${student.studentId}",
                    assessmentId = assessment,
                    studentId = student.studentId,
                    obtainedMarks = marks,
                    status = status,
                )
            }

            if (validation.isNotEmpty()) {
                _uiState.value = state.copy(
                    gradebookSaving = false,
                    gradebookValidationByStudent = validation,
                    error = "Fix validation errors before saving",
                    info = null,
                )
                return@launch
            }

            _uiState.value = state.copy(
                gradebookSaving = true,
                gradebookValidationByStudent = emptyMap(),
                error = null,
                info = null,
            )
            runCatching {
                marksRepository.upsertScoresBulk(BulkScorePatch(assessmentId = assessment, rows = rows))
            }.onSuccess {
                val refreshDashboard = _uiState.value.dashboardSearchDone
                _uiState.value = _uiState.value.copy(
                    gradebookSaving = false,
                    gradebookValidationByStudent = emptyMap(),
                    info = "Gradebook draft saved",
                    error = null,
                )
                refresh()
                if (refreshDashboard) {
                    runDashboardSearch()
                }
            }.onFailure { error ->
                _uiState.value = _uiState.value.copy(
                    gradebookSaving = false,
                    error = error.message,
                )
            }
        }
    }

    fun checkPublishReadiness() {
        val sectionId = _uiState.value.context.sectionId ?: return
        val cycleId = _uiState.value.context.cycleId ?: return
        viewModelScope.launch {
            runCatching {
                marksRepository.validatePublishReadiness(cycleId, sectionId)
            }.onSuccess { readiness ->
                _uiState.value = _uiState.value.copy(
                    publishReadinessMessage = readiness.message,
                    publishReadinessError = !readiness.ready,
                    error = if (readiness.ready) null else readiness.message,
                )
            }.onFailure {
                _uiState.value = _uiState.value.copy(
                    publishReadinessMessage = it.message,
                    publishReadinessError = true,
                    error = it.message,
                )
            }
        }
    }

    fun publishCurrentContext() {
        val sectionId = _uiState.value.context.sectionId ?: return
        val cycleId = _uiState.value.context.cycleId ?: return
        viewModelScope.launch {
            runCatching {
                val readiness = marksRepository.validatePublishReadiness(cycleId, sectionId)
                if (!readiness.ready) {
                    throw IllegalStateException(readiness.message)
                }
                marksRepository.publishCycle(cycleId, sectionId)
                readiness.message
            }.onSuccess {
                _uiState.value = _uiState.value.copy(
                    publishReadinessMessage = "Results published",
                    publishReadinessError = false,
                    info = "Results published successfully",
                    error = null,
                )
                refresh()
            }.onFailure {
                _uiState.value = _uiState.value.copy(
                    publishReadinessMessage = it.message,
                    publishReadinessError = true,
                    error = it.message,
                )
            }
        }
    }

    private fun loadStudentProfile(studentId: String, preferredCycleId: String?) {
        viewModelScope.launch {
            runCatching {
                val current = _uiState.value
                val student = current.studentsBySection.values.flatten().firstOrNull { it.studentId == studentId }
                    ?: error("Student not found")
                val section = current.sections.firstOrNull { it.sectionId == student.sectionId }
                val cycles = masterDataRepository.getCycles()
                val selectedCycleId = preferredCycleId
                    ?: current.profileSelectedCycleId
                    ?: current.selectedCycleId
                    ?: cycles.firstOrNull()?.cycleId

                val subjects = masterDataRepository.getSubjectsBySection(student.sectionId)
                val subjectNameMap = subjects.associate { it.subjectId to it.name }
                val assessments = if (selectedCycleId != null) {
                    masterDataRepository.getAssessments(student.sectionId, selectedCycleId)
                } else {
                    emptyList()
                }.sortedWith(compareBy<Assessment> { subjectNameMap[it.subjectId] ?: it.subjectId }.thenBy { it.title })

                val profileRows = assessments.map { assessment ->
                    StudentAssessmentProfileRow(
                        assessment = assessment,
                        subjectName = subjectNameMap[assessment.subjectId] ?: assessment.subjectId,
                    )
                }

                val scoreDrafts = mutableMapOf<String, String>()
                val statusMap = mutableMapOf<String, ScoreStatus>()
                profileRows.forEach { row ->
                    val score = marksRepository.getScoreByAssessmentAndStudent(
                        assessmentId = row.assessment.assessmentId,
                        studentId = student.studentId,
                    )
                    statusMap[row.assessment.assessmentId] = score?.status ?: ScoreStatus.PRESENT
                    scoreDrafts[row.assessment.assessmentId] = when {
                        score == null -> ""
                        score.obtainedMarks % 1.0 == 0.0 -> score.obtainedMarks.toInt().toString()
                        else -> "%.2f".format(score.obtainedMarks).trimEnd('0').trimEnd('.')
                    }
                }

                val trend = analyticsRepository.getStudentTrend(student.studentId).snapshots
                val sectionAverage = if (selectedCycleId != null) {
                    runCatching {
                        analyticsRepository.getSectionSummary(selectedCycleId, student.sectionId).averagePct
                    }.getOrDefault(0.0)
                } else {
                    0.0
                }

                current.copy(
                    profileLoading = false,
                    selectedStudentProfileId = student.studentId,
                    profileStudent = student,
                    profileSection = section,
                    profileCycles = cycles,
                    profileSelectedCycleId = selectedCycleId,
                    profileAssessments = profileRows,
                    profileScoreDrafts = scoreDrafts,
                    profileStatusByAssessment = statusMap,
                    profileTrend = trend,
                    profileSectionAverage = sectionAverage,
                    error = null,
                )
            }.onSuccess { _uiState.value = it }
                .onFailure { _uiState.value = _uiState.value.copy(profileLoading = false, error = it.message) }
        }
    }

    fun showAddStudentDialog() { _uiState.value = _uiState.value.copy(showAddStudentDialog = true) }
    fun dismissAddStudentDialog() { _uiState.value = _uiState.value.copy(showAddStudentDialog = false) }

    fun addStudent(sectionId: String, fullName: String, rollNumber: Int) {
        viewModelScope.launch {
            runCatching {
                require(sectionId.isNotBlank()) { "Section is required" }
                require(fullName.isNotBlank()) { "Student name is required" }
                require(rollNumber > 0) { "Roll number must be greater than 0" }
                val section = _uiState.value.sections.firstOrNull { it.sectionId == sectionId } ?: error("Section not found")
                val duplicateRoll = masterDataRepository.getStudentsBySection(sectionId).any { it.rollNumber == rollNumber }
                require(!duplicateRoll) { "Roll number $rollNumber already exists in this section" }
                masterDataRepository.upsertStudent(
                    Student(
                        studentId = "student_${System.currentTimeMillis()}_${Random.nextInt(1000)}",
                        schoolId = section.schoolId,
                        sectionId = sectionId,
                        rollNumber = rollNumber,
                        fullName = fullName.trim(),
                        active = true,
                    ),
                )
            }.onSuccess {
                _uiState.value = _uiState.value.copy(showAddStudentDialog = false, info = "Student added")
                refresh()
            }.onFailure { _uiState.value = _uiState.value.copy(error = it.message) }
        }
    }

    fun updateStudent(studentId: String, sectionId: String, fullName: String, rollNumber: Int) {
        viewModelScope.launch {
            runCatching {
                require(studentId.isNotBlank()) { "Student is required" }
                require(sectionId.isNotBlank()) { "Section is required" }
                require(fullName.isNotBlank()) { "Student name is required" }
                require(rollNumber > 0) { "Roll number must be greater than 0" }

                val existing = _uiState.value.studentsBySection.values.flatten()
                    .firstOrNull { it.studentId == studentId }
                    ?: error("Student not found")
                val section = _uiState.value.sections.firstOrNull { it.sectionId == sectionId } ?: error("Section not found")
                val duplicateRoll = masterDataRepository.getStudentsBySection(sectionId).any {
                    it.rollNumber == rollNumber && it.studentId != studentId
                }
                require(!duplicateRoll) { "Roll number $rollNumber already exists in this section" }

                masterDataRepository.upsertStudent(
                    existing.copy(
                        schoolId = section.schoolId,
                        sectionId = sectionId,
                        fullName = fullName.trim(),
                        rollNumber = rollNumber,
                    ),
                )
            }.onSuccess {
                _uiState.value = _uiState.value.copy(info = "Student updated", error = null)
                refresh()
                if (_uiState.value.selectedStudentProfileId == studentId) {
                    loadStudentProfile(studentId, _uiState.value.profileSelectedCycleId)
                }
            }.onFailure { _uiState.value = _uiState.value.copy(error = it.message) }
        }
    }

    fun deleteStudent(studentId: String) {
        viewModelScope.launch {
            runCatching {
                require(studentId.isNotBlank()) { "Student is required" }
                masterDataRepository.deleteStudent(studentId)
            }.onSuccess {
                val shouldCloseProfile = _uiState.value.selectedStudentProfileId == studentId
                _uiState.value = if (shouldCloseProfile) {
                    _uiState.value.copy(
                        selectedStudentProfileId = null,
                        profileStudent = null,
                        profileAssessments = emptyList(),
                        profileTrend = emptyList(),
                        info = "Student deleted",
                        error = null,
                    )
                } else {
                    _uiState.value.copy(info = "Student deleted", error = null)
                }
                refresh()
            }.onFailure { _uiState.value = _uiState.value.copy(error = it.message) }
        }
    }

    fun showCreateCycleDialog() { _uiState.value = _uiState.value.copy(showCreateCycleDialog = true) }
    fun dismissCreateCycleDialog() { _uiState.value = _uiState.value.copy(showCreateCycleDialog = false) }

    fun createCycle(name: String, startDate: String, endDate: String) {
        viewModelScope.launch {
            runCatching {
                require(name.isNotBlank()) { "Cycle name is required" }
                require(startDate.isNotBlank() && endDate.isNotBlank()) { "Start and end dates are required" }
                val isoRegex = Regex("\\d{4}-\\d{2}-\\d{2}")
                require(startDate.matches(isoRegex) && endDate.matches(isoRegex)) { "Use YYYY-MM-DD format" }
                val schoolId = signedInSchoolId()
                masterDataRepository.upsertAssessmentCycle(
                    AssessmentCycle(
                        cycleId = "cycle_${System.currentTimeMillis()}",
                        schoolId = schoolId,
                        name = name.trim(),
                        startDateIso = startDate.trim(),
                        endDateIso = endDate.trim(),
                        published = false,
                    ),
                )
            }.onSuccess {
                _uiState.value = _uiState.value.copy(showCreateCycleDialog = false, info = "Cycle created")
                refresh()
            }.onFailure { _uiState.value = _uiState.value.copy(error = it.message) }
        }
    }

    fun showCreateSectionDialog() { _uiState.value = _uiState.value.copy(showCreateSectionDialog = true) }
    fun dismissCreateSectionDialog() { _uiState.value = _uiState.value.copy(showCreateSectionDialog = false) }

    fun createSection(grade: Int, sectionName: String) {
        viewModelScope.launch {
            runCatching {
                require(grade in 1..10) { "Grade must be between 1 and 10" }
                require(sectionName.isNotBlank()) { "Section name is required" }

                val normalized = sectionName.trim().uppercase()
                val schoolId = signedInSchoolId()
                val baseId = "g${grade}_${normalized.lowercase()}"
                val id = if (_uiState.value.sections.any { it.sectionId == baseId }) {
                    "${baseId}_${System.currentTimeMillis()}"
                } else {
                    baseId
                }

                masterDataRepository.upsertSection(
                    GradeSection(
                        sectionId = id,
                        schoolId = schoolId,
                        grade = grade,
                        sectionName = normalized,
                    ),
                )
            }.onSuccess {
                _uiState.value = _uiState.value.copy(showCreateSectionDialog = false, info = "Section created")
                refresh()
            }.onFailure { _uiState.value = _uiState.value.copy(error = it.message) }
        }
    }

    fun updateSection(sectionId: String, grade: Int, sectionName: String) {
        viewModelScope.launch {
            runCatching {
                require(sectionId.isNotBlank()) { "Section is required" }
                require(grade in 1..10) { "Grade must be between 1 and 10" }
                require(sectionName.isNotBlank()) { "Section name is required" }

                val existing = _uiState.value.sections.firstOrNull { it.sectionId == sectionId } ?: error("Section not found")
                val normalized = sectionName.trim().uppercase()
                val duplicate = _uiState.value.sections.any {
                    it.sectionId != sectionId &&
                        it.grade == grade &&
                        it.sectionName.equals(normalized, ignoreCase = true)
                }
                require(!duplicate) { "Same grade and section already exists" }

                masterDataRepository.upsertSection(
                    existing.copy(
                        grade = grade,
                        sectionName = normalized,
                    ),
                )
            }.onSuccess {
                _uiState.value = _uiState.value.copy(info = "Section updated", error = null)
                refresh()
            }.onFailure { _uiState.value = _uiState.value.copy(error = it.message) }
        }
    }

    fun deleteSection(sectionId: String) {
        viewModelScope.launch {
            runCatching {
                require(sectionId.isNotBlank()) { "Section is required" }
                masterDataRepository.deleteSection(sectionId)
            }.onSuccess {
                _uiState.value = _uiState.value.copy(
                    context = if (_uiState.value.context.sectionId == sectionId) {
                        _uiState.value.context.copy(sectionId = null, subjectId = null, assessmentId = null)
                    } else {
                        _uiState.value.context
                    },
                    selectedStudentProfileId = null,
                    profileStudent = null,
                    profileAssessments = emptyList(),
                    profileTrend = emptyList(),
                    info = "Section deleted",
                    error = null,
                )
                refresh()
            }.onFailure { _uiState.value = _uiState.value.copy(error = it.message) }
        }
    }

    fun showCreateSubjectDialog() { _uiState.value = _uiState.value.copy(showCreateSubjectDialog = true) }
    fun dismissCreateSubjectDialog() { _uiState.value = _uiState.value.copy(showCreateSubjectDialog = false) }

    fun createSubject(sectionId: String, subjectName: String, passThreshold: Double, mandatory: Boolean) {
        viewModelScope.launch {
            runCatching {
                require(sectionId.isNotBlank()) { "Section is required" }
                require(subjectName.isNotBlank()) { "Subject name is required" }
                val section = _uiState.value.sections.firstOrNull { it.sectionId == sectionId } ?: error("Section not found")

                val cleaned = subjectName.trim()
                    .lowercase()
                    .replace(Regex("[^a-z0-9]+"), "_")
                    .trim('_')
                    .ifBlank { "subject" }

                val clampedThreshold = passThreshold.coerceIn(0.0, 100.0)
                masterDataRepository.upsertSubject(
                    Subject(
                        subjectId = "sub_${cleaned}_${section.grade}_${System.currentTimeMillis()}",
                        schoolId = section.schoolId,
                        grade = section.grade,
                        name = subjectName.trim(),
                        passThresholdPct = clampedThreshold,
                        mandatory = mandatory,
                    ),
                )
            }.onSuccess {
                _uiState.value = _uiState.value.copy(showCreateSubjectDialog = false, info = "Subject created")
                refresh()
            }.onFailure { _uiState.value = _uiState.value.copy(error = it.message) }
        }
    }

    fun updateSubject(subjectId: String, sectionId: String, subjectName: String, passThreshold: Double, mandatory: Boolean) {
        viewModelScope.launch {
            runCatching {
                require(subjectId.isNotBlank()) { "Subject is required" }
                require(sectionId.isNotBlank()) { "Section is required" }
                require(subjectName.isNotBlank()) { "Subject name is required" }

                val section = _uiState.value.sections.firstOrNull { it.sectionId == sectionId } ?: error("Section not found")
                val current = _uiState.value.subjectsBySection[sectionId].orEmpty()
                    .firstOrNull { it.subjectId == subjectId }
                    ?: error("Subject not found")
                masterDataRepository.upsertSubject(
                    current.copy(
                        schoolId = section.schoolId,
                        grade = section.grade,
                        name = subjectName.trim(),
                        passThresholdPct = passThreshold.coerceIn(0.0, 100.0),
                        mandatory = mandatory,
                    ),
                )
            }.onSuccess {
                _uiState.value = _uiState.value.copy(info = "Subject updated", error = null)
                refresh()
            }.onFailure { _uiState.value = _uiState.value.copy(error = it.message) }
        }
    }

    fun deleteSubject(subjectId: String) {
        viewModelScope.launch {
            runCatching {
                require(subjectId.isNotBlank()) { "Subject is required" }
                masterDataRepository.deleteSubject(subjectId)
            }.onSuccess {
                val context = _uiState.value.context
                _uiState.value = _uiState.value.copy(
                    context = if (context.subjectId == subjectId) {
                        context.copy(subjectId = null, assessmentId = null)
                    } else {
                        context
                    },
                    info = "Subject deleted",
                    error = null,
                )
                refresh()
            }.onFailure { _uiState.value = _uiState.value.copy(error = it.message) }
        }
    }

    fun promoteStudents(sourceSectionId: String, targetSectionId: String) {
        viewModelScope.launch {
            runCatching {
                require(sourceSectionId.isNotBlank()) { "Source section is required" }
                require(targetSectionId.isNotBlank()) { "Target section is required" }
                require(sourceSectionId != targetSectionId) { "Source and target section must be different" }
                val sourceStudents = masterDataRepository.getStudentsBySection(sourceSectionId).sortedBy { it.rollNumber }
                require(sourceStudents.isNotEmpty()) { "No students available in source section" }
                val targetStudents = masterDataRepository.getStudentsBySection(targetSectionId)
                val nextRollStart = (targetStudents.maxOfOrNull { it.rollNumber } ?: 0) + 1

                sourceStudents.forEachIndexed { index, student ->
                    masterDataRepository.upsertStudent(
                        student.copy(
                            sectionId = targetSectionId,
                            rollNumber = nextRollStart + index,
                        ),
                    )
                }
                sourceStudents.size
            }.onSuccess { count ->
                _uiState.value = _uiState.value.copy(
                    info = "$count students promoted",
                    error = null,
                )
                refresh()
            }.onFailure { _uiState.value = _uiState.value.copy(error = it.message) }
        }
    }

    fun showCreateAssessmentDialog() { _uiState.value = _uiState.value.copy(showCreateAssessmentDialog = true) }
    fun dismissCreateAssessmentDialog() { _uiState.value = _uiState.value.copy(showCreateAssessmentDialog = false) }

    fun createAssessment(
        sectionId: String,
        cycleId: String,
        subjectId: String,
        title: String,
        maxMarks: Double,
        weight: Double,
    ) {
        viewModelScope.launch {
            runCatching {
                require(sectionId.isNotBlank()) { "Section is required" }
                require(cycleId.isNotBlank()) { "Cycle is required" }
                require(subjectId.isNotBlank()) { "Subject is required" }
                require(title.isNotBlank()) { "Assessment title is required" }
                require(maxMarks > 0) { "Maximum marks must be greater than 0" }
                require(weight > 0) { "Weight must be greater than 0" }
                val existing = masterDataRepository.getAssessments(sectionId, cycleId)
                val duplicateTitle = existing.any {
                    it.subjectId == subjectId && it.title.equals(title.trim(), ignoreCase = true)
                }
                require(!duplicateTitle) { "Assessment title already exists for this subject and cycle" }

                masterDataRepository.upsertAssessment(
                    Assessment(
                        assessmentId = "assess_${sectionId}_${subjectId}_${System.currentTimeMillis()}",
                        cycleId = cycleId,
                        sectionId = sectionId,
                        subjectId = subjectId,
                        title = title.trim(),
                        maxMarks = maxMarks,
                        weight = weight,
                    ),
                )
            }.onSuccess {
                _uiState.value = _uiState.value.copy(showCreateAssessmentDialog = false, info = "Assessment created")
                refresh()
            }.onFailure { _uiState.value = _uiState.value.copy(error = it.message) }
        }
    }

    fun createExamForm(title: String, subjectId: String, maxMarks: Double, weight: Double) {
        val state = _uiState.value
        val sectionId = state.context.sectionId
        val cycleId = state.context.cycleId
        if (sectionId == null || cycleId == null) {
            _uiState.value = state.copy(error = "Select class section and cycle first")
            return
        }
        viewModelScope.launch {
            runCatching {
                require(subjectId.isNotBlank()) { "Subject is required" }
                require(title.isNotBlank()) { "Exam name is required" }
                require(maxMarks > 0) { "Maximum marks must be greater than 0" }
                require(weight > 0) { "Weight must be greater than 0" }
                val existing = masterDataRepository.getAssessments(sectionId, cycleId)
                val duplicate = existing.any { it.subjectId == subjectId && it.title.equals(title.trim(), ignoreCase = true) }
                require(!duplicate) { "Exam name already exists for this subject" }

                val assessmentId = "assess_${sectionId}_${subjectId}_${System.currentTimeMillis()}"
                masterDataRepository.upsertAssessment(
                    Assessment(
                        assessmentId = assessmentId,
                        cycleId = cycleId,
                        sectionId = sectionId,
                        subjectId = subjectId,
                        title = title.trim(),
                        maxMarks = maxMarks,
                        weight = weight,
                    ),
                )
                assessmentId
            }.onSuccess { assessmentId ->
                _uiState.value = _uiState.value.copy(
                    context = _uiState.value.context.copy(subjectId = subjectId, assessmentId = assessmentId),
                    info = "Exam form created. Fill scores below.",
                    error = null,
                )
                refresh()
            }.onFailure { _uiState.value = _uiState.value.copy(error = it.message) }
        }
    }

    fun addStudentsBatch(sectionId: String, namesRaw: String, startRoll: Int) {
        viewModelScope.launch {
            runCatching {
                require(sectionId.isNotBlank()) { "Section is required" }
                require(startRoll > 0) { "Start roll number must be greater than 0" }
                val section = _uiState.value.sections.firstOrNull { it.sectionId == sectionId } ?: error("Section not found")

                val names = namesRaw
                    .split("\n", ",", ";")
                    .map { it.trim() }
                    .filter { it.isNotBlank() }
                require(names.isNotEmpty()) { "Add at least one student name" }

                val existingRolls = masterDataRepository.getStudentsBySection(sectionId).map { it.rollNumber }.toSet()
                val plannedRolls = names.indices.map { startRoll + it }
                require(plannedRolls.none { it in existingRolls }) { "Some roll numbers already exist in this section" }

                names.forEachIndexed { index, name ->
                    masterDataRepository.upsertStudent(
                        Student(
                            studentId = "student_${System.currentTimeMillis()}_${index}_${Random.nextInt(1000)}",
                            schoolId = section.schoolId,
                            sectionId = sectionId,
                            rollNumber = startRoll + index,
                            fullName = name,
                            active = true,
                        ),
                    )
                }
            }.onSuccess {
                _uiState.value = _uiState.value.copy(info = "Batch students added", error = null)
                refresh()
            }.onFailure { _uiState.value = _uiState.value.copy(error = it.message) }
        }
    }

    fun createCycleWithSemesterPlan(
        sectionId: String,
        cycleName: String,
        startDate: String,
        endDate: String,
        semesterMarks: List<Double>,
        useMaxMarksAsWeight: Boolean,
        customWeights: List<Double>? = null,
    ) {
        viewModelScope.launch {
            runCatching {
                require(sectionId.isNotBlank()) { "Section is required" }
                require(cycleName.isNotBlank()) { "Cycle name is required" }
                require(startDate.isNotBlank() && endDate.isNotBlank()) { "Start and end dates are required" }
                val isoRegex = Regex("\\d{4}-\\d{2}-\\d{2}")
                require(startDate.matches(isoRegex) && endDate.matches(isoRegex)) { "Use YYYY-MM-DD format" }
                require(semesterMarks.isNotEmpty()) { "Add at least one semester max marks value" }
                require(semesterMarks.all { it > 0 }) { "Semester marks must be greater than 0" }
                if (customWeights != null) {
                    require(customWeights.size == semesterMarks.size) { "Custom weights must match semester count" }
                    require(customWeights.all { it > 0 }) { "Custom weights must be positive" }
                    val weightSum = customWeights.sum()
                    require(weightSum in 99.9..100.1) { "Custom weights must sum to 100" }
                }

                val section = _uiState.value.sections.firstOrNull { it.sectionId == sectionId } ?: error("Section not found")
                val subjects = masterDataRepository.getSubjectsBySection(sectionId)
                require(subjects.isNotEmpty()) { "Create subjects first for this section" }

                val cycleId = "cycle_${System.currentTimeMillis()}"
                masterDataRepository.upsertAssessmentCycle(
                    AssessmentCycle(
                        cycleId = cycleId,
                        schoolId = section.schoolId,
                        name = cycleName.trim(),
                        startDateIso = startDate.trim(),
                        endDateIso = endDate.trim(),
                        published = false,
                    ),
                )

                semesterMarks.forEachIndexed { semIndex, maxMarks ->
                    val semNo = semIndex + 1
                    subjects.forEach { subject ->
                        val weight = when {
                            customWeights != null -> customWeights[semIndex]
                            useMaxMarksAsWeight -> maxMarks
                            else -> 1.0
                        }
                        masterDataRepository.upsertAssessment(
                            Assessment(
                                assessmentId = "assess_${sectionId}_${subject.subjectId}_${semNo}_${System.currentTimeMillis()}_${Random.nextInt(1000)}",
                                cycleId = cycleId,
                                sectionId = sectionId,
                                subjectId = subject.subjectId,
                                title = "Semester $semNo - ${subject.name}",
                                maxMarks = maxMarks,
                                weight = weight,
                            ),
                        )
                    }
                }
            }.onSuccess {
                _uiState.value = _uiState.value.copy(
                    info = "Cycle and semester assessments created",
                    error = null,
                )
                refresh()
            }.onFailure { _uiState.value = _uiState.value.copy(error = it.message) }
        }
    }

    fun importData(fileUri: String, fileType: String, entityType: String) {
        viewModelScope.launch {
            runCatching {
                marksRepository.bulkImport(fileUri = fileUri, type = fileType, entityType = entityType)
            }.onSuccess {
                _uiState.value = _uiState.value.copy(
                    info = "${entityType.replaceFirstChar { it.uppercase() }} import completed",
                    error = null,
                )
                refresh()
            }.onFailure {
                _uiState.value = _uiState.value.copy(error = it.message)
            }
        }
    }

    /** New records belong to the signed-in admin's school, never a hardcoded one. */
    private suspend fun signedInSchoolId(): String =
        SchoolScope.requireSessionSchoolId(authRepository.currentUser()?.schoolId)

    companion object {
        private fun displaySchoolName(schoolId: String?): String {
            val raw = schoolId?.trim().orEmpty()
            if (raw.isBlank()) return "School Performance Hub"
            return raw
                .replace('_', ' ')
                .replace('-', ' ')
                .split(' ')
                .filter { it.isNotBlank() }
                .joinToString(" ") { part ->
                    part.lowercase().replaceFirstChar { c -> c.uppercase() }
                }
        }

        fun factory(
            authRepository: AuthRepository,
            masterDataRepository: MasterDataRepository,
            analyticsRepository: AnalyticsRepository,
            marksRepository: MarksRepository,
        ): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    AdminViewModel(authRepository, masterDataRepository, analyticsRepository, marksRepository) as T
            }
    }
}
