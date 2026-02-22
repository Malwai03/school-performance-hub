package com.schoolapp.ui.admin.profile

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.Composable
import com.schoolapp.core.ui.components.GlassCard
import com.schoolapp.core.ui.theme.SurfaceBackground
import com.schoolapp.core.ui.theme.TextSecondary
import com.schoolapp.ui.admin.AdminDashboardState

@Composable
fun AdminProfileScreen(
    state: AdminDashboardState,
    onLogout: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(SurfaceBackground)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        GlassCard(Modifier.fillMaxWidth()) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Admin Profile", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                Text("Role: Administrator", style = MaterialTheme.typography.bodyMedium)
                Text("Total students: ${state.totalStudents}", style = MaterialTheme.typography.bodySmall, color = TextSecondary)
                Text("Pass rate: ${"%.1f".format(state.passRatePct)}%", style = MaterialTheme.typography.bodySmall, color = TextSecondary)
            }
        }

        GlassCard(Modifier.fillMaxWidth()) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Session", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Text("Use logout when you are done.", style = MaterialTheme.typography.bodySmall, color = TextSecondary)
                Button(onClick = onLogout, modifier = Modifier.fillMaxWidth()) {
                    Text("Logout")
                }
            }
        }
    }
}
