package com.prism.launcher.nora

import android.content.Context
import com.prism.launcher.PrismLogger
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

/**
 * The bridge between a thumb pressed in the Messages page and a change in Nora's connectome.
 *
 * A rating arrives after the generation it refers to has finished -- seconds later if the user
 * is quick, days later if they are not, and possibly after the process has been killed and
 * restarted in between. Nothing in the cortical state survives that, which is why every
 * generation leaves a TRACE behind: the semantic cue and the IT pattern that produced the image.
 * Those two arrays are enough to replay the generation and reach the same synapses, which is
 * what [NoraBrain.reinforce] does with them. See the long comment there for why an episode is
 * the right trace and a per-synapse eligibility tag is not.
 *
 * Two things are persisted here:
 *
 *   TRACES  one small file per generation, newest [NoraConfig.FEEDBACK_TRACE_KEEP] kept. A trace
 *           records its own rating, so a thumb pressed while training is running is not lost --
 *           it sits as pending until the brain is free, and [applyPending] collects it.
 *
 *   BIAS    a word -> score map, which is the only part of feedback that changes how generation
 *           RUNS rather than what the weights contain. See [biasFor].
 */
object NoraFeedback {

    /** Word scores saturate here, so no amount of repetition can pin generation to an extreme. */
    private const val WORD_SCORE_LIMIT = 4f

    private const val MAGIC = 0x4E464244   // "NFBD"
    private const val VERSION = 1

    class Trace(
        val token: String,
        val prompt: String,
        val caption: String,
        val semantic: FloatArray,
        val itPattern: FloatArray,
        val createdAt: Long,
        /** 0 unrated, +1 approved, -1 rejected. */
        var valence: Int,
        /** True once [NoraBrain.reinforce] has consumed this rating. */
        var applied: Boolean
    )

    // ── Recording ───────────────────────────────────────────────────────────

    /**
     * Files a trace for a generation that just completed. Returns the token the chat message
     * carries, or null if the trace could not be written -- in which case the UI simply does not
     * offer thumbs for that message rather than offering buttons that would do nothing.
     */
    @Synchronized
    fun record(
        ctx: Context,
        prompt: String,
        caption: String,
        semantic: FloatArray,
        itPattern: FloatArray
    ): String? {
        val token = "%013x%04x".format(
            System.currentTimeMillis(),
            (Math.random() * 0xFFFF).toInt()
        )
        val trace = Trace(token, prompt, caption, semantic, itPattern, System.currentTimeMillis(), 0, false)
        return if (write(ctx, trace)) {
            prune(ctx)
            token
        } else {
            null
        }
    }

    /**
     * Records the user's rating against a trace.
     *
     * Deliberately separate from applying it. The rating is a fact about what the user thinks and
     * must be durable the instant they press the button; applying it needs exclusive access to
     * the brain, which may be busy for the next several hours.
     *
     * @return false if the trace has expired -- rated after more than
     *         [NoraConfig.FEEDBACK_TRACE_KEEP] later generations pushed it out.
     */
    @Synchronized
    fun rate(ctx: Context, token: String, positive: Boolean): Boolean {
        val trace = read(traceFile(ctx, token)) ?: return false
        // Re-rating an already-applied trace is allowed and re-applies. Flipping a thumb should
        // do something, not be silently ignored because the first press was consumed.
        trace.valence = if (positive) 1 else -1
        trace.applied = false
        if (!write(ctx, trace)) return false
        adjustBias(ctx, trace.prompt, if (positive) 1f else -1f)
        return true
    }

    // ── Application ─────────────────────────────────────────────────────────

    /**
     * Applies one rated trace to the brain.
     *
     * @return a human-readable account of what changed, or null if there was nothing to apply.
     */
    fun apply(ctx: Context, token: String): String? {
        val trace = synchronized(this) { read(traceFile(ctx, token)) } ?: return null
        if (trace.valence == 0 || trace.applied) return null
        return applyTrace(ctx, trace)
    }

