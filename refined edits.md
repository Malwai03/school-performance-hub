School Performance Hub: Data-First Pro UI MigrationThis document contains the refactored Jetpack Compose code to transition the app from generic Material 3 components to the dense, professional "Data-First Pro" design system.AI Assistants (Copilot/Cursor/Codex): Please use the code blocks below to update the corresponding files in the Android project.1. Update Color PaletteFile: app/src/main/java/com/schoolapp/core/ui/theme/Color.ktpackage com.schoolapp.core.ui.theme

import androidx.compose.ui.graphics.Color

// Data-First Pro Palette
val PrimaryBlue = Color(0xFF0F4C81)
val AccentCyan = Color(0xFF06B6D4)
val SurfaceBackground = Color(0xFFF7FAFC) // Very light gray/slate for main screens
val SurfaceWhite = Color(0xFFFFFFFF)
val BorderGray = Color(0xFFE2E8F0)

val TextPrimary = Color(0xFF0F172A)
val TextSecondary = Color(0xFF475569)

val SuccessGreen = Color(0xFF16A34A)
val WarningYellow = Color(0xFFF59E0B)
val DangerRed = Color(0xFFDC2626)

// Translucent states for chips
val SuccessGreenBg = Color(0x1A16A34A)
val DangerRedBg = Color(0x1ADC2626)
val ExemptGrayBg = Color(0xFFE2E8F0)
2. Refactor Context Selector (Clean Light Theme)File: app/src/main/java/com/schoolapp/core/ui/components/ContextSelectorBar.ktAI Instruction: The previous blue header was too heavy for a light theme. Update it to use a white background, a subtle bottom border, and light gray pills with dark text to match a clean web-dashboard aesthetic.package com.schoolapp.core.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.FilterAlt
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.schoolapp.core.ui.theme.*

@Composable
fun ContextSelectorBar(
    grades: List<String>, selectedGrade: String?, onGradeSelected: (String) -> Unit,
    sections: List<String>, selectedSection: String?, onSectionSelected: (String) -> Unit,
    cycles: List<String>, selectedCycle: String?, onCycleSelected: (String) -> Unit,
    subjects: List<String>, selectedSubject: String?, onSubjectSelected: (String) -> Unit,
    assessments: List<String>, selectedAssessment: String?, onAssessmentSelected: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    // White background with a tight bottom border
    Column(modifier = modifier.fillMaxWidth().background(SurfaceWhite)) {
        LazyRow(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp), // Tighter vertical padding
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            item {
                Icon(Icons.Default.FilterAlt, contentDescription = "Filter", tint = PrimaryBlue, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(2.dp))
            }

            val pill: @Composable (String, String?) -> Unit = { label, value ->
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(SurfaceBackground) // Soft gray instead of heavy blue
                        .border(1.dp, BorderGray, RoundedCornerShape(8.dp))
                        .clickable { /* TODO: Dropdown */ }
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(label.uppercase(), color = TextSecondary, fontSize = 10.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(right = 4.dp))
                    Text(value ?: "Select", color = TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    Icon(Icons.Default.ArrowDropDown, contentDescription = null, tint = TextSecondary, modifier = Modifier.size(16.dp))
                }
            }

            item { pill("GR", selectedGrade) }
            item { DividerIcon() }
            item { pill("SEC", selectedSection) }
            item { DividerIcon() }
            item { pill("CYC", selectedCycle) }
            item { DividerIcon() }
            item { pill("SUB", selectedSubject) }
            item { DividerIcon() }
            item { pill("ASM", selectedAssessment) }
        }
        Divider(color = BorderGray, thickness = 1.dp)
    }
}

@Composable
private fun DividerIcon() {
    Icon(Icons.Default.ChevronRight, contentDescription = null, tint = BorderGray, modifier = Modifier.size(16.dp))
}
3. Refactor Gradebook Screen layout (Tighter Spacing)File: app/src/main/java/com/schoolapp/ui/admin/gradebook/GradebookScreen.ktAI Instruction: Ensure the bulk action bar seamlessly attaches to the ContextSelectorBar without weird gaps.package com.schoolapp.ui.admin.gradebook

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.schoolapp.core.ui.components.ContextSelectorBar
import com.schoolapp.core.ui.theme.*
import androidx.hilt.navigation.compose.hiltViewModel
import com.schoolapp.ui.admin.AdminViewModel

