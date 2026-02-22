package com.schoolapp.ui.admin.dashboard

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.schoolapp.core.model.ScoreStatus
import com.schoolapp.core.ui.components.DropdownOption
import com.schoolapp.core.ui.components.EmptyStateCard
import com.schoolapp.core.ui.components.FormDropdownField
import com.schoolapp.core.ui.components.GlassCard
import com.schoolapp.core.ui.components.Sparkline
import com.schoolapp.core.ui.theme.DangerRed
import com.schoolapp.core.ui.theme.SuccessGreen
import com.schoolapp.core.ui.theme.SurfaceBackground
import com.schoolapp.core.ui.theme.TextPrimary
import com.schoolapp.core.ui.theme.TextSecondary
import com.schoolapp.core.ui.theme.WarningAmber
import com.schoolapp.ui.admin.AdminDashboardState
import com.schoolapp.ui.admin.AdminViewModel
import com.schoolapp.ui.admin.DashboardStudentRow

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AdminDashboardScreen(
    state: AdminDashboardState,
    viewModel: AdminViewModel,
    modifier: Modifier = Modifier,
) {
    if (state.loading) {
        Column(
            modifier = modifier.fillMaxSize(),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("Loading dashboard...", style = MaterialTheme.typography.titleMedium, color = TextSecondary)
        }
        return
    }

    if (state.selectedStudentProfileId != null) {
        StudentProfilePopup(state = state, onDismiss = viewModel::closeStudentProfile)
    }

    val gradeOptions = state.sections
        .map { it.grade }
        .distinct()
        .sorted()
        .map { DropdownOption(it.toString(), "Grade $it") }
    val sectionOptions = state.sections
        .filter { it.grade == state.context.grade }
        .sortedWith(compareBy({ it.grade }, { it.sectionName }))
        .map { DropdownOption(it.sectionId, "${it.sectionName} (Grade ${it.grade})") }
    val examOptions = state.context.sectionId
        ?.let { sectionId -> state.assessmentsBySection[sectionId].orEmpty() }
        .orEmpty()
        .sortedBy { it.title }
        .map { DropdownOption(it.assessmentId, it.title) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(SurfaceBackground)
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        GlassCard(Modifier.fillMaxWidth()) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Dashboard", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                Text(
                    "Select class, section, and exam then press search.",
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
                    label = "Exam",
                    options = examOptions,
                    selectedId = state.context.assessmentId,
                    onSelected = viewModel::selectContextAssessment,
                    enabled = examOptions.isNotEmpty(),
                )
                Button(
                    onClick = viewModel::runDashboardSearch,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = state.context.grade != null && state.context.sectionId != null && state.context.assessmentId != null,
                ) {
                    Text("Search")
                }
            }
        }

        GlassCard(Modifier.fillMaxWidth()) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Quick Summary", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Text("Class average: ${"%.1f".format(state.sectionAverageScorePct)}%", style = MaterialTheme.typography.bodySmall, color = TextSecondary)
                Text("Pass percentage: ${"%.1f".format(state.sectionPassPct)}%", style = MaterialTheme.typography.bodySmall, color = TextSecondary)
                val topper = state.sectionLeaderboard.firstOrNull { it.studentId == state.sectionTopperStudentId }?.fullName ?: "-"
                Text("Topper: $topper", style = MaterialTheme.typography.bodySmall, color = TextSecondary)
            }
        }

        when {
            !state.dashboardSearchDone -> {
                EmptyStateCard(
                    title = "No results loaded",
                    description = "Press Search to load student results for selected class/section/exam.",
                )
            }

            state.dashboardRows.isEmpty() -> {
                EmptyStateCard(
                    title = "No students or scores found",
                    description = "Add students and fill marks from the Mass Fill page.",
                )
            }

            else -> {
                Text(
                    "Students Overview (single glance)",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Text("Tap a card to open detailed profile", style = MaterialTheme.typography.bodySmall, color = TextSecondary)
                state.dashboardRows.forEach { row ->
                    StudentOverviewCard(row = row, onClick = { viewModel.openStudentProfile(row.studentId) })
                }
            }
        }

        state.error?.let { message ->
            Text(message, style = MaterialTheme.typography.bodySmall, color = DangerRed)
        }
        state.info?.let { message ->
            Text(message, style = MaterialTheme.typography.bodySmall, color = SuccessGreen)
        }
    }
}