    /**
     * Applies every rating that was made while the brain was busy.
     *
     * Called before a training run starts and after each service job finishes, so a thumb
     * pressed during an overnight session lands rather than being quietly discarded.
     *
     * @return how many ratings were applied.
     */
    fun applyPending(ctx: Context): Int {
        val pending = synchronized(this) {
            traceFiles(ctx).mapNotNull { read(it) }.filter { it.valence != 0 && !it.applied }
        }
        var n = 0
        for (t in pending) {
            if (applyTrace(ctx, t) != null) n++
        }
        return n
    }

    private fun applyTrace(ctx: Context, trace: Trace): String? = try {
        val brain = NoraStudio.brain(ctx)
        val note = brain.reinforce(
            trace.semantic,
            trace.itPattern,
            trace.caption,
            positive = trace.valence > 0
        )
        // Only mark applied if the brain is still healthy; if reinforcement pushed it
        // non-finite, the checkpoint below refuses to save and the rating should stay pending
        // rather than being recorded as delivered to a connectome that never took it.
        if (NoraHealth.healthy) {
            NoraPersistence.save(ctx, brain)
            synchronized(this) {
                trace.applied = true
                write(ctx, trace)
            }
            note
        } else {
            PrismLogger.logError("Nora", "Feedback left the brain unhealthy: ${NoraHealth.firstFault}")
            null
        }
    } catch (e: Exception) {
        PrismLogger.logError("Nora", "Could not apply feedback: ${e.message}", e)
        null
    }

    // ── Generation bias ─────────────────────────────────────────────────────

    /**
     * Net feedback for a prompt, in [-1, +1]. Negative means disliked.
     *
     * Scored per WORD rather than per exact prompt string, which is what makes feedback
     * generalize at all: rating "a redhead in a red dress" teaches something about "redhead",
     * not only about that twenty-character string. It is also the honest limit of what this can
     * do -- the semantic hub is a bag of words with no compositionality (see SemanticHub's
     * honesty flag), so per-word is exactly as fine-grained as the representation underneath it.
     *
     * [NoraBrain.imagine] consumes this: negative displaces the starting point and jitters the
     * trajectory so a rejected image is not simply reproduced; positive raises the top-down
     * prior so an approved concept settles harder toward the same answer.
     */
    fun biasFor(ctx: Context, prompt: String): Float {
        val scores = loadBias(ctx)
        if (scores.isEmpty()) return 0f
        val words = tokenize(prompt)
        if (words.isEmpty()) return 0f
        var sum = 0f
        var n = 0
        for (w in words) {
            val s = scores[w] ?: continue
            sum += s
            n++
        }
        if (n == 0) return 0f
        return (sum / n / WORD_SCORE_LIMIT).coerceIn(-1f, 1f)
    }

    private fun adjustBias(ctx: Context, prompt: String, delta: Float) {
        val scores = loadBias(ctx).toMutableMap()
        for (w in tokenize(prompt)) {
            scores[w] = ((scores[w] ?: 0f) + delta).coerceIn(-WORD_SCORE_LIMIT, WORD_SCORE_LIMIT)
        }
        saveBias(ctx, scores)
    }

    /** Human-readable summary for /status. */
    fun summary(ctx: Context): String {
        val scores = loadBias(ctx)
        if (scores.isEmpty()) return "no feedback yet"
        val liked = scores.count { it.value > 0 }
        val disliked = scores.count { it.value < 0 }
        return "$liked liked / $disliked disliked concept words"
    }

    /** Same tokenization the semantic hub uses, so the two agree on what a word is. */
    private fun tokenize(text: String): List<String> =
        text.lowercase()
            .replace(Regex("[^a-z0-9 ]"), " ")
            .split(" ")
            .filter { it.length > 1 }

    // ── Storage ─────────────────────────────────────────────────────────────

