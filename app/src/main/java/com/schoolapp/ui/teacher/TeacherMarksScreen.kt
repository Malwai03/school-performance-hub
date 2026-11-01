package com.schoolapp.ui.teacher

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.schoolapp.core.model.AcademicContext
import com.schoolapp.core.model.ScoreStatus
import com.schoolapp.core.ui.components.AppTopBar
import com.schoolapp.core.ui.components.ContextSelectorBar
import com.schoolapp.core.ui.components.EmptyStateCard
import com.schoolapp.core.ui.components.MarksEntryTable
import com.schoolapp.core.ui.components.MarksRowUi
import com.schoolapp.core.ui.theme.BorderGray
import com.schoolapp.core.ui.theme.DangerRed
import com.schoolapp.core.ui.theme.PrimaryBlue
import com.schoolapp.core.ui.theme.SuccessGreen
import com.schoolapp.core.ui.theme.SurfaceBackground
import com.schoolapp.core.ui.theme.SurfaceWhite
import com.schoolapp.core.ui.theme.TextPrimary
import com.schoolapp.core.ui.theme.TextSecondary
import com.schoolapp.ui.common.SyncRefreshBox

@Composable
fun TeacherMarksScreen(
    viewModel: TeacherViewModel,
    onLogout: () -> Unit,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var bulkMarks by rememberSaveable { mutableStateOf("") }
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
                    IconButton(onClick = viewModel::pullToRefresh) {
                        Icon(Icons.Filled.Refresh, contentDescription = "Refresh", tint = TextSecondary)
                    }
                    IconButton(onClick = onLogout) {
                        Icon(Icons.AutoMirrored.Filled.Logout, contentDescription = "Logout", tint = TextSecondary)
                    }
                },
            )
        },
    ) { padding ->
        SyncRefreshBox(
            syncing = state.syncing,
            notice = state.syncNotice,
            onRefresh = viewModel::pullToRefresh,
            modifier = Modifier.padding(padding),
        ) {
            if (state.loading) {
                Column(
                    modifier = Modifier
                        .fillMaxSize(),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text("Loading gradebook...", style = MaterialTheme.typography.titleMedium, color = TextSecondary)
                }
                return@SyncRefreshBox
            }

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(SurfaceBackground),
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

                Surface(color = SurfaceWhite, modifier = Modifier.fillMaxWidth(), shadowElevation = 2.dp) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(onClick = { viewModel.applyBulkStatus(ScoreStatus.PRESENT) }) {
                                    Text("All Present", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                }
                                OutlinedButton(onClick = { viewModel.applyBulkStatus(ScoreStatus.ABSENT) }) {
                                    Text("All Absent", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                            Box(
                                modifier = Modifier
                                    .background(SurfaceBackground, RoundedCornerShape(8.dp))
                                    .border(1.dp, BorderGray, RoundedCornerShape(8.dp))
                                    .padding(horizontal = 8.dp, vertical = 4.dp),
                            ) {
                                Text(
                                    "Max: ${state.maxMarks?.let { "%.0f".format(it) } ?: "-"}",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = TextPrimary,
                                )
                            }
                        }

                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                            OutlinedTextField(
                                value = bulkMarks,
                                onValueChange = { bulkMarks = it.filter { c -> c.isDigit() || c == '.' } },
                                placeholder = { Text("Apply marks to all present...") },
                                modifier = Modifier.weight(1f),
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            )
                            Button(
                                onClick = { viewModel.applyBulkMarks(bulkMarks) },
                                colors = ButtonDefaults.buttonColors(containerColor = PrimaryBlue),
                            ) {
                                Text("Apply", fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }

                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    if (rows.isEmpty()) {
                        item {
                            EmptyStateCard(
                                title = "No marks rows",
                                description = "Select section/cycle/subject/assessment to load students.",
                            )
                        }
                    } else {
                        item {
                            MarksEntryTable(
                                rows = rows,
                                onStatusToggle = viewModel::toggleStudentStatus,
                                onMarksChange = viewModel::onScoreChange,
                                dense = true,
                            )
                        }
                    }

                    item {
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
                    }

                    state.error?.let { message ->
                        item { Text(message, style = MaterialTheme.typography.bodySmall, color = DangerRed) }
                    }
                    state.info?.let { message ->
                        item { Text(message, style = MaterialTheme.typography.bodySmall, color = SuccessGreen) }
                    }
                }
            }
        }
    }
}
