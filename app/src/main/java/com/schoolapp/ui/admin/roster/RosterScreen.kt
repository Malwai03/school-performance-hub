package com.schoolapp.ui.admin.roster

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.schoolapp.core.ui.components.ContextSelectorBar
import com.schoolapp.core.ui.components.DropdownOption
import com.schoolapp.core.ui.components.EmptyStateCard
import com.schoolapp.core.ui.components.FormDropdownField
import com.schoolapp.core.ui.components.GlassCard
import com.schoolapp.core.ui.components.SectionHierarchyPanel
import com.schoolapp.core.ui.theme.DangerRed
import com.schoolapp.core.ui.theme.PrimaryBlue
import com.schoolapp.core.ui.theme.SuccessGreen
import com.schoolapp.core.ui.theme.TextSecondary
import com.schoolapp.ui.admin.AdminDashboardState
import com.schoolapp.ui.admin.AdminViewModel
import com.schoolapp.ui.admin.profile.StudentProfileScreen

@Composable
@OptIn(ExperimentalMaterial3Api::class)
fun RosterScreen(
    state: AdminDashboardState,
    viewModel: AdminViewModel,
    modifier: Modifier = Modifier,
) {
    if (state.selectedStudentProfileId != null) {
        StudentProfileScreen(
            state = state,
            viewModel = viewModel,
            modifier = modifier,
        )
        return
    }

    val selectedSectionId = state.context.sectionId
    val studentsInSection = selectedSectionId?.let { state.studentsBySection[it] }
        .orEmpty()
        .sortedBy { it.rollNumber }

    var searchQuery by rememberSaveable { mutableStateOf("") }
    var studentName by rememberSaveable { mutableStateOf("") }
    var rollInput by rememberSaveable { mutableStateOf("") }
    var batchNames by rememberSaveable { mutableStateOf("") }
    var batchStartRoll by rememberSaveable { mutableStateOf("1") }
    var importType by rememberSaveable { mutableStateOf("csv") }
    var selectedImportUri by rememberSaveable { mutableStateOf("") }
    var selectedImportName by rememberSaveable { mutableStateOf("") }
    var showAddSheet by rememberSaveable { mutableStateOf(false) }
    var showBatchSheet by rememberSaveable { mutableStateOf(false) }
    var showImportSheet by rememberSaveable { mutableStateOf(false) }

    val context = LocalContext.current
    val importPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            }
            selectedImportUri = uri.toString()
            selectedImportName = uri.lastPathSegment.orEmpty()
        }
    }

    val filtered = remember(studentsInSection, searchQuery) {
        studentsInSection.filter { student ->
            if (searchQuery.isBlank()) return@filter true
            student.fullName.contains(searchQuery.trim(), ignoreCase = true) ||
                student.rollNumber.toString().contains(searchQuery.trim())
        }
    }

    val grades = state.sections.map { it.grade }.distinct().sorted()
    LaunchedEffect(grades, state.context.grade) {
        if (grades.isNotEmpty() && state.context.grade == null) {
            viewModel.selectContextGrade(grades.first())
        }
    }

    if (showAddSheet) {
        ModalBottomSheet(onDismissRequest = { showAddSheet = false }) {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Add Student", style = MaterialTheme.typography.titleMedium)
                OutlinedTextField(
                    value = studentName,
                    onValueChange = { studentName = it },
                    label = { Text("Student Name") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
                OutlinedTextField(
                    value = rollInput,
                    onValueChange = { rollInput = it.filter { c -> c.isDigit() } },
                    label = { Text("Roll Number") },
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                )
                Button(
                    onClick = {
                        viewModel.addStudent(
                            sectionId = state.context.sectionId.orEmpty(),
                            fullName = studentName,
                            rollNumber = rollInput.toIntOrNull() ?: 0,
                        )
                        studentName = ""
                        rollInput = ""
                        showAddSheet = false
                    },
                    enabled = state.context.sectionId != null &&
                        studentName.isNotBlank() &&
                        (rollInput.toIntOrNull() ?: 0) > 0,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Save Student")
                }
            }
        }
    }

    if (showBatchSheet) {
        ModalBottomSheet(onDismissRequest = { showBatchSheet = false }) {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Batch Add Students", style = MaterialTheme.typography.titleMedium)
                OutlinedTextField(
                    value = batchStartRoll,
                    onValueChange = { batchStartRoll = it.filter { c -> c.isDigit() } },
                    label = { Text("Start Roll Number") },
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                )
                OutlinedTextField(
                    value = batchNames,
                    onValueChange = { batchNames = it },
                    label = { Text("Names (comma/new line)") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 3,
                )
                Button(
                    onClick = {
                        viewModel.addStudentsBatch(
                            sectionId = state.context.sectionId.orEmpty(),
                            namesRaw = batchNames,
                            startRoll = batchStartRoll.toIntOrNull() ?: 0,
                        )
                        batchNames = ""
                        showBatchSheet = false
                    },
                    enabled = state.context.sectionId != null &&
                        batchNames.isNotBlank() &&
                        (batchStartRoll.toIntOrNull() ?: 0) > 0,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Save Batch")
                }
            }
        }
    }

    if (showImportSheet) {
        ModalBottomSheet(onDismissRequest = { showImportSheet = false }) {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Import Students", style = MaterialTheme.typography.titleMedium)
                FormDropdownField(
                    label = "File Type",
                    options = listOf(
                        DropdownOption("csv", "CSV"),
                        DropdownOption("xlsx", "Excel (.xlsx)"),
                    ),
                    selectedId = importType,
                    onSelected = { importType = it },
                )
                Button(
                    onClick = {
                        val mimeTypes = if (importType == "xlsx") {
                            arrayOf(
                                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                                "application/vnd.ms-excel",
                            )
                        } else {
                            arrayOf("text/csv", "text/comma-separated-values", "text/plain")
                        }
                        importPicker.launch(mimeTypes)
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(if (selectedImportName.isBlank()) "Pick File" else "Change File")
                }
                if (selectedImportName.isNotBlank()) {
                    Text("Selected: $selectedImportName", style = MaterialTheme.typography.bodySmall, color = TextSecondary)
                }
                Button(
                    onClick = {
                        viewModel.importData(selectedImportUri, importType, "students")
                        showImportSheet = false
                    },
                    enabled = selectedImportUri.isNotBlank(),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Start Import")
                }
            }
        }
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
                showSubject = false,
                showAssessment = false,
                onGradeSelected = viewModel::selectContextGrade,
                onSectionSelected = viewModel::selectContextSection,
                onCycleSelected = viewModel::selectContextCycle,
                onSubjectSelected = viewModel::selectContextSubject,
                onAssessmentSelected = viewModel::selectContextAssessment,
            )
        }

        item {
            SectionHierarchyPanel(
                selectedGrade = state.context.grade,
                selectedSectionId = state.context.sectionId,
                sections = state.sections,
                studentCountBySection = state.studentsBySection.mapValues { it.value.size },
                onSectionSelected = viewModel::selectContextSection,
            )
        }

        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                Button(onClick = { showAddSheet = true }, modifier = Modifier.weight(1f)) { Text("Add Student") }
                Button(onClick = { showBatchSheet = true }, modifier = Modifier.weight(1f)) { Text("Batch Add") }
                Button(onClick = { showImportSheet = true }, modifier = Modifier.weight(1f)) { Text("Import") }
            }
        }

        item {
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Search by roll/name") },
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
            )
        }

        if (filtered.isEmpty()) {
            item {
                EmptyStateCard(
                    title = "No students in current section",
                    description = "Use Add/Batch/Import actions to populate roster.",
                )
            }
        } else {
            items(filtered) { student ->
                GlassCard(Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { viewModel.openStudentProfile(student.studentId) },
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Column {
                            Text(
                                "${student.rollNumber}. ${student.fullName}",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium,
                            )
                            Text("Tap to open profile", style = MaterialTheme.typography.bodySmall, color = TextSecondary)
                        }
                        Text(
                            text = if (student.active) "Active" else "Inactive",
                            color = if (student.active) SuccessGreen else DangerRed,
                            style = MaterialTheme.typography.labelMedium,
                        )
                    }
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
