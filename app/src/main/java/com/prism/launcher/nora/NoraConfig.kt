package com.prism.launcher.nora

import android.content.Context
import java.io.File

/**
 * How the generative hierarchy is run during imagery.
 *
 * All three build the canvas the same way -- foveal patches integrated across saccades, which
 * is the memory strategy rather than a generation algorithm. What differs is the SETTLING
 * DYNAMICS underneath.
 */
enum class NoraImageryMode {
    /**
     * Deterministic descent to the nearest attractor. The default, and the fastest.
     * Same concept always gives the same image.
     */
    DETERMINISTIC,

    /**
     * Annealed stochastic settling -- Langevin dynamics on the free-energy landscape.
     *
     * Diffusion sampling is annealed Langevin on a learned score function; predictive-coding
     * inference is gradient descent on a free-energy landscape. Adding noise on a decaying
     * temperature schedule turns the second into the first: the hierarchy SAMPLES from the
     * distribution instead of collapsing to its mode.
     *
     * The biological warrant is the neural sampling hypothesis -- that cortical response
     * variability IS sampling from a posterior rather than noise corrupting a point estimate
     * (Hoyer & Hyvärinen 2003; Fiser, Berkes, Orbán & Lengyel 2010; Buesing et al. 2011 for the
     * spiking version). Under that reading a deterministic cortex is the less defensible
     * option, and this mode is fixing an omission rather than borrowing a trick.
     */
    SAMPLED,

    /**
     * Coarse-to-fine: anneal spatial SCALE rather than noise.
     *
     * Diffusion's most useful emergent property is that its noise schedule implicitly orders
     * generation from low spatial frequency to high -- global structure first, detail last.
     * The visual system already does this, and Nora already has the machinery: a magno/parvo
     * split and three spatial-frequency bands in the V1 Gabor bank. Coarse channels settle
     * first and finer ones unlock progressively.
     *
     * This is the coarse-to-fine hypothesis, not an analogy to diffusion: a fast
     * low-spatial-frequency magnocellular projection reaches frontal cortex ahead of the
     * detailed ventral signal and supplies a coarse "gist" that constrains the slower
     * fine-grained analysis (Bar 2003; Bar et al. 2006; Hegdé 2008 reviews the literature).
     * Diffusion rediscovered a schedule cortex was already using.
     */
    COARSE_TO_FINE
}

/**
 * Every scale decision in Nora, in one place, with the memory arithmetic written down.
 *
 * Target hardware is a phone (Snapdragon 8 Gen 1 class, ~8 GB shared RAM, of which an Android
 * app realistically gets a few hundred MB of heap). There is no CUDA, no autodiff framework,
 * and no tensor library -- which is precisely why the architecture is predictive coding with
 * local learning rules rather than a diffusion U-Net. See NORA.md for the full argument.
 *
 * Working-set arithmetic at these constants:
 *   retina  8ch x 32x48   =  12,288 floats
 *   V1     24ch x 32x48   =  36,864
 *   V2     32ch x 16x24   =  12,288
 *   V4     48ch x  8x12   =   4,608
 *   IT     64ch x  4x 6   =   1,536
 *   MT     16ch x 16x24   =   6,144
 * Each region holds L4 / L2-3 / L5-6 plus a prediction and error buffer (~5x the above),
 * so all cortical state is well under 2 MB. The weights dominate nothing either:
 *   V1<-V2   24x32x5x5 =  19,200 floats
 *   V2<-V4   32x48x5x5 =  38,400
 *   V4<-IT   48x64x5x5 =  76,800
 *   retina<-V1 8x24x5x5 =   4,800
 * Total learned parameters are ~150k -- four orders of magnitude below a small diffusion model.
 * That is not an accident of laziness: local receptive fields with shared weights are what
 * cortex actually has, and they are the reason this fits on a phone at all.
 */
object NoraConfig {

    // ── Retina / cortical sheet geometry ────────────────────────────────────

    /** Log-polar rings (eccentricity samples). Each ring is one step of cortical magnification. */
    const val RINGS = 32

    /** Log-polar wedges (polar-angle samples). This axis is circular and wraps. */
    const val WEDGES = 48

    /** Innermost eccentricity sampled, in units of canvas half-width. Sets foveal resolution. */
    const val FOVEA_MIN_ECC = 0.012f

    /** Outermost eccentricity sampled, in units of canvas half-width. */
    const val FOVEA_MAX_ECC = 1.35f

