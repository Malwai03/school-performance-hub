package com.schoolapp.ui.admin.gradebook

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.schoolapp.core.model.ScoreStatus
import com.schoolapp.core.ui.components.ContextSelectorBar
import com.schoolapp.core.ui.components.EmptyStateCard
import com.schoolapp.core.ui.components.InlineValidationText
import com.schoolapp.core.ui.components.MarksEntryTable
import com.schoolapp.core.ui.components.MarksRowUi
import com.schoolapp.core.ui.theme.DangerRed
import com.schoolapp.core.ui.theme.SuccessGreen
import com.schoolapp.ui.admin.AdminDashboardState
import com.schoolapp.ui.admin.AdminViewModel

@Composable
fun GradebookScreen(
    state: AdminDashboardState,
    viewModel: AdminViewModel,
    modifier: Modifier = Modifier,
) {
    var bulkMarks by rememberSaveable { mutableStateOf("") }
    val rows = state.gradebookStudents.map { student ->
        MarksRowUi(
            studentId = student.studentId,
            rollNumber = student.rollNumber,
            studentName = student.fullName,
            status = state.gradebookStatusByStudent[student.studentId] ?: ScoreStatus.PRESENT,
            marksDraft = state.gradebookScoreDrafts[student.studentId].orEmpty(),
            maxMarks = state.gradebookMaxMarks,
            validationMessage = state.gradebookValidationByStudent[student.studentId],
        )
    }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            ContextSelectorBar(
                sections = state.sections,
                cycles = state.cycles,
                subjectsBySection = state.subjectsBySection,
                assessmentsBySection = state.assessmentsBySection,
                context = state.context,
                onGradeSelected = viewModel::selectContextGrade,
                onSectionSelected = viewModel::selectContextSection,
                onCycleSelected = viewModel::selectContextCycle,
                onSubjectSelected = viewModel::selectContextSubject,
                onAssessmentSelected = viewModel::selectContextAssessment,
            )
        }

        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Bulk Actions", style = MaterialTheme.typography.titleSmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    OutlinedButton(onClick = { viewModel.applyBulkStatus(ScoreStatus.PRESENT) }, modifier = Modifier.weight(1f)) {
                        Text("All Present")
                    }
                    OutlinedButton(onClick = { viewModel.applyBulkStatus(ScoreStatus.ABSENT) }, modifier = Modifier.weight(1f)) {
                        Text("All Absent")
                    }
                    OutlinedButton(onClick = { viewModel.applyBulkStatus(ScoreStatus.EXEMPT) }, modifier = Modifier.weight(1f)) {
                        Text("All Exempt")
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    OutlinedTextField(
                        value = bulkMarks,
                        onValueChange = { bulkMarks = it.filter { c -> c.isDigit() || c == '.' } },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        label = { Text("Apply marks to all present") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        shape = RoundedCornerShape(12.dp),
                    )
                    Button(
                        onClick = { viewModel.applyBulkMarks(bulkMarks) },
                        modifier = Modifier.weight(0.5f),
                    ) {
                        Text("Apply")
                    }
                }
            }
        }

        if (rows.isEmpty()) {
            item {
                EmptyStateCard(
                    title = "No gradebook rows available",
                    description = "Select a valid grade, section, cycle, subject, and assessment.",
                )
            }
        } else {
            item {
                MarksEntryTable(
                    rows = rows,
                    onStatusToggle = viewModel::toggleGradebookStatus,
                    onMarksChange = viewModel::onGradebookScoreChange,
                )
            }
        }

        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                Button(
                    onClick = viewModel::saveGradebookScores,
                    enabled = !state.gradebookSaving && rows.isNotEmpty(),
                    modifier = Modifier.weight(1f),
                ) {
                    Text(if (state.gradebookSaving) "Saving..." else "Save Draft")
                }
                OutlinedButton(
                    onClick = viewModel::checkPublishReadiness,
                    enabled = rows.isNotEmpty(),
                    modifier = Modifier.weight(1f),
                ) {
                    Text("Check Ready")
                }
                Button(
                    onClick = viewModel::publishCurrentContext,
                    enabled = rows.isNotEmpty(),
                    modifier = Modifier.weight(1f),
                ) {
                    Text("Publish")
                }
            }
        }

        state.publishReadinessMessage?.let { message ->
            item {
                InlineValidationText(message = message, isError = state.publishReadinessError)
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
