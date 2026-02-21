package com.schoolapp.ui.admin.gradebook

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
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import com.schoolapp.core.model.ScoreStatus
import com.schoolapp.core.ui.components.ContextSelectorBar
import com.schoolapp.core.ui.components.EmptyStateCard
import com.schoolapp.core.ui.components.InlineValidationText
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

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(SurfaceBackground),
    ) {
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

        Surface(
            color = SurfaceWhite,
            modifier = Modifier.fillMaxWidth(),
            shadowElevation = 2.dp,
        ) {
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
                        OutlinedButton(
                            onClick = { viewModel.applyBulkStatus(ScoreStatus.PRESENT) },
                            shape = RoundedCornerShape(8.dp),
                        ) {
                            Text("All Present", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                        OutlinedButton(
                            onClick = { viewModel.applyBulkStatus(ScoreStatus.ABSENT) },
                            shape = RoundedCornerShape(8.dp),
                        ) {
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
                            text = "Max: ${state.gradebookMaxMarks?.let { "%.0f".format(it) } ?: "-"}",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = TextPrimary,
                        )
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OutlinedTextField(
                        value = bulkMarks,
                        onValueChange = { bulkMarks = it.filter { c -> c.isDigit() || c == '.' } },
                        placeholder = { Text("Apply marks to all present...", color = TextSecondary) },
                        modifier = Modifier
                            .weight(1f)
                            .height(50.dp),
                        shape = RoundedCornerShape(12.dp),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    )
                    Button(
                        onClick = { viewModel.applyBulkMarks(bulkMarks) },
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = PrimaryBlue),
                        modifier = Modifier.height(50.dp),
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
                        title = "No gradebook rows available",
                        description = "Select grade/section/cycle/subject/assessment to load the table.",
                    )
                }
            } else {
                item {
                    MarksEntryTable(
                        rows = rows,
                        onStatusToggle = viewModel::toggleGradebookStatus,
                        onMarksChange = viewModel::onGradebookScoreChange,
                        dense = true,
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
                item { InlineValidationText(message = message, isError = state.publishReadinessError) }
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
