package com.schoolapp.ui.admin.setup

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.schoolapp.core.ui.components.CalendarDateField
import com.schoolapp.core.ui.components.ContextSelectorBar
import com.schoolapp.core.ui.components.DropdownOption
import com.schoolapp.core.ui.components.FormDropdownField
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
fun SetupWizardScreen(
    state: AdminDashboardState,
    viewModel: AdminViewModel,
    modifier: Modifier = Modifier,
) {
    var activeStep by rememberSaveable { mutableIntStateOf(1) }

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

    val sectionsDone = state.sections.isNotEmpty()
    val subjectsDone = state.subjectsBySection.values.flatten().isNotEmpty()
    val cycleDone = state.cycles.isNotEmpty()
    val importDone = selectedImportName.isNotBlank()
    val customWeightSum = buildList {
        add(customW1.toDoubleOrNull() ?: 0.0)
        if ((semCount.toIntOrNull() ?: 1) >= 2) add(customW2.toDoubleOrNull() ?: 0.0)
        if ((semCount.toIntOrNull() ?: 1) >= 3) add(customW3.toDoubleOrNull() ?: 0.0)
    }.sum()

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .background(SurfaceBackground)
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
            Text("System Configuration", style = MaterialTheme.typography.headlineSmall, color = TextPrimary)
            Text("Complete each step. Finished steps collapse into one-line summaries.", color = TextSecondary, style = MaterialTheme.typography.bodySmall)
        }

        item {
            CollapsibleStepCard(
                stepNumber = 1,
                title = "Create Sections",
                isCompleted = sectionsDone,
                isExpanded = activeStep == 1,
                summaryText = "Sections: ${state.sections.size} configured",
                onEditClick = { activeStep = 1 },
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    DataEntryField(
                        label = "Grade",
                        placeholder = "1-10",
                        value = sectionGrade,
                        onValueChange = { sectionGrade = it.filter { c -> c.isDigit() }.take(2) },
                        keyboardType = KeyboardType.Number,
                        modifier = Modifier.weight(1f),
                    )
                    DataEntryField(
                        label = "Section",
                        placeholder = "A/B/C",
                        value = sectionName,
                        onValueChange = { sectionName = it.take(3) },
                        modifier = Modifier.weight(1f),
                    )
                }
                Button(
                    onClick = {
                        viewModel.createSection(sectionGrade.toIntOrNull() ?: 0, sectionName)
                        activeStep = 2
                    },
                    enabled = (sectionGrade.toIntOrNull() ?: 0) in 1..10 && sectionName.isNotBlank(),
                    colors = ButtonDefaults.buttonColors(containerColor = PrimaryBlue),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp),
                ) {
                    Text("Save & Continue", fontWeight = FontWeight.Bold)
                }
            }
        }

        item {
            CollapsibleStepCard(
                stepNumber = 2,
                title = "Subjects",
                isCompleted = subjectsDone,
                isExpanded = activeStep == 2,
                summaryText = "Subjects: ${state.subjectsBySection.values.flatten().size} created",
                onEditClick = { activeStep = 2 },
            ) {
                DataEntryField(
                    label = "Subject Name",
                    placeholder = "Mathematics",
                    value = subjectName,
                    onValueChange = { subjectName = it },
                )
                DataEntryField(
                    label = "Pass Threshold %",
                    placeholder = "33",
                    value = subjectPass,
                    onValueChange = { subjectPass = it.filter { c -> c.isDigit() || c == '.' } },
                    keyboardType = KeyboardType.Number,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Mandatory Subject", style = MaterialTheme.typography.bodyMedium)
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
                        activeStep = 3
                    },
                    enabled = state.context.sectionId != null && subjectName.isNotBlank(),
                    colors = ButtonDefaults.buttonColors(containerColor = PrimaryBlue),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp),
                ) {
                    Text("Save & Continue", fontWeight = FontWeight.Bold)
                }
            }
        }

        item {
            CollapsibleStepCard(
                stepNumber = 3,
                title = "Cycle & Timeline",
                isCompleted = cycleDone,
                isExpanded = activeStep == 3,
                summaryText = "Cycles: ${state.cycles.size} available",
                onEditClick = { activeStep = 3 },
            ) {
                DataEntryField(
                    label = "Cycle Name",
                    placeholder = "Term 1",
                    value = cycleName,
                    onValueChange = { cycleName = it },
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
                DataEntryField(
                    label = "Semester 1 Max Marks",
                    placeholder = "100",
                    value = sem1,
                    onValueChange = { sem1 = it.filter { c -> c.isDigit() || c == '.' } },
                    keyboardType = KeyboardType.Number,
                )
                if ((semCount.toIntOrNull() ?: 1) >= 2) {
                    DataEntryField(
                        label = "Semester 2 Max Marks",
                        placeholder = "100",
                        value = sem2,
                        onValueChange = { sem2 = it.filter { c -> c.isDigit() || c == '.' } },
                        keyboardType = KeyboardType.Number,
                    )
                }
                if ((semCount.toIntOrNull() ?: 1) >= 3) {
                    DataEntryField(
                        label = "Semester 3 Max Marks",
                        placeholder = "100",
                        value = sem3,
                        onValueChange = { sem3 = it.filter { c -> c.isDigit() || c == '.' } },
                        keyboardType = KeyboardType.Number,
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
                    DataEntryField(
                        label = "Semester 1 Weight %",
                        placeholder = "34",
                        value = customW1,
                        onValueChange = { customW1 = it.filter { c -> c.isDigit() || c == '.' } },
                        keyboardType = KeyboardType.Number,
                    )
                    if ((semCount.toIntOrNull() ?: 1) >= 2) {
                        DataEntryField(
                            label = "Semester 2 Weight %",
                            placeholder = "33",
                            value = customW2,
                            onValueChange = { customW2 = it.filter { c -> c.isDigit() || c == '.' } },
                            keyboardType = KeyboardType.Number,
                        )
                    }
                    if ((semCount.toIntOrNull() ?: 1) >= 3) {
                        DataEntryField(
                            label = "Semester 3 Weight %",
                            placeholder = "33",
                            value = customW3,
                            onValueChange = { customW3 = it.filter { c -> c.isDigit() || c == '.' } },
                            keyboardType = KeyboardType.Number,
                        )
                    }
                    Text(
                        text = "Custom weight total: ${"%.1f".format(customWeightSum)}%",
                        color = if (customWeightSum in 99.9..100.1) SuccessGreen else DangerRed,
                        style = MaterialTheme.typography.bodySmall,
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
                            buildList {
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
                        activeStep = 4
                    },
                    enabled = state.context.sectionId != null &&
                        cycleName.isNotBlank() &&
                        cycleStart.isNotBlank() &&
                        cycleEnd.isNotBlank() &&
                        (weightMode != "custom" || customWeightSum in 99.9..100.1),
                    colors = ButtonDefaults.buttonColors(containerColor = PrimaryBlue),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp),
                ) {
                    Text("Save & Continue", fontWeight = FontWeight.Bold)
                }
            }
        }

        item {
            CollapsibleStepCard(
                stepNumber = 4,
                title = "Import Center",
                isCompleted = importDone,
                isExpanded = activeStep == 4,
                summaryText = if (importDone) "Last file: $selectedImportName" else "No import selected",
                onEditClick = { activeStep = 4 },
            ) {
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
                    colors = ButtonDefaults.buttonColors(containerColor = PrimaryBlue),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp),
                ) {
                    Text("Start Import", fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
private fun CollapsibleStepCard(
    stepNumber: Int,
    title: String,
    isCompleted: Boolean,
    isExpanded: Boolean,
    summaryText: String,
    onEditClick: () -> Unit,
    content: @Composable () -> Unit,
) {
    val borderColor = when {
        isExpanded -> PrimaryBlue
        isCompleted -> SuccessGreen
        else -> BorderGray
    }
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = SurfaceWhite,
        shadowElevation = if (isExpanded) 4.dp else 0.dp,
        border = androidx.compose.foundation.BorderStroke(1.dp, BorderGray),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(modifier = Modifier.fillMaxWidth()) {
            Box(
                modifier = Modifier
                    .width(4.dp)
                    .fillMaxSize()
                    .background(borderColor),
            )
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Box(
                            modifier = Modifier
                                .size(24.dp)
                                .background(
                                    color = if (isCompleted) SuccessGreen else if (isExpanded) PrimaryBlue else BorderGray,
                                    shape = RoundedCornerShape(12.dp),
                                ),
                            contentAlignment = Alignment.Center,
                        ) {
                            if (isCompleted) {
                                androidx.compose.material3.Icon(
                                    imageVector = Icons.Filled.Check,
                                    contentDescription = null,
                                    tint = Color.White,
                                    modifier = Modifier.size(16.dp),
                                )
                            } else {
                                Text(
                                    stepNumber.toString(),
                                    color = if (isExpanded) Color.White else TextSecondary,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                )
                            }
                        }
                        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    }
                    if (isCompleted && !isExpanded) {
                        Text(
                            "Edit",
                            color = TextSecondary,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.clickable(onClick = onEditClick),
                        )
                    }
                }

                if (isExpanded) {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(top = 14.dp)) {
                        content()
                    }
                } else if (summaryText.isNotBlank()) {
                    Text(
                        summaryText,
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary,
                        modifier = Modifier.padding(top = 8.dp, start = 34.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun DataEntryField(
    label: String,
    placeholder: String,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    keyboardType: KeyboardType = KeyboardType.Text,
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = TextSecondary)
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            placeholder = { Text(placeholder, fontSize = 14.sp, color = TextSecondary) },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        )
    }
}
