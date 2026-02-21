package com.schoolapp.ui.admin.setup

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.schoolapp.core.ui.components.CalendarDateField
import com.schoolapp.core.ui.components.ContextSelectorBar
import com.schoolapp.core.ui.components.DropdownOption
import com.schoolapp.core.ui.components.FormDropdownField
import com.schoolapp.core.ui.components.GlassCard
import com.schoolapp.core.ui.components.WizardStepCard
import com.schoolapp.core.ui.theme.DangerRed
import com.schoolapp.core.ui.theme.SuccessGreen
import com.schoolapp.core.ui.theme.TextSecondary
import com.schoolapp.ui.admin.AdminDashboardState
import com.schoolapp.ui.admin.AdminViewModel

@Composable
fun SetupWizardScreen(
    state: AdminDashboardState,
    viewModel: AdminViewModel,
    modifier: Modifier = Modifier,
) {
    var sectionGrade by rememberSaveable { mutableStateOf(state.context.grade?.toString() ?: "6") }
    var sectionName by rememberSaveable { mutableStateOf("A") }
    var subjectName by rememberSaveable { mutableStateOf("") }
    var subjectPass by rememberSaveable { mutableStateOf("33") }
    var subjectMandatory by rememberSaveable { mutableStateOf(true) }
    var cycleName by rememberSaveable { mutableStateOf("") }
    var cycleStart by rememberSaveable { mutableStateOf("") }
    var cycleEnd by rememberSaveable { mutableStateOf("") }
    var semCount by rememberSaveable { mutableStateOf("2") }
    var sem1 by rememberSaveable { mutableStateOf("100") }
    var sem2 by rememberSaveable { mutableStateOf("100") }
    var sem3 by rememberSaveable { mutableStateOf("100") }
    var weightMode by rememberSaveable { mutableStateOf("equal") }
    var customW1 by rememberSaveable { mutableStateOf("34") }
    var customW2 by rememberSaveable { mutableStateOf("33") }
    var customW3 by rememberSaveable { mutableStateOf("33") }
    var importEntity by rememberSaveable { mutableStateOf("students") }
    var importType by rememberSaveable { mutableStateOf("csv") }
    var selectedImportUri by rememberSaveable { mutableStateOf("") }
    var selectedImportName by rememberSaveable { mutableStateOf("") }

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

    LaunchedEffect(state.context.grade) {
        if (state.context.grade != null) {
            sectionGrade = state.context.grade.toString()
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
            WizardStepCard(
                stepNumber = 1,
                title = "Create Section",
                subtitle = "Create class-section containers first (grade 1-10).",
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    OutlinedTextField(
                        value = sectionGrade,
                        onValueChange = { sectionGrade = it.filter { c -> c.isDigit() }.take(2) },
                        label = { Text("Grade") },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    )
                    OutlinedTextField(
                        value = sectionName,
                        onValueChange = { sectionName = it.take(3) },
                        label = { Text("Section") },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                    )
                }
                Button(
                    onClick = { viewModel.createSection(sectionGrade.toIntOrNull() ?: 0, sectionName) },
                    enabled = (sectionGrade.toIntOrNull() ?: 0) in 1..10 && sectionName.isNotBlank(),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Create Section")
                }
            }
        }

        item {
            WizardStepCard(
                stepNumber = 2,
                title = "Create Subject",
                subtitle = "Add mandatory/optional subject and pass threshold.",
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = subjectName,
                        onValueChange = { subjectName = it },
                        label = { Text("Subject Name") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                    )
                    OutlinedTextField(
                        value = subjectPass,
                        onValueChange = { subjectPass = it.filter { c -> c.isDigit() || c == '.' } },
                        label = { Text("Pass Threshold %") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("Mandatory", style = MaterialTheme.typography.bodyMedium)
                        Switch(checked = subjectMandatory, onCheckedChange = { subjectMandatory = it })
                    }
                    Button(
                        onClick = {
                            viewModel.createSubject(
                                sectionId = state.context.sectionId.orEmpty(),
                                subjectName = subjectName,
                                passThreshold = subjectPass.toDoubleOrNull() ?: 33.0,
                                mandatory = subjectMandatory,
                            )
                            subjectName = ""
                        },
                        enabled = state.context.sectionId != null && subjectName.isNotBlank(),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Create Subject")
                    }
                }
            }
        }

        item {
            WizardStepCard(
                stepNumber = 3,
                title = "Cycle + Semester Plan",
                subtitle = "Generate assessments for each subject with weighted logic.",
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = cycleName,
                        onValueChange = { cycleName = it },
                        label = { Text("Cycle Name") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        CalendarDateField(
                            label = "Start Date",
                            valueIsoDate = cycleStart,
                            onDateSelected = { cycleStart = it },
                            modifier = Modifier.weight(1f),
                        )
                        CalendarDateField(
                            label = "End Date",
                            valueIsoDate = cycleEnd,
                            onDateSelected = { cycleEnd = it },
                            modifier = Modifier.weight(1f),
                        )
                    }
                    FormDropdownField(
                        label = "No. of Semesters",
                        options = listOf(
                            DropdownOption("1", "1 semester"),
                            DropdownOption("2", "2 semesters"),
                            DropdownOption("3", "3 semesters"),
                        ),
                        selectedId = semCount,
                        onSelected = { semCount = it },
                    )
                    OutlinedTextField(
                        value = sem1,
                        onValueChange = { sem1 = it.filter { c -> c.isDigit() || c == '.' } },
                        label = { Text("Semester 1 Max Marks") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    )
                    if ((semCount.toIntOrNull() ?: 1) >= 2) {
                        OutlinedTextField(
                            value = sem2,
                            onValueChange = { sem2 = it.filter { c -> c.isDigit() || c == '.' } },
                            label = { Text("Semester 2 Max Marks") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        )
                    }
                    if ((semCount.toIntOrNull() ?: 1) >= 3) {
                        OutlinedTextField(
                            value = sem3,
                            onValueChange = { sem3 = it.filter { c -> c.isDigit() || c == '.' } },
                            label = { Text("Semester 3 Max Marks") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        )
                    }
                    FormDropdownField(
                        label = "Weight Logic",
                        options = listOf(
                            DropdownOption("equal", "Equal weight"),
                            DropdownOption("max", "By max marks"),
                            DropdownOption("custom", "Custom % (sum 100)"),
                        ),
                        selectedId = weightMode,
                        onSelected = { weightMode = it },
                    )
                    if (weightMode == "custom") {
                        OutlinedTextField(
                            value = customW1,
                            onValueChange = { customW1 = it.filter { c -> c.isDigit() || c == '.' } },
                            label = { Text("Semester 1 Weight %") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        )
                        if ((semCount.toIntOrNull() ?: 1) >= 2) {
                            OutlinedTextField(
                                value = customW2,
                                onValueChange = { customW2 = it.filter { c -> c.isDigit() || c == '.' } },
                                label = { Text("Semester 2 Weight %") },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            )
                        }
                        if ((semCount.toIntOrNull() ?: 1) >= 3) {
                            OutlinedTextField(
                                value = customW3,
                                onValueChange = { customW3 = it.filter { c -> c.isDigit() || c == '.' } },
                                label = { Text("Semester 3 Weight %") },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            )
                        }
                        val customSum = buildList {
                            add(customW1.toDoubleOrNull() ?: 0.0)
                            if ((semCount.toIntOrNull() ?: 1) >= 2) add(customW2.toDoubleOrNull() ?: 0.0)
                            if ((semCount.toIntOrNull() ?: 1) >= 3) add(customW3.toDoubleOrNull() ?: 0.0)
                        }.sum()
                        Text(
                            "Custom weight total: ${"%.1f".format(customSum)}%",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (customSum in 99.9..100.1) SuccessGreen else DangerRed,
                        )
                    }
                    Button(
                        onClick = {
                            val marks = buildList {
                                add(sem1.toDoubleOrNull() ?: 0.0)
                                if ((semCount.toIntOrNull() ?: 1) >= 2) add(sem2.toDoubleOrNull() ?: 0.0)
                                if ((semCount.toIntOrNull() ?: 1) >= 3) add(sem3.toDoubleOrNull() ?: 0.0)
                            }
                            val customWeights: List<Double>? = if (weightMode == "custom") {
                                buildList<Double> {
                                    add(customW1.toDoubleOrNull() ?: 0.0)
                                    if ((semCount.toIntOrNull() ?: 1) >= 2) add(customW2.toDoubleOrNull() ?: 0.0)
                                    if ((semCount.toIntOrNull() ?: 1) >= 3) add(customW3.toDoubleOrNull() ?: 0.0)
                                }
                            } else {
                                null
                            }
                            viewModel.createCycleWithSemesterPlan(
                                sectionId = state.context.sectionId.orEmpty(),
                                cycleName = cycleName,
                                startDate = cycleStart,
                                endDate = cycleEnd,
                                semesterMarks = marks,
                                useMaxMarksAsWeight = weightMode == "max",
                                customWeights = customWeights,
                            )
                        },
                        enabled = state.context.sectionId != null &&
                            cycleName.isNotBlank() &&
                            cycleStart.isNotBlank() &&
                            cycleEnd.isNotBlank() &&
                            (
                                weightMode != "custom" || buildList {
                                    add(customW1.toDoubleOrNull() ?: 0.0)
                                    if ((semCount.toIntOrNull() ?: 1) >= 2) add(customW2.toDoubleOrNull() ?: 0.0)
                                    if ((semCount.toIntOrNull() ?: 1) >= 3) add(customW3.toDoubleOrNull() ?: 0.0)
                                }.sum() in 99.9..100.1
                            ),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Generate Assessments")
                    }
                }
            }
        }

        item {
            WizardStepCard(
                stepNumber = 4,
                title = "Import Center",
                subtitle = "Import students, sections, subjects or scores via CSV/XLSX.",
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    FormDropdownField(
                        label = "Entity",
                        options = listOf(
                            DropdownOption("students", "Students"),
                            DropdownOption("sections", "Sections"),
                            DropdownOption("subjects", "Subjects"),
                            DropdownOption("scores", "Scores"),
                        ),
                        selectedId = importEntity,
                        onSelected = { importEntity = it },
                    )
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
                        onClick = { viewModel.importData(selectedImportUri, importType, importEntity) },
                        enabled = selectedImportUri.isNotBlank(),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Start Import")
                    }
                }
            }
        }

        item {
            GlassCard(Modifier.fillMaxWidth()) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Setup Snapshot", style = MaterialTheme.typography.titleSmall)
                    Text("Sections: ${state.sections.size}", style = MaterialTheme.typography.bodySmall, color = TextSecondary)
                    Text("Subjects: ${state.subjectsBySection.values.flatten().size}", style = MaterialTheme.typography.bodySmall, color = TextSecondary)
                    Text("Cycles: ${state.cycles.size}", style = MaterialTheme.typography.bodySmall, color = TextSecondary)
                    Text("Assessments: ${state.assessmentsBySection.values.flatten().size}", style = MaterialTheme.typography.bodySmall, color = TextSecondary)
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
