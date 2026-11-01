package com.schoolapp.ui.login

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.schoolapp.core.logic.UnsyncedMarksChoice
import com.schoolapp.core.model.SchoolUser
import com.schoolapp.core.model.UserRole
import com.schoolapp.domain.repository.AuthRepository
import com.schoolapp.domain.repository.SignOutResult
import com.schoolapp.domain.repository.UnsyncedMarksException
import com.schoolapp.ui.navigation.Routes
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class LoginUiState(
    val loading: Boolean = false,
    val loginCode: String = "",
    val password: String = "",
    val error: String? = null,
    val user: SchoolUser? = null,
    /** Signing in would delete this many never-uploaded marks of another account: ask first. */
    val otherAccountUnsyncedMarks: Int? = null,
    /** Sign-out in progress (includes the last upload of unsynced marks). */
    val signingOut: Boolean = false,
    /** Sign-out stopped: this many marks are still not uploaded. Ask before deleting them. */
    val unsyncedMarksOnSignOut: Int? = null,
    val signOutError: String? = null,
    /** One-shot: sign-out finished, go to the login screen. */
    val signedOut: Boolean = false,
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

    fun login() = signIn(discardOtherAccountsUnsyncedMarks = false)

    /** The user agreed to delete another account's never-uploaded marks to sign in. */
    fun confirmDiscardOtherAccountMarks() = signIn(discardOtherAccountsUnsyncedMarks = true)

    fun cancelDiscardOtherAccountMarks() {
        _uiState.update { it.copy(otherAccountUnsyncedMarks = null) }
    }

    private fun signIn(discardOtherAccountsUnsyncedMarks: Boolean) {
        val code = _uiState.value.loginCode.trim()
        val password = _uiState.value.password
        viewModelScope.launch {
            _uiState.update { it.copy(loading = true, error = null, otherAccountUnsyncedMarks = null) }
            runCatching {
                authRepository.signIn(code, password, discardOtherAccountsUnsyncedMarks)
            }.onSuccess { user ->
                _uiState.update { it.copy(loading = false, user = user, password = "") }
            }.onFailure { error ->
                _uiState.update {
                    if (error is UnsyncedMarksException) {
                        it.copy(loading = false, otherAccountUnsyncedMarks = error.unsyncedMarks)
                    } else {
                        it.copy(loading = false, error = error.message ?: "Unable to sign in")
                    }
                }
            }
        }
    }

    /**
     * Signs out after one last upload of unsynced marks. If some still cannot be uploaded the
     * user stays signed in and is asked; their answer comes back as [unsyncedMarks].
     */
    fun logout(unsyncedMarks: UnsyncedMarksChoice = UnsyncedMarksChoice.ASK) {
        if (_uiState.value.signingOut) return
        viewModelScope.launch {
            _uiState.update { it.copy(signingOut = true, unsyncedMarksOnSignOut = null, signOutError = null) }
            runCatching { authRepository.signOut(unsyncedMarks) }
                .onSuccess { result ->
                    when (result) {
                        SignOutResult.SignedOut -> _uiState.value = LoginUiState(signedOut = true)
                        is SignOutResult.UnsyncedMarks -> _uiState.update {
                            it.copy(signingOut = false, unsyncedMarksOnSignOut = result.count)
                        }
                    }
                }
                .onFailure { error ->
                    _uiState.update {
                        it.copy(signingOut = false, signOutError = error.message ?: "Unable to sign out")
                    }
                }
        }
    }

    /** The user chose to stay signed in so their marks can still upload. */
    fun staySignedIn() {
        _uiState.update { it.copy(unsyncedMarksOnSignOut = null, signOutError = null) }
    }

    fun consumeSignedOut() {
        _uiState.update { it.copy(signedOut = false) }
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
