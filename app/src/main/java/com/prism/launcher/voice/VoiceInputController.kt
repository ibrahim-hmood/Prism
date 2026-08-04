package com.prism.launcher.voice

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.widget.EditText
import android.widget.ImageButton
import android.widget.Toast
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.widget.ImageViewCompat
import com.prism.launcher.PrismLogger

/**
 * Dictation for a single text field: a mic button that toggles to a stop button while listening.
 *
 * One of these is bound to each input surface in the app — Messages (SMS, Sam, Nora), Nebula's
 * composer, its comment and nested-reply boxes, and its bot conversations. The behaviour is
 * identical everywhere; the only thing that varies is [autoSubmit].
 *
 * WHY [SpeechRecognizer] AND NOT [RecognizerIntent.ACTION_RECOGNIZE_SPEECH]. The intent form is
 * far less code, but it hands control to a system dialog with its own listening UI and its own
 * stop affordance — which is not what was asked for, and would mean the mic button never becomes
 * a stop button because the button is not what the user is looking at. It also cannot be driven
 * from a plain View, and two of the five surfaces here (Nebula's chat and reply boxes) are views
 * inside the launcher rather than Activities. [SpeechRecognizer] keeps the control in our button.
 *
 * DICTATION APPENDS, IT DOES NOT REPLACE. Whatever was already typed is kept as a prefix and the
 * transcript is added after it, so speaking into a half-written message extends it rather than
 * destroying it.
 */