@Composable
fun GradebookScreen(
    viewModel: AdminViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()

    Column(modifier = Modifier.fillMaxSize().background(SurfaceBackground)) {
        // 1. Light Theme Context Bar
        ContextSelectorBar(
            grades = uiState.grades, selectedGrade = uiState.selectedGrade, onGradeSelected = { viewModel.selectGrade(it) },
            sections = uiState.sections, selectedSection = uiState.selectedSection, onSectionSelected = { viewModel.selectSection(it) },
            cycles = uiState.cycles, selectedCycle = uiState.selectedCycle, onCycleSelected = { viewModel.selectCycle(it) },
            subjects = uiState.subjects, selectedSubject = uiState.selectedSubject, onSubjectSelected = { viewModel.selectSubject(it) },
            assessments = uiState.assessments, selectedAssessment = uiState.selectedAssessment, onAssessmentSelected = { viewModel.selectAssessment(it) }
        )

        // 2. Tightly packed Bulk Action Bar
        Surface(color = SurfaceWhite, modifier = Modifier.fillMaxWidth(), shadowElevation = 1.dp) {
            Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = { /* TODO: Mark all present */ },
                            shape = RoundedCornerShape(8.dp),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = TextPrimary),
                            border = androidx.compose.foundation.BorderStroke(1.dp, BorderGray),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                            modifier = Modifier.height(32.dp)
                        ) { Text("All Present", fontSize = 12.sp, fontWeight = FontWeight.Bold) }
                    }
                    Box(modifier = Modifier.background(SurfaceBackground, RoundedCornerShape(6.dp)).border(1.dp, BorderGray, RoundedCornerShape(6.dp)).padding(horizontal = 8.dp, vertical = 4.dp)) {
                        Text("Max: 50", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
                    }
                }

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    var bulkMarks by remember { mutableStateOf("") }
                    OutlinedTextField(
                        value = bulkMarks, onValueChange = { bulkMarks = it },
                        placeholder = { Text("Apply marks...", fontSize = 13.sp, color = TextSecondary) },
                        modifier = Modifier.weight(1f).height(44.dp), // Tighter height
                        shape = RoundedCornerShape(8.dp),
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = SurfaceBackground, unfocusedContainerColor = SurfaceBackground,
                            focusedIndicatorColor = PrimaryBlue, unfocusedIndicatorColor = BorderGray
                        ),
                        singleLine = true
                    )
                    Button(onClick = { /* Apply */ }, shape = RoundedCornerShape(8.dp), colors = ButtonDefaults.buttonColors(containerColor = PrimaryBlue), modifier = Modifier.height(44.dp)) {
                        Text("Apply", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    }
                }
            }
        }
    }
}
5. Refactor Admin Setup (Cleaner Accordion)File: app/src/main/java/com/schoolapp/ui/admin/setup/SetupWizardScreen.ktAI Instruction: Fix the Setup Wizard card. It must stay SurfaceWhite to match the light theme. Reduce the massive spacing and paddings. Use a simple, clean left-border to indicate active status instead of background color shifts.package com.schoolapp.ui.admin.setup

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.schoolapp.core.ui.theme.*

