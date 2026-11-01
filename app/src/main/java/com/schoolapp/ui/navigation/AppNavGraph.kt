package com.schoolapp.ui.navigation

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.School
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.schoolapp.core.ui.theme.Background
import com.schoolapp.core.ui.theme.Primary
import com.schoolapp.core.ui.theme.PrimaryContainer
import com.schoolapp.core.ui.theme.TextSecondary
import com.schoolapp.core.logic.UnsyncedMarksChoice
import com.schoolapp.data.repository.AppContainer
import com.schoolapp.ui.admin.AdminRootScreen
import com.schoolapp.ui.admin.AdminViewModel
import com.schoolapp.ui.login.LoginScreen
import com.schoolapp.ui.login.LoginUiState
import com.schoolapp.ui.login.LoginViewModel
import com.schoolapp.ui.student.StudentDashboardScreen
import com.schoolapp.ui.student.StudentViewModel
import com.schoolapp.ui.teacher.TeacherMarksScreen
import com.schoolapp.ui.teacher.TeacherViewModel
import kotlinx.coroutines.delay

@Composable
fun SchoolNavGraph(
    appContainer: AppContainer,
    navController: NavHostController = rememberNavController(),
) {
    val loginViewModel: LoginViewModel = viewModel(
        factory = LoginViewModel.factory(appContainer.authRepository),
    )
    val loginState by loginViewModel.uiState.collectAsStateWithLifecycle()

    // Sign-out finished: back to login with the whole back stack gone, so the previous user's
    // screens and their (back-stack scoped) view models are destroyed.
    LaunchedEffect(loginState.signedOut) {
        if (!loginState.signedOut) return@LaunchedEffect
        navController.navigate(Routes.Login) {
            popUpTo(navController.graph.id) { inclusive = true }
            launchSingleTop = true
        }
        loginViewModel.consumeSignedOut()
    }
    SignOutDialogs(
        state = loginState,
        onStaySignedIn = loginViewModel::staySignedIn,
        onKeepOnDeviceAndSignOut = { loginViewModel.logout(UnsyncedMarksChoice.KEEP_ON_DEVICE) },
        onDiscardAndSignOut = { loginViewModel.logout(UnsyncedMarksChoice.DISCARD) },
    )

    NavHost(navController = navController, startDestination = Routes.Splash) {
        composable(Routes.Splash) {
            SplashScreen {
                navController.navigate(Routes.Login) {
                    popUpTo(Routes.Splash) { inclusive = true }
                }
            }
        }
        composable(Routes.Login) {
            LoginScreen(
                viewModel = loginViewModel,
                onNavigateByRole = { route ->
                    navController.navigate(route) {
                        popUpTo(Routes.Login) { inclusive = true }
                    }
                },
            )
        }
        // Role view models are scoped to their destination, not the activity: each sign-in gets
        // fresh ones that load the new user's data, and sign-out drops the old user's state.
        composable(Routes.AdminHome) {
            val adminViewModel: AdminViewModel = viewModel(
                factory = AdminViewModel.factory(
                    appContainer.authRepository,
                    appContainer.masterDataRepository,
                    appContainer.analyticsRepository,
                    appContainer.marksRepository,
                ),
            )
            AdminRootScreen(
                viewModel = adminViewModel,
                onLogout = { loginViewModel.logout() },
            )
        }
        composable(Routes.TeacherHome) {
            val teacherViewModel: TeacherViewModel = viewModel(
                factory = TeacherViewModel.factory(
                    appContainer.masterDataRepository,
                    appContainer.marksRepository,
                ),
            )
            TeacherMarksScreen(
                viewModel = teacherViewModel,
                onLogout = { loginViewModel.logout() },
            )
        }
        composable(Routes.StudentHome) {
            val studentViewModel: StudentViewModel = viewModel(
                factory = StudentViewModel.factory(
                    appContainer.authRepository,
                    appContainer.analyticsRepository,
                    appContainer.masterDataRepository,
                ),
            )
            StudentDashboardScreen(
                viewModel = studentViewModel,
                onLogout = { loginViewModel.logout() },
            )
        }
    }
}

@Composable
private fun SignOutDialogs(
    state: LoginUiState,
    onStaySignedIn: () -> Unit,
    onKeepOnDeviceAndSignOut: () -> Unit,
    onDiscardAndSignOut: () -> Unit,
) {
    when {
        state.signingOut -> AlertDialog(
            onDismissRequest = {},
            title = { Text("Signing out") },
            text = {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(20.dp))
                    Text("Uploading marks...")
                }
            },
            confirmButton = {},
        )
        state.unsyncedMarksOnSignOut != null -> AlertDialog(
            onDismissRequest = onStaySignedIn,
            title = { Text("Marks not uploaded yet") },
            text = {
                Text(
                    "${state.unsyncedMarksOnSignOut} marks have not reached the school server. " +
                        "Stay signed in and connect to the internet so they can upload, or keep " +
                        "them on this device: they upload the next time you sign in here. " +
                        "Anyone else who signs in on this device is asked before they are deleted.",
                )
            },
            confirmButton = {
                Column(horizontalAlignment = Alignment.End) {
                    Button(onClick = onStaySignedIn) { Text("Stay signed in") }
                    TextButton(onClick = onKeepOnDeviceAndSignOut) { Text("Sign out, keep marks on this device") }
                    TextButton(onClick = onDiscardAndSignOut) {
                        Text("Delete marks and sign out", color = MaterialTheme.colorScheme.error)
                    }
                }
            },
        )
        state.signOutError != null -> AlertDialog(
            onDismissRequest = onStaySignedIn,
            title = { Text("Could not sign out") },
            text = { Text(state.signOutError) },
            confirmButton = { Button(onClick = onStaySignedIn) { Text("OK") } },
        )
    }
}

@Composable
private fun SplashScreen(onFinished: () -> Unit) {
    LaunchedEffect(Unit) {
        delay(900)
        onFinished()
    }
    Box(
        modifier = Modifier.fillMaxSize().background(Background),
        contentAlignment = Alignment.Center,
    ) {
        AnimatedVisibility(visible = true, enter = fadeIn() + scaleIn()) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(
                    modifier = Modifier.size(64.dp).clip(CircleShape).background(PrimaryContainer),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Filled.School, contentDescription = null, tint = Primary, modifier = Modifier.size(32.dp))
                }
                Text("School Performance Hub", style = MaterialTheme.typography.headlineMedium)
                Text("Loading...", style = MaterialTheme.typography.bodyMedium, color = TextSecondary)
            }
        }
    }
}
