package com.prism.launcher

import android.app.Application
import android.content.Intent
import android.content.IntentFilter
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.prism.launcher.browser.PrismTunnelEngine
import com.prism.launcher.browser.DnsProxyService

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

        // Nora's unattended retraining cycle. Cancels itself if the option is off or the
        // Messages page isn't on a desktop slot, so calling this unconditionally is correct.
        com.prism.launcher.nora.NoraAutoTrainWorker.schedule(this)

        // Model download completion — application-scoped so a download started from any screen
        // (Settings, Model Store) finishes importing even if the user has navigated away.
        registerReceiver(
            com.prism.launcher.messaging.ModelDownloadManager.completionReceiver,
            IntentFilter(android.app.DownloadManager.ACTION_DOWNLOAD_COMPLETE),
            RECEIVER_EXPORTED
        )

        // Decentralized Mesh Components
        com.prism.launcher.browser.P2pDnsManager.init(this)
        com.prism.launcher.mesh.PrismMeshService.start()

        // DNS Proxy Service (Access Point mode)
        if (PrismSettings.getDnsProxyEnabled(this)) {
            startService(Intent(this, DnsProxyService::class.java))
        }
    }

    companion object {
        lateinit var instance: PrismApp
            private set

        fun get(app: android.app.Application) = (app as PrismApp).tunnelEngine
    }
}

