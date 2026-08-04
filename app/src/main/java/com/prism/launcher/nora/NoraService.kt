package com.prism.launcher.nora

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import com.prism.launcher.PrismLogger
import com.prism.launcher.R
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Owns every long-running piece of Nora's work: training, chat generation, and test images.
 *
 * All of it lives here rather than in an Activity for the same reason. Training runs for hours;
 * a still image takes tens of seconds and a clip takes minutes. Anything in an Activity's
 * lifecycleScope dies when the user navigates away, backgrounds the app, or rotates the phone --
 * which is exactly where the "Job was cancelled" replies were coming from.
 *
 * One service, one job at a time. The brain is a single shared object with mutable weights, so
 * running training and generation concurrently would corrupt both; new work is refused while
 * something is already in flight rather than queued, because the honest answer to "generate this
 * while you train" is no.
 *
 * Three things are needed to survive an overnight run, and missing any one means waking up to a
 * job that quietly stopped at 2am: the foreground service itself (background threads in a
 * backgrounded app are killable), a partial wake lock (a foreground service keeps the process
 * alive but not the CPU — without it the device suspends when the screen goes off), and a
 * battery-optimization exemption (Doze throttles even a foreground service over a long idle
 * stretch). The activity prompts for the third; it cannot be granted from code.
 */
