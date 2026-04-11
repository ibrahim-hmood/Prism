package com.prism.launcher

import android.app.Application
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager

class PrismApp : Application() {

    override fun onCreate() {
        super.onCreate()
        // Seed the installed-apps DB once ever (KEEP = skip if already queued or running).
        // AppPackageReceiver handles all incremental changes after first run.
        WorkManager.getInstance(this).enqueueUniqueWork(
            AppSyncWorker.WORK_NAME,
            ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<AppSyncWorker>().build(),
        )
    }
}