    /**
     * Retinal output channels, in fixed order. The magno/parvo/konio split is a real anatomical
     * division of labour (Livingstone & Hubel 1988), not a naming flourish.
     */
    const val RET_P_ON = 0      // parvo, sustained, fine DoG, luminance
    const val RET_P_OFF = 1
    const val RET_M_ON = 2      // magno, transient, coarse DoG, luminance
    const val RET_M_OFF = 3
    const val RET_RG_ON = 4     // parvo chromatic, L-M
    const val RET_RG_OFF = 5
    const val RET_K_ON = 6      // konio, S-(L+M)
    const val RET_K_OFF = 7

    /**
     * Surface / "filled-in" channels (R, G, B).
     *
     * HONESTY FLAG: real retinal ganglion cells are band-pass and throw away the DC component
     * almost entirely -- that loss is why the Craik-O'Brien-Cornsweet illusion works, and the
     * brain recovers surface brightness and colour by filling-in from borders (Paradiso &
     * Nakayama 1991; Komatsu 2006). Nora carries an explicit low-pass surface channel instead
     * of reconstructing DC purely by filling-in. This is an engineering shortcut, not a claim
     * about the retina. The V4 fill-in stage is still implemented and still does the boundary-
     * bounded diffusion; this channel just means a generated image has a defined DC to start
     * from rather than having to hallucinate absolute brightness from contrast alone.
     */
    const val RET_S_R = 8
    const val RET_S_G = 9
    const val RET_S_B = 10

    const val RETINA_CH = 11

    const val PARVO_CENTER_SIGMA = 0.9f
    const val PARVO_SURROUND_SIGMA = 2.4f
    const val MAGNO_CENTER_SIGMA = 1.8f
    const val MAGNO_SURROUND_SIGMA = 5.0f

    // ── V1 ──────────────────────────────────────────────────────────────────

    const val V1_ORIENTATIONS = 8
    const val V1_SCALES = 3
    /** Complex (energy) cells only; the quadrature simple-cell pair is collapsed per Adelson-Bergen. */
    const val V1_CH = V1_ORIENTATIONS * V1_SCALES   // 24
    const val V1_H = RINGS
    const val V1_W = WEDGES

    /** Gabor aspect ratio (spatial envelope y/x). ~0.5 matches macaque V1 (Ringach 2002). */
    const val GABOR_GAMMA = 0.6f
    /** Bandwidth-preserving sigma/lambda ratio for ~1.4 octave tuning. */
    const val GABOR_SIGMA_RATIO = 0.56f
    val GABOR_WAVELENGTHS = floatArrayOf(3.0f, 5.0f, 8.0f)

    /**
     * Strength of the orientation-preference map as a constraint on V1 gain.
     * 0 = no columnar structure (every orientation equally available everywhere, biologically wrong);
     * 1 = hard columns (a unit is silent unless its orientation matches the local column).
     * Real cortex is in between -- columns are a gain bias, not a hard gate.
     */
    const val PINWHEEL_STRENGTH = 0.45f

    // ── V2 / V4 / IT ────────────────────────────────────────────────────────

    const val V2_CH = 32
    const val V2_H = RINGS / 2      // 16
    const val V2_W = WEDGES / 2     // 24

    const val V4_CH = 48
    const val V4_H = V2_H / 2       // 8
    const val V4_W = V2_W / 2       // 12

    const val IT_CH = 64
    const val IT_H = V4_H / 2       // 4
    const val IT_W = V4_W / 2       // 6

    /** k-winners-take-all sparsity in IT. Macaque IT population sparseness is ~5-10%
     *  (Rolls & Tovee 1995), so ~6% of 64x4x6 = ~92 units. */
    const val IT_SPARSITY = 0.06f

    // ── Dorsal stream ───────────────────────────────────────────────────────

    const val MT_DIRECTIONS = 8
    const val MT_SPEEDS = 2
    const val MT_CH = MT_DIRECTIONS * MT_SPEEDS   // 16
    const val MT_H = V2_H
    const val MT_W = V2_W

    /** Frames of V1 history retained for space-time motion energy. */
    const val MOTION_HISTORY = 3

    /** MST optic-flow templates: expansion, contraction, CW, CCW, 8 translations. */
    const val MST_TEMPLATES = 12

    // ── Semantic hub / hippocampus ──────────────────────────────────────────

    const val SEMANTIC_UNITS = 256
    const val DG_UNITS = 1024       // sparse expansion for pattern separation
    const val CA3_UNITS = 256       // recurrent autoassociator
    const val DG_SPARSITY = 0.04f   // dentate gyrus is extremely sparse (~2-5% active)
    const val CA3_SPARSITY = 0.10f
    const val GRID_MODULES = 3      // Hafting et al. 2005; ratio ~1.42 between modules
    const val GRID_PHASES = 8

    const val EPISODE_CAPACITY = 512

    // ── Predictive coding dynamics ──────────────────────────────────────────

