package com.schoolapp.ui.student

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.schoolapp.core.model.PassStatus
import com.schoolapp.core.ui.components.AppTopBar
import com.schoolapp.core.ui.components.GlassCard
import com.schoolapp.core.ui.components.KpiCard
import com.schoolapp.core.ui.components.PassStatusBadge
import com.schoolapp.core.ui.components.ProgressRing
import com.schoolapp.core.ui.components.RiskBadge
import com.schoolapp.core.ui.components.Sparkline
import com.schoolapp.core.ui.components.StatRow
import com.schoolapp.core.ui.theme.DangerRed
import com.schoolapp.core.ui.theme.PrimaryBlue
import com.schoolapp.core.ui.theme.SuccessGreen
import com.schoolapp.core.ui.theme.TextSecondary
import com.schoolapp.core.ui.theme.WarningAmber

@Composable
fun StudentDashboardScreen(
    viewModel: StudentViewModel,
    onLogout: () -> Unit,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            AppTopBar(title = "My Results") {
                IconButton(onClick = viewModel::refresh) {
                    Icon(Icons.Filled.Refresh, contentDescription = "Refresh", tint = TextSecondary)
                }
                IconButton(onClick = onLogout) {
                    Icon(Icons.AutoMirrored.Filled.Logout, contentDescription = "Logout", tint = TextSecondary)
                }
            }
        },
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = state.selectedTab == 0,
                    onClick = { viewModel.selectTab(0) },
                    icon = { Icon(Icons.Filled.BarChart, null) },
                    label = { Text("Overview") },
                )
                NavigationBarItem(
                    selected = state.selectedTab == 1,
                    onClick = { viewModel.selectTab(1) },
                    icon = { Icon(Icons.AutoMirrored.Filled.MenuBook, null) },
                    label = { Text("Subjects") },
                )
            }
        },
    ) { padding ->
        AnimatedContent(
            targetState = state.selectedTab,
            transitionSpec = { fadeIn() togetherWith fadeOut() },
            label = "student-tab",
            modifier = Modifier.padding(padding),
        ) { tab ->
            when (tab) {
                0 -> OverviewTab(state, onLogout)
                1 -> SubjectsTab(state)
            }
        }
    }
}

@Composable
private fun OverviewTab(state: StudentDashboardState, onLogout: () -> Unit) {
    if (state.loading) {
        Column(Modifier.fillMaxSize(), Arrangement.Center, Alignment.CenterHorizontally) {
            Text("Loading results...", style = MaterialTheme.typography.titleMedium, color = TextSecondary)
        }
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("Welcome, ${state.studentName}", style = MaterialTheme.typography.headlineMedium)
                if (state.cycleName.isNotBlank()) {
                    Text(state.cycleName, style = MaterialTheme.typography.bodyMedium, color = TextSecondary)
                }
                if (state.resultsMayBeOutdated) {
                    Text(
                        "Couldn't check with the school server. These are the results saved on this " +
                            "device and may be out of date.",
                        style = MaterialTheme.typography.bodySmall,
                        color = WarningAmber,
                    )
                }
            }
        }

        // Performance card
        item {
            val ringColor = when (state.latest?.passStatus) {
                PassStatus.PASS -> SuccessGreen
                PassStatus.FAIL -> DangerRed
                else -> WarningAmber
            }
            GlassCard(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("Overall Performance", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        state.latest?.passStatus?.let { PassStatusBadge(it) }
                        state.latest?.riskLevel?.let { RiskBadge(it) }
                    }
                    ProgressRing(
                        percent = state.latest?.overallPercentage ?: 0.0,
                        ringColor = ringColor,
                    )
                }
            }
        }

        // Trend card
        item {
            GlassCard(modifier = Modifier.fillMaxWidth()) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Progress Trend", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Sparkline(
                        points = state.trend.map { (it.overallPercentage ?: 0.0).toFloat() }
                            .ifEmpty { listOf(40f, 45f, 52f) },
                        modifier = Modifier.fillMaxWidth().height(100.dp),
                    )
                    StatRow("Section average", "${"%.1f".format(state.sectionAverage)}%")
                    StatRow("Performance band", state.rankHint)
                }
            }
        }

        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                KpiCard(
                    title = "Section Avg",
                    value = "${"%.1f".format(state.sectionAverage)}%",
                    accent = PrimaryBlue,
                    modifier = Modifier.weight(1f),
                )
                KpiCard(
                    title = "Band",
                    value = when {
                        state.rankHint.startsWith("Top", ignoreCase = true) -> "Top"
                        state.rankHint.startsWith("Mid", ignoreCase = true) -> "Mid"
                        else -> "Improve"
                    },
                    accent = when {
                        state.rankHint.startsWith("Top", ignoreCase = true) -> SuccessGreen
                        state.rankHint.startsWith("Mid", ignoreCase = true) -> PrimaryBlue
                        else -> DangerRed
                    },
                    modifier = Modifier.weight(1f),
                )
            }
        }

        // Error / Info
        state.error?.let { message ->
            item { Text(message, color = DangerRed, style = MaterialTheme.typography.bodySmall) }
        }

        item { Spacer(Modifier.height(8.dp)) }
        item {
            OutlinedButton(onClick = onLogout, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp)) { Text("Logout") }
        }
    }
}

@Composable
private fun SubjectsTab(state: StudentDashboardState) {
    if (state.loading) {
        Column(Modifier.fillMaxSize(), Arrangement.Center, Alignment.CenterHorizontally) {
            Text("Loading...", style = MaterialTheme.typography.titleMedium, color = TextSecondary)
        }
        return
    }

    val subjectEntries = state.latest?.subjectPercentages?.entries?.toList().orEmpty()

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            Text("Subject Breakdown", style = MaterialTheme.typography.headlineMedium)
        }

        if (subjectEntries.isEmpty()) {
            item {
                GlassCard(Modifier.fillMaxWidth()) {
                    Text("No subject results available yet.", style = MaterialTheme.typography.bodyMedium, color = TextSecondary)
                }
            }
        } else {
            items(subjectEntries) { entry ->
                val subjectLabel = state.subjectNames[entry.key] ?: entry.key
                val pct = entry.value
                val valueColor = when {
                    pct >= 75 -> SuccessGreen
                    pct >= 33 -> PrimaryBlue
                    else -> DangerRed
                }
                GlassCard(modifier = Modifier.fillMaxWidth()) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(subjectLabel, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                            Text("${"%.1f".format(pct)}%", style = MaterialTheme.typography.titleMedium, color = valueColor, fontWeight = FontWeight.SemiBold)
                        }
                        val status = when {
                            pct >= 75 -> "Excellent"
                            pct >= 60 -> "Good"
                            pct >= 33 -> "Average"
                            else -> "Needs Improvement"
                        }
                        Text(status, style = MaterialTheme.typography.labelSmall, color = TextSecondary)
                    }
                }
            }
        }
    }
}
