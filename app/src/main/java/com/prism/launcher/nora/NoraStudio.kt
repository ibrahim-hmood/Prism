package com.prism.launcher.nora

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.provider.MediaStore
import com.prism.launcher.PrismLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

/**
 * The Prism-facing surface of Nora. Everything the launcher touches goes through here.
 *
 * One brain instance for the process, lazily built and lazily loaded from disk. Building the
 * connectome allocates the Gabor bank, the orientation map, and all the link weights, which is
 * on the order of a second -- worth doing once.
 */
object NoraStudio {

    @Volatile
    private var brainInstance: NoraBrain? = null

    /** True while a generation or training run holds the brain. */
    @Volatile
    var busy: Boolean = false
        private set

    /** Whether a connectome was actually loaded, as opposed to merely existing on disk. */
    @Volatile
    private var connectomeLoaded = false

    fun brain(ctx: Context): NoraBrain {
        brainInstance?.let { return it }
        return synchronized(this) {
            brainInstance ?: NoraBrain().also { b ->
                connectomeLoaded = NoraPersistence.load(ctx, b)
                if (connectomeLoaded) {
                    PrismLogger.logInfo(
                        "Nora", "Connectome loaded (${NoraPersistence.sizeBytes(ctx) / 1024} KB)"
                    )
                } else if (NoraPersistence.exists(ctx)) {
                    PrismLogger.logInfo(
                        "Nora",
                        "A connectome file exists but was rejected -- wrong version, changed " +
                            "geometry, or non-finite weights. Erase it and retrain."
                    )
                } else {
                    PrismLogger.logInfo("Nora", "No connectome on disk -- starting from a naive brain")
                }
                brainInstance = b
            }
        }
    }

    /**
     * True only when a connectome was successfully loaded or trained this session.
     *
     * Deliberately not "does the file exist": a rejected file left Nora reporting herself as
     * trained while running on naive weights, which is precisely the kind of confidently wrong
     * status that made the divergence bug so hard to see.
     */
    fun isTrained(ctx: Context): Boolean {
        brain(ctx)
        return connectomeLoaded
    }

    /** Called by the trainer once a healthy checkpoint has been written. */
    fun markTrained() {
        connectomeLoaded = true
    }

    /**
     * Drops the in-memory brain so the next access reloads from disk.
     *
     * Needed after an import: the files on storage have changed underneath a brain that is
     * still holding the old weights, and without this the restored connectome would not take
     * effect until the process restarted.
     */
    fun reload(ctx: Context) {
        synchronized(this) {
            brainInstance = null
            connectomeLoaded = false
            NoraHealth.reset()
        }
    }

    fun status(ctx: Context): String = brain(ctx).status()

    /** Forgets everything. Deletes the connectome and drops the in-memory brain. */
    fun forget(ctx: Context) {
        synchronized(this) {
            NoraPersistence.deleteConnectome(ctx)
            // Feedback traces reference IT patterns from a connectome that no longer exists.
            // Applying one to a naive brain would reinforce noise, so they go with it.
            NoraFeedback.clear(ctx)
            brainInstance = null
            connectomeLoaded = false
            NoraHealth.reset()
        }
    }

    // ── Generation ──────────────────────────────────────────────────────────

    /**
     * @param feedbackToken identifies the trace this generation left behind, so a thumb pressed
     *        later can be routed back to the cortical state that produced it. Null when the
     *        trace could not be written -- the UI then offers no thumbs rather than buttons that
     *        would silently do nothing.
     */
    data class Generated(
        val uri: Uri?,
        val file: File?,
        val note: String,
        val feedbackToken: String? = null
    )

    suspend fun generateImage(
        ctx: Context,
        prompt: String,
        mode: NoraImageryMode = NoraImageryMode.DETERMINISTIC,
        onProgress: ((Int, Int) -> Unit)? = null
    ): Generated = withContext(Dispatchers.Default) {
        busy = true
        try {
            val b = brain(ctx)
            val grounding = b.groundingOf(prompt)
            val imagery = MentalImagery(b)
            val bias = NoraFeedback.biasFor(ctx, prompt)
            val bitmap = imagery.generateStill(prompt, mode = mode, bias = bias, onProgress = onProgress)

            val file = File(NoraConfig.outputDir(ctx), "nora_${System.currentTimeMillis()}.png")
            FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            val uri = publishImage(ctx, bitmap)
            bitmap.recycle()

            Generated(uri, file, groundingNote(ctx, prompt, grounding, bias), traceFor(ctx, prompt, imagery, b))
        } catch (e: Exception) {
            PrismLogger.logError("Nora", "Image generation failed: ${e.message}", e)
            Generated(null, null, "Generation failed: ${e.message}")
        } finally {
            busy = false
        }
    }

    /**
     * Files the trace a later thumbs-up or thumbs-down will act on.
     *
     * Records the concept the image was ACTUALLY built from rather than re-deriving it from the
     * prompt: hippocampal recall settles stochastically, so a second derivation is not
     * guaranteed to land on the same pattern, and reinforcing a pattern the user never saw would
     * be worse than not reinforcing at all.
     */
    private fun traceFor(
        ctx: Context,
        prompt: String,
        imagery: MentalImagery,
        b: NoraBrain
    ): String? {
        val concept = imagery.lastConcept ?: return null
        return NoraFeedback.record(
            ctx,
            prompt = prompt,
            caption = prompt,
            semantic = b.semanticHub.encode(prompt),
            itPattern = concept
        )
    }

