package com.schoolapp.ui.admin.gradebook

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.schoolapp.core.model.ScoreStatus
import com.schoolapp.core.ui.components.DropdownOption
import com.schoolapp.core.ui.components.EmptyStateCard
import com.schoolapp.core.ui.components.FormDropdownField
import com.schoolapp.core.ui.components.GlassCard
import com.schoolapp.core.ui.components.InlineValidationText
import com.schoolapp.core.ui.components.MarksEntryTable
import com.schoolapp.core.ui.components.MarksRowUi
import com.schoolapp.core.ui.theme.DangerRed
import com.schoolapp.core.ui.theme.SuccessGreen
import com.schoolapp.core.ui.theme.SurfaceBackground
import com.schoolapp.core.ui.theme.TextSecondary
import com.schoolapp.ui.admin.AdminDashboardState
import com.schoolapp.ui.admin.AdminViewModel

@Composable
fun GradebookScreen(
    state: AdminDashboardState,
    viewModel: AdminViewModel,
    modifier: Modifier = Modifier,
) {
    var quickExamName by rememberSaveable { mutableStateOf("Class Test") }
    var maxMarksText by rememberSaveable { mutableStateOf("100") }
    var showEntryForm by rememberSaveable { mutableStateOf(false) }
    var waitingForAutoExam by rememberSaveable { mutableStateOf(false) }
    var selectedExistingExamId by rememberSaveable { mutableStateOf("") }

    val gradeOptions = state.sections
        .map { it.grade }
        .distinct()
        .sorted()
        .map { DropdownOption(it.toString(), "Grade $it") }
    val sectionOptions = state.sections
        .filter { it.grade == state.context.grade }
        .sortedWith(compareBy({ it.grade }, { it.sectionName }))
        .map { DropdownOption(it.sectionId, "${it.sectionName} (Grade ${it.grade})") }
    val subjectOptions = state.context.sectionId
        ?.let { sectionId -> state.subjectsBySection[sectionId].orEmpty() }
        .orEmpty()
        .sortedBy { it.name }
        .map { DropdownOption(it.subjectId, it.name) }
    val selectedAssessments = state.context.sectionId
        ?.let { sectionId -> state.assessmentsBySection[sectionId].orEmpty() }
        .orEmpty()
        .filter { assessment -> state.context.subjectId == null || assessment.subjectId == state.context.subjectId }
        .sortedBy { it.title }
    val existingExamOptions = selectedAssessments.map { DropdownOption(it.assessmentId, it.title) }

    LaunchedEffect(state.context.sectionId, state.context.subjectId) {
        showEntryForm = false
        waitingForAutoExam = false
        selectedExistingExamId = ""
    }

    LaunchedEffect(waitingForAutoExam, selectedAssessments) {
        if (waitingForAutoExam && selectedAssessments.isNotEmpty()) {
            val newest = selectedAssessments.lastOrNull() ?: selectedAssessments.first()
            viewModel.selectContextAssessment(newest.assessmentId)
            showEntryForm = true
            waitingForAutoExam = false
        }
    }

    LaunchedEffect(selectedExistingExamId, selectedAssessments) {
        val selected = selectedAssessments.firstOrNull { it.assessmentId == selectedExistingExamId } ?: return@LaunchedEffect
        quickExamName = selected.title
        maxMarksText = if (selected.maxMarks % 1.0 == 0.0) {
            selected.maxMarks.toInt().toString()
        } else {
            "%.2f".format(selected.maxMarks).trimEnd('0').trimEnd('.')
        }
    }

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
            .background(SurfaceBackground)
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        GlassCard(Modifier.fillMaxWidth()) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Mass Score Entry", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                Text(
                    "Select class, section, and subject. Press Done to open marks form.",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary,
                )
                FormDropdownField(
                    label = "Class",
                    options = gradeOptions,
                    selectedId = state.context.grade?.toString(),
                    onSelected = { selected -> selected.toIntOrNull()?.let(viewModel::selectContextGrade) },
                    enabled = gradeOptions.isNotEmpty(),
                )
                FormDropdownField(
                    label = "Section",
                    options = sectionOptions,
                    selectedId = state.context.sectionId,
                    onSelected = viewModel::selectContextSection,
                    enabled = sectionOptions.isNotEmpty(),
                )
                FormDropdownField(
                    label = "Subject",
                    options = subjectOptions,
                    selectedId = state.context.subjectId,
                    onSelected = viewModel::selectContextSubject,
                    enabled = subjectOptions.isNotEmpty(),
                )
                FormDropdownField(
                    label = "Existing Exam (Edit)",
                    options = existingExamOptions,
                    selectedId = selectedExistingExamId.ifBlank { null },
                    onSelected = { selectedExistingExamId = it },
                    enabled = existingExamOptions.isNotEmpty(),
                )
                if (existingExamOptions.isNotEmpty()) {
                    Button(
                        onClick = {
                            if (selectedExistingExamId.isNotBlank()) {
                                viewModel.selectContextAssessment(selectedExistingExamId)
                                showEntryForm = true
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = selectedExistingExamId.isNotBlank(),
                    ) {
                        Text("Open Existing Exam for Edit")
                    }
                }
                OutlinedTextField(
                    value = quickExamName,
                    onValueChange = { quickExamName = it },
                    label = { Text("New Exam Name") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
                OutlinedTextField(
                    value = maxMarksText,
                    onValueChange = { maxMarksText = it.filter { c -> c.isDigit() || c == '.' } },
                    label = { Text("Max Marks") },
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                )
                Button(
                    onClick = {
                        val requestedExamName = quickExamName.trim()
                        val existing = selectedAssessments.firstOrNull {
                            it.title.equals(requestedExamName, ignoreCase = true)
                        }
                        if (existing != null) {
                            viewModel.selectContextAssessment(existing.assessmentId)
                            showEntryForm = true
                        } else {
                            viewModel.createExamForm(
                                title = requestedExamName.ifBlank { "Class Test" },
                                subjectId = state.context.subjectId.orEmpty(),
                                maxMarks = maxMarksText.toDoubleOrNull() ?: 100.0,
                                weight = 1.0,
                            )
                            waitingForAutoExam = true
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = state.context.grade != null &&
                        state.context.sectionId != null &&
                        state.context.subjectId != null &&
                        state.context.cycleId != null &&
                        quickExamName.isNotBlank(),
                ) {
                    Text("Create New Exam and Open")
                }
                if (state.context.cycleId == null) {
                    Text("Create a cycle first from Manage page.", style = MaterialTheme.typography.bodySmall, color = TextSecondary)
                }
            }
        }

        if (!showEntryForm) {
            EmptyStateCard(
                title = "Marks form hidden",
                description = "Select details and press Done.",
            )
        } else if (rows.isEmpty()) {
            EmptyStateCard(
                title = "No students in selected section",
                description = "Add students from Students page, then return here.",
            )
        } else {
            GlassCard(Modifier.fillMaxWidth()) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Enter Marks", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    Text(
                        "Exam max marks: ${state.gradebookMaxMarks?.let { "%.0f".format(it) } ?: "-"}",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary,
                    )
                    MarksEntryTable(
                        rows = rows,
                        onStatusToggle = viewModel::toggleGradebookStatus,
                        onMarksChange = viewModel::onGradebookScoreChange,
                        dense = true,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        Button(
                            onClick = viewModel::saveGradebookScores,
                            enabled = !state.gradebookSaving,
                            modifier = Modifier.weight(1f),
                        ) {
                            Text(if (state.gradebookSaving) "Saving..." else "Save Marks")
                        }
                        Button(
                            onClick = viewModel::runDashboardSearch,
                            modifier = Modifier.weight(1f),
                        ) {
                            Text("Refresh Dashboard")
                        }
                    }
                }
            }
        }

        state.publishReadinessMessage?.let { message ->
            InlineValidationText(message = message, isError = state.publishReadinessError)
        }
        state.error?.let { message ->
            Text(message, style = MaterialTheme.typography.bodySmall, color = DangerRed)
        }
        state.info?.let { message ->
            Text(message, style = MaterialTheme.typography.bodySmall, color = SuccessGreen)
        }
    }
}
