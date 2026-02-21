package com.schoolapp.ui.login

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.schoolapp.core.model.SchoolUser
import com.schoolapp.core.model.UserRole
import com.schoolapp.domain.repository.AuthRepository
import com.schoolapp.ui.navigation.Routes
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class LoginUiState(
    val loading: Boolean = false,
    val loginCode: String = "",
    val password: String = "",
    val error: String? = null,
    val user: SchoolUser? = null,
)

class LoginViewModel(
    private val authRepository: AuthRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(LoginUiState())
    val uiState: StateFlow<LoginUiState> = _uiState.asStateFlow()

    init {
        bootstrap()
    }

    private fun bootstrap() {
        viewModelScope.launch {
            val current = authRepository.currentUser()
            if (current != null) {
                _uiState.value = _uiState.value.copy(user = current)
            }
        }
    }

    fun onCodeChanged(value: String) {
        _uiState.value = _uiState.value.copy(loginCode = value, error = null)
    }

    fun onPasswordChanged(value: String) {
        _uiState.value = _uiState.value.copy(password = value, error = null)
    }

    fun login() {
        val code = _uiState.value.loginCode.trim()
        val password = _uiState.value.password
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(loading = true, error = null)
            runCatching {
                authRepository.signIn(code, password)
            }.onSuccess { user ->
                _uiState.value = _uiState.value.copy(loading = false, user = user)
            }.onFailure { error ->
                _uiState.value = _uiState.value.copy(
                    loading = false,
                    error = error.message ?: "Unable to sign in",
                )
            }
        }
    }

    fun logout() {
        viewModelScope.launch {
            authRepository.signOut()
            _uiState.value = LoginUiState()
        }
    }

    fun consumeUserNavigation() {
        _uiState.value = _uiState.value.copy(user = null)
    }

    fun routeForRole(userRole: UserRole): String {
        return when (userRole) {
            UserRole.ADMIN -> Routes.AdminHome
            UserRole.TEACHER -> Routes.TeacherHome
            UserRole.STUDENT -> Routes.StudentHome
        }
    }

    companion object {
        fun factory(authRepository: AuthRepository): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    return LoginViewModel(authRepository) as T
                }
            }
    }
}
