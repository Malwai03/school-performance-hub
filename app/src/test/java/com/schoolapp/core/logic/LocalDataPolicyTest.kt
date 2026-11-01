package com.schoolapp.core.logic

import com.google.common.truth.Truth.assertThat
import com.schoolapp.core.logic.LocalDataPolicy.DEMO_OWNER
import com.schoolapp.core.logic.LocalDataPolicy.SessionKind
import com.schoolapp.core.logic.LocalDataPolicy.SignInAction
import com.schoolapp.core.logic.LocalDataPolicy.SignOutAction
import org.junit.Test

class LocalDataPolicyTest {

    // --- sessions

    @Test
    fun `a real session needs Firebase signed in as the same uid`() {
        assertThat(LocalDataPolicy.sessionKind("uidA", "uidA", demoLoginEnabled = false)).isEqualTo(SessionKind.FIREBASE)
        // Opened without Firebase (the old pass123 path for a cached real account): not restored.
        assertThat(LocalDataPolicy.sessionKind("uidA", null, demoLoginEnabled = true)).isEqualTo(SessionKind.NONE)
        assertThat(LocalDataPolicy.sessionKind("uidA", "uidB", demoLoginEnabled = true)).isEqualTo(SessionKind.NONE)
    }

    @Test
    fun `a demo session is only restored in demo builds`() {
        assertThat(LocalDataPolicy.sessionKind("teacher-1", null, demoLoginEnabled = true)).isEqualTo(SessionKind.DEMO)
        assertThat(LocalDataPolicy.sessionKind("teacher-1", null, demoLoginEnabled = false)).isEqualTo(SessionKind.NONE)
    }

    @Test
    fun `a real account whose uid looks like a seed uid is still a Firebase session`() {
        // Student accounts use the studentId as uid, so a real "student-1" can exist.
        assertThat(LocalDataPolicy.sessionKind("student-1", "student-1", demoLoginEnabled = false))
            .isEqualTo(SessionKind.FIREBASE)
    }

    @Test
    fun `no saved session is no session`() {
        assertThat(LocalDataPolicy.sessionKind(null, "uidA", demoLoginEnabled = true)).isEqualTo(SessionKind.NONE)
        assertThat(LocalDataPolicy.sessionKind("", null, demoLoginEnabled = true)).isEqualTo(SessionKind.NONE)
    }

    // --- sign-in

    @Test
    fun `signing in again as the owner keeps the local data`() {
        assertThat(
            LocalDataPolicy.onSignIn("uidA", "uidA", hasLocalData = true, unsyncedMarks = 4, discardConfirmed = false),
        ).isEqualTo(SignInAction.Keep)
    }

    @Test
    fun `another account's data is cleared before the new account sees it`() {
        assertThat(
            LocalDataPolicy.onSignIn("uidA", "uidB", hasLocalData = true, unsyncedMarks = 0, discardConfirmed = false),
        ).isEqualTo(SignInAction.Clear)
    }

    @Test
    fun `another account's unsynced marks are never dropped without asking`() {
        assertThat(
            LocalDataPolicy.onSignIn("uidA", "uidB", hasLocalData = true, unsyncedMarks = 3, discardConfirmed = false),
        ).isEqualTo(SignInAction.ConfirmDiscard(3))
        assertThat(
            LocalDataPolicy.onSignIn("uidA", "uidB", hasLocalData = true, unsyncedMarks = 3, discardConfirmed = true),
        ).isEqualTo(SignInAction.Clear)
    }

    @Test
    fun `data from older versions has no owner and counts as someone else's`() {
        assertThat(
            LocalDataPolicy.onSignIn(null, "uidB", hasLocalData = true, unsyncedMarks = 0, discardConfirmed = false),
        ).isEqualTo(SignInAction.Clear)
        assertThat(
            LocalDataPolicy.onSignIn(null, "uidB", hasLocalData = true, unsyncedMarks = 2, discardConfirmed = false),
        ).isEqualTo(SignInAction.ConfirmDiscard(2))
    }

    @Test
    fun `demo data is cleared for a real account without asking`() {
        assertThat(
            LocalDataPolicy.onSignIn(DEMO_OWNER, "uidA", hasLocalData = true, unsyncedMarks = 5, discardConfirmed = false),
        ).isEqualTo(SignInAction.Clear)
    }

    @Test
    fun `demo users share the demo data`() {
        val demoOwner = LocalDataPolicy.ownerFor("teacher-1", demoSession = true)
        assertThat(demoOwner).isEqualTo(DEMO_OWNER)
        assertThat(
            LocalDataPolicy.onSignIn(DEMO_OWNER, demoOwner, hasLocalData = true, unsyncedMarks = 0, discardConfirmed = false),
        ).isEqualTo(SignInAction.Keep)
        assertThat(LocalDataPolicy.ownerFor("uidA", demoSession = false)).isEqualTo("uidA")
    }

    @Test
    fun `an empty database is simply claimed`() {
        assertThat(
            LocalDataPolicy.onSignIn("uidA", "uidB", hasLocalData = false, unsyncedMarks = 0, discardConfirmed = false),
        ).isEqualTo(SignInAction.Clear)
    }

    // --- sign-out

    @Test
    fun `sign-out clears the account's data once everything is uploaded`() {
        for (choice in UnsyncedMarksChoice.values()) {
            assertThat(LocalDataPolicy.onSignOut("uidA", unsyncedMarksAfterUpload = 0, choice = choice))
                .isEqualTo(SignOutAction.ClearAndSignOut)
        }
    }

    @Test
    fun `sign-out stops and asks while marks are still not uploaded`() {
        assertThat(LocalDataPolicy.onSignOut("uidA", unsyncedMarksAfterUpload = 7, choice = UnsyncedMarksChoice.ASK))
            .isEqualTo(SignOutAction.AskUser(7))
    }

