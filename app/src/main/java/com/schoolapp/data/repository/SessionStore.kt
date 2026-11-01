package com.schoolapp.data.repository

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.schoolapp.core.logic.SchoolScope
import com.schoolapp.data.local.SchoolDatabase
import kotlinx.coroutines.flow.firstOrNull

private val Context.sessionDataStore by preferencesDataStore(name = "session")

class SessionStore(
    private val context: Context,
) {
    private val uidKey = stringPreferencesKey("uid")

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
}

/** schoolId cached on the Room row of the user this device is signed in as, if any. */
internal suspend fun storedSessionSchoolId(db: SchoolDatabase, sessionStore: SessionStore): String? {
    val uid = sessionStore.getUserId() ?: return null
    return SchoolScope.sessionSchoolIdOrNull(db.userDao().getByUid(uid)?.schoolId)
}
