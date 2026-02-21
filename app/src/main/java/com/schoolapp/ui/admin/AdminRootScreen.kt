package com.schoolapp.ui.admin

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.TableChart
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.schoolapp.core.model.AdminDestination
import com.schoolapp.core.ui.components.AppTopBar
import com.schoolapp.core.ui.theme.TextSecondary
import com.schoolapp.ui.admin.dashboard.AdminDashboardScreen
import com.schoolapp.ui.admin.gradebook.GradebookScreen
import com.schoolapp.ui.admin.roster.RosterScreen
import com.schoolapp.ui.admin.setup.SetupWizardScreen

@Composable
fun AdminRootScreen(
    viewModel: AdminViewModel,
    onLogout: () -> Unit,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            AppTopBar(
                title = when (state.destination) {
                    AdminDestination.DASHBOARD -> "Admin Dashboard"
                    AdminDestination.ROSTER -> "Roster"
                    AdminDestination.GRADEBOOK -> "Gradebook"
                    AdminDestination.SETUP -> "Setup"
                },
                actions = {
                    IconButton(onClick = onLogout) {
                        Icon(Icons.AutoMirrored.Filled.Logout, contentDescription = "Logout", tint = TextSecondary)
                    }
                },
            )
        },
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = state.destination == AdminDestination.DASHBOARD,
                    onClick = { viewModel.navigateTo(AdminDestination.DASHBOARD) },
                    icon = { Icon(Icons.Filled.Dashboard, contentDescription = null) },
                    label = { Text("Dashboard") },
                )
                NavigationBarItem(
                    selected = state.destination == AdminDestination.ROSTER,
                    onClick = { viewModel.navigateTo(AdminDestination.ROSTER) },
                    icon = { Icon(Icons.AutoMirrored.Filled.ViewList, contentDescription = null) },
                    label = { Text("Roster") },
                )
                NavigationBarItem(
                    selected = state.destination == AdminDestination.GRADEBOOK,
                    onClick = { viewModel.navigateTo(AdminDestination.GRADEBOOK) },
                    icon = { Icon(Icons.Filled.TableChart, contentDescription = null) },
                    label = { Text("Gradebook") },
                )
                NavigationBarItem(
                    selected = state.destination == AdminDestination.SETUP,
                    onClick = { viewModel.navigateTo(AdminDestination.SETUP) },
                    icon = { Icon(Icons.Filled.Settings, contentDescription = null) },
                    label = { Text("Setup") },
                )
            }
        },
    ) { padding ->
        when (state.destination) {
            AdminDestination.DASHBOARD -> AdminDashboardScreen(
                state = state,
                viewModel = viewModel,
                modifier = Modifier.padding(padding),
            )

            AdminDestination.ROSTER -> RosterScreen(
                state = state,
                viewModel = viewModel,
                modifier = Modifier.padding(padding),
            )

            AdminDestination.GRADEBOOK -> GradebookScreen(
                state = state,
                viewModel = viewModel,
                modifier = Modifier.padding(padding),
            )

            AdminDestination.SETUP -> SetupWizardScreen(
                state = state,
                viewModel = viewModel,
                modifier = Modifier.padding(padding),
            )
        }
    }
}
