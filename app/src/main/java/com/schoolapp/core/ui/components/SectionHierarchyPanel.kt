package com.schoolapp.core.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AssistChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.schoolapp.core.model.GradeSection
import com.schoolapp.core.ui.theme.TextSecondary

@Composable
fun SectionHierarchyPanel(
    selectedGrade: Int?,
    selectedSectionId: String?,
    sections: List<GradeSection>,
    studentCountBySection: Map<String, Int>,
    onSectionSelected: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val sectionsForGrade = sections
        .filter { it.grade == selectedGrade }
        .sortedBy { it.sectionName }

    GlassCard(modifier = modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Section Hierarchy", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            if (sectionsForGrade.isEmpty()) {
                Text("No section available for selected class.", color = TextSecondary, style = MaterialTheme.typography.bodySmall)
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    sectionsForGrade.forEach { section ->
                        val count = studentCountBySection[section.sectionId] ?: 0
                        AssistChip(
                            onClick = { onSectionSelected(section.sectionId) },
                            label = {
                                Text(
                                    "${section.sectionName} ($count)",
                                    style = MaterialTheme.typography.labelMedium,
                                )
                            },
                            leadingIcon = {
                                if (section.sectionId == selectedSectionId) {
                                    Text("•", style = MaterialTheme.typography.labelLarge)
                                }
                            },
                        )
                    }
                }
            }
        }
    }
}
