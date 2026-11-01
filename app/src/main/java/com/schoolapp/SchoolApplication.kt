package com.schoolapp

import android.app.Application
import androidx.work.Configuration
import com.schoolapp.data.repository.AppContainer
import com.schoolapp.data.sync.InboundSyncWorker
import com.schoolapp.data.sync.ScoreSyncWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class SchoolApplication : Application(), Configuration.Provider {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        CoroutineScope(Dispatchers.IO).launch {
            container.prepareLocalData()
        }
        ScoreSyncWorker.schedule(this)
        InboundSyncWorker.schedule(this)
    }

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setMinimumLoggingLevel(android.util.Log.INFO).build()
}
