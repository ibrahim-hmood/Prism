package com.prism.launcher.nora

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.prism.launcher.PrismLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.coroutineContext
import kotlin.math.max
import kotlin.random.Random

/**
 * Training: a wake phase that learns from images, and a sleep phase that consolidates.
 *
 * The two-phase structure is not decoration. Complementary Learning Systems (McClelland,
 * McNaughton & O'Reilly 1995) makes a specific claim: a system that learns quickly from single
 * experiences and a system that learns slowly from their statistics have to be SEPARATE,
 * because a single network that does both suffers catastrophic interference. The hippocampus
 * takes the fast path, cortex takes the slow one, and offline replay is the bridge.
 *
 * So Nora's wake phase does two different things at once -- it applies the local predictive-
 * coding rule to cortical links (slow, small steps) AND imprints the whole episode into the
 * hippocampus (fast, one shot). The sleep phase then replays hippocampal episodes interleaved,
 * training cortex on old material alongside new, and finishes with synaptic downscaling.
 *
 * DATASET CONVENTION: drop images into Prism/Nora/dataset/. The FILENAME is the caption --
 * "a red apple on a table.png" teaches exactly that. This matches what you already built for
 * AetherCortex, so existing datasets carry over unchanged.
 *
 * COMPUTE, HONESTLY: this is a phone running plain Kotlin with no GPU and no autodiff. Expect
 * roughly 1-3 seconds per training image per epoch on a Snapdragon 8 Gen 1. A hundred images
 * for twenty epochs is therefore an hour or two, best run plugged in. That is slow by the
 * standards of a GPU training run and fast by the standards of "impossible", which is what
 * training a diffusion model on this hardware would be.
 */
class NoraTrainer(private val brain: NoraBrain) {

    data class Progress(
        val epoch: Int,
        val totalEpochs: Int,
        val sample: Int,
        val totalSamples: Int,
        val caption: String,
        /** Mean per-unit RMS prediction error. Healthy values sit well under 1. */
        val errorRms: Float,
        val phase: String
    )

    data class DatasetItem(val file: File, val caption: String)

    companion object {
        private val IMAGE_EXTENSIONS = setOf("png", "jpg", "jpeg", "webp", "bmp")

        /** Reads the dataset directory. Filename (minus extension) is the caption. */
        fun loadDataset(ctx: Context): List<DatasetItem> {
            val dir = NoraConfig.datasetDir(ctx)
            val files = dir.listFiles() ?: return emptyList()
            return files
                .filter { it.isFile && it.extension.lowercase() in IMAGE_EXTENSIONS }
                .sortedBy { it.name }
                .map {
                    DatasetItem(
                        it,
                        it.nameWithoutExtension
                            .replace('_', ' ')
                            .replace('-', ' ')
                            .trim()
                    )
                }
        }
    }