@Composable
private fun StudentOverviewCard(
    row: DashboardStudentRow,
    onClick: () -> Unit,
) {
    GlassCard(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "${row.rollNumber}. ${row.studentName}",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = row.rank?.let { "Rank #$it" } ?: "Rank -",
                    style = MaterialTheme.typography.labelMedium,
                    color = TextSecondary,
                )
            }
            Text(
                text = "Status: ${row.passStatus}",
                style = MaterialTheme.typography.labelMedium,
                color = passStatusColor(row.passStatus),
            )
            Text(
                text = "Overall Average: ${row.averagePct?.let { "%.1f".format(it) + "%" } ?: "-"}",
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary,
            )
            Text(
                text = "${row.examName} (${row.subjectName}): ${row.marks?.let { "%.1f".format(it) } ?: "-"}  |  ${row.percentage?.let { "%.1f".format(it) + "%" } ?: "-"}",
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary,
            )
            HorizontalDivider()
            Text("Subject Marks", style = MaterialTheme.typography.labelLarge, color = TextSecondary)
            row.subjectMarks.forEach { subject ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(subject.subjectName, style = MaterialTheme.typography.bodySmall, color = TextPrimary)
                    Text(
                        text = "${subject.obtained?.let { "%.1f".format(it) } ?: "-"}/${"%.0f".format(subject.max)}  ${subject.percentage?.let { "(" + "%.1f".format(it) + "%)" } ?: ""}",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary,
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun StudentProfilePopup(
    state: AdminDashboardState,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        if (state.profileLoading || state.profileStudent == null) {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Loading student profile...", style = MaterialTheme.typography.titleMedium)
            }
            return@ModalBottomSheet
        }

        val student = state.profileStudent
        val latest = state.profileTrend.lastOrNull()
        val progressPoints = state.profileTrend.map { (it.overallPercentage ?: 0.0).toFloat() }.ifEmpty { listOf(0f) }
        val grouped = state.profileAssessments.groupBy { it.subjectName }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(student.fullName, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
            Text(
                "Roll ${student.rollNumber} • Grade ${state.profileSection?.grade ?: "-"}-${state.profileSection?.sectionName ?: "-"}",
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                Text(
                    "Overall: ${latest?.overallPercentage?.let { "%.1f".format(it) + "%" } ?: "-"}",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary,
                )
                Text(
                    "Status: ${latest?.passStatus?.name ?: "INCOMPLETE"}",
                    style = MaterialTheme.typography.bodySmall,
                    color = passStatusColor(latest?.passStatus?.name ?: "INCOMPLETE"),
                )
            }

            GlassCard(Modifier.fillMaxWidth()) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Progress", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    Sparkline(
                        points = progressPoints,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(100.dp),
                    )
                    state.profileTrend.forEach { snapshot ->
                        Text(
                            "${snapshot.cycleId}: ${snapshot.overallPercentage?.let { "%.1f".format(it) + "%" } ?: "-"}",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary,
                        )
                    }
                }
            }

            GlassCard(Modifier.fillMaxWidth()) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Subjects and Tests", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    if (grouped.isEmpty()) {
                        Text("No tests available.", style = MaterialTheme.typography.bodySmall, color = TextSecondary)
                    } else {
                        grouped.forEach { (subjectName, rows) ->
                            val percentages = rows.mapNotNull { row ->
                                val status = state.profileStatusByAssessment[row.assessment.assessmentId] ?: ScoreStatus.PRESENT
                                when (status) {
                                    ScoreStatus.PRESENT -> {
                                        val marks = state.profileScoreDrafts[row.assessment.assessmentId]?.toDoubleOrNull() ?: return@mapNotNull null
                                        (marks / row.assessment.maxMarks) * 100.0
                                    }
                                    ScoreStatus.ABSENT, ScoreStatus.EXEMPT -> 0.0
                                }
                            }
                            val avg = percentages.average().takeIf { !it.isNaN() }
                            Text(
                                "$subjectName - Avg ${avg?.let { "%.1f".format(it) + "%" } ?: "-"}",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold,
                            )
                            rows.forEach { row ->
                                val assessment = row.assessment
                                val status = state.profileStatusByAssessment[assessment.assessmentId] ?: ScoreStatus.PRESENT
                                val marksText = when (status) {
                                    ScoreStatus.PRESENT -> {
                                        val raw = state.profileScoreDrafts[assessment.assessmentId].orEmpty()
                                        if (raw.isBlank()) "-" else raw
                                    }
                                    ScoreStatus.ABSENT -> "AB"
                                    ScoreStatus.EXEMPT -> "EX"
                                }
                                val pctText = when (status) {
                                    ScoreStatus.PRESENT -> {
                                        val marks = state.profileScoreDrafts[assessment.assessmentId]?.toDoubleOrNull()
                                        marks?.let { "%.1f".format((it / assessment.maxMarks) * 100.0) + "%" } ?: "-"
                                    }
                                    ScoreStatus.ABSENT, ScoreStatus.EXEMPT -> "0.0%"
                                }
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(assessment.title, style = MaterialTheme.typography.bodySmall, color = TextPrimary)
                                    Text("$marksText/${"%.0f".format(assessment.maxMarks)}  ($pctText)", style = MaterialTheme.typography.bodySmall, color = TextSecondary)
                                }
                            }
                            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                        }
                    }
                }
            }
        }
    }
}

private fun passStatusColor(status: String): Color {
    return when (status.uppercase()) {
        "PASS" -> SuccessGreen
        "FAIL" -> DangerRed
        else -> WarningAmber
    }
}
