package com.schoolapp.ui.student

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.schoolapp.core.model.ResultSnapshot
import com.schoolapp.domain.repository.AnalyticsRepository
import com.schoolapp.domain.repository.AuthRepository
import com.schoolapp.domain.repository.MasterDataRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class StudentDashboardState(
    val loading: Boolean = true,
    val selectedTab: Int = 0,
    val studentName: String = "",
    val latest: ResultSnapshot? = null,
    val trend: List<ResultSnapshot> = emptyList(),
    val sectionAverage: Double = 0.0,
    val rankHint: String = "",
    val subjectNames: Map<String, String> = emptyMap(),
    val cycleName: String = "",
    /** The server could not be asked for results just now: these are the saved ones. */
    val resultsMayBeOutdated: Boolean = false,
    val error: String? = null,
)

class StudentViewModel(
    private val authRepository: AuthRepository,
    private val analyticsRepository: AnalyticsRepository,
    private val masterDataRepository: MasterDataRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(StudentDashboardState())
    val uiState: StateFlow<StudentDashboardState> = _uiState.asStateFlow()

    init {
        refresh()
    }

    fun selectTab(tab: Int) {
        _uiState.value = _uiState.value.copy(selectedTab = tab)
    }

    fun refresh() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(loading = true, error = null)
            runCatching {
                val user = authRepository.currentUser() ?: error("No session")
                val studentTrend = analyticsRepository.getStudentTrend(user.uid)
                val trend = studentTrend.snapshots
                val latest = trend.lastOrNull()

                val subjectNames = if (latest != null) {
                    runCatching {
                        masterDataRepository.getSubjectsBySection(latest.sectionId)
                            .associate { it.subjectId to it.name }
                    }.getOrDefault(emptyMap())
                } else {
                    emptyMap()
                }

                val sectionAverage = if (latest != null) {
                    runCatching {
                        analyticsRepository.getSectionSummary(latest.cycleId, latest.sectionId).averagePct
                    }.getOrElse {
                        trend.mapNotNull { it.overallPercentage }.average().takeIf { !it.isNaN() } ?: 0.0
                    }
                } else {
                    0.0
                }

                val cycleName = if (latest != null) {
                    runCatching {
                        masterDataRepository.getCycles().firstOrNull { it.cycleId == latest.cycleId }?.name
                    }.getOrNull() ?: latest.cycleId
                } else {
                    ""
                }

                StudentDashboardState(
                    loading = false,
                    selectedTab = _uiState.value.selectedTab,
                    studentName = user.displayName,
                    latest = latest,
                    trend = trend,
                    sectionAverage = sectionAverage,
                    rankHint = when {
                        latest?.overallPercentage == null -> "No rank until published"
                        latest.overallPercentage >= 85 -> "Top band in section"
                        latest.overallPercentage >= 65 -> "Mid-high section band"
                        else -> "Needs improvement"
                    },
                    subjectNames = subjectNames,
                    cycleName = cycleName,
                    resultsMayBeOutdated = studentTrend.serverUnreachable,
                )
            }.onSuccess { _uiState.value = it }
                .onFailure { error -> _uiState.value = _uiState.value.copy(loading = false, error = error.message) }
        }
    }

    companion object {
        fun factory(
            authRepository: AuthRepository,
            analyticsRepository: AnalyticsRepository,
            masterDataRepository: MasterDataRepository,
        ): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    return StudentViewModel(authRepository, analyticsRepository, masterDataRepository) as T
                }
            }
    }
}
