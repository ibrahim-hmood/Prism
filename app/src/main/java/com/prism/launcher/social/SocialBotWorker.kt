package com.prism.launcher.social

import android.content.Context
import androidx.work.*
import java.util.concurrent.TimeUnit

class SocialBotWorker(context: Context, workerParams: WorkerParameters) : CoroutineWorker(context, workerParams) {

    override suspend fun doWork(): Result {
        return try {
            NebulaSocialManager.generateNewContent(applicationContext, manual = false)
            Result.success()
        } catch (e: Exception) {
            Result.retry()
        }
    }

    companion object {
        private const val WORK_NAME = "NebulaSocialSync"

        fun schedule(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                // Constraints are enforced for Local models inside NebulaSocialManager,
                // but we can set them here too for better efficiency.
                .build()

            val request = PeriodicWorkRequestBuilder<SocialBotWorker>(1, TimeUnit.HOURS)
                .setConstraints(constraints)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.MINUTES)
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
        }
    }
}
