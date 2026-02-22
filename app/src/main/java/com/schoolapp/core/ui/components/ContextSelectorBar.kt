package com.schoolapp.core.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.FilterAlt
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.schoolapp.core.model.AcademicContext
import com.schoolapp.core.model.Assessment
import com.schoolapp.core.model.AssessmentCycle
import com.schoolapp.core.model.GradeSection
import com.schoolapp.core.model.Subject
import com.schoolapp.core.ui.theme.BorderGray
import com.schoolapp.core.ui.theme.PrimaryBlue
import com.schoolapp.core.ui.theme.SurfaceBackground
import com.schoolapp.core.ui.theme.SurfaceWhite
import com.schoolapp.core.ui.theme.TextPrimary
import com.schoolapp.core.ui.theme.TextSecondary

@Composable
fun ContextSelectorBar(
    sections: List<GradeSection>,
    cycles: List<AssessmentCycle>,
    subjectsBySection: Map<String, List<Subject>>,
    assessmentsBySection: Map<String, List<Assessment>>,
    context: AcademicContext,
    onGradeSelected: (Int) -> Unit,
    onSectionSelected: (String) -> Unit,
    onCycleSelected: (String) -> Unit,
    onSubjectSelected: (String) -> Unit,
    onAssessmentSelected: (String) -> Unit,
    showCycle: Boolean = true,
    showSubject: Boolean = true,
    showAssessment: Boolean = true,
    modifier: Modifier = Modifier,
) {
    val grades = sections.map { it.grade }.distinct().sorted()
    val sectionsForGrade = sections.filter { it.grade == context.grade }.sortedBy { it.sectionName }
    val subjects = context.sectionId?.let { subjectsBySection[it] }.orEmpty()
    val assessments = context.sectionId?.let { assessmentsBySection[it] }.orEmpty()
    val filteredAssessments = if (context.subjectId == null) assessments else assessments.filter { it.subjectId == context.subjectId }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(SurfaceWhite),
    ) {
        LazyRow(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            item {
                Icon(
                    imageVector = Icons.Filled.FilterAlt,
                    contentDescription = "Filters",
                    tint = PrimaryBlue,
                    modifier = Modifier.size(16.dp),
                )
            }
            item {
                ContextPill(
                    shortLabel = "GR",
                    value = context.grade?.toString(),
                    options = grades.map { it.toString() to "Grade $it" },
                    onSelected = { selected -> selected.toIntOrNull()?.let(onGradeSelected) },
                )
            }
            item { DividerIcon() }
            item {
                ContextPill(
                    shortLabel = "SEC",
                    value = sectionsForGrade.firstOrNull { it.sectionId == context.sectionId }?.sectionName,
                    options = sectionsForGrade.map { it.sectionId to it.sectionName },
                    onSelected = onSectionSelected,
                )
            }
            if (showCycle) {
                item { DividerIcon() }
                item {
                    ContextPill(
                        shortLabel = "CYC",
                        value = cycles.firstOrNull { it.cycleId == context.cycleId }?.name,
                        options = cycles.map { it.cycleId to it.name },
                        onSelected = onCycleSelected,
                    )
                }
            }
            if (showSubject) {
                item { DividerIcon() }
                item {
                    ContextPill(
                        shortLabel = "SUB",
                        value = subjects.firstOrNull { it.subjectId == context.subjectId }?.name,
                        options = subjects.map { it.subjectId to it.name },
                        onSelected = onSubjectSelected,
                    )
                }
            }
            if (showAssessment) {
                item { DividerIcon() }
                item {
                    ContextPill(
                        shortLabel = "ASM",
                        value = filteredAssessments.firstOrNull { it.assessmentId == context.assessmentId }?.title,
                        options = filteredAssessments.map { it.assessmentId to it.title },
                        onSelected = onAssessmentSelected,
                    )
                }
            }
        }
        HorizontalDivider(color = BorderGray, thickness = 1.dp)
    }
}

@Composable
private fun ContextPill(
    shortLabel: String,
    value: String?,
    options: List<Pair<String, String>>,
    onSelected: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .background(SurfaceBackground)
                .border(1.dp, BorderGray, RoundedCornerShape(8.dp))
                .clickable(enabled = options.isNotEmpty()) { expanded = true }
                .padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = shortLabel,
                color = TextSecondary,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = " ${value ?: "Select"}",
                color = TextPrimary,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Icon(
                imageVector = Icons.Filled.ArrowDropDown,
                contentDescription = null,
                tint = TextSecondary,
                modifier = Modifier.size(16.dp),
            )
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            if (options.isEmpty()) {
                DropdownMenuItem(
                    text = { Text("No options") },
                    onClick = { expanded = false },
                )
            } else {
                options.forEach { (id, label) ->
                    DropdownMenuItem(
                        text = { Text(label) },
                        onClick = {
                            expanded = false
                            onSelected(id)
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun DividerIcon() {
    Icon(
        imageVector = Icons.Filled.ChevronRight,
        contentDescription = null,
        tint = BorderGray,
        modifier = Modifier.size(16.dp),
    )
}
