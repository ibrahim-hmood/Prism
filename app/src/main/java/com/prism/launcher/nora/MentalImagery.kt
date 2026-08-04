package com.prism.launcher.nora

import android.graphics.Bitmap
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/**
 * Generation: saccadic refinement for stills, temporal predictive coding for video.
 *
 * SACCADIC REFINEMENT is the main resolution lever, and it is worth being precise about why.
 * A foveated visual system never has a high-resolution image of a whole scene. It has a
 * sequence of small high-resolution samples, taken at ~3-4 per second, stitched into a stable
 * percept across saccades (Melcher & Colby 2008 review trans-saccadic integration). The
 * subjective impression of a uniformly detailed visual world is famously an illusion --
 * change blindness is the cleanest demonstration.
 *
 * For Nora this is not a curiosity, it is the memory strategy. Peak working memory tracks the
 * 32x48 retinal sheet, not the output canvas. Generating a larger image means taking more
 * fixations, not allocating a bigger tensor. That is what makes high-resolution output
 * tractable on a phone, and it is a direct consequence of copying the biology rather than
 * building a conventional generator that must materialize the whole image at once.
 *
 * FIXATION SELECTION is precision-weighted error: the next saccade goes where the model is
 * least confident. This is the standard active-inference account of eye movements (Friston et
 * al. 2012), and it matches the finding that fixations land on informative, not merely
 * salient, locations (Najemnik & Geisler 2005 showed human search fixations are close to the
 * ideal-observer optimum).
 */
class MentalImagery(private val brain: NoraBrain) {

    private val canvas = NoraConfig.CANVAS
    private val accum = FloatArray(canvas * canvas * 3)
    private val weight = FloatArray(canvas * canvas)

    /**
     * Fixed random projection from the entorhinal grid code onto IT.
     *
     * This is what makes a fixation at one place produce different content from a fixation at
     * another while the CONCEPT stays clamped -- the scene has a layout, and where you are
     * looking within it matters. See the honesty flag on Hippocampus.gridCode: using grid cells
     * this way is an engineering appropriation, not a biological claim.
     */
    private val gridProjection: Array<FloatArray>

    /**
     * The IT pattern the last generation was built from, before per-fixation contextualization.
     *
     * Kept so the feedback trace can record what actually drove the image rather than
     * re-deriving it from the prompt afterwards -- hippocampal recall is stochastic in its
     * settling, so a second call is not guaranteed to reproduce the same concept, and a trace
     * that does not match what the user saw would reinforce the wrong thing.
     */
    var lastConcept: FloatArray? = null
        private set

    init {
        val gridSize = NoraConfig.GRID_MODULES * NoraConfig.GRID_PHASES
        val rng = Random(909L)
        gridProjection = Array(brain.itSize) { FloatArray(gridSize) { (rng.nextFloat() * 2f - 1f) } }
    }

    private fun clearCanvas() {
        java.util.Arrays.fill(accum, 0f)
        java.util.Arrays.fill(weight, 0f)
    }

    /** Applies the spatial context of a fixation to the clamped concept. */
    private fun contextualize(concept: FloatArray, fx: Float, fy: Float): FloatArray {
        val grid = brain.hippocampus.gridCode(fx * 2f - 1f, fy * 2f - 1f)
        val out = FloatArray(concept.size)
        for (i in concept.indices) {
            var g = 0f
            val row = gridProjection[i]
            for (j in grid.indices) g += row[j] * grid[j]
            // Modest modulation: the concept must still dominate, or each fixation generates a
            // different object and the saccades never integrate into one coherent scene.
            out[i] = max(0f, concept[i] * (1f + 0.25f * g) + 0.05f * g)
        }
        return out
    }

    /**
     * Generates a still image from a text prompt.
     *
     * @param onProgress called with (completedSaccades, totalSaccades)
     */
    fun generateStill(
        prompt: String,
        saccades: Int = NoraConfig.SACCADES,
        mode: NoraImageryMode = NoraImageryMode.DETERMINISTIC,
        bias: Float = 0f,
        onProgress: ((Int, Int) -> Unit)? = null
    ): Bitmap {
        clearCanvas()
        brain.retinaModel.resetTemporal()
        val concept = brain.conceptFromPrompt(prompt)
        lastConcept = concept.copyOf()

        // Fresh entropy per call: without it, stochastic settling would replay the same draw
        // every time and defeat the whole point of sampling.
        val rng = java.util.Random()
        val iterations = when (mode) {
            NoraImageryMode.SAMPLED -> NoraConfig.SAMPLE_ITERATIONS
            NoraImageryMode.COARSE_TO_FINE -> NoraConfig.COARSE_TO_FINE_ITERATIONS
            NoraImageryMode.DETERMINISTIC -> NoraConfig.PC_ITERATIONS_IMAGERY
        }

        // First fixation at the centre; the rest chosen by precision-weighted error.
        var fx = 0.5f
        var fy = 0.5f

        for (s in 0 until saccades) {
            val contextualized = contextualize(concept, fx, fy)
            val retinal = brain.imagine(contextualized, iterations, mode = mode, rng = rng, bias = bias)
            brain.retinaModel.splatToCanvas(retinal, fx, fy, canvas, accum, weight)
            onProgress?.invoke(s + 1, saccades)

            if (s < saccades - 1) {
                val next = nextFixation(fx, fy, s)
                fx = next.first
                fy = next.second
            }
        }

        return Retina.accumulatorToBitmap(canvas, accum, weight)
    }