    /** Inference iterations per settle. More = better convergence, linearly more time. */
    const val PC_ITERATIONS = 12
    /** Extra iterations during generation, where there is no sensory evidence to pin things down. */
    const val PC_ITERATIONS_IMAGERY = 20
    /** Representation update rate (Rao & Ballard 1999, eq. 6). */
    const val PC_RATE = 0.28f
    /** Leak toward zero each step -- the prior that most units are silent most of the time. */
    const val PC_DECAY = 0.02f

    /**
     * Maximum sustained firing rate, in the arbitrary units the sheets use.
     *
     * Every cortical representation is compressively saturated at this level. This is the
     * structural guarantee that activations cannot explode: predictive-coding inference is a
     * gradient step on ||input - W·representation||², which is only stable while
     * PC_RATE × λmax(WᵀW) < 2, and nothing in a network with no normalization layers enforces
     * that bound as the weights grow. Bounding the representation makes the failure mode
     * "saturated and wrong" instead of "Infinity, then NaN, then a silently dead brain".
     */
    const val MAX_FIRING_RATE = 6.0f

    /** Divisive normalization semi-saturation constant (Carandini & Heeger 2012). */
    const val NORM_SIGMA = 0.25f
    const val NORM_EXPONENT = 2.0f

    /** Lateral (GABAergic) inhibition strength within a sheet. */
    const val LATERAL_INHIBITION = 0.18f

    // ── Learning ────────────────────────────────────────────────────────────

    /**
     * Local learning rate, applied to the MEAN of the Hebbian product over the sheet.
     *
     * History worth keeping, because this constant has been wrong twice in different ways.
     * Originally 0.006 applied to a SUM over ~1500 sheet positions, giving an effective rate
     * near 9 -- immediate weight divergence. Corrected to a mean and raised to 0.02, which
     * stopped the weights exploding but left a second problem: early in training every
     * prediction starts near zero, so errors are systematically same-signed and every synapse
     * moves the same way at once. Weight magnitude grew roughly linearly, the operator norm
     * grew with it, and once PC_RATE × λmax(WᵀW) crossed 2 the inference loop itself began
     * amplifying instead of converging -- around the fifth training image.
     *
     * Now 0.005, with the real safeguards elsewhere: bounded representations, a per-step clamp
     * at 5% of the initialization scale, and an RMS ceiling on each link's weights. Those make
     * the system stable by construction rather than by a well-chosen constant, which means this
     * number can be tuned up again on evidence instead of guarded superstitiously.
     */
    const val LEARN_RATE = 0.005f
    const val WEIGHT_DECAY = 2e-5f
    /** Sleep-phase synaptic downscaling (Tononi & Cirelli's SHY, 2014). */
    const val SLEEP_DOWNSCALE = 0.985f
    /** Pruning threshold: synapses weaker than this after downscaling are zeroed. */
    const val PRUNE_THRESHOLD = 0.002f

    // ── Generation ──────────────────────────────────────────────────────────

    /** Final composited canvas edge, in pixels. Built up from foveal patches, not rendered at once. */
    const val CANVAS = 128

    /** Saccades per still image. This is the resolution lever: peak memory tracks the fovea, not the canvas. */
    const val SACCADES = 6

    // ── Sampled (annealed Langevin) imagery ─────────────────────────────────

    /**
     * Noise levels for annealed stochastic settling, geometric from max to min.
     *
     * Geometric rather than linear for the same reason score-based models use it: the
     * landscape's curvature varies over orders of magnitude, so the schedule has to as well
     * (Song & Ermon 2019). Starting high lets the sample explore; ending near zero lets it
     * commit.
     */
    const val SAMPLE_SIGMA_MAX = 0.55f
    const val SAMPLE_SIGMA_MIN = 0.012f

    /** Sampling needs more steps than mode-seeking -- it is exploring, not descending. */
    const val SAMPLE_ITERATIONS = 32

    /** Initial retinal noise, so two draws from one concept diverge from the first step. */
    const val SAMPLE_SEED_NOISE = 0.35f

    // ── Coarse-to-fine imagery ──────────────────────────────────────────────

    /**
     * Fraction of the settling schedule at which each V1 spatial-frequency band unlocks,
     * indexed by scale (0 = finest, GABOR_WAVELENGTHS.lastIndex = coarsest).
     * The coarsest is available immediately; the finest only in the last third.
     */
    val SCALE_UNLOCK = floatArrayOf(0.55f, 0.30f, 0.0f)

    /** Residual gain a locked band keeps. Not zero -- suppression in cortex is never total. */
    const val SCALE_FLOOR = 0.10f

    /** How quickly a band ramps from floor to full once it starts unlocking. */
    const val SCALE_RAMP = 0.22f

    const val COARSE_TO_FINE_ITERATIONS = 28

