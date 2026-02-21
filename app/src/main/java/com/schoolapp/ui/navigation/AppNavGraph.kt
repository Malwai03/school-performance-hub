package com.schoolapp.ui.navigation

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.School
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.schoolapp.core.ui.theme.Background
import com.schoolapp.core.ui.theme.Primary
import com.schoolapp.core.ui.theme.PrimaryContainer
import com.schoolapp.core.ui.theme.TextSecondary
import com.schoolapp.data.repository.AppContainer
import com.schoolapp.ui.admin.AdminRootScreen
import com.schoolapp.ui.admin.AdminViewModel
import com.schoolapp.ui.login.LoginScreen
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
    val adminViewModel: AdminViewModel = viewModel(
        factory = AdminViewModel.factory(
            appContainer.masterDataRepository,
            appContainer.analyticsRepository,
            appContainer.marksRepository,
        ),
    )
    val teacherViewModel: TeacherViewModel = viewModel(
        factory = TeacherViewModel.factory(
            appContainer.masterDataRepository,
            appContainer.marksRepository,
        ),
    )
    val studentViewModel: StudentViewModel = viewModel(
        factory = StudentViewModel.factory(
            appContainer.authRepository,
            appContainer.analyticsRepository,
            appContainer.masterDataRepository,
        ),
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
        composable(Routes.AdminHome) {
            AdminRootScreen(
                viewModel = adminViewModel,
                onLogout = {
                    loginViewModel.logout()
                    navController.navigate(Routes.Login) {
                        popUpTo(navController.graph.startDestinationId) { inclusive = true }
                        launchSingleTop = true
                    }
                },
            )
        }
        composable(Routes.TeacherHome) {
            TeacherMarksScreen(
                viewModel = teacherViewModel,
                onLogout = {
                    loginViewModel.logout()
                    navController.navigate(Routes.Login) {
                        popUpTo(navController.graph.startDestinationId) { inclusive = true }
                        launchSingleTop = true
                    }
                },
            )
        }
        composable(Routes.StudentHome) {
            StudentDashboardScreen(
                viewModel = studentViewModel,
                onLogout = {
                    loginViewModel.logout()
                    navController.navigate(Routes.Login) {
                        popUpTo(navController.graph.startDestinationId) { inclusive = true }
                        launchSingleTop = true
                    }
                },
            )
        }
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
