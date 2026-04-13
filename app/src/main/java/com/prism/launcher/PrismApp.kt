package com.prism.launcher

import android.app.Application
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.prism.launcher.browser.PrismTunnelEngine

class PrismApp : Application() {

    /** Global mesh proxy engine — lives for the entire app lifetime so P2P DNS works
     *  in both public and private browsing modes, regardless of VPN state. */
    lateinit var tunnelEngine: PrismTunnelEngine
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        
        // Initialize Terminal Diagnostics & Crash Interceptor
        PrismLogger.init(this)
        
        tunnelEngine = PrismTunnelEngine(this)
        tunnelEngine.start()

        // Seed the installed-apps DB once ever (KEEP = skip if already queued or running).
        WorkManager.getInstance(this).enqueueUniqueWork(
            AppSyncWorker.WORK_NAME,
            ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<AppSyncWorker>().build(),
        )

        // Start the AI Social Media Bot cycle
        com.prism.launcher.social.SocialBotWorker.schedule(this)
    }

    companion object {
        lateinit var instance: PrismApp
            private set

        fun get(app: android.app.Application) = (app as PrismApp).tunnelEngine
    }
}

