package com.schoolapp.core.logic

/** What to do with marks that could not be uploaded at sign-out. */
enum class UnsyncedMarksChoice {
    /** Nothing decided yet: try one last upload, and ask if marks are left. */
    ASK,

    /** Sign out but keep them on this device for the same account's next sign-in. */
    KEEP_ON_DEVICE,

    /** Delete them with the rest of this account's local data. */
    DISCARD,
}

/**
 * Who the data in the local Room database belongs to, and what happens to it when the
 * signed-in account changes.
 *
 * Room holds one owner's data at a time: a real account's uid, or [DEMO_OWNER] for the bundled
 * demo data that the demo seed users share. Unsynced marks are only ever uploaded as their
 * owner, and are only deleted after the person at the device agrees to it.
 */
object LocalDataPolicy {
    /** Owner of the bundled demo data. Never uploaded anywhere. */
    const val DEMO_OWNER = "demo"

    enum class SessionKind {
        /** Nobody signed in, or a saved session that may not be restored. */
        NONE,

        /** A real account: Firebase Auth is signed in as the same uid as the app session. */
        FIREBASE,

        /** A demo seed user, in a build with demo login on. */
        DEMO,
    }

    /**
     * What a saved app session is. A real account's session is only valid while Firebase Auth
     * is signed in as that same uid, so a session opened without Firebase (as the old pass123
     * login did for any cached account) does not survive. A demo seed session needs demo login.
     */
    fun sessionKind(sessionUid: String?, firebaseUid: String?, demoLoginEnabled: Boolean): SessionKind = when {
        sessionUid.isNullOrBlank() -> SessionKind.NONE
        firebaseUid == sessionUid -> SessionKind.FIREBASE
        demoLoginEnabled && DemoLogin.isDemoUid(sessionUid) -> SessionKind.DEMO
        else -> SessionKind.NONE
    }

    fun ownerFor(uid: String, demoSession: Boolean): String = if (demoSession) DEMO_OWNER else uid

    sealed interface SignInAction {
        /** The local data already belongs to the account signing in. */
        data object Keep : SignInAction

        /** Delete the other owner's local data before the new account sees anything. */
        data object Clear : SignInAction

        /** The other owner's data still has marks that never reached the server: ask first. */
        data class ConfirmDiscard(val unsyncedMarks: Int) : SignInAction
    }

    /**
     * What to do with the local data when [incomingOwner] signs in. [currentOwner] is null for
     * data left by an app version that did not record owners; that data is treated as someone
     * else's. Demo marks are never uploaded, so dropping them needs no confirmation.
     */
    fun onSignIn(
        currentOwner: String?,
        incomingOwner: String,
        hasLocalData: Boolean,
        unsyncedMarks: Int,
        discardConfirmed: Boolean,
    ): SignInAction = when {
        currentOwner == incomingOwner -> SignInAction.Keep
        !hasLocalData -> SignInAction.Clear
        currentOwner != DEMO_OWNER && unsyncedMarks > 0 && !discardConfirmed ->
            SignInAction.ConfirmDiscard(unsyncedMarks)
        else -> SignInAction.Clear
    }

    sealed interface SignOutAction {
        /**
         * End the session but leave the local data, still recorded as its owner's. Only that
         * owner gets it back (and uploads it) on their next sign-in here; anyone else is asked
         * before it is deleted ([onSignIn]).
         */
        data object SignOutKeepData : SignOutAction

        /** Delete this account's local data, then end the session. */
        data object ClearAndSignOut : SignOutAction

        /** Marks are still not uploaded after the last try: stay signed in and ask the user. */
        data class AskUser(val unsyncedMarks: Int) : SignOutAction
    }

    /**
     * What sign-out does. [unsyncedMarksAfterUpload] is what is still not uploaded after the
     * final upload attempt made while the account is still signed in; [choice] is the user's
     * answer once asked. Demo data is shared by the demo users and never uploaded, so it stays.
     */
    fun onSignOut(
        currentOwner: String?,
        unsyncedMarksAfterUpload: Int,
        choice: UnsyncedMarksChoice,
    ): SignOutAction = when {
        currentOwner == DEMO_OWNER -> SignOutAction.SignOutKeepData
        unsyncedMarksAfterUpload == 0 -> SignOutAction.ClearAndSignOut
        else -> when (choice) {
            UnsyncedMarksChoice.ASK -> SignOutAction.AskUser(unsyncedMarksAfterUpload)
            UnsyncedMarksChoice.KEEP_ON_DEVICE -> SignOutAction.SignOutKeepData
            UnsyncedMarksChoice.DISCARD -> SignOutAction.ClearAndSignOut
        }
    }

    /**
     * Local changes (unsynced marks, master data) may be uploaded only as their owner: a real
     * account that owns the local data and that Firebase Auth is signed in as right now. Demo
     * data and data of another (or an unknown) account are never uploaded.
     */
    fun mayUpload(dataOwner: String?, sessionUid: String?, firebaseUid: String?): Boolean =
        !sessionUid.isNullOrBlank() &&
            dataOwner == sessionUid &&
            firebaseUid == sessionUid

    /**
     * Owner to record at startup for local data whose owner was never recorded (older app
     * versions): the account that is still validly signed in, which the old sync worker
     * uploaded as anyway. With nobody signed in the owner stays unknown.
     */
    fun ownerForUntrackedData(recordedOwner: String?, sessionUid: String?, kind: SessionKind): String? = when {
        recordedOwner != null -> recordedOwner
        sessionUid.isNullOrBlank() || kind == SessionKind.NONE -> null
        else -> ownerFor(sessionUid, demoSession = kind == SessionKind.DEMO)
    }

    /** The demo data is seeded only for demo builds, into an empty database nobody else owns. */
    fun shouldSeedDemo(demoLoginEnabled: Boolean, currentOwner: String?, hasLocalData: Boolean): Boolean =
        demoLoginEnabled && !hasLocalData && (currentOwner == null || currentOwner == DEMO_OWNER)
}