    private fun traceFile(ctx: Context, token: String): File {
        // Tokens are generated here and are pure hex, but they arrive back through an Intent
        // extra, so treat them as untrusted input rather than assuming they are well-formed.
        val safe = token.filter { it.isLetterOrDigit() }
        return File(NoraConfig.feedbackDir(ctx), "$safe.trace")
    }

    private fun traceFiles(ctx: Context): List<File> =
        NoraConfig.feedbackDir(ctx).listFiles()
            ?.filter { it.isFile && it.name.endsWith(".trace") }
            ?.sortedBy { it.lastModified() }
            ?: emptyList()

    private fun prune(ctx: Context) {
        val files = traceFiles(ctx)
        val excess = files.size - NoraConfig.FEEDBACK_TRACE_KEEP
        if (excess <= 0) return
        for (i in 0 until excess) {
            // Never evict a rating that has not been delivered yet -- that is the one case where
            // dropping a file loses information the user actually supplied.
            val t = read(files[i])
            if (t != null && t.valence != 0 && !t.applied) continue
            files[i].delete()
        }
    }

    private fun write(ctx: Context, trace: Trace): Boolean = try {
        DataOutputStream(BufferedOutputStream(FileOutputStream(traceFile(ctx, trace.token)))).use { out ->
            out.writeInt(MAGIC)
            out.writeInt(VERSION)
            out.writeUTF(trace.prompt)
            out.writeUTF(trace.caption)
            out.writeLong(trace.createdAt)
            out.writeInt(trace.valence)
            out.writeBoolean(trace.applied)
            out.writeInt(trace.semantic.size)
            for (v in trace.semantic) out.writeFloat(v)
            out.writeInt(trace.itPattern.size)
            for (v in trace.itPattern) out.writeFloat(v)
        }
        true
    } catch (e: Exception) {
        PrismLogger.logError("Nora", "Could not write feedback trace: ${e.message}")
        false
    }

    private fun read(file: File): Trace? {
        if (!file.exists()) return null
        return try {
            DataInputStream(BufferedInputStream(FileInputStream(file))).use { input ->
                if (input.readInt() != MAGIC) return null
                if (input.readInt() != VERSION) return null
                val prompt = input.readUTF()
                val caption = input.readUTF()
                val createdAt = input.readLong()
                val valence = input.readInt()
                val applied = input.readBoolean()
                val semantic = FloatArray(input.readInt()) { input.readFloat() }
                val itPattern = FloatArray(input.readInt()) { input.readFloat() }
                Trace(
                    file.nameWithoutExtension, prompt, caption,
                    semantic, itPattern, createdAt, valence, applied
                )
            }
        } catch (e: Exception) {
            PrismLogger.logError("Nora", "Discarding unreadable feedback trace: ${e.message}")
            null
        }
    }

    private fun biasFile(ctx: Context) = File(NoraConfig.feedbackDir(ctx), "bias.json")

    private fun loadBias(ctx: Context): Map<String, Float> {
        val file = biasFile(ctx)
        if (!file.exists()) return emptyMap()
        return try {
            val obj = JSONObject(file.readText())
            val out = HashMap<String, Float>(obj.length())
            for (k in obj.keys()) out[k] = obj.optDouble(k, 0.0).toFloat()
            out
        } catch (e: Exception) {
            emptyMap()
        }
    }

    private fun saveBias(ctx: Context, scores: Map<String, Float>) {
        try {
            val obj = JSONObject()
            for ((k, v) in scores) if (v != 0f) obj.put(k, v.toDouble())
            biasFile(ctx).writeText(obj.toString())
        } catch (e: Exception) {
            PrismLogger.logError("Nora", "Could not write feedback bias: ${e.message}")
        }
    }

    /** Wipes every trace and the bias map. Called by /forget and by an archive import. */
    @Synchronized
    fun clear(ctx: Context) {
        try {
            NoraConfig.feedbackDir(ctx).listFiles()?.forEach { it.delete() }
        } catch (e: Exception) {
            PrismLogger.logError("Nora", "Could not clear feedback: ${e.message}")
        }
    }
}
