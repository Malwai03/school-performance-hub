package com.schoolapp.ui.admin.profile

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.schoolapp.core.model.PassStatus
import com.schoolapp.core.model.ScoreStatus
import com.schoolapp.core.model.StudentProfileTab
import com.schoolapp.core.ui.components.DropdownOption
import com.schoolapp.core.ui.components.FormDropdownField
import com.schoolapp.core.ui.components.GlassCard
import com.schoolapp.core.ui.components.PassStatusBadge
import com.schoolapp.core.ui.components.ProgressRing
import com.schoolapp.core.ui.components.RiskBadge
import com.schoolapp.core.ui.components.Sparkline
import com.schoolapp.core.ui.components.StatRow
import com.schoolapp.core.ui.theme.DangerRed
import com.schoolapp.core.ui.theme.Primary
import com.schoolapp.core.ui.theme.SuccessGreen
import com.schoolapp.core.ui.theme.TextSecondary
import com.schoolapp.core.ui.theme.WarningAmber
import com.schoolapp.ui.admin.AdminDashboardState
import com.schoolapp.ui.admin.AdminViewModel

@Composable
fun StudentProfileScreen(
    state: AdminDashboardState,
    viewModel: AdminViewModel,
    modifier: Modifier = Modifier,
) {
    val student = state.profileStudent
    val latest = state.profileTrend.lastOrNull()
    val cycleOptions = state.profileCycles.map { DropdownOption(it.cycleId, it.name) }

    if (state.profileLoading || student == null) {
        Column(
            modifier = modifier
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            OutlinedButton(onClick = viewModel::closeStudentProfile) { Text("Back to Roster") }
            GlassCard(Modifier.fillMaxWidth()) {
                Text("Loading student profile...", style = MaterialTheme.typography.bodyMedium, color = TextSecondary)
            }
        }
        return
    }

    val ringColor = when (latest?.passStatus) {
        PassStatus.PASS -> SuccessGreen
        PassStatus.FAIL -> DangerRed
        else -> WarningAmber
    }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            OutlinedButton(onClick = viewModel::closeStudentProfile) { Text("Back to Roster") }
        }

        item {
            GlassCard(Modifier.fillMaxWidth()) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(student.fullName, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                    Text(
                        "Roll #${student.rollNumber} - Grade ${state.profileSection?.grade ?: "-"} ${state.profileSection?.sectionName ?: "-"}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextSecondary,
                    )
                }
            }
        }

        item {
            FormDropdownField(
                label = "Cycle",
                options = cycleOptions,
                selectedId = state.profileSelectedCycleId,
                onSelected = viewModel::selectProfileCycle,
                enabled = cycleOptions.isNotEmpty(),
            )
        }

        item {
            TabRow(selectedTabIndex = state.profileSelectedTab.ordinal) {
                StudentProfileTab.values().forEach { tab ->
                    Tab(
                        selected = state.profileSelectedTab == tab,
                        onClick = { viewModel.selectStudentProfileTab(tab) },
                        text = {
                            Text(
                                when (tab) {
                                    StudentProfileTab.OVERVIEW -> "Overview"
                                    StudentProfileTab.SUBJECTS -> "Subjects"
                                    StudentProfileTab.MARKS_ENTRY -> "Marks Entry"
                                    StudentProfileTab.TRENDS -> "Trends"
                                },
                            )
                        },
                    )
                }
            }
        }

        when (state.profileSelectedTab) {
            StudentProfileTab.OVERVIEW -> {
                item {
                    GlassCard(Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text("Latest Performance", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                                latest?.passStatus?.let { PassStatusBadge(it) }
                                latest?.riskLevel?.let { RiskBadge(it) }
                                StatRow("Section average", "${"%.1f".format(state.profileSectionAverage)}%")
                            }
                            ProgressRing(percent = latest?.overallPercentage ?: 0.0, ringColor = ringColor)
                        }
                    }
                }
            }

            StudentProfileTab.SUBJECTS -> {
                val subjects = latest?.subjectPercentages?.entries?.toList().orEmpty()
                if (subjects.isEmpty()) {
                    item {
                        GlassCard(Modifier.fillMaxWidth()) {
                            Text("No subject analytics available for selected cycle.", color = TextSecondary)
                        }
                    }
                } else {
                    items(subjects) { entry ->
                        GlassCard(Modifier.fillMaxWidth()) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Text(entry.key, style = MaterialTheme.typography.bodyMedium)
                                Text(
                                    "${"%.1f".format(entry.value)}%",
                                    style = MaterialTheme.typography.titleMedium,
                                    color = if (entry.value >= 33) SuccessGreen else DangerRed,
                                )
                            }
                        }
                    }
                }
            }

            StudentProfileTab.MARKS_ENTRY -> {
                val grouped = state.profileAssessments.groupBy { it.subjectName }
                if (grouped.isEmpty()) {
                    item {
                        GlassCard(Modifier.fillMaxWidth()) {
                            Text("No assessments available for selected cycle.", color = TextSecondary)
                        }
                    }
                } else {
                    grouped.forEach { (subject, rows) ->
                        item {
                            GlassCard(Modifier.fillMaxWidth()) {
                                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text(subject, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                                    rows.forEach { row ->
                                        val assessment = row.assessment
                                        val status = state.profileStatusByAssessment[assessment.assessmentId] ?: ScoreStatus.PRESENT
                                        val statusLabel = when (status) {
                                            ScoreStatus.PRESENT -> "Present"
                                            ScoreStatus.ABSENT -> "Absent"
                                            ScoreStatus.EXEMPT -> "Exempt"
                                        }
                                        val statusColor = when (status) {
                                            ScoreStatus.PRESENT -> SuccessGreen
                                            ScoreStatus.ABSENT -> DangerRed
                                            ScoreStatus.EXEMPT -> WarningAmber
                                        }
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                        ) {
                                            Text(
                                                text = assessment.title,
                                                modifier = Modifier.weight(1f),
                                                style = MaterialTheme.typography.bodySmall,
                                            )
                                            AssistChip(
                                                onClick = { viewModel.toggleProfileStatus(assessment.assessmentId) },
                                                label = { Text(statusLabel, style = MaterialTheme.typography.labelSmall) },
                                                colors = AssistChipDefaults.assistChipColors(
                                                    containerColor = statusColor.copy(alpha = 0.16f),
                                                    labelColor = statusColor,
                                                ),
                                            )
                                            OutlinedTextField(
                                                value = if (status == ScoreStatus.PRESENT) {
                                                    state.profileScoreDrafts[assessment.assessmentId].orEmpty()
                                                } else {
                                                    ""
                                                },
                                                onValueChange = { value ->
                                                    if (status == ScoreStatus.PRESENT) {
                                                        viewModel.onProfileScoreChange(assessment.assessmentId, value)
                                                    }
                                                },
                                                enabled = status == ScoreStatus.PRESENT,
                                                singleLine = true,
                                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                                label = { Text("/${"%.0f".format(assessment.maxMarks)}") },
                                                modifier = Modifier.fillMaxWidth(0.35f),
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                    item {
                        Button(
                            onClick = viewModel::saveProfileScores,
                            enabled = !state.profileSaving,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(if (state.profileSaving) "Saving..." else "Save Draft")
                        }
                    }
                }
            }

            StudentProfileTab.TRENDS -> {
                item {
                    GlassCard(Modifier.fillMaxWidth()) {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("Cycle Trends", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                            Sparkline(
                                points = state.profileTrend.map { (it.overallPercentage ?: 0.0).toFloat() }.ifEmpty { listOf(45f, 50f, 56f) },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(120.dp),
                            )
                            state.profileTrend.forEach { snapshot ->
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                    Text(snapshot.cycleId, style = MaterialTheme.typography.bodySmall)
                                    Text(
                                        "${"%.1f".format(snapshot.overallPercentage ?: 0.0)}%",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = Primary,
                                    )
                                }
                            }
                        }
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
