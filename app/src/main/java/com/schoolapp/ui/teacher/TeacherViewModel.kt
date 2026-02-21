package com.schoolapp.ui.teacher

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.schoolapp.core.model.Assessment
import com.schoolapp.core.model.AssessmentCycle
import com.schoolapp.core.model.GradeSection
import com.schoolapp.core.model.ScoreEntry
import com.schoolapp.core.model.ScoreStatus
import com.schoolapp.core.model.Student
import com.schoolapp.core.model.Subject
import com.schoolapp.domain.repository.MarksRepository
import com.schoolapp.domain.repository.MasterDataRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class TeacherMarksState(
    val loading: Boolean = true,
    val sections: List<GradeSection> = emptyList(),
    val selectedSectionId: String? = null,
    val cycles: List<AssessmentCycle> = emptyList(),
    val selectedCycleId: String? = null,
    val subjects: List<Subject> = emptyList(),
    val selectedSubjectId: String? = null,
    val assessments: List<Assessment> = emptyList(),
    val selectedAssessmentId: String? = null,
    val maxMarks: Double? = null,
    val students: List<Student> = emptyList(),
    val scoreDrafts: Map<String, String> = emptyMap(),
    val absenceStatus: Map<String, ScoreStatus> = emptyMap(),
    val saving: Boolean = false,
    val showPublishDialog: Boolean = false,
    val info: String? = null,
    val error: String? = null,
)

