package com.prism.launcher.nora

import android.content.Context
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

/**
 * Connectome persistence.
 *
 * Nora's weights live on storage, not only in RAM, and every training epoch checkpoints. The
 * practical consequence is that the process can be killed at any point -- by the user, by
 * Android's low-memory killer, by a crash -- and nothing is forgotten beyond the current epoch.
 *
 * Worth naming precisely what this is and is not: it is CHECKPOINTING, not out-of-core compute.
 * The whole connectome is ~150k parameters and fits in RAM many times over, so there is no need
 * to stream weights from disk during a forward pass. If Nora ever scales past available RAM,
 * out-of-core would become a real requirement; at this scale it would be pure overhead.
 *
 * The format is versioned and shape-checked. A load against a connectome whose geometry has
 * changed fails cleanly and leaves the in-memory weights alone rather than silently corrupting
 * them, which matters because NoraConfig constants are the kind of thing that gets tuned.
 */
object NoraPersistence {

    private const val MAGIC = 0x4E4F5241   // "NORA"

    /**
     * Version 2 rejects every version-1 connectome on sight, deliberately.
     *
     * Version 1 was written by a build whose learning rule diverged, so those files contain
     * Infinity and NaN weights. Loading one would resurrect a dead brain that then fails
     * silently -- black images and "I haven't seen that" -- rather than obviously. There is no
     * migration because there is nothing worth migrating.
     */
    private const val VERSION = 2

    private fun connectomeFile(ctx: Context) = File(NoraConfig.weightsDir(ctx), "connectome.bin")
    private fun tempFile(ctx: Context) = File(NoraConfig.weightsDir(ctx), "connectome.bin.tmp")

    fun exists(ctx: Context): Boolean = connectomeFile(ctx).exists()

    fun sizeBytes(ctx: Context): Long =
        connectomeFile(ctx).let { if (it.exists()) it.length() else 0L }

    /**
     * Writes to a temporary file and renames on success, so a kill mid-write cannot leave a
     * half-written connectome where a good one used to be.
     */
    fun save(ctx: Context, brain: NoraBrain) {
        // Never checkpoint a diverged brain over a good one. Without this, a run that goes NaN
        // in epoch three overwrites two epochs of healthy weights with garbage.
        if (!NoraHealth.healthy) {
            com.prism.launcher.PrismLogger.logError(
                "Nora", "Refusing to save a diverged connectome (${NoraHealth.firstFault})"
            )
            return
        }
        for (link in brain.links) {
            if (!link.isHealthy()) {
                com.prism.launcher.PrismLogger.logError(
                    "Nora", "Refusing to save: ${link.name} holds non-finite weights"
                )
                return
            }
        }

        val tmp = tempFile(ctx)
        DataOutputStream(BufferedOutputStream(FileOutputStream(tmp))).use { out ->
            out.writeInt(MAGIC)
            out.writeInt(VERSION)

            // Geometry fingerprint, so a config change is detected rather than misread.
            out.writeInt(NoraConfig.RINGS)
            out.writeInt(NoraConfig.WEDGES)
            out.writeInt(NoraConfig.V1_CH)
            out.writeInt(NoraConfig.V2_CH)
            out.writeInt(NoraConfig.V4_CH)
            out.writeInt(NoraConfig.IT_CH)
            out.writeInt(NoraConfig.SEMANTIC_UNITS)

            out.writeInt(brain.links.size)
            for (link in brain.links) link.save(out)

            brain.semanticHub.save(out)
            brain.hippocampus.save(out)
            brain.itModel.saveState(out)
        }
        val target = connectomeFile(ctx)
        if (target.exists()) target.delete()
        if (!tmp.renameTo(target)) {
            tmp.copyTo(target, overwrite = true)
            tmp.delete()
        }
    }

    /** Returns true if a compatible connectome was loaded. */
    fun load(ctx: Context, brain: NoraBrain): Boolean {
        val file = connectomeFile(ctx)
        if (!file.exists()) return false
        return try {
            DataInputStream(BufferedInputStream(FileInputStream(file))).use { input ->
                if (input.readInt() != MAGIC) return false
                if (input.readInt() != VERSION) return false

                val geometryMatches = input.readInt() == NoraConfig.RINGS &&
                    input.readInt() == NoraConfig.WEDGES &&
                    input.readInt() == NoraConfig.V1_CH &&
                    input.readInt() == NoraConfig.V2_CH &&
                    input.readInt() == NoraConfig.V4_CH &&
                    input.readInt() == NoraConfig.IT_CH &&
                    input.readInt() == NoraConfig.SEMANTIC_UNITS
                if (!geometryMatches) return false

                val nLinks = input.readInt()
                if (nLinks != brain.links.size) return false
                for (link in brain.links) {
                    if (!link.load(input)) return false
                }

                brain.semanticHub.load(input)
                brain.hippocampus.load(input)
                brain.itModel.loadState(input)

                // Final gate: even a correctly-shaped, correctly-versioned file gets rejected
                // if the numbers in it are not usable.
                for (link in brain.links) {
                    if (!link.isHealthy()) {
                        com.prism.launcher.PrismLogger.logError(
                            "Nora", "Connectome rejected: ${link.name} holds non-finite weights"
                        )
                        return false
                    }
                }
                true
            }
        } catch (e: Exception) {
            com.prism.launcher.PrismLogger.logError(
                "Nora", "Failed to load connectome: ${e.message}"
            )
            false
        }
    }

    fun deleteConnectome(ctx: Context) {
        connectomeFile(ctx).delete()
        tempFile(ctx).delete()
    }
}
