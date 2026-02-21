package com.schoolapp.data.sync

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import com.schoolapp.data.local.SchoolDatabase
import kotlinx.coroutines.tasks.await
import java.util.concurrent.TimeUnit

class ScoreSyncWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val db = SchoolDatabase.get(applicationContext)
        val firestore = runCatching { FirebaseFirestore.getInstance() }.getOrNull()
            ?: return Result.success()

        return runCatching {
            val unsynced = db.scoreDao().getUnsynced()
            unsynced.forEach { score ->
                firestore.collection("schools")
                    .document("school_001")
                    .collection("scores")
                    .document(score.scoreId)
                    .set(
                        mapOf(
                            "scoreId" to score.scoreId,
                            "assessmentId" to score.assessmentId,
                            "studentId" to score.studentId,
                            "obtainedMarks" to score.obtainedMarks,
                            "status" to score.status,
                        ),
                        SetOptions.merge(),
                    )
                    .await()
                db.scoreDao().markSynced(score.scoreId)
            }
        }.fold(
            onSuccess = { Result.success() },
            onFailure = { Result.retry() },
        )
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