    @Test
    fun `sign-out deletes unsynced marks only when the user says so`() {
        assertThat(LocalDataPolicy.onSignOut("uidA", unsyncedMarksAfterUpload = 7, choice = UnsyncedMarksChoice.DISCARD))
            .isEqualTo(SignOutAction.ClearAndSignOut)
    }

    @Test
    fun `kept marks stay for the same account and are not handed to the next one`() {
        assertThat(
            LocalDataPolicy.onSignOut("uidA", unsyncedMarksAfterUpload = 7, choice = UnsyncedMarksChoice.KEEP_ON_DEVICE),
        ).isEqualTo(SignOutAction.SignOutKeepData)
        // Owner A signs back in: data kept, and A may upload it.
        assertThat(
            LocalDataPolicy.onSignIn("uidA", "uidA", hasLocalData = true, unsyncedMarks = 7, discardConfirmed = false),
        ).isEqualTo(SignInAction.Keep)
        assertThat(LocalDataPolicy.mayUpload("uidA", "uidA", "uidA")).isTrue()
        // B signs in instead: asked first, never uploads A's marks.
        assertThat(
            LocalDataPolicy.onSignIn("uidA", "uidB", hasLocalData = true, unsyncedMarks = 7, discardConfirmed = false),
        ).isEqualTo(SignInAction.ConfirmDiscard(7))
        assertThat(LocalDataPolicy.mayUpload("uidA", "uidB", "uidB")).isFalse()
    }

    @Test
    fun `sign-out of a session with unknown data owner still clears the data`() {
        assertThat(LocalDataPolicy.onSignOut(null, unsyncedMarksAfterUpload = 0, choice = UnsyncedMarksChoice.ASK))
            .isEqualTo(SignOutAction.ClearAndSignOut)
    }

    @Test
    fun `demo sign-out keeps the shared demo data`() {
        assertThat(LocalDataPolicy.onSignOut(DEMO_OWNER, unsyncedMarksAfterUpload = 0, choice = UnsyncedMarksChoice.ASK))
            .isEqualTo(SignOutAction.SignOutKeepData)
    }

    // --- uploads

    @Test
    fun `marks are uploaded only as the account that owns them`() {
        assertThat(LocalDataPolicy.mayUpload(dataOwner = "uidA", sessionUid = "uidA", firebaseUid = "uidA")).isTrue()
    }

    @Test
    fun `the previous user's marks are never uploaded for the next user`() {
        // Data still owned by A while B is signed in.
        assertThat(LocalDataPolicy.mayUpload(dataOwner = "uidA", sessionUid = "uidB", firebaseUid = "uidB")).isFalse()
        // Unknown owner (older version, nobody signed in at upgrade).
        assertThat(LocalDataPolicy.mayUpload(dataOwner = null, sessionUid = "uidB", firebaseUid = "uidB")).isFalse()
    }

    @Test
    fun `nothing is uploaded without a matching Firebase user`() {
        assertThat(LocalDataPolicy.mayUpload(dataOwner = "uidA", sessionUid = "uidA", firebaseUid = null)).isFalse()
        assertThat(LocalDataPolicy.mayUpload(dataOwner = "uidA", sessionUid = "uidA", firebaseUid = "uidB")).isFalse()
        assertThat(LocalDataPolicy.mayUpload(dataOwner = "uidA", sessionUid = null, firebaseUid = "uidA")).isFalse()
    }

    @Test
    fun `demo data is never uploaded`() {
        assertThat(LocalDataPolicy.mayUpload(dataOwner = DEMO_OWNER, sessionUid = "teacher-1", firebaseUid = null)).isFalse()
        assertThat(LocalDataPolicy.mayUpload(dataOwner = DEMO_OWNER, sessionUid = "teacher-1", firebaseUid = "teacher-1")).isFalse()
    }

    // --- startup

    @Test
    fun `untracked data is adopted by the account still signed in`() {
        assertThat(LocalDataPolicy.ownerForUntrackedData(null, "uidA", SessionKind.FIREBASE)).isEqualTo("uidA")
        assertThat(LocalDataPolicy.ownerForUntrackedData(null, "teacher-1", SessionKind.DEMO)).isEqualTo(DEMO_OWNER)
    }

    @Test
    fun `untracked data stays ownerless without a valid session`() {
        assertThat(LocalDataPolicy.ownerForUntrackedData(null, null, SessionKind.NONE)).isNull()
        assertThat(LocalDataPolicy.ownerForUntrackedData(null, "uidA", SessionKind.NONE)).isNull()
    }

    @Test
    fun `a recorded owner is kept`() {
        assertThat(LocalDataPolicy.ownerForUntrackedData("uidA", "uidB", SessionKind.FIREBASE)).isEqualTo("uidA")
    }

    @Test
    fun `demo data is seeded only in demo builds into a free, empty database`() {
        assertThat(LocalDataPolicy.shouldSeedDemo(demoLoginEnabled = true, currentOwner = null, hasLocalData = false)).isTrue()
        assertThat(LocalDataPolicy.shouldSeedDemo(demoLoginEnabled = true, currentOwner = DEMO_OWNER, hasLocalData = false)).isTrue()
        assertThat(LocalDataPolicy.shouldSeedDemo(demoLoginEnabled = false, currentOwner = null, hasLocalData = false)).isFalse()
        assertThat(LocalDataPolicy.shouldSeedDemo(demoLoginEnabled = true, currentOwner = null, hasLocalData = true)).isFalse()
        // A real account's database is never filled with demo data, even when it is empty.
        assertThat(LocalDataPolicy.shouldSeedDemo(demoLoginEnabled = true, currentOwner = "uidA", hasLocalData = false)).isFalse()
    }
}