    // ── Denoising training curriculum ───────────────────────────────────────

    /**
     * Range of corruption strength applied during denoising training.
     *
     * Sampled per presentation rather than fixed, which is the property that makes denoising
     * score matching so well-conditioned: the model sees the same content at many degradation
     * levels and learns the landscape rather than one point on it.
     */
    const val DENOISE_MIN = 0.10f
    const val DENOISE_MAX = 0.65f

    /** Probability that a presentation is corrupted by occlusion rather than additive noise. */
    const val DENOISE_OCCLUSION_CHANCE = 0.35f

    // ── Feedback: three-factor (reward-modulated) learning ──────────────────

    /**
     * How long the reinforcing replay runs when the user rates a generation.
     *
     * Fewer iterations than a normal imagery pass. The point is not to produce a good image --
     * it is to re-instate the cortical state that produced the rated one, so the prediction
     * errors sitting in the L2/3 buffers belong to that image and the local rule has something
     * truthful to act on.
     */
    const val FEEDBACK_REPLAY_ITERATIONS = 10

    /**
     * How much harder a rated example is learned from than an ordinary training presentation.
     *
     * Deliberately large. One thumb is one example, against a dataset of hundreds seen many
     * times over -- at the ordinary rate its effect would be unmeasurable and the buttons would
     * be decoration. 3x is enough for a handful of ratings on the same concept to visibly move
     * generation, and small enough that a mis-tap is not destructive.
     */
    const val FEEDBACK_GAIN = 3.0f

    /**
     * Phasic dopamine for an approved generation, and the dip for a rejected one.
     *
     * The dip is the important half and the easy one to get wrong. A negative reward prediction
     * error is signalled by dopamine falling BELOW baseline, not by the absence of a burst
     * (Schultz, Dayan & Montague 1997; Bayer & Glimcher 2005 measured the signed encoding
     * directly). Weight change here is therefore proportional to (dopamine - 1), which is
     * positive for a burst and negative for a dip -- the same expression produces potentiation
     * and depression, which is what makes it a reward prediction error rather than two rules.
     *
     * The dip value is 0.4 rather than lower because Neuromodulators clamps dopamine to
     * [0.4, 2.5]; asking for less would be silently coerced and the constant would lie.
     */
    const val REWARD_DOPAMINE = 2.2f
    const val PUNISH_DOPAMINE = 0.4f

    /** Rate at which a rating strengthens or weakens the caption -> IT association in ATL. */
    const val FEEDBACK_SEMANTIC_RATE = 0.06f

    /**
     * Seed noise added when generating a prompt that has been thumbed down before.
     *
     * Noradrenaline's computational role is to ABANDON a failed hypothesis rather than
     * incrementally correct it (Bouret & Sara 2005), and a rejected image is precisely a failed
     * hypothesis. Without this, the depressed weights would still settle into the neighbourhood
     * of the same attractor and the user would get a slightly degraded version of the picture
     * they just rejected.
     */
    const val AVERSIVE_SEED_NOISE = 0.45f

    /** Per-iteration jitter for a disliked concept, on top of the seed noise. */
    const val AVERSIVE_JITTER = 0.09f

    /** How much a well-liked concept raises the top-down prior -- settle harder, wander less. */
    const val CONVICTION_PRIOR_BOOST = 0.45f

    /** Feedback traces retained on disk. Each is ~7 KB, so this costs ~110 KB total. */
    const val FEEDBACK_TRACE_KEEP = 16

    // ── Autonomous training ─────────────────────────────────────────────────

    /**
     * Epochs per unattended training run.
     *
     * Short on purpose. An autonomous run has no one watching it, so it should make steady
     * incremental progress and hand the device back, not commit to an open-ended session. The
     * cycle repeats every few hours, so the epochs accumulate across runs.
     */
    const val AUTO_TRAIN_EPOCHS = 3

    /** Default frames for a generated clip. */
    const val VIDEO_FRAMES = 24
    const val VIDEO_FPS = 8

    // ── Storage ─────────────────────────────────────────────────────────────

    fun rootDir(ctx: Context): File =
        File(android.os.Environment.getExternalStorageDirectory(), "Prism/Nora").also { it.mkdirs() }

    fun datasetDir(ctx: Context): File = File(rootDir(ctx), "dataset").also { it.mkdirs() }
    fun weightsDir(ctx: Context): File = File(rootDir(ctx), "connectome").also { it.mkdirs() }
    fun outputDir(ctx: Context): File = File(rootDir(ctx), "output").also { it.mkdirs() }
    fun feedbackDir(ctx: Context): File = File(rootDir(ctx), "feedback").also { it.mkdirs() }
    fun chatFile(ctx: Context): File = File(rootDir(ctx), "conversation.json")
}