    /**
     * Chooses the next fixation from the LGN precision map -- the location whose prediction
     * error the model currently weights most heavily.
     *
     * The precision peak is expressed in log-polar sheet coordinates relative to the CURRENT
     * fixation, so it has to be converted back into canvas coordinates before it means
     * anything. That conversion is the inverse of the retinal mapping.
     */
    private fun nextFixation(fx: Float, fy: Float, saccadeIndex: Int): Pair<Float, Float> {
        val (ring, wedge) = brain.lgn.peakPrecisionLocation()
        val ecc = brain.retinaModel.eccentricityOf(ring)
        val theta = 2f * PI.toFloat() * wedge / NoraConfig.WEDGES

        // Saccades undershoot their target by ~10% in humans, and the residual is corrected by
        // a smaller corrective saccade (Becker & Fuchs 1969). Damping here serves the same
        // purpose: it stops the sequence oscillating between two extreme points.
        val damping = 0.55f
        var nx = fx + ecc * cos(theta) * 0.5f * damping
        var ny = fy + ecc * sin(theta) * 0.5f * damping

        // Keep fixations inside the canvas, and force some spread on the early saccades so the
        // whole image gets covered rather than the model staring at one interesting corner.
        if (saccadeIndex < 3) {
            val spreadAngle = 2f * PI.toFloat() * saccadeIndex / 3f + 0.7f
            nx = 0.5f + 0.24f * cos(spreadAngle)
            ny = 0.5f + 0.24f * sin(spreadAngle)
        }
        return nx.coerceIn(0.18f, 0.82f) to ny.coerceIn(0.18f, 0.82f)
    }

    /**
     * Generates a video clip.
     *
     * The mechanism, and why it differs from frame-wise diffusion:
     *
     *   A frame-wise diffusion video generator samples each frame from noise, conditioned on
     *   text and possibly on the previous frame. Two independent samples of "the same" scene
     *   differ in every detail that the conditioning does not pin down, which is where flicker
     *   and identity drift come from -- the model is re-deciding, every frame, facts it already
     *   decided.
     *
     *   Nora never re-decides. The concept stays clamped in IT for the whole clip, so object
     *   identity is a constant of the generation, not a per-frame sample. Each new frame starts
     *   from the PREVIOUS frame's cortical representation, transported forward by the flow
     *   field MST synthesized -- so the prior for frame t+1 is literally frame t, moved.
     *   Predictive coding then only has to explain the residual: what the warp got wrong.
     *   Content that is not moving has nothing to explain and therefore does not change.
     *
     *   This is temporal predictive coding in the sense of Rao & Ballard's dynamic extension
     *   and Friston's generalized filtering: the model predicts forward in time and corrects,
     *   rather than re-inferring from scratch at every timestep.
     *
     * The tradeoff, stated plainly: because the whole clip is one continuous settling of one
     * concept, Nora cannot produce a cut, a new object entering frame, or any genuine change of
     * scene content. It produces a continuously-transformed view of a single held concept.
     * That is a real limitation and it comes directly from the mechanism.
     *
     * @param motion one of Mst.templateNames, or null to hold still
     */
    fun generateVideo(
        prompt: String,
        frames: Int = NoraConfig.VIDEO_FRAMES,
        motion: String? = "expansion",
        bias: Float = 0f,
        onProgress: ((Int, Int) -> Unit)? = null
    ): List<Bitmap> {
        brain.retinaModel.resetTemporal()
        brain.mt.reset()
        val concept = brain.conceptFromPrompt(prompt)
        lastConcept = concept.copyOf()
        val out = ArrayList<Bitmap>(frames)

        val template = motion?.let { brain.mst.templateIndex(it) } ?: -1
        if (template >= 0) {
            // Just under one sheet cell of displacement per frame. Enough to read as motion,
            // small enough that the warp stays sub-receptive-field and predictive coding only
            // has a residual to explain rather than a whole new scene.
            brain.mst.synthesizeFlow(template, 0.7f, brain.mt.flowX, brain.mt.flowY)
        } else {
            java.util.Arrays.fill(brain.mt.flowX, 0f)
            java.util.Arrays.fill(brain.mt.flowY, 0f)
        }

        val fx = 0.5f
        val fy = 0.5f
        val contextualized = contextualize(concept, fx, fy)

        for (f in 0 until frames) {
            val retinal = if (f == 0) {
                // Bias applies to the first frame only. Every later frame is a warm start from
                // the one before it, and re-seeding noise mid-clip would break exactly the
                // property that stops Nora's video flickering.
                brain.imagine(contextualized, bias = bias)
            } else {
                // Transport the previous frame's ventral representation along the flow field,
                // then let predictive coding settle only the residual.
                val warpedV2 = brain.mt.warpForward(brain.v2.representation)
                brain.v2.representation.copyFrom(warpedV2)
                val warpedV1 = brain.mt.warpForward(brain.v1.representation)
                brain.v1.representation.copyFrom(warpedV1)
                brain.imagine(
                    contextualized,
                    iterations = max(4, NoraConfig.PC_ITERATIONS_IMAGERY / 3),
                    warmStart = true
                )
            }

            clearCanvas()
            brain.retinaModel.splatToCanvas(retinal, fx, fy, canvas, accum, weight)
            out.add(Retina.accumulatorToBitmap(canvas, accum, weight))
            onProgress?.invoke(f + 1, frames)
        }
        return out
    }
}