    suspend fun generateVideo(
        ctx: Context,
        prompt: String,
        frames: Int = NoraConfig.VIDEO_FRAMES,
        motion: String? = "expansion",
        onProgress: ((Int, Int) -> Unit)? = null
    ): Generated = withContext(Dispatchers.Default) {
        busy = true
        try {
            val b = brain(ctx)
            val grounding = b.groundingOf(prompt)
            val imagery = MentalImagery(b)
            val bias = NoraFeedback.biasFor(ctx, prompt)
            val bitmaps = imagery.generateVideo(prompt, frames, motion, bias, onProgress)
            if (bitmaps.isEmpty()) return@withContext Generated(null, null, "No frames were produced.")

            val file = File(NoraConfig.outputDir(ctx), "nora_${System.currentTimeMillis()}.mp4")
            val written = NoraVideoWriter.write(bitmaps, file)
            val uri = written?.let { publishVideo(ctx, it) }
            for (bm in bitmaps) bm.recycle()

            if (written == null) {
                Generated(null, null, "Frames generated but the encoder failed -- see diagnostics.")
            } else {
                Generated(
                    uri, written,
                    groundingNote(ctx, prompt, grounding, bias),
                    traceFor(ctx, prompt, imagery, b)
                )
            }
        } catch (e: Exception) {
            PrismLogger.logError("Nora", "Video generation failed: ${e.message}", e)
            Generated(null, null, "Generation failed: ${e.message}")
        } finally {
            busy = false
        }
    }

    /**
     * Tells the user honestly how grounded their prompt is.
     *
     * A prompt made entirely of words Nora has never been trained on will produce something,
     * but it will not be a picture of what was asked for -- the semantic hub has nothing to
     * associate, so IT gets a near-empty pattern and generation runs off the priors alone.
     * Saying so is better than shipping noise and letting the user guess why.
     */
    private fun groundingNote(ctx: Context, prompt: String, grounding: Float, bias: Float): String {
        val feedbackNote = when {
            bias <= -0.15f ->
                "\n\nYou've told me this one was wrong before, so I started somewhere else " +
                    "rather than settling back into the same picture."
            bias >= 0.15f ->
                "\n\nYou've liked this before, so I held the concept harder and let it settle " +
                    "closer to what worked."
            else -> ""
        }
        return groundingText(ctx, prompt, grounding) + feedbackNote
    }

    private fun groundingText(ctx: Context, prompt: String, grounding: Float): String {
        if (!isTrained(ctx)) {
            return "I haven't been trained yet, so this is what an untrained visual cortex " +
                "produces -- structure without content. Train me from the Nora page first."
        }
        val hub = brain(ctx).semanticHub
        // Same tokenizer the encoder uses, and an exact vocabulary lookup rather than a
        // membership test against the 400 most frequent words -- that ranking reported any
        // rare-but-learned word as unknown.
        val words = hub.tokenize(prompt)
        val unknown = words.filter { !hub.knows(it) }
        return when {
            hub.knownWords() == 0 ->
                "My vocabulary is empty — training ran but nothing was bound to a caption. " +
                    "Check the training log for the 'taught nothing' warning."
            grounding < 0.02f && unknown.size == words.size ->
                "None of those words are grounded in anything I've seen. This is essentially " +
                    "unconditioned -- teach me with images named after what they show."
            grounding < 0.02f ->
                "I know those words but they aren't bound to any visual pattern yet, so this " +
                    "is mostly prior. More epochs on those images should fix it."
            unknown.isNotEmpty() ->
                "Grounded on ${words.size - unknown.size} of ${words.size} words " +
                    "(new to me: ${unknown.joinToString(", ")})."
            else -> "Grounded on all ${words.size} words."
        }
    }

    // ── Training ────────────────────────────────────────────────────────────

    suspend fun train(
        ctx: Context,
        epochs: Int,
        focus: String? = null,
        onProgress: (NoraTrainer.Progress) -> Unit
    ): String {
        busy = true
        return try {
            NoraTrainer(brain(ctx)).train(ctx, epochs, focus, onProgress)
        } finally {
            busy = false
        }
    }

    fun datasetSize(ctx: Context): Int = NoraTrainer.loadDataset(ctx).size

    // ── MediaStore publishing ───────────────────────────────────────────────

    private fun publishImage(ctx: Context, bitmap: Bitmap): Uri? {
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "nora_${UUID.randomUUID()}.png")
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(MediaStore.Images.Media.RELATIVE_PATH, "DCIM/Prism/Nora")
        }
        return try {
            val uri = ctx.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            uri?.let {
                ctx.contentResolver.openOutputStream(it)?.use { out ->
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                }
            }
            uri
        } catch (e: Exception) {
            PrismLogger.logError("Nora", "Could not publish image to MediaStore: ${e.message}")
            null
        }
    }

    private fun publishVideo(ctx: Context, file: File): Uri? {
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, file.name)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, "DCIM/Prism/Nora")
        }
        return try {
            val uri = ctx.contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
            uri?.let {
                ctx.contentResolver.openOutputStream(it)?.use { out ->
                    file.inputStream().use { input -> input.copyTo(out) }
                }
            }
            uri
        } catch (e: Exception) {
            PrismLogger.logError("Nora", "Could not publish video to MediaStore: ${e.message}")
            null
        }
    }
}
