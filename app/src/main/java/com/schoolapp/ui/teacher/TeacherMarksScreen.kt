package com.schoolapp.ui.teacher

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.schoolapp.core.model.AcademicContext
import com.schoolapp.core.model.ScoreStatus
import com.schoolapp.core.ui.components.AppTopBar
import com.schoolapp.core.ui.components.ContextSelectorBar
import com.schoolapp.core.ui.components.EmptyStateCard
import com.schoolapp.core.ui.components.MarksEntryTable
import com.schoolapp.core.ui.components.MarksRowUi
import com.schoolapp.core.ui.theme.DangerRed
import com.schoolapp.core.ui.theme.SuccessGreen
import com.schoolapp.core.ui.theme.TextSecondary

@Composable
fun TeacherMarksScreen(
    viewModel: TeacherViewModel,
    onLogout: () -> Unit,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val selectedSection = state.sections.firstOrNull { it.sectionId == state.selectedSectionId }
    val context = AcademicContext(
        grade = selectedSection?.grade,
        sectionId = state.selectedSectionId,
        cycleId = state.selectedCycleId,
        subjectId = state.selectedSubjectId,
        assessmentId = state.selectedAssessmentId,
    )
    val subjectsBySection = state.sections.associate { section ->
        section.sectionId to if (section.sectionId == state.selectedSectionId) state.subjects else emptyList()
    }
    val assessmentsBySection = state.sections.associate { section ->
        section.sectionId to if (section.sectionId == state.selectedSectionId) state.assessments else emptyList()
    }
    val rows = state.students.map { student ->
        MarksRowUi(
            studentId = student.studentId,
            rollNumber = student.rollNumber,
            studentName = student.fullName,
            status = state.absenceStatus[student.studentId] ?: ScoreStatus.PRESENT,
            marksDraft = state.scoreDrafts[student.studentId].orEmpty(),
            maxMarks = state.maxMarks,
        )
    }

    if (state.showPublishDialog) {
        AlertDialog(
            onDismissRequest = viewModel::dismissPublishDialog,
            title = { Text("Publish Results?") },
            text = { Text("This finalizes current cycle for the selected section.") },
            confirmButton = { Button(onClick = viewModel::confirmPublish) { Text("Publish") } },
            dismissButton = { OutlinedButton(onClick = viewModel::dismissPublishDialog) { Text("Cancel") } },
        )
    }

    Scaffold(
        topBar = {
            AppTopBar(
                title = "Teacher Gradebook",
                actions = {
                    IconButton(onClick = viewModel::refresh) {
                        Icon(Icons.Filled.Refresh, contentDescription = "Refresh", tint = TextSecondary)
                    }
                    IconButton(onClick = onLogout) {
                        Icon(Icons.AutoMirrored.Filled.Logout, contentDescription = "Logout", tint = TextSecondary)
                    }
                },
            )
        },
    ) { padding ->
        if (state.loading) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("Loading gradebook...", style = MaterialTheme.typography.titleMedium, color = TextSecondary)
            }
            return@Scaffold
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            ContextSelectorBar(
                sections = state.sections,
                cycles = state.cycles,
                subjectsBySection = subjectsBySection,
                assessmentsBySection = assessmentsBySection,
                context = context,
                onGradeSelected = { grade ->
                    state.sections.firstOrNull { it.grade == grade }?.let { viewModel.selectSection(it.sectionId) }
                },
                onSectionSelected = viewModel::selectSection,
                onCycleSelected = viewModel::selectCycle,
                onSubjectSelected = viewModel::selectSubject,
                onAssessmentSelected = viewModel::selectAssessment,
            )

            if (rows.isEmpty()) {
                EmptyStateCard(
                    title = "No marks rows",
                    description = "Select a valid section/cycle/assessment to load students.",
                )
            } else {
                MarksEntryTable(
                    rows = rows,
                    onStatusToggle = viewModel::toggleStudentStatus,
                    onMarksChange = viewModel::onScoreChange,
                    modifier = Modifier.weight(1f),
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                Button(
                    onClick = viewModel::saveScores,
                    enabled = !state.saving && state.selectedAssessmentId != null,
                    modifier = Modifier.weight(1f),
                ) {
                    Text(if (state.saving) "Saving..." else "Save Draft")
                }
                Button(
                    onClick = viewModel::requestPublish,
                    enabled = state.selectedCycleId != null && state.selectedSectionId != null,
                    modifier = Modifier.weight(1f),
                ) {
                    Text("Publish")
                }
            }

            state.error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = DangerRed) }
            state.info?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = SuccessGreen) }
        }
    }
}