class TeacherViewModel(
    private val masterDataRepository: MasterDataRepository,
    private val marksRepository: MarksRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(TeacherMarksState())
    val uiState: StateFlow<TeacherMarksState> = _uiState.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(loading = true, error = null)
            runCatching {
                val previous = _uiState.value
                val sections = masterDataRepository.getSections()
                val cycles = masterDataRepository.getCycles()
                val selectedSection = previous.selectedSectionId ?: sections.firstOrNull()?.sectionId
                val selectedCycle = previous.selectedCycleId ?: cycles.firstOrNull()?.cycleId
                val subjects = selectedSection?.let { masterDataRepository.getSubjectsBySection(it) }.orEmpty()
                val selectedSubject = previous.selectedSubjectId?.takeIf { subjectId ->
                    subjects.any { it.subjectId == subjectId }
                } ?: subjects.firstOrNull()?.subjectId
                val allAssessments = if (selectedSection != null && selectedCycle != null) {
                    masterDataRepository.getAssessments(selectedSection, selectedCycle)
                } else {
                    emptyList()
                }
                val assessments = if (selectedSubject != null) {
                    allAssessments.filter { it.subjectId == selectedSubject }
                } else {
                    allAssessments
                }
                val selectedAssessment = previous.selectedAssessmentId?.takeIf { assessmentId ->
                    assessments.any { it.assessmentId == assessmentId }
                } ?: assessments.firstOrNull()?.assessmentId
                val maxMarks = assessments.firstOrNull { it.assessmentId == selectedAssessment }?.maxMarks
                val students = selectedSection?.let { masterDataRepository.getStudentsBySection(it) }.orEmpty()
                val scores = selectedAssessment?.let { marksRepository.getScoresByAssessment(it) }.orEmpty()

                val draftMap = students.associate { student ->
                    val score = scores.firstOrNull { it.studentId == student.studentId }
                    student.studentId to (score?.obtainedMarks?.toInt()?.toString() ?: "")
                }
                val statusMap = students.associate { student ->
                    val score = scores.firstOrNull { it.studentId == student.studentId }
                    student.studentId to (score?.status ?: ScoreStatus.PRESENT)
                }

                TeacherMarksState(
                    loading = false,
                    sections = sections,
                    selectedSectionId = selectedSection,
                    cycles = cycles,
                    selectedCycleId = selectedCycle,
                    subjects = subjects,
                    selectedSubjectId = selectedSubject,
                    assessments = assessments,
                    selectedAssessmentId = selectedAssessment,
                    maxMarks = maxMarks,
                    students = students,
                    scoreDrafts = draftMap,
                    absenceStatus = statusMap,
                )
            }.onSuccess { _uiState.value = it }
                .onFailure { error -> _uiState.value = _uiState.value.copy(loading = false, error = error.message) }
        }
    }

    fun selectSection(sectionId: String) {
        _uiState.value = _uiState.value.copy(
            selectedSectionId = sectionId,
            selectedSubjectId = null,
            selectedAssessmentId = null,
        )
        refresh()
    }

    fun selectCycle(cycleId: String) {
        _uiState.value = _uiState.value.copy(
            selectedCycleId = cycleId,
            selectedAssessmentId = null,
        )
        refresh()
    }

    fun selectSubject(subjectId: String) {
        _uiState.value = _uiState.value.copy(
            selectedSubjectId = subjectId,
            selectedAssessmentId = null,
        )
        refresh()
    }

    fun selectAssessment(assessmentId: String) {
        _uiState.value = _uiState.value.copy(selectedAssessmentId = assessmentId)
        refresh()
    }

    fun onScoreChange(studentId: String, value: String) {
        _uiState.value = _uiState.value.copy(
            scoreDrafts = _uiState.value.scoreDrafts + (studentId to value),
            info = null,
            error = null,
        )
    }

    fun toggleStudentStatus(studentId: String) {
        val current = _uiState.value.absenceStatus[studentId] ?: ScoreStatus.PRESENT
        val next = when (current) {
            ScoreStatus.PRESENT -> ScoreStatus.ABSENT
            ScoreStatus.ABSENT -> ScoreStatus.EXEMPT
            ScoreStatus.EXEMPT -> ScoreStatus.PRESENT
        }
        _uiState.value = _uiState.value.copy(
            absenceStatus = _uiState.value.absenceStatus + (studentId to next),
        )
    }

    fun applyBulkStatus(status: ScoreStatus) {
        val map = _uiState.value.students.associate { it.studentId to status }
        _uiState.value = _uiState.value.copy(absenceStatus = map)
    }

    fun applyBulkMarks(value: String) {
        val state = _uiState.value
        val parsed = value.toDoubleOrNull()
        val max = state.maxMarks
        if (parsed == null || parsed < 0 || (max != null && parsed > max)) {
            _uiState.value = state.copy(error = "Enter valid bulk marks", info = null)
            return
        }
        val map = state.students.associate { student ->
            val status = state.absenceStatus[student.studentId] ?: ScoreStatus.PRESENT
            student.studentId to if (status == ScoreStatus.PRESENT) value else ""
        }
        _uiState.value = state.copy(scoreDrafts = map, error = null, info = null)
    }

    fun saveScores() {
        val state = _uiState.value
        val assessmentId = state.selectedAssessmentId
        if (assessmentId == null) {
            _uiState.value = state.copy(error = "Create/select an assessment first", info = null)
            return
        }

        viewModelScope.launch {
            _uiState.value = state.copy(saving = true, info = null, error = null)
            runCatching {
                state.students.forEach { student ->
                    val status = state.absenceStatus[student.studentId] ?: ScoreStatus.PRESENT
                    val raw = state.scoreDrafts[student.studentId].orEmpty().trim()

                    val marks = when (status) {
                        ScoreStatus.PRESENT -> {
                            if (raw.isEmpty()) return@forEach
                            val v = raw.toDoubleOrNull()
                                ?: throw IllegalArgumentException("Invalid marks for ${student.fullName}")
                            if (v < 0) {
                                throw IllegalArgumentException("${student.fullName}: marks cannot be negative")
                            }
                            val max = state.maxMarks
                            if (max != null && v > max) {
                                throw IllegalArgumentException("${student.fullName}: marks exceed max (${"%.0f".format(max)})")
                            }
                            v
                        }
                        ScoreStatus.ABSENT, ScoreStatus.EXEMPT -> 0.0
                    }

                    marksRepository.upsertScore(
                        ScoreEntry(
                            scoreId = "${assessmentId}_${student.studentId}",
                            assessmentId = assessmentId,
                            studentId = student.studentId,
                            obtainedMarks = marks,
                            status = status,
                        ),
                    )
                }
            }.onSuccess {
                _uiState.value = _uiState.value.copy(saving = false, info = "Scores saved and queued for sync")
            }.onFailure { error ->
                _uiState.value = _uiState.value.copy(saving = false, error = error.message)
            }
        }
    }

    fun requestPublish() {
        _uiState.value = _uiState.value.copy(showPublishDialog = true)
    }

    fun dismissPublishDialog() {
        _uiState.value = _uiState.value.copy(showPublishDialog = false)
    }

    fun confirmPublish() {
        _uiState.value = _uiState.value.copy(showPublishDialog = false)
        val state = _uiState.value
        val cycleId = state.selectedCycleId ?: return
        val sectionId = state.selectedSectionId ?: return
        viewModelScope.launch {
            runCatching {
                marksRepository.publishCycle(cycleId, sectionId)
            }.onSuccess {
                _uiState.value = _uiState.value.copy(info = "Results published successfully")
            }.onFailure { error ->
                _uiState.value = _uiState.value.copy(error = error.message)
            }
        }
    }

    companion object {
        fun factory(
            masterDataRepository: MasterDataRepository,
            marksRepository: MarksRepository,
        ): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    return TeacherViewModel(masterDataRepository, marksRepository) as T
                }
            }
    }
}
