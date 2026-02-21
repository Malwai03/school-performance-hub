package com.schoolapp.core.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.schoolapp.core.model.AcademicContext
import com.schoolapp.core.model.Assessment
import com.schoolapp.core.model.AssessmentCycle
import com.schoolapp.core.model.GradeSection
import com.schoolapp.core.model.Subject
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
    showSubject: Boolean = true,
    showAssessment: Boolean = true,
    modifier: Modifier = Modifier,
) {
    val grades = sections.map { it.grade }.distinct().sorted()
    val sectionsForGrade = sections
        .filter { it.grade == context.grade }
        .sortedBy { it.sectionName }
    val subjects = context.sectionId?.let { subjectsBySection[it] }.orEmpty()
    val assessments = context.sectionId?.let { assessmentsBySection[it] }.orEmpty()
    val filteredAssessments = if (context.subjectId == null) assessments else assessments.filter { it.subjectId == context.subjectId }

    GlassCard(modifier = modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (grades.isNotEmpty()) {
                Text("Context", style = MaterialTheme.typography.titleSmall)
                TabRow(
                    selectedTabIndex = grades.indexOf(context.grade).coerceAtLeast(0),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    grades.forEach { grade ->
                        Tab(
                            selected = context.grade == grade,
                            onClick = { onGradeSelected(grade) },
                            text = { Text("Grade $grade") },
                        )
                    }
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                FormDropdownField(
                    label = "Section",
                    options = sectionsForGrade.map {
                        DropdownOption(it.sectionId, "${it.sectionName} (G${it.grade})")
                    },
                    selectedId = context.sectionId,
                    onSelected = onSectionSelected,
                    enabled = sectionsForGrade.isNotEmpty(),
                    modifier = Modifier.weight(1f),
                )
                FormDropdownField(
                    label = "Cycle",
                    options = cycles.map { DropdownOption(it.cycleId, it.name) },
                    selectedId = context.cycleId,
                    onSelected = onCycleSelected,
                    enabled = cycles.isNotEmpty(),
                    modifier = Modifier.weight(1f),
                )
            }

            if (showSubject) {
                FormDropdownField(
                    label = "Subject",
                    options = subjects.map { DropdownOption(it.subjectId, it.name) },
                    selectedId = context.subjectId,
                    onSelected = onSubjectSelected,
                    enabled = subjects.isNotEmpty(),
                )
            }

            if (showAssessment) {
                FormDropdownField(
                    label = "Assessment",
                    options = filteredAssessments.map {
                        DropdownOption(
                            it.assessmentId,
                            "${it.title} (Max ${"%.0f".format(it.maxMarks)})",
                        )
                    },
                    selectedId = context.assessmentId,
                    onSelected = onAssessmentSelected,
                    enabled = filteredAssessments.isNotEmpty(),
                )
            }

            if (context.sectionId == null) {
                Text(
                    text = "Create a section first in Setup.",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }
}
