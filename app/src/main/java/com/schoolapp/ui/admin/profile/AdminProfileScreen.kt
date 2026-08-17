package com.schoolapp.ui.admin.profile

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import com.schoolapp.core.ui.components.GlassCard
import com.schoolapp.core.ui.theme.SurfaceBackground
import com.schoolapp.core.ui.theme.SuccessGreen
import com.schoolapp.core.ui.theme.TextSecondary
import com.schoolapp.core.ui.theme.DangerRed
import com.schoolapp.ui.admin.AdminDashboardState
import com.schoolapp.ui.admin.AdminViewModel

@Composable
fun AdminProfileScreen(
    state: AdminDashboardState,
    viewModel: AdminViewModel,
    onLogout: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var currentPassword by rememberSaveable { mutableStateOf("") }
    var newPassword by rememberSaveable { mutableStateOf("") }
    var confirmPassword by rememberSaveable { mutableStateOf("") }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(SurfaceBackground)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        GlassCard(Modifier.fillMaxWidth()) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("My Profile", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                Text("Display Name: ${state.currentUserName.ifBlank { "Admin" }}", style = MaterialTheme.typography.bodyMedium)
                Text("Login Code: ${state.currentUserCode.ifBlank { "-" }}", style = MaterialTheme.typography.bodySmall, color = TextSecondary)
                Text("Role: ${state.currentUserRole?.name ?: "ADMIN"}", style = MaterialTheme.typography.bodySmall, color = TextSecondary)
                Text("School Name: ${state.schoolName.ifBlank { "School Performance Hub" }}", style = MaterialTheme.typography.bodySmall, color = TextSecondary)
                Text("Total students: ${state.totalStudents}", style = MaterialTheme.typography.bodySmall, color = TextSecondary)
                Text("Pass rate: ${"%.1f".format(state.passRatePct)}%", style = MaterialTheme.typography.bodySmall, color = TextSecondary)
            }
        }

        GlassCard(Modifier.fillMaxWidth()) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Security", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Text(
                    "You can change your password here. Students and teachers will get unique credentials created by admin.",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary,
                )
                OutlinedTextField(
                    value = currentPassword,
                    onValueChange = {
                        currentPassword = it
                        viewModel.clearPasswordChangeMessage()
                    },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Current Password") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                )
                OutlinedTextField(
                    value = newPassword,
                    onValueChange = {
                        newPassword = it
                        viewModel.clearPasswordChangeMessage()
                    },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("New Password") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                )
                OutlinedTextField(
                    value = confirmPassword,
                    onValueChange = {
                        confirmPassword = it
                        viewModel.clearPasswordChangeMessage()
                    },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Confirm New Password") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                )
                Button(
                    onClick = {
                        viewModel.changeMyPassword(
                            currentPassword = currentPassword,
                            newPassword = newPassword,
                            confirmPassword = confirmPassword,
                        )
                    },
                    enabled = !state.passwordChangeLoading &&
                        currentPassword.isNotBlank() &&
                        newPassword.isNotBlank() &&
                        confirmPassword.isNotBlank(),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(if (state.passwordChangeLoading) "Changing..." else "Change Password")
                }
                state.passwordChangeMessage?.let { message ->
                    Text(
                        text = message,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (state.passwordChangeError) DangerRed else SuccessGreen,
                    )
                }
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
