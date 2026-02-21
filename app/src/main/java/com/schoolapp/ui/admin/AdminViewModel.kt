package com.schoolapp.ui.admin

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.schoolapp.core.model.AcademicContext
import com.schoolapp.core.model.AdminDestination
import com.schoolapp.core.model.Assessment
import com.schoolapp.core.model.AssessmentCycle
import com.schoolapp.core.model.BulkScorePatch
import com.schoolapp.core.model.GradeSection
import com.schoolapp.core.model.ResultSnapshot
import com.schoolapp.core.model.ScoreEntry
import com.schoolapp.core.model.ScoreStatus
import com.schoolapp.core.model.SectionSummary
import com.schoolapp.core.model.Student
import com.schoolapp.core.model.StudentProfileTab
import com.schoolapp.core.model.Subject
import com.schoolapp.domain.repository.AnalyticsRepository
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
    val gradebookSaving: Boolean = false,
    val publishReadinessMessage: String? = null,
    val publishReadinessError: Boolean = false,
    val error: String? = null,
    val info: String? = null,
)

class AdminViewModel(
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
            error = null,
            info = null,
        )
        refresh()
    }

    fun selectContextAssessment(assessmentId: String) {
        _uiState.value = _uiState.value.copy(
            context = _uiState.value.context.copy(assessmentId = assessmentId),
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

                AdminDashboardState(
                    loading = false,
                    selectedTab = destinationToTab(previous.destination),
                    destination = previous.destination,
                    context = context,
                    totalStudents = totalStudents,
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

    fun openStudentProfile(studentId: String) {
        _uiState.value = _uiState.value.copy(
            destination = AdminDestination.ROSTER,
            selectedTab = destinationToTab(AdminDestination.ROSTER),
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
                _uiState.value = _uiState.value.copy(
                    gradebookSaving = false,
                    gradebookValidationByStudent = emptyMap(),
                    info = "Gradebook draft saved",
                    error = null,
                )
                refresh()
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

                val trend = analyticsRepository.getStudentTrend(student.studentId)
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

    fun showCreateCycleDialog() { _uiState.value = _uiState.value.copy(showCreateCycleDialog = true) }
    fun dismissCreateCycleDialog() { _uiState.value = _uiState.value.copy(showCreateCycleDialog = false) }

    fun createCycle(name: String, startDate: String, endDate: String) {
        viewModelScope.launch {
            runCatching {
                require(name.isNotBlank()) { "Cycle name is required" }
                require(startDate.isNotBlank() && endDate.isNotBlank()) { "Start and end dates are required" }
                val isoRegex = Regex("\\d{4}-\\d{2}-\\d{2}")
                require(startDate.matches(isoRegex) && endDate.matches(isoRegex)) { "Use YYYY-MM-DD format" }
                val schoolId = _uiState.value.sections.firstOrNull()?.schoolId ?: "school_001"
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
                val schoolId = _uiState.value.sections.firstOrNull()?.schoolId ?: "school_001"
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

    companion object {
        fun factory(
            masterDataRepository: MasterDataRepository,
            analyticsRepository: AnalyticsRepository,
            marksRepository: MarksRepository,
        ): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    AdminViewModel(masterDataRepository, analyticsRepository, marksRepository) as T
            }
    }
}
