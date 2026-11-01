package com.schoolapp.core.logic

/**
 * The offline demo login: the bundled seed users with one shared demo password.
 *
 * It must never open a real account. It only matches the three seed login codes, only opens
 * the seed users' fixed uids (never a cached real account with the same code), and only when
 * the build turns demo login on (debug builds: BuildConfig.DEMO_LOGIN_ENABLED). Every other
 * sign-in goes through Firebase Auth.
 */
object DemoLogin {
    const val PASSWORD = "pass123"

    const val ADMIN_CODE = "admin001"
    const val TEACHER_CODE = "teacher001"
    const val STUDENT_CODE = "student001"

    const val ADMIN_UID = "admin-1"
    const val TEACHER_UID = "teacher-1"
    const val STUDENT_UID = "student-1"

    /** Seed login code -> seed uid. */
    private val SEED_UIDS: Map<String, String> = mapOf(
        ADMIN_CODE to ADMIN_UID,
        TEACHER_CODE to TEACHER_UID,
        STUDENT_CODE to STUDENT_UID,
    )

    fun isDemoUid(uid: String?): Boolean = uid != null && uid in SEED_UIDS.values

    /**
     * Seed uid this login opens locally, or null when it has to go through Firebase Auth:
     * demo login off, a code that is not a seed code, or not the demo password.
     */
    fun demoUidFor(loginCode: String, password: String, demoLoginEnabled: Boolean): String? {
        if (!demoLoginEnabled) return null
        if (password != PASSWORD) return null
        return SEED_UIDS[loginCode.trim().lowercase()]
    }
}
