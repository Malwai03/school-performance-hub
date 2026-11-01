package com.schoolapp.core.logic

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class DemoLoginTest {

    @Test
    fun `demo builds open the seed users with the demo password`() {
        assertThat(DemoLogin.demoUidFor("admin001", "pass123", demoLoginEnabled = true)).isEqualTo("admin-1")
        assertThat(DemoLogin.demoUidFor("teacher001", "pass123", demoLoginEnabled = true)).isEqualTo("teacher-1")
        assertThat(DemoLogin.demoUidFor("student001", "pass123", demoLoginEnabled = true)).isEqualTo("student-1")
    }

    @Test
    fun `seed codes are matched trimmed and case-insensitively`() {
        assertThat(DemoLogin.demoUidFor(" Teacher001 ", "pass123", demoLoginEnabled = true)).isEqualTo("teacher-1")
    }

    @Test
    fun `release builds never use the demo login`() {
        assertThat(DemoLogin.demoUidFor("admin001", "pass123", demoLoginEnabled = false)).isNull()
        assertThat(DemoLogin.demoUidFor("teacher001", "pass123", demoLoginEnabled = false)).isNull()
    }

    @Test
    fun `pass123 does not open any other account, cached or not`() {
        // Real accounts (any code that is not a seed code) always go through Firebase Auth.
        assertThat(DemoLogin.demoUidFor("teacher042", "pass123", demoLoginEnabled = true)).isNull()
        assertThat(DemoLogin.demoUidFor("admin_school_002", "pass123", demoLoginEnabled = true)).isNull()
        assertThat(DemoLogin.demoUidFor("", "pass123", demoLoginEnabled = true)).isNull()
    }

    @Test
    fun `seed codes with any other password go through Firebase Auth`() {
        assertThat(DemoLogin.demoUidFor("admin001", "realPassword!", demoLoginEnabled = true)).isNull()
        assertThat(DemoLogin.demoUidFor("admin001", "PASS123", demoLoginEnabled = true)).isNull()
        assertThat(DemoLogin.demoUidFor("admin001", " pass123", demoLoginEnabled = true)).isNull()
    }

    @Test
    fun `only the seed uids are demo uids`() {
        assertThat(DemoLogin.isDemoUid("admin-1")).isTrue()
        assertThat(DemoLogin.isDemoUid("teacher-1")).isTrue()
        assertThat(DemoLogin.isDemoUid("student-1")).isTrue()
        assertThat(DemoLogin.isDemoUid("Xq3firebaseUid")).isFalse()
        assertThat(DemoLogin.isDemoUid(null)).isFalse()
    }
}