class VoiceInputController(
    private val micButton: ImageButton,
    private val input: EditText,
    /**
     * Invoked after a successful transcript is placed in the field, or null to leave it there.
     *
     * Non-null on AI conversations, where dictation is a prompt and the point is to speak and be
     * answered. Null on SMS and on anything published to Nebula, where the text is a message to a
     * person and the user gets to read it back before it goes anywhere.
     *
     * Hosts pass their existing send action — usually `sendButton.performClick()` — rather than a
     * duplicate of it, so dictated sends go down exactly the same path as typed ones and cannot
     * drift away from it.
     */
    private val autoSubmit: (() -> Unit)? = null
) {

    /**
     * Host-supplied permission request.
     *
     * An Activity sets this to something backed by an ActivityResultLauncher, which can call
     * [start] the moment permission is granted. A View host leaves it null and gets the fallback
     * below, which asks through the host Activity but cannot be called back — so it says "tap
     * again" rather than pretending it will start on its own.
     */
    var permissionRequester: (() -> Unit)? = null

    private val context: Context get() = micButton.context

    private var recognizer: SpeechRecognizer? = null
    private var listening = false

    /** Text already in the field when listening began. The transcript is appended to it. */
    private var prefix = ""

    /** Set between stopListening() and the result callback, when neither icon state is honest. */
    private var transcribing = false

    /**
     * The button's resting tint, captured so it can be restored after the red recording state.
     *
     * Read through [ImageViewCompat] rather than [ImageButton.getImageTintList]. Every one of
     * these buttons sets its colour with `app:tint` in XML, which AppCompat routes to
     * `setSupportImageTintList` on API levels where the platform property is unreliable -- so the
     * platform getter can return null even though the button is visibly tinted. Capturing null
     * would mean the mic came back untinted after its first use.
     */
    private val idleTint: ColorStateList? = ImageViewCompat.getImageTintList(micButton)

    private val handler = android.os.Handler(android.os.Looper.getMainLooper())

    /**
     * Forces the button back to idle if the recognizer never calls back.
     *
     * Not defensive padding. The silence-length extras below are documented as hints an
     * implementation may ignore, and recognizer services are third-party and occasionally drop a
     * session without ever reporting a result or an error. A stop button that can never become a
     * mic button again is exactly the failure that would make dictation feel broken, so it is
     * given an outer bound.
     */
    private val transcriptionTimeout = Runnable {
        if (transcribing || listening) {
            PrismLogger.logError("Voice", "Recognizer never returned a result; resetting")
            finish(null)
        }
    }

    init {
        micButton.setOnClickListener { toggle() }
        applyIdleIcon()
    }

    fun toggle() {
        when {
            transcribing -> Unit          // already converting; a second press would do nothing useful
            listening -> stop()
            else -> start()
        }
    }

    fun start() {
        if (listening || transcribing) return

        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermission()
            return
        }

        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            toast("No speech recognition service is available on this device.")
            return
        }

        val r = recognizer ?: try {
            SpeechRecognizer.createSpeechRecognizer(context).also {
                it.setRecognitionListener(listener)
                recognizer = it
            }
        } catch (e: Exception) {
            PrismLogger.logError("Voice", "Could not create recognizer: ${e.message}", e)
            toast("Couldn't start the microphone.")
            return
        }

        prefix = input.text?.toString()?.trimEnd()?.let { if (it.isEmpty()) "" else "$it " } ?: ""

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
            // The user decides when to stop, so the recognizer is asked to sit through long
            // pauses rather than deciding a thought has ended. These are hints the service may
            // ignore, which is what the timeout above exists for.
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, SILENCE_MS)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, SILENCE_MS)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 1500L)
        }

        return try {
            r.startListening(intent)
            listening = true
            applyListeningIcon()
        } catch (e: Exception) {
            PrismLogger.logError("Voice", "startListening failed: ${e.message}", e)
            toast("Couldn't start listening.")
        }
    }

    /**
     * Stops recording and converts what was captured.
     *
     * The icon does NOT flip here. Transcription takes a moment, and returning the button to a
     * mic before the text arrives would invite a second press against a session that is still
     * finishing. It flips when the result lands, in [finish].
     */
    fun stop() {
        if (!listening) return
        listening = false
        transcribing = true
        applyTranscribingIcon()
        handler.postDelayed(transcriptionTimeout, TRANSCRIBE_TIMEOUT_MS)
        try {
            recognizer?.stopListening()
        } catch (e: Exception) {
            PrismLogger.logError("Voice", "stopListening failed: ${e.message}")
            finish(null)
        }
    }

    /** Abandons the session without transcribing. Used when the surface goes away mid-listen. */
    fun cancel() {
        if (!listening && !transcribing) return
        try {
            recognizer?.cancel()
        } catch (e: Exception) {
            // Nothing to recover; the reset below is what matters.
        }
        restorePrefixOnly()
        resetState()
    }

    /**
     * Releases the recognizer. Hosts must call this — an Activity from onDestroy, a View from
     * onDetachedFromWindow — because SpeechRecognizer holds a binding to an out-of-process
     * service and a live microphone session.
     */
    fun release() {
        handler.removeCallbacks(transcriptionTimeout)
        try {
            recognizer?.destroy()
        } catch (e: Exception) {
            // Already destroyed.
        }
        recognizer = null
        resetState()
    }

    // ── Result handling ─────────────────────────────────────────────────────

    /** @param text the transcript, or null when the session produced nothing usable. */
    private fun finish(text: String?) {
        handler.removeCallbacks(transcriptionTimeout)
        resetState()

        val spoken = text?.trim().orEmpty()
        if (spoken.isEmpty()) {
            // Roll back any partial results that were shown live, so a session that heard
            // nothing leaves the field exactly as it was found.
            restorePrefixOnly()
            return
        }

        input.setText(prefix + spoken)
        input.setSelection(input.text?.length ?: 0)
        autoSubmit?.invoke()
    }

    private fun restorePrefixOnly() {
        input.setText(prefix.trimEnd())
        input.setSelection(input.text?.length ?: 0)
    }

    private fun resetState() {
        listening = false
        transcribing = false
        applyIdleIcon()
    }

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {}
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}

        /**
         * The recognizer decided speech ended on its own, despite the silence hints.
         * Treated as if stop had been pressed, so the result still lands in the field.
         */
        override fun onEndOfSpeech() {
            if (listening) {
                listening = false
                transcribing = true
                applyTranscribingIcon()
                handler.postDelayed(transcriptionTimeout, TRANSCRIBE_TIMEOUT_MS)
            }
        }

        override fun onPartialResults(partialResults: Bundle?) {
            val partial = partialResults
                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull()
                ?.takeIf { it.isNotBlank() } ?: return
            // Live feedback while speaking. Never auto-submitted — only a final result is.
            input.setText(prefix + partial)
            input.setSelection(input.text?.length ?: 0)
        }

        override fun onResults(results: Bundle?) {
            finish(results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull())
        }

        override fun onError(error: Int) {
            val message = when (error) {
                SpeechRecognizer.ERROR_NO_MATCH,
                SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Didn't catch that."
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Microphone access is needed."
                SpeechRecognizer.ERROR_NETWORK,
                SpeechRecognizer.ERROR_NETWORK_TIMEOUT ->
                    "Speech recognition needs a network connection on this device."
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "The recognizer is busy — try again."
                SpeechRecognizer.ERROR_AUDIO -> "Couldn't record audio."
                else -> null
            }
            finish(null)
            if (message != null) toast(message)
        }

        override fun onEvent(eventType: Int, params: Bundle?) {}
    }

    // ── Button appearance ───────────────────────────────────────────────────

    private fun applyIdleIcon() {
        micButton.setImageResource(com.prism.launcher.R.drawable.ic_mic_24)
        ImageViewCompat.setImageTintList(micButton, idleTint)
        micButton.alpha = 1f
        micButton.contentDescription = "Dictate"
    }

    private fun applyListeningIcon() {
        micButton.setImageResource(com.prism.launcher.R.drawable.ic_stop_24)
        // Red while live, regardless of the surface's own accent. A recording indicator is one
        // of the few places where a universal convention beats matching the local palette.
        ImageViewCompat.setImageTintList(micButton, ColorStateList.valueOf(RECORDING_TINT))
        micButton.alpha = 1f
        micButton.contentDescription = "Stop and transcribe"
    }

    private fun applyTranscribingIcon() {
        micButton.setImageResource(com.prism.launcher.R.drawable.ic_stop_24)
        ImageViewCompat.setImageTintList(micButton, ColorStateList.valueOf(RECORDING_TINT))
        micButton.alpha = 0.45f
        micButton.contentDescription = "Transcribing"
    }

    // ── Permission ──────────────────────────────────────────────────────────

    private fun requestPermission() {
        permissionRequester?.let { it(); return }

        // Fallback for View hosts, which cannot register an ActivityResultLauncher. The request
        // still goes through the host Activity, but there is no callback to hang a start on, so
        // this says so instead of leaving the user waiting for something that will not happen.
        val activity = findActivity()
        if (activity == null) {
            toast("Microphone access is needed for dictation.")
            return
        }
        ActivityCompat.requestPermissions(
            activity, arrayOf(Manifest.permission.RECORD_AUDIO), REQUEST_CODE
        )
        toast("Allow microphone access, then tap the mic again.")
    }

    private fun findActivity(): Activity? {
        var c: Context? = context
        while (c is ContextWrapper) {
            if (c is Activity) return c
            c = c.baseContext
        }
        return null
    }

    private fun toast(message: String) {
        try {
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            // No usable context to toast from; the log line is enough.
            PrismLogger.logError("Voice", message)
        }
    }

    companion object {
        const val REQUEST_CODE = 7331

        /** How long the recognizer is asked to tolerate silence before deciding speech ended. */
        private const val SILENCE_MS = 10_000L

        /** Outer bound on transcription before the button is forced back to idle. */
        private const val TRANSCRIBE_TIMEOUT_MS = 10_000L

        private const val RECORDING_TINT = 0xFFFF3B30.toInt()   // iOS system red

        val PERMISSION: String = Manifest.permission.RECORD_AUDIO
    }
}
