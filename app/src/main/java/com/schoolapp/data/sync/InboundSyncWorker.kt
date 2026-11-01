package com.schoolapp.data.sync

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.schoolapp.SchoolApplication
import com.schoolapp.domain.repository.SyncOutcome
import com.schoolapp.domain.repository.SyncTrigger
import java.util.concurrent.TimeUnit

/**
 * Background download from the school server (inbound sync), every [INTERVAL_HOURS] hours while
 * the device is online. Uses the app's own sync repository, so it shares the local-data lock
 * and the ownership checks with sign-in and sign-out.
 */
class InboundSyncWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext as? SchoolApplication ?: return Result.success()
        val report = app.container.syncRepository.syncNow(SyncTrigger.PERIODIC)
        return if (report.outcome == SyncOutcome.FAILED) Result.retry() else Result.success()
    }

    companion object {
        private const val WORK_NAME = "inbound_sync_worker"

        /** Bounded per run (SyncLimits); the rest of a large school comes with later runs. */
        const val INTERVAL_HOURS = 6L

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<InboundSyncWorker>(INTERVAL_HOURS, TimeUnit.HOURS)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .setRequiresBatteryNotLow(true)
                        .build(),
                )
                .build()
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.UPDATE, request)
        }
    }
}