    /**
     * Runs a full training session.
     *
     * @param focus optional word to prioritize -- examples whose caption contains it get a
     *              dopamine boost, so their synapses potentiate harder and gain permanence
     *              faster. This is directed curriculum learning through the reward channel
     *              rather than through the loss function.
     */
    suspend fun train(
        ctx: Context,
        epochs: Int,
        focus: String? = null,
        onProgress: (Progress) -> Unit
    ): String = withContext(Dispatchers.Default) {
        val dataset = loadDataset(ctx)
        if (dataset.isEmpty()) {
            return@withContext "No images found in ${NoraConfig.datasetDir(ctx).absolutePath}.\n\n" +
                "Drop images in there and name each file after what it shows -- " +
                "\"a red apple on a table.png\" teaches exactly that."
        }

        NoraPersistence.load(ctx, brain)
        NoraHealth.reset()

        // Ratings made while the brain was busy land here, before the epochs start. Applying
        // them first is not just tidiness: a thumbs-up retags its episode for replay, and the
        // sleep phases in this very run are what act on that tag.
        val pending = NoraFeedback.applyPending(ctx)
        if (pending > 0) {
            onProgress(Progress(0, epochs, 0, 0, "applied $pending pending rating(s)", 0f, "feedback"))
        }
        val rng = Random(System.currentTimeMillis())
        val javaRng = java.util.Random()
        val denoising = com.prism.launcher.PrismSettings.getNoraDenoisingEnabled(ctx)
        var lastError = 0f
        var trained = 0
        var unbound = 0

        for (epoch in 0 until epochs) {
            // ── WAKE ────────────────────────────────────────────────────────
            val order = dataset.indices.shuffled(rng)
            for ((n, idx) in order.withIndex()) {
                coroutineContext.ensureActive()
                val item = dataset[idx]
                val bitmap = decodeScaled(item.file) ?: continue

                val focused = focus != null && item.caption.contains(focus, ignoreCase = true)
                // Focused items get a dopamine boost, which raises the learning rate AND makes
                // their synapses accumulate permanence, so they survive the sleep-phase
                // downscaling that erodes everything else.
                brain.neuromod.setDopamine(if (focused) 2.2f else 1.0f)

                try {
                    // Three fixations per presentation: cortex learns the same object from
                    // different retinal projections, which is where position tolerance comes
                    // from. A single centred fixation would teach a position-specific code.
                    for (f in 0 until 3) {
                        val fx = 0.5f + (rng.nextFloat() - 0.5f) * 0.3f
                        val fy = 0.5f + (rng.nextFloat() - 0.5f) * 0.3f
                        if (denoising) {
                            // Corruption strength is drawn fresh per fixation, not fixed. That
                            // is the property that makes denoising score matching so
                            // well-conditioned: the same content is seen at many degradation
                            // levels, so what gets learned is the shape of the landscape rather
                            // than one point on it.
                            val strength = NoraConfig.DENOISE_MIN +
                                javaRng.nextFloat() * (NoraConfig.DENOISE_MAX - NoraConfig.DENOISE_MIN)
                            brain.perceiveDenoising(bitmap, fx, fy, strength, javaRng)
                        } else {
                            brain.perceive(bitmap, fx, fy)
                        }
                        brain.learn()
                    }
                    if (!brain.bindCaption(item.caption, reward = if (focused) 2f else 1f)) {
                        unbound++
                    }
                    lastError = brain.lastErrorRms
                    trained++
                } catch (e: Exception) {
                    PrismLogger.logError("Nora", "Training failed on ${item.file.name}: ${e.message}")
                } finally {
                    bitmap.recycle()
                }

                // Abort on divergence rather than spending an hour writing NaN to disk. The
                // error readout going NaN used to be the only symptom, and it was easy to
                // mistake for a display quirk while every weight in the connectome rotted.
                if (!NoraHealth.healthy || !lastError.isFinite()) {
                    if (lastError.isFinite().not()) {
                        NoraHealth.report("prediction error became non-finite")
                    }
                    return@withContext NoraHealth.explain()
                }

                onProgress(
                    Progress(epoch + 1, epochs, n + 1, dataset.size, item.caption, lastError, "wake")
                )
            }

            // ── SLEEP ───────────────────────────────────────────────────────
            // Every third epoch, mirroring the fact that consolidation happens periodically
            // rather than continuously.
            if ((epoch + 1) % 3 == 0) {
                onProgress(Progress(epoch + 1, epochs, 0, dataset.size, "consolidating", lastError, "sleep"))
                sleepPhase(rng)
            }

            NoraPersistence.save(ctx, brain)
            if (NoraHealth.healthy) NoraStudio.markTrained()
        }

        buildString {
            append("Training complete.\n\n")
            append("$trained presentations over $epochs epochs on ${dataset.size} images.\n")
            append("Vocabulary: ${brain.semanticHub.knownWords()} words.\n")
            append("Episodes stored: ${brain.hippocampus.episodeCount()}.\n")
            append("Final prediction error (rms): %.4f\n".format(lastError))
            append("Connectome: ${NoraPersistence.sizeBytes(ctx) / 1024} KB on disk.")
            if (unbound > 0) {
                // Surfaced rather than swallowed: a high count here means IT was not producing
                // a pattern to associate captions with, so those presentations taught nothing
                // even though they ran. This is what a silently-empty vocabulary looks like
                // from the outside.
                append("\n\nWarning: $unbound of $trained presentations produced no IT pattern ")
                append("and taught nothing. If that is most of them, generation will be blank.")
            }
        }
    }

    /**
     * Offline consolidation.
     *
     * Replays hippocampal episodes with no sensory input at all: the stored IT pattern is
     * clamped, the hierarchy generates from it, and the resulting internal prediction errors
     * drive the same local learning rule the wake phase used. Cortex is learning from the
     * hippocampus's record rather than from the world, which is the whole point of replay.
     *
     * Interleaving matters here -- episodes are sampled at random from the whole store, so a
     * batch of new images gets consolidated alongside old ones rather than on its own.
     */
    private suspend fun sleepPhase(rng: Random) {
        val episodes = brain.hippocampus.sampleForReplay(24, rng)
        for (ep in episodes) {
            coroutineContext.ensureActive()
            brain.neuromod.setDopamine(0.8f + 0.4f * ep.reward.coerceIn(0f, 2f))
            try {
                brain.imagine(ep.itPattern, iterations = 6)
                // The imagery pass leaves real prediction errors in the L2/3 buffers -- the
                // mismatch between what the analytic encoders extract from the generated image
                // and what the links predicted. Learning on those is what sharpens generation.
                brain.learn(rate = NoraConfig.LEARN_RATE * 0.6f)
                brain.semanticHub.bind(ep.semantic, ep.itPattern, rate = 0.02f)
            } catch (e: Exception) {
                PrismLogger.logError("Nora", "Replay failed: ${e.message}")
            }
        }
        // Synaptic downscaling closes the sleep cycle (Tononi & Cirelli 2014).
        brain.sleepCycle()
        brain.neuromod.setDopamine(1f)
    }

    /**
     * Decodes an image down to roughly canvas scale before it ever hits the retina.
     * A 12-megapixel photo decoded at full size would be most of the app's heap, and the
     * log-polar sampler cannot use the resolution anyway.
     */
    private fun decodeScaled(file: File): Bitmap? {
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, bounds)
            var sample = 1
            val target = NoraConfig.CANVAS * 2
            while (bounds.outWidth / sample > target * 2 && bounds.outHeight / sample > target * 2) {
                sample *= 2
            }
            val opts = BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            BitmapFactory.decodeFile(file.absolutePath, opts)
        } catch (e: Exception) {
            PrismLogger.logError("Nora", "Could not decode ${file.name}: ${e.message}")
            null
        }
    }
}