@Composable
fun SetupWizardScreen() {
    var expandedStep by remember { mutableStateOf(2) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(SurfaceBackground)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp) // Reduced spacing
    ) {
        Column {
            Text("System Configuration", fontSize = 20.sp, fontWeight = FontWeight.ExtraBold, color = TextPrimary)
            Spacer(modifier = Modifier.height(2.dp))
            Text("Complete steps to initialize the term.", fontSize = 13.sp, color = TextSecondary)
        }
        Spacer(modifier = Modifier.height(4.dp))

        // STEP 1
        WizardStepCard(
            stepNumber = 1, title = "Create Sections", isCompleted = true, isExpanded = expandedStep == 1,
            summaryText = "Grade 10, Sections A, B created.", onEditClick = { expandedStep = 1 }
        ) { Text("Section entry form goes here...", color = TextSecondary, fontSize = 13.sp) }

        // STEP 2
        WizardStepCard(
            stepNumber = 2, title = "Cycle & Timeline", isCompleted = false, isExpanded = expandedStep == 2,
            summaryText = "", onEditClick = { expandedStep = 2 }
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) { // Tighter form spacing
                DataEntryField(label = "Cycle Name", placeholder = "e.g. Term 1")
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    DataEntryField(label = "Start Date", placeholder = "DD/MM/YYYY", modifier = Modifier.weight(1f))
                    DataEntryField(label = "End Date", placeholder = "DD/MM/YYYY", modifier = Modifier.weight(1f))
                }
                Button(
                    onClick = { expandedStep = 3 },
                    colors = ButtonDefaults.buttonColors(containerColor = PrimaryBlue),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth().height(40.dp).padding(top = 4.dp)
                ) { Text("Save & Continue", fontWeight = FontWeight.Bold, fontSize = 13.sp) }
            }
        }
        
        // STEP 3
        WizardStepCard(
            stepNumber = 3, title = "Import Roster", isCompleted = false, isExpanded = expandedStep == 3,
            summaryText = "", onEditClick = { expandedStep = 3 }
        ) { Text("CSV Import component goes here...", color = TextSecondary, fontSize = 13.sp) }
    }
}

@Composable
fun WizardStepCard(
    stepNumber: Int, title: String, isCompleted: Boolean, isExpanded: Boolean,
    summaryText: String, onEditClick: () -> Unit, content: @Composable () -> Unit
) {
    // Keep it white, just change the left accent bar
    val accentColor = if (isExpanded) PrimaryBlue else if (isCompleted) SuccessGreen else BorderGray

    Surface(
        shape = RoundedCornerShape(8.dp),
        color = SurfaceWhite, // Always white
        shadowElevation = if (isExpanded) 2.dp else 0.dp,
        border = androidx.compose.foundation.BorderStroke(1.dp, BorderGray),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(modifier = Modifier.fillMaxWidth()) {
            // Clean left accent strip
            Box(modifier = Modifier.width(4.dp).defaultMinSize(minHeight = 48.dp).background(accentColor))
            
            Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 12.dp)) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Box(modifier = Modifier.size(20.dp).background(if (isCompleted) SuccessGreen else if (isExpanded) PrimaryBlue else BorderGray, RoundedCornerShape(10.dp)), contentAlignment = Alignment.Center) {
                            if (isCompleted) Icon(Icons.Default.Check, contentDescription = null, tint = Color.White, modifier = Modifier.size(12.dp))
                            else Text(stepNumber.toString(), color = if (isExpanded) Color.White else TextSecondary, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                        }
                        Text(title, fontWeight = FontWeight.Bold, fontSize = 15.sp, color = TextPrimary)
                    }
                    if (isCompleted && !isExpanded) {
                        Text("Edit", color = PrimaryBlue, fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.clickable { onEditClick() })
                    }
                }

                if (isExpanded) {
                    Spacer(modifier = Modifier.height(12.dp))
                    content()
                } else if (isCompleted) {
                    Text(summaryText, fontSize = 13.sp, color = TextSecondary, modifier = Modifier.padding(top = 4.dp, start = 30.dp))
                }
            }
        }
    }
}

@Composable
fun DataEntryField(label: String, placeholder: String, modifier: Modifier = Modifier) {
    var text by remember { mutableStateOf("") }
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = TextSecondary)
        OutlinedTextField(
            value = text, onValueChange = { text = it }, placeholder = { Text(placeholder, fontSize = 13.sp, color = TextSecondary) },
            modifier = Modifier.fillMaxWidth().height(44.dp), // Tighter input heights
            shape = RoundedCornerShape(8.dp),
            colors = TextFieldDefaults.colors(
                focusedContainerColor = SurfaceBackground, unfocusedContainerColor = SurfaceBackground,
                focusedIndicatorColor = PrimaryBlue, unfocusedIndicatorColor = BorderGray
            ),
            singleLine = true
        )
    }
}
