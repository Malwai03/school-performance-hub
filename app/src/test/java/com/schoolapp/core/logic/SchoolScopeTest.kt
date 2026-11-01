package com.schoolapp.core.logic

import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test

class SchoolScopeTest {

    @Test
    fun `sign-in uses the token schoolId claim`() {
        val claims = mapOf<String, Any?>("role" to "TEACHER", "schoolId" to "school_002")

        assertThat(SchoolScope.schoolIdForSignIn(claims)).isEqualTo("school_002")
    }

    @Test
    fun `sign-in trims the claim`() {
        assertThat(SchoolScope.schoolIdForSignIn(mapOf("schoolId" to " school-3 "))).isEqualTo("school-3")
    }

    @Test
    fun `sign-in without a schoolId claim falls back to the default school`() {
        assertThat(SchoolScope.schoolIdForSignIn(mapOf("role" to "ADMIN")))
            .isEqualTo(SchoolScope.DEFAULT_SCHOOL_ID)
        assertThat(SchoolScope.schoolIdForSignIn(emptyMap())).isEqualTo(SchoolScope.DEFAULT_SCHOOL_ID)
        assertThat(SchoolScope.schoolIdForSignIn(null)).isEqualTo(SchoolScope.DEFAULT_SCHOOL_ID)
    }

    @Test
    fun `sign-in rejects a present but invalid claim instead of falling back`() {
        listOf<Any>("", "   ", "school/../school_001", "a".repeat(65), 42, true).forEach { bad ->
            assertThrows(IllegalArgumentException::class.java) {
                SchoolScope.schoolIdForSignIn(mapOf("schoolId" to bad))
            }
        }
    }

    @Test
    fun `session school is the cached user's school`() {
        assertThat(SchoolScope.sessionSchoolIdOrNull("school_002")).isEqualTo("school_002")
        assertThat(SchoolScope.requireSessionSchoolId("school_002")).isEqualTo("school_002")
    }

    @Test
    fun `demo seed users keep the default school`() {
        assertThat(SchoolScope.requireSessionSchoolId(SchoolScope.DEFAULT_SCHOOL_ID))
            .isEqualTo("school_001")
    }

    @Test
    fun `no signed-in user has no session school and no default`() {
        assertThat(SchoolScope.sessionSchoolIdOrNull(null)).isNull()
        assertThat(SchoolScope.sessionSchoolIdOrNull("  ")).isNull()
        assertThat(SchoolScope.sessionSchoolIdOrNull("bad/id")).isNull()
        assertThrows(IllegalStateException::class.java) { SchoolScope.requireSessionSchoolId(null) }
    }
}