class NoraService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null

    /** Rate-limits notification updates; the system throttles callers that post too fast. */
    private var lastNotificationAt = 0L

    private val isWorking: Boolean get() = job?.isActive == true

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopWork("Cancelled.")
                return START_NOT_STICKY
            }

            ACTION_CHAT -> {
                val text = intent.getStringExtra(EXTRA_TEXT).orEmpty()
                if (isWorking) {
                    respondBusy()
                    return START_NOT_STICKY
                }
                startChat(text)
                return START_NOT_STICKY
            }

            ACTION_TEST_IMAGE -> {
                val prompt = intent.getStringExtra(EXTRA_TEXT).orEmpty()
                if (isWorking) return START_NOT_STICKY
                startTestImage(prompt)
                return START_NOT_STICKY
            }

            ACTION_FEEDBACK -> {
                val token = intent.getStringExtra(EXTRA_TOKEN).orEmpty()
                val positive = intent.getBooleanExtra(EXTRA_POSITIVE, true)
                // The rating itself is already durable -- the UI wrote it before starting this
                // service. Nothing is lost by declining to apply it now: NoraFeedback keeps it
                // pending and the next training run or generation collects it.
                if (isWorking || token.isEmpty()) return START_NOT_STICKY
                startFeedback(token, positive)
                return START_NOT_STICKY
            }

            else -> {
                // Training. Redelivery is intentional: if the system does kill this mid-run,
                // it restarts with the same settings and picks up from the last checkpoint.
                if (isWorking) return START_REDELIVER_INTENT
                val epochs = intent?.getIntExtra(EXTRA_EPOCHS, 10) ?: 10
                val focus = intent?.getStringExtra(EXTRA_FOCUS)
                startTraining(epochs, focus)
                return START_REDELIVER_INTENT
            }
        }
    }

    // ── Training ────────────────────────────────────────────────────────────

    private fun startTraining(epochs: Int, focus: String?) {
        beginForeground("Starting…", indeterminate = true)

        NoraTrainingState.markStarted()
        NoraTrainingState.emitLog(
            "Training for $epochs epochs${focus?.let { ", focusing on \"$it\"" } ?: ""}…"
        )

        job = scope.launch {
            val summary = try {
                NoraStudio.train(applicationContext, epochs, focus) { p ->
                    NoraTrainingState.publish(p)
                    if (p.phase == "sleep") {
                        NoraTrainingState.emitLog("epoch ${p.epoch}: sleep — replaying and consolidating")
                    } else if (p.sample % 5 == 0 || p.sample == p.totalSamples) {
                        NoraTrainingState.emitLog(
                            "e${p.epoch}/${p.totalEpochs} · ${p.sample}/${p.totalSamples} · " +
                                "err %.4f · %s".format(p.errorRms, p.caption)
                        )
                    }
                    updateTrainingNotification(p)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                PrismLogger.logError("Nora", "Training failed: ${e.message}", e)
                "Training failed: ${e.message}"
            }
            NoraTrainingState.emitLog(summary)
            NoraTrainingState.markFinished(summary)
            finishWork()
        }
    }

    // ── Chat generation ─────────────────────────────────────────────────────

    private fun startChat(text: String) {
        beginForeground("Imagining…", indeterminate = true)
        NoraChatState.markBusy("Nora is thinking")

        job = scope.launch {
            val reply = try {
                NoraChat.respond(applicationContext, text) { status ->
                    NoraChatState.progress(status)
                    notifyWork("Nora", status)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                PrismLogger.logError("Nora", "Chat turn failed: ${e.message}", e)
                NoraChat.Reply("Something went wrong: ${e.message}")
            }

            // Written here, not in the UI, so a generation that finishes while the conversation
            // is closed still lands in the transcript.
            NoraChatStore.append(
                applicationContext,
                NoraChatStore.Entry(
                    reply.text,
                    isSent = false,
                    attachmentUri = reply.attachmentUri?.toString(),
                    attachmentType = reply.attachmentType,
                    feedbackToken = reply.feedbackToken,
                    // Thumbs start visible on anything rateable. The user asked for them to be
                    // offered, not hidden behind a gesture -- tapping the bubble is how they go
                    // away, and long-pressing is how they come back.
                    showFeedback = reply.feedbackToken != null
                )
            )
            NoraChatState.markIdle()
            NoraChatState.bumpRevision()
            finishWork()
        }
    }

    // ── User feedback ───────────────────────────────────────────────────────

    /**
     * Applies a thumb to the connectome.
     *
     * Runs here rather than in the Activity for the same reason everything else does: it is a
     * replay pass plus a learning step plus a checkpoint write, which is a second or two of CPU
     * and a few hundred kilobytes of IO. Short enough that no progress reporting is warranted,
     * long enough that it has no business on the main thread.
     */
    private fun startFeedback(token: String, positive: Boolean) {
        beginForeground(if (positive) "Reinforcing…" else "Adjusting…", indeterminate = true)

        job = scope.launch {
            val note: String? = try {
                val primary = NoraFeedback.apply(applicationContext, token)
                // Sweep up anything rated while the brain was busy. Doing it here rather than
                // only at the start of a training run means a backlog clears the next time the
                // user touches a thumb, not hours later.
                val backlog = NoraFeedback.applyPending(applicationContext)
                when {
                    primary == null -> null
                    backlog > 0 -> "$primary\n\n(Also applied $backlog rating" +
                        "${if (backlog == 1) "" else "s"} made while I was busy.)"
                    else -> primary
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                PrismLogger.logError("Nora", "Feedback failed: ${e.message}", e)
                null
            }
            if (note != null) {
                NoraChatStore.append(
                    applicationContext,
                    NoraChatStore.Entry(note, isSent = false)
                )
                NoraChatState.bumpRevision()
            }
            finishWork()
        }
    }

    private fun respondBusy() {
        scope.launch {
            NoraChatStore.append(
                applicationContext,
                NoraChatStore.Entry(
                    "I'm busy with something else right now — my cortex only runs one thing at " +
                        "a time. Try again when it finishes.",
                    isSent = false
                )
            )
            NoraChatState.bumpRevision()
        }
    }

    // ── Test image from the training screen ─────────────────────────────────

    private fun startTestImage(prompt: String) {
        beginForeground("Generating…", indeterminate = true)
        NoraTrainingState.emitLog("Generating \"$prompt\"…")

        job = scope.launch {
            val result = try {
                NoraStudio.generateImage(applicationContext, prompt) { done, total ->
                    NoraTrainingState.emitLog("  saccade $done/$total")
                    notifyWork("Nora", "Saccade $done of $total")
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                NoraStudio.Generated(null, null, "Generation failed: ${e.message}")
            }
            NoraTrainingState.emitLog(
                result.file?.let { "Saved ${it.name}\n${result.note}" } ?: result.note
            )
            finishWork()
        }
    }

    // ── Lifecycle plumbing ──────────────────────────────────────────────────

    private fun beginForeground(text: String, indeterminate: Boolean) {
        startForeground(NOTIFICATION_ID, buildNotification("Nora", text, 0, indeterminate))
        acquireWakeLock()
    }

    private fun finishWork() {
        job = null
        releaseWakeLock()
        stopForegroundCompat()
        stopSelf()
    }

    private fun stopWork(message: String) {
        job?.cancel()
        job = null
        if (NoraTrainingState.running.value) {
            NoraTrainingState.emitLog(message)
            NoraTrainingState.markFinished(message)
        }
        NoraChatState.markIdle()
        releaseWakeLock()
        stopForegroundCompat()
        stopSelf()
    }

    override fun onDestroy() {
        job?.cancel()
        releaseWakeLock()
        scope.cancel()
        // If the process is going down mid-run, say so rather than leaving the UI showing a
        // spinner for a job that no longer exists.
        if (NoraTrainingState.running.value) {
            NoraTrainingState.markFinished("Training stopped — the service was shut down.")
        }
        NoraChatState.markIdle()
        super.onDestroy()
    }

    @Suppress("DEPRECATION")
    private fun stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            stopForeground(true)
        }
    }

    // ── Wake lock ───────────────────────────────────────────────────────────

    private fun acquireWakeLock() {
        if (wakeLock != null) return
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Prism:Nora").apply {
                setReferenceCounted(false)
                // No timeout: an overnight run is exactly the case this exists for. The lock is
                // released in every exit path -- completion, cancellation, and onDestroy.
                acquire()
            }
        } catch (e: Exception) {
            PrismLogger.logError("Nora", "Could not acquire wake lock: ${e.message}")
        }
    }

    private fun releaseWakeLock() {
        try {
            wakeLock?.let { if (it.isHeld) it.release() }
        } catch (e: Exception) {
            // Already released, or never successfully acquired.
        }
        wakeLock = null
    }

    // ── Notification ────────────────────────────────────────────────────────

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Nora",
            // LOW: visible and persistent, but silent. An overnight job must not make a sound
            // every time it finishes an image.
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Progress while Nora is training or generating."
            setShowBadge(false)
            enableVibration(false)
            setSound(null, null)
        }
        manager.createNotificationChannel(channel)
    }

    private fun updateTrainingNotification(p: NoraTrainer.Progress) {
        if (!throttle()) return
        val text = if (p.phase == "sleep") {
            "Epoch ${p.epoch} of ${p.totalEpochs} · consolidating"
        } else {
            "Epoch ${p.epoch}/${p.totalEpochs} · ${p.sample}/${p.totalSamples} · err %.4f"
                .format(p.errorRms)
        }
        notify(buildNotification("Nora is training", text, NoraTrainingState.percent, false))
    }

    private fun notifyWork(title: String, text: String) {
        if (!throttle()) return
        notify(buildNotification(title, text, 0, true))
    }

    /** One update per second is plenty and stays clear of the system's post-rate limit. */
    private fun throttle(): Boolean {
        val now = System.currentTimeMillis()
        if (now - lastNotificationAt < 1000L) return false
        lastNotificationAt = now
        return true
    }

    private fun notify(notification: Notification) {
        try {
            getSystemService(NotificationManager::class.java)?.notify(NOTIFICATION_ID, notification)
        } catch (e: Exception) {
            // Notifications denied. The work continues regardless -- the notification is a
            // progress readout, not a dependency.
        }
    }

    private fun buildNotification(
        title: String,
        text: String,
        percent: Int,
        indeterminate: Boolean
    ): Notification {
        val pendingFlags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE

        val openIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, NoraTrainingActivity::class.java).setFlags(
                // SINGLE_TOP plus the activity's launchMode means tapping returns to the
                // existing screen rather than stacking a second copy of it.
                Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            ),
            pendingFlags
        )

        val stopIntent = PendingIntent.getService(
            this, 1,
            Intent(this, NoraService::class.java).setAction(ACTION_STOP),
            pendingFlags
        )

        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }

        return builder
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setSmallIcon(R.drawable.ic_wand_24)
            .setContentIntent(openIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setProgress(100, percent, indeterminate)
            .addAction(
                Notification.Action.Builder(
                    null as android.graphics.drawable.Icon?, "Stop", stopIntent
                ).build()
            )
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "nora_training"
        private const val NOTIFICATION_ID = 4711

        private const val ACTION_STOP = "com.prism.launcher.nora.STOP"
        private const val ACTION_CHAT = "com.prism.launcher.nora.CHAT"
        private const val ACTION_TEST_IMAGE = "com.prism.launcher.nora.TEST_IMAGE"
        private const val ACTION_FEEDBACK = "com.prism.launcher.nora.FEEDBACK"

        private const val EXTRA_EPOCHS = "epochs"
        private const val EXTRA_FOCUS = "focus"
        private const val EXTRA_TEXT = "text"
        private const val EXTRA_TOKEN = "token"
        private const val EXTRA_POSITIVE = "positive"

        fun startTraining(ctx: Context, epochs: Int, focus: String?) = launch(
            ctx,
            Intent(ctx, NoraService::class.java)
                .putExtra(EXTRA_EPOCHS, epochs)
                .putExtra(EXTRA_FOCUS, focus)
        )

        fun sendChat(ctx: Context, text: String) = launch(
            ctx,
            Intent(ctx, NoraService::class.java)
                .setAction(ACTION_CHAT)
                .putExtra(EXTRA_TEXT, text)
        )

        fun sendFeedback(ctx: Context, token: String, positive: Boolean) = launch(
            ctx,
            Intent(ctx, NoraService::class.java)
                .setAction(ACTION_FEEDBACK)
                .putExtra(EXTRA_TOKEN, token)
                .putExtra(EXTRA_POSITIVE, positive)
        )

        fun testImage(ctx: Context, prompt: String) = launch(
            ctx,
            Intent(ctx, NoraService::class.java)
                .setAction(ACTION_TEST_IMAGE)
                .putExtra(EXTRA_TEXT, prompt)
        )

        fun stop(ctx: Context) {
            try {
                ctx.startService(Intent(ctx, NoraService::class.java).setAction(ACTION_STOP))
            } catch (e: Exception) {
                // Service already gone; nothing to stop.
            }
        }

        private fun launch(ctx: Context, intent: Intent) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ctx.startForegroundService(intent)
            } else {
                ctx.startService(intent)
            }
        }
    }
}
