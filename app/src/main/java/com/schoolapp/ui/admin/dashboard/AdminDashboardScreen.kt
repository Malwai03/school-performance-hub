package com.schoolapp.ui.admin.dashboard

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.schoolapp.core.model.AdminDestination
import com.schoolapp.core.model.RiskLevel
import com.schoolapp.core.ui.components.DropdownOption
import com.schoolapp.core.ui.components.EmptyStateCard
import com.schoolapp.core.ui.components.FormDropdownField
import com.schoolapp.core.ui.components.GlassCard
import com.schoolapp.core.ui.components.KpiCard
import com.schoolapp.core.ui.components.Sparkline
import com.schoolapp.core.ui.theme.DangerRed
import com.schoolapp.core.ui.theme.Primary
import com.schoolapp.core.ui.theme.SuccessGreen
import com.schoolapp.core.ui.theme.TextSecondary
import com.schoolapp.ui.admin.AdminDashboardState
import com.schoolapp.ui.admin.AdminViewModel

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

    val cycleOptions = state.cycles.map { DropdownOption(it.cycleId, it.name) }
    val draftCycles = state.cycles.count { !it.published }
    val atRiskSections = state.sectionSummaries.values.count { (it.riskDistribution[RiskLevel.HIGH] ?: 0) > 0 }
    val setupPending = listOf(
        state.sections.isEmpty(),
        state.subjectsBySection.values.flatten().isEmpty(),
        state.cycles.isEmpty(),
        state.studentsBySection.values.flatten().isEmpty(),
    ).count { it }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            Text("Command Center", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
        }

        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                KpiCard("Students", state.totalStudents.toString(), Primary, Modifier.weight(1f))
                KpiCard("Pass Rate", "${"%.1f".format(state.passRatePct)}%", SuccessGreen, Modifier.weight(1f))
                KpiCard("High Risk", state.highRiskCount.toString(), DangerRed, Modifier.weight(1f))
            }
        }

        item {
            GlassCard(Modifier.fillMaxWidth()) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    FormDropdownField(
                        label = "Assessment Cycle",
                        options = cycleOptions,
                        selectedId = state.context.cycleId,
                        onSelected = viewModel::selectContextCycle,
                        enabled = cycleOptions.isNotEmpty(),
                    )
                    Sparkline(
                        points = state.sparkline.ifEmpty { listOf(52f, 58f, 61f, 66f, 71f) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(110.dp),
                    )
                }
            }
        }

        item {
            GlassCard(Modifier.fillMaxWidth()) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Action Queue", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    Text("Pending setup steps: $setupPending", style = MaterialTheme.typography.bodySmall, color = TextSecondary)
                    Text("Draft result cycles: $draftCycles", style = MaterialTheme.typography.bodySmall, color = TextSecondary)
                    Text("Sections needing intervention: $atRiskSections", style = MaterialTheme.typography.bodySmall, color = TextSecondary)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        Button(
                            onClick = { viewModel.navigateTo(AdminDestination.ROSTER) },
                            modifier = Modifier.weight(1f),
                        ) {
                            Text("Open Roster")
                        }
                        Button(
                            onClick = { viewModel.navigateTo(AdminDestination.GRADEBOOK) },
                            modifier = Modifier.weight(1f),
                        ) {
                            Text("Open Gradebook")
                        }
                    }
                    OutlinedButton(
                        onClick = { viewModel.navigateTo(AdminDestination.SETUP) },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Open Setup Wizard")
                    }
                }
            }
        }

        if (state.sections.isEmpty()) {
            item {
                EmptyStateCard(
                    title = "No academic structure yet",
                    description = "Start with Setup to create sections, subjects, cycles and assessments.",
                )
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
