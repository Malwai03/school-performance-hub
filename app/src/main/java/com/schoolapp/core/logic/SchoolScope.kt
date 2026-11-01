package com.schoolapp.core.logic

/**
 * Which school the signed-in user belongs to.
 *
 * The backend gives managed accounts a `schoolId` custom claim on their Firebase ID token, and
 * their profile lives at schools/{schoolId}/users/{uid}. That claim is the source of truth at
 * sign-in; the result is cached on the Room user row, and everything after sign-in (repositories,
 * the score sync worker) reads the school from there.
 */
object SchoolScope {
    /**
     * School of the bundled demo/seed data, and of accounts whose token has no schoolId claim
     * (accounts made before claims carried one, such as hand-made admins). Firestore rules still
     * refuse such an account unless school_001 has a user record for it.
     */
    const val DEFAULT_SCHOOL_ID = "school_001"

    const val SCHOOL_ID_CLAIM = "schoolId"

    /** Same shape the backend accepts. */
    private val SCHOOL_ID_PATTERN = Regex("^[A-Za-z0-9_-]{1,64}$")

    fun isValidSchoolId(schoolId: String?): Boolean =
        schoolId != null && SCHOOL_ID_PATTERN.matches(schoolId)

    /**
     * School to load a Firebase user's profile from, given their ID token claims.
     * No claim: [DEFAULT_SCHOOL_ID]. A claim that is present but not a valid school id fails
     * instead of falling back, so a bad claim never lands the user in another school.
     */
    fun schoolIdForSignIn(claims: Map<String, Any?>?): String {
        val claimed = claims?.get(SCHOOL_ID_CLAIM) ?: return DEFAULT_SCHOOL_ID
        val schoolId = (claimed as? String)?.trim()
        require(isValidSchoolId(schoolId)) {
            "This account's school is not set up correctly. Ask your admin to check it."
        }
        return schoolId!!
    }

    /** The signed-in user's cached school, or null when nobody (or no valid school) is signed in. */
    fun sessionSchoolIdOrNull(cachedUserSchoolId: String?): String? =
        cachedUserSchoolId?.trim()?.takeIf { isValidSchoolId(it) }

    /**
     * The signed-in user's cached school. There is no default: with nobody signed in, reads and
     * writes would otherwise go to school_001.
     */
    fun requireSessionSchoolId(cachedUserSchoolId: String?): String =
        sessionSchoolIdOrNull(cachedUserSchoolId)
            ?: throw IllegalStateException("No signed-in user with a school. Sign in again.")
}
