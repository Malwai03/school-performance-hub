package com.schoolapp.ui.admin.roster

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.schoolapp.core.ui.components.DropdownOption
import com.schoolapp.core.ui.components.EmptyStateCard
import com.schoolapp.core.ui.components.FormDropdownField
import com.schoolapp.core.ui.components.GlassCard
import com.schoolapp.core.ui.theme.DangerRed
import com.schoolapp.core.ui.theme.SuccessGreen
import com.schoolapp.core.ui.theme.SurfaceBackground
import com.schoolapp.core.ui.theme.TextSecondary
import com.schoolapp.ui.admin.AdminDashboardState
import com.schoolapp.ui.admin.AdminViewModel

@Composable
fun RosterScreen(
    state: AdminDashboardState,
    viewModel: AdminViewModel,
    modifier: Modifier = Modifier,
) {
    val sectionOptions = state.sections
        .sortedWith(compareBy({ it.grade }, { it.sectionName }))
        .map { DropdownOption(it.sectionId, "Grade ${it.grade} - ${it.sectionName}") }

    var selectedSectionId by rememberSaveable { mutableStateOf("new") }
    var sectionGrade by rememberSaveable { mutableStateOf((state.context.grade ?: 6).toString()) }
    var sectionName by rememberSaveable { mutableStateOf("") }

    var subjectSectionId by rememberSaveable { mutableStateOf(state.context.sectionId.orEmpty()) }
    var selectedSubjectId by rememberSaveable { mutableStateOf("new") }
    var subjectName by rememberSaveable { mutableStateOf("") }
    var subjectPassThreshold by rememberSaveable { mutableStateOf("33") }
    var subjectMandatory by rememberSaveable { mutableStateOf(true) }

    var studentSectionId by rememberSaveable { mutableStateOf(state.context.sectionId.orEmpty()) }
    var selectedStudentId by rememberSaveable { mutableStateOf("new") }
    var studentName by rememberSaveable { mutableStateOf("") }
    var studentRoll by rememberSaveable { mutableStateOf("") }

    var promoteFromSectionId by rememberSaveable { mutableStateOf(state.context.sectionId.orEmpty()) }
    var promoteToSectionId by rememberSaveable { mutableStateOf("") }

    val subjectOptions = subjectSectionId.takeIf { it.isNotBlank() }
        ?.let { state.subjectsBySection[it].orEmpty() }
        .orEmpty()
        .sortedBy { it.name }
        .map { DropdownOption(it.subjectId, it.name) }
    val studentOptions = studentSectionId.takeIf { it.isNotBlank() }
        ?.let { state.studentsBySection[it].orEmpty() }
        .orEmpty()
        .sortedBy { it.rollNumber }
        .map { DropdownOption(it.studentId, "${it.rollNumber}. ${it.fullName}") }

    LaunchedEffect(state.context.sectionId, state.context.grade, state.sections) {
        if (selectedSectionId == "new") {
            sectionGrade = (state.context.grade ?: state.sections.firstOrNull()?.grade ?: 6).toString()
            sectionName = ""
        }
        if (subjectSectionId.isBlank()) {
            subjectSectionId = state.context.sectionId.orEmpty()
        }
        if (studentSectionId.isBlank()) {
            studentSectionId = state.context.sectionId.orEmpty()
        }
        if (promoteFromSectionId.isBlank()) {
            promoteFromSectionId = state.context.sectionId.orEmpty()
        }
    }

    LaunchedEffect(selectedSectionId, state.sections) {
        val selected = state.sections.firstOrNull { it.sectionId == selectedSectionId }
        if (selected != null) {
            sectionGrade = selected.grade.toString()
            sectionName = selected.sectionName
            if (subjectSectionId.isBlank()) subjectSectionId = selected.sectionId
            if (studentSectionId.isBlank()) studentSectionId = selected.sectionId
        }
    }

    LaunchedEffect(selectedSubjectId, subjectSectionId, state.subjectsBySection) {
        val selected = state.subjectsBySection[subjectSectionId].orEmpty().firstOrNull { it.subjectId == selectedSubjectId }
        if (selected != null) {
            subjectName = selected.name
            subjectPassThreshold = selected.passThresholdPct.toString()
            subjectMandatory = selected.mandatory
        } else if (selectedSubjectId == "new") {
            subjectName = ""
            subjectPassThreshold = "33"
            subjectMandatory = true
        }
    }

    LaunchedEffect(selectedStudentId, studentSectionId, state.studentsBySection) {
        val selected = state.studentsBySection.values.flatten().firstOrNull { it.studentId == selectedStudentId }
        if (selected != null) {
            studentName = selected.fullName
            studentRoll = selected.rollNumber.toString()
            studentSectionId = selected.sectionId
        } else if (selectedStudentId == "new") {
            studentName = ""
            studentRoll = ""
        }
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
                Text("Manage School Data", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                Text(
                    "Create/edit classes, sections, students, subjects, and promote students.",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary,
                )
                if (state.sections.isEmpty()) {
                    EmptyStateCard(
                        title = "No classes or sections",
                        description = "Create your first class/section below.",
                    )
                } else {
                    state.sections
                        .sortedWith(compareBy({ it.grade }, { it.sectionName }))
                        .forEach { section ->
                            val count = state.studentsBySection[section.sectionId].orEmpty().size
                            Text(
                                text = "Grade ${section.grade} - ${section.sectionName} ($count students)",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                }
            }
        }

        GlassCard(Modifier.fillMaxWidth()) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Classes and Sections", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                FormDropdownField(
                    label = "Select Existing",
                    options = listOf(DropdownOption("new", "Create New")) + sectionOptions,
                    selectedId = selectedSectionId,
                    onSelected = { selectedSectionId = it },
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    OutlinedTextField(
                        value = sectionGrade,
                        onValueChange = { sectionGrade = it.filter { c -> c.isDigit() }.take(2) },
                        label = { Text("Class (1-10)") },
                        modifier = Modifier.weight(1f),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                    )
                    OutlinedTextField(
                        value = sectionName,
                        onValueChange = { sectionName = it.take(3) },
                        label = { Text("Section") },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    Button(
                        onClick = {
                            val grade = sectionGrade.toIntOrNull() ?: 0
                            if (selectedSectionId == "new") {
                                viewModel.createSection(grade = grade, sectionName = sectionName)
                            } else {
                                viewModel.updateSection(sectionId = selectedSectionId, grade = grade, sectionName = sectionName)
                            }
                        },
                        modifier = Modifier.weight(1f),
                        enabled = (sectionGrade.toIntOrNull() ?: 0) in 1..10 && sectionName.isNotBlank(),
                    ) {
                        Text(if (selectedSectionId == "new") "Add" else "Edit")
                    }
                    Button(
                        onClick = { viewModel.deleteSection(selectedSectionId) },
                        modifier = Modifier.weight(1f),
                        enabled = selectedSectionId != "new",
                    ) {
                        Text("Delete")
                    }
                }
            }
        }

        GlassCard(Modifier.fillMaxWidth()) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Subjects", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                FormDropdownField(
                    label = "Section",
                    options = sectionOptions,
                    selectedId = subjectSectionId.ifBlank { null },
                    onSelected = { subjectSectionId = it },
                    enabled = sectionOptions.isNotEmpty(),
                )
                FormDropdownField(
                    label = "Select Existing Subject",
                    options = listOf(DropdownOption("new", "Create New")) + subjectOptions,
                    selectedId = selectedSubjectId,
                    onSelected = { selectedSubjectId = it },
                    enabled = subjectSectionId.isNotBlank(),
                )
                OutlinedTextField(
                    value = subjectName,
                    onValueChange = { subjectName = it },
                    label = { Text("Subject Name") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    OutlinedTextField(
                        value = subjectPassThreshold,
                        onValueChange = { subjectPassThreshold = it.filter { c -> c.isDigit() || c == '.' } },
                        label = { Text("Pass %") },
                        modifier = Modifier.weight(1f),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                    )
                    Row(
                        modifier = Modifier.weight(1f),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text("Mandatory", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 14.dp))
                        Switch(checked = subjectMandatory, onCheckedChange = { subjectMandatory = it })
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    Button(
                        onClick = {
                            if (selectedSubjectId == "new") {
                                viewModel.createSubject(
                                    sectionId = subjectSectionId,
                                    subjectName = subjectName,
                                    passThreshold = subjectPassThreshold.toDoubleOrNull() ?: 33.0,
                                    mandatory = subjectMandatory,
                                )
                            } else {
                                viewModel.updateSubject(
                                    subjectId = selectedSubjectId,
                                    sectionId = subjectSectionId,
                                    subjectName = subjectName,
                                    passThreshold = subjectPassThreshold.toDoubleOrNull() ?: 33.0,
                                    mandatory = subjectMandatory,
                                )
                            }
                        },
                        modifier = Modifier.weight(1f),
                        enabled = subjectSectionId.isNotBlank() && subjectName.isNotBlank(),
                    ) {
                        Text(if (selectedSubjectId == "new") "Add" else "Edit")
                    }
                    Button(
                        onClick = { viewModel.deleteSubject(selectedSubjectId) },
                        modifier = Modifier.weight(1f),
                        enabled = selectedSubjectId != "new",
                    ) {
                        Text("Delete")
                    }
                }
            }
        }

        GlassCard(Modifier.fillMaxWidth()) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Students", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                FormDropdownField(
                    label = "Section",
                    options = sectionOptions,
                    selectedId = studentSectionId.ifBlank { null },
                    onSelected = { studentSectionId = it },
                    enabled = sectionOptions.isNotEmpty(),
                )
                FormDropdownField(
                    label = "Select Existing Student",
                    options = listOf(DropdownOption("new", "Create New")) + studentOptions,
                    selectedId = selectedStudentId,
                    onSelected = { selectedStudentId = it },
                    enabled = studentSectionId.isNotBlank(),
                )
                OutlinedTextField(
                    value = studentName,
                    onValueChange = { studentName = it },
                    label = { Text("Student Name") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
                OutlinedTextField(
                    value = studentRoll,
                    onValueChange = { studentRoll = it.filter { c -> c.isDigit() } },
                    label = { Text("Roll Number") },
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    Button(
                        onClick = {
                            val roll = studentRoll.toIntOrNull() ?: 0
                            if (selectedStudentId == "new") {
                                viewModel.addStudent(
                                    sectionId = studentSectionId,
                                    fullName = studentName,
                                    rollNumber = roll,
                                )
                            } else {
                                viewModel.updateStudent(
                                    studentId = selectedStudentId,
                                    sectionId = studentSectionId,
                                    fullName = studentName,
                                    rollNumber = roll,
                                )
                            }
                        },
                        modifier = Modifier.weight(1f),
                        enabled = studentSectionId.isNotBlank() && studentName.isNotBlank() && (studentRoll.toIntOrNull() ?: 0) > 0,
                    ) {
                        Text(if (selectedStudentId == "new") "Add" else "Edit")
                    }
                    Button(
                        onClick = { viewModel.deleteStudent(selectedStudentId) },
                        modifier = Modifier.weight(1f),
                        enabled = selectedStudentId != "new",
                    ) {
                        Text("Delete")
                    }
                }
            }
        }

        GlassCard(Modifier.fillMaxWidth()) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Promote Students", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                FormDropdownField(
                    label = "From Section",
                    options = sectionOptions,
                    selectedId = promoteFromSectionId.ifBlank { null },
                    onSelected = { promoteFromSectionId = it },
                    enabled = sectionOptions.isNotEmpty(),
                )
                FormDropdownField(
                    label = "To Section",
                    options = sectionOptions,
                    selectedId = promoteToSectionId.ifBlank { null },
                    onSelected = { promoteToSectionId = it },
                    enabled = sectionOptions.isNotEmpty(),
                )
                Button(
                    onClick = { viewModel.promoteStudents(promoteFromSectionId, promoteToSectionId) },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = promoteFromSectionId.isNotBlank() && promoteToSectionId.isNotBlank() && promoteFromSectionId != promoteToSectionId,
                ) {
                    Text("Promote All Students")
                }
            }
        }

        HorizontalDivider()
        state.error?.let { message ->
            Text(message, style = MaterialTheme.typography.bodySmall, color = DangerRed)
        }
        state.info?.let { message ->
            Text(message, style = MaterialTheme.typography.bodySmall, color = SuccessGreen)
        }
    }
}
