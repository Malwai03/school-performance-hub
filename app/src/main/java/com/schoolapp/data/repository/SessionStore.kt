package com.schoolapp.data.repository

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.google.firebase.auth.FirebaseAuth
import com.schoolapp.core.logic.LocalDataPolicy
import com.schoolapp.core.logic.SchoolScope
import com.schoolapp.data.local.SchoolDatabase
import kotlinx.coroutines.flow.firstOrNull

private val Context.sessionDataStore by preferencesDataStore(name = "session")

class SessionStore(
    private val context: Context,
) {
    private val uidKey = stringPreferencesKey("uid")

    /** Whose data the local Room database holds (see LocalDataPolicy). */
    private val dataOwnerKey = stringPreferencesKey("localDataOwner")

    suspend fun saveUserId(uid: String) {
        context.sessionDataStore.edit { prefs ->
            prefs[uidKey] = uid
        }
    }

    suspend fun getUserId(): String? {
        return context.sessionDataStore.data.firstOrNull()?.get(uidKey)
    }

    suspend fun clear() {
        context.sessionDataStore.edit { prefs ->
            prefs.remove(uidKey)
        }
    }

    suspend fun getDataOwner(): String? {
        return context.sessionDataStore.data.firstOrNull()?.get(dataOwnerKey)
    }

    suspend fun saveDataOwner(owner: String) {
        context.sessionDataStore.edit { prefs ->
            prefs[dataOwnerKey] = owner
        }
    }

    suspend fun clearDataOwner() {
        context.sessionDataStore.edit { prefs ->
            prefs.remove(dataOwnerKey)
        }
    }
}

/** schoolId cached on the Room row of the user this device is signed in as, if any. */
internal suspend fun storedSessionSchoolId(db: SchoolDatabase, sessionStore: SessionStore): String? {
    val uid = sessionStore.getUserId() ?: return null
    return SchoolScope.sessionSchoolIdOrNull(db.userDao().getByUid(uid)?.schoolId)
}

/**
 * School to upload the signed-in account's own local changes to, or null when they have to stay
 * on the device: no real Firebase session, a demo session, or local data owned by someone else.
 */
internal suspend fun uploadSchoolIdOrNull(
    db: SchoolDatabase,
    sessionStore: SessionStore,
    auth: FirebaseAuth?,
): String? {
    val sessionUid = sessionStore.getUserId()
    val firebaseUid = auth?.currentUser?.uid
    if (!LocalDataPolicy.mayUpload(sessionStore.getDataOwner(), sessionUid, firebaseUid)) return null
    return storedSessionSchoolId(db, sessionStore)
}
