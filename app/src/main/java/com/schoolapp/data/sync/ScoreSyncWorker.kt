package com.schoolapp.data.sync

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.schoolapp.data.local.SchoolDatabase
import com.schoolapp.data.repository.SessionStore
import com.schoolapp.data.repository.uploadSchoolIdOrNull
import java.util.concurrent.TimeUnit

class ScoreSyncWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val db = SchoolDatabase.get(applicationContext)
        val firestore = runCatching { FirebaseFirestore.getInstance() }.getOrNull()
            ?: return Result.success()
        val auth = runCatching { FirebaseAuth.getInstance() }.getOrNull()
        val sessionStore = SessionStore(applicationContext)

        // Marks are only uploaded as the account that owns them, to that account's school.
        // Nobody signed in, a demo session, or someone else's data: leave them alone.
        val schoolId = uploadSchoolIdOrNull(db, sessionStore, auth) ?: return Result.success()
        val uid = sessionStore.getUserId() ?: return Result.success()

        val stillUnsynced = ScoreUploader.uploadUnsynced(db, firestore, schoolId) {
            auth?.currentUser?.uid == uid
        }
        return if (stillUnsynced == 0) Result.success() else Result.retry()
    }

    companion object {
        private const val WORK_NAME = "score_sync_worker"

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<ScoreSyncWorker>(15, TimeUnit.MINUTES).build()
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.UPDATE, request)
        }
    }
}
