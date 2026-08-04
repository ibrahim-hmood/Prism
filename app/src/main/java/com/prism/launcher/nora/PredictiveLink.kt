package com.prism.launcher.nora

import java.io.DataInputStream
import java.io.DataOutputStream
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * A reciprocal cortico-cortical connection between two areas, carrying predictions DOWN and
 * prediction errors UP.
 *
 * Weights are locally-connected with a shared kernel: a unit in the higher area predicts a
 * small patch of the lower area, and the same kernel applies across the sheet. This is not a
 * convenience -- cortical connections genuinely are local and topographic, receptive fields
 * genuinely do grow by a roughly fixed factor per area, and the resulting parameter count is
 * what lets the whole connectome fit in a few hundred kilobytes.
 *
 * THE LEARNING RULE IS LOCAL. Rao & Ballard (1999) derive it as gradient descent on prediction
 * error, but the resulting update
 *
 *      dW  ∝  (prediction error at the lower area) × (activity in the higher area)
 *
 * requires only quantities available at the synapse itself: a presynaptic rate and a
 * postsynaptic error signal. No backward pass, no stored activation graph, no autodiff engine.
 * That is the entire reason Nora can train on a phone in plain Kotlin. Had the architecture
 * been a diffusion U-Net, an autodiff framework would have been mandatory and this project
 * would not exist. Choosing biological fidelity bought a real engineering capability here --
 * one of the few places in this design where the two goals genuinely point the same way.
 *
 * See Whittington & Bogacz (2017) and Millidge, Tschantz & Buckley (2020) for the formal
 * relationship between this rule and backpropagation.
 */
class PredictiveLink(
    val name: String,
    val topC: Int, val topH: Int, val topW: Int,
    val botC: Int, val botH: Int, val botW: Int,
    val kernel: Int = 5,
    seed: Long = 7L
) {

    val strideY = maxOf(1, botH / topH)
    val strideX = maxOf(1, botW / topW)
    private val pad = kernel / 2

    /** W[topChannel][botChannel][ky][kx], flattened. */
    val weights = FloatArray(topC * botC * kernel * kernel)

    /** Per-synapse permanence. High-permanence synapses resist pruning and downscaling. */
    private val permanence = FloatArray(topC * botC * kernel * kernel)

    private val kk = kernel * kernel
    private val perTop = botC * kk

    /**
     * Fan-in initialization scale. Kept as a field because the update clamps are expressed
     * relative to it -- "a quarter of the initial weight scale" is a meaningful step bound at
     * any layer size, whereas an absolute number is not.
     */
    private val initScale = (1.0 / sqrt((botC * kk).toDouble())).toFloat()

    private val maxDelta = initScale * 0.05f

    /**
     * Hard per-synapse ceiling, tightened from 25x to 3x the initialization scale.
     *
     * 25x was far too permissive. The operator norm of this link scales with its weight
     * magnitude, and the inference loop is only stable while PC_RATE × λmax(WᵀW) < 2 -- so
     * allowing a 25x growth in magnitude permits a 625x growth in λmax, which guarantees the
     * loop eventually destabilizes no matter how careful the learning rule is.
     */
    private val maxWeight = initScale * 3f

    /** RMS of the initial weights. The spectrum constraint is expressed relative to this. */
    private var initRms = 0f

    init {
        // Scaled so that the initial prediction has roughly unit variance given unit-variance
        // input -- the standard fan-in initialization, which matters more here than usual
        // because predictive coding has no normalization layers to rescue a bad init.
        val rng = Random(seed)
        for (i in weights.indices) {
            weights[i] = ((rng.nextFloat() * 2f - 1f) * initScale)
        }
        initRms = rms()
    }

    private fun rms(): Float {
        var s = 0.0
        for (v in weights) s += v.toDouble() * v
        return sqrt(s / weights.size).toFloat()
    }

    /**
     * Keeps the link's weight magnitude -- and therefore its operator norm, and therefore the
     * stability of the inference loop it participates in -- inside a known band.
     *
     * Early in training every prediction starts near zero, so prediction errors are
     * systematically same-signed and every synapse is pushed the same way at once. Magnitude
     * therefore grows roughly linearly rather than settling, and once λmax(WᵀW) crosses
     * 2 / PC_RATE the twelve inference iterations in a single settle amplify instead of
     * converging. That is what took the last run out at image five.
     *
     * Rescaling preserves the direction the learning rule chose -- the relative pattern of
     * weights, which is where the information is -- and discards only the overall gain, which
     * carries none. This is weight normalization, and it is the standard remedy.
     */
    private fun constrainSpectrum() {
        val current = rms()
        if (!current.isFinite()) {
            NoraHealth.report("$name weight RMS became non-finite")
            return
        }
        val ceiling = initRms * MAX_RMS_GROWTH
        if (current <= ceiling || current <= 0f) return
        val scale = ceiling / current
        for (i in weights.indices) weights[i] *= scale
    }

    private inline fun wIdx(ct: Int, cb: Int, ky: Int, kx: Int) =
        ct * perTop + cb * kk + ky * kernel + kx

    /**
     * Top-down: generates this area's prediction of the area below.
     *
     * Feedback carries PREDICTIONS. This is the deep-layer (L5/6) projection to L1 of the
     * lower area, and its content is "what I expect you to be reporting" (Rao & Ballard 1999;
     * Bastos et al. 2012).
     */
    fun predict(top: Tensor3, out: Tensor3) {
        out.zero()
        Par.forRange(botC) { cb ->
            val base = cb * out.plane
            for (yb in 0 until botH) {
                for (xb in 0 until botW) {
                    var acc = 0f
                    for (ky in 0 until kernel) {
                        val num = yb + pad - ky
                        if (num < 0 || num % strideY != 0) continue
                        val yt = num / strideY
                        if (yt >= topH) continue
                        for (kx in 0 until kernel) {
                            val numX = xb + pad - kx
                            if (numX % strideX != 0) continue
                            var xt = (numX / strideX) % topW
                            if (xt < 0) xt += topW
                            for (ct in 0 until topC) {
                                acc += weights[wIdx(ct, cb, ky, kx)] * top.data[ct * top.plane + yt * topW + xt]
                            }
                        }
                    }
                    out.data[base + yb * botW + xb] = acc
                }
            }
        }
    }

    /**
     * Bottom-up: carries the lower area's prediction ERROR into this area.
     *
     * Feedforward carries ERROR, not raw activity. This is the superficial-layer (L2/3)
     * projection into L4 of the higher area. The asymmetry -- errors ascend, predictions
     * descend -- is the core structural commitment of predictive coding, and it is the thing
     * laminar recordings have been used to test (Bastos et al. 2015 found feedforward gamma
     * and feedback beta with the predicted laminar profiles).
     */
    fun propagateError(errorBelow: Tensor3, out: Tensor3, gain: Float = 1f) {
        out.zero()
        Par.forRange(topC) { ct ->
            val base = ct * out.plane
            for (yt in 0 until topH) {
                for (xt in 0 until topW) {
                    var acc = 0f
                    for (ky in 0 until kernel) {
                        val yb = yt * strideY + ky - pad
                        if (yb < 0 || yb >= botH) continue
                        for (kx in 0 until kernel) {
                            var xb = (xt * strideX + kx - pad) % botW
                            if (xb < 0) xb += botW
                            for (cb in 0 until botC) {
                                acc += weights[wIdx(ct, cb, ky, kx)] *
                                    errorBelow.data[cb * errorBelow.plane + yb * botW + xb]
                            }
                        }
                    }
                    out.data[base + yt * topW + xt] = acc * gain
                }
            }
        }
    }

    /**
     * The local learning rule. Purely Hebbian in form: coincidence of presynaptic activity in
     * the higher area with a postsynaptic error signal in the lower area.
     *
     * [dopamine] modulates the rate. At 1.0 this is plain predictive-coding learning; above 1
     * it is reward-modulated, which is what the trainer uses to weight well-captioned or
     * user-favoured examples more heavily.
     */
    fun learn(errorBelow: Tensor3, top: Tensor3, rate: Float, dopamine: Float = 1f) {
        val lr = rate * dopamine
        if (!NoraHealth.check(lr, "$name learning rate")) return

        Par.forRange(topC) { ct ->
            for (cb in 0 until botC) {
                for (ky in 0 until kernel) {
                    for (kx in 0 until kernel) {
                        var acc = 0f
                        var n = 0
                        for (yt in 0 until topH) {
                            val yb = yt * strideY + ky - pad
                            if (yb < 0 || yb >= botH) continue
                            for (xt in 0 until topW) {
                                var xb = (xt * strideX + kx - pad) % botW
                                if (xb < 0) xb += botW
                                acc += top.data[ct * top.plane + yt * topW + xt] *
                                    errorBelow.data[cb * errorBelow.plane + yb * botW + xb]
                                n++
                            }
                        }
                        if (n == 0) continue

                        // THE MEAN, not the sum. This is the fix for the divergence that made
                        // the first trained connectomes worthless.
                        //
                        // predict() and propagateError() are already correctly scaled, because
                        // the fan-in initialization (1/sqrt(botC * kernel^2)) accounts for
                        // exactly the axes they sum over. learn() sums over a different axis --
                        // cortical SPACE, topH x topW -- which the initialization never
                        // compensated for. On the V1->retina link that is 1536 terms, and since
                        // early predictions are near zero the errors are same-signed rather
                        // than cancelling, so the sum lands in the hundreds. A nominal rate of
                        // 0.006 became an effective rate near 9, the weights left float range
                        // within a handful of images, and every downstream value went NaN.
                        //
                        // Averaging makes the rate mean what it says at any sheet size.
                        acc /= n

                        val wi = wIdx(ct, cb, ky, kx)
                        var delta = lr * acc - NoraConfig.WEIGHT_DECAY * weights[wi]

                        if (!delta.isFinite()) {
                            NoraHealth.report("$name weight update produced a non-finite value")
                            continue
                        }
                        // Belt to the averaging's braces: no single step may move a synapse by
                        // more than a fraction of its initialization scale, so even a
                        // pathological batch cannot bootstrap a runaway.
                        if (delta > maxDelta) delta = maxDelta
                        else if (delta < -maxDelta) delta = -maxDelta

                        val updated = (weights[wi] + delta).coerceIn(-maxWeight, maxWeight)
                        weights[wi] = updated

                        // Synaptic permanence: consistently useful synapses accumulate
                        // protection. Loosely stands in for myelination and structural
                        // stabilization of strong pathways -- flagged in NORA.md as analogy.
                        if (abs(delta) > 1e-7f && dopamine > 1.2f) {
                            permanence[wi] = kotlin.math.min(1f, permanence[wi] + 0.01f)
                        }
                    }
                }
            }
        }
        constrainSpectrum()
    }

    /** True when every sampled weight is finite. Cheap enough to call once per training image. */
    fun isHealthy(): Boolean = NoraHealth.scan(weights, "$name weights")

    /**
     * Sleep-phase synaptic homeostasis: global downscaling plus pruning of the weakest
     * synapses. Tononi & Cirelli's synaptic homeostasis hypothesis (2003, 2014) holds that
     * waking potentiates synapses net-positive and sleep renormalizes them, preserving relative
     * strength while restoring capacity and signal-to-noise. High-permanence synapses are
     * spared, which is how consolidated knowledge survives the renormalization.
     */
    fun sleepDownscale() {
        for (i in weights.indices) {
            val protection = permanence[i]
            val factor = NoraConfig.SLEEP_DOWNSCALE + (1f - NoraConfig.SLEEP_DOWNSCALE) * protection
            weights[i] *= factor
            if (abs(weights[i]) < NoraConfig.PRUNE_THRESHOLD && protection < 0.2f) {
                weights[i] = 0f
            }
            permanence[i] *= 0.999f
        }
    }

    /** Fraction of synapses currently pruned to zero -- a readout of structural sparsity. */
    fun sparsity(): Float {
        var z = 0
        for (v in weights) if (v == 0f) z++
        return z.toFloat() / weights.size
    }

    fun save(out: DataOutputStream) {
        out.writeUTF(name)
        out.writeInt(weights.size)
        for (v in weights) out.writeFloat(v)
        for (v in permanence) out.writeFloat(v)
    }

    companion object {
        /**
         * How far the weight RMS may grow above its initialization value.
         *
         * Loose enough that learning can meaningfully change connection strengths, tight enough
         * that λmax(WᵀW) stays within a factor of ~6 of its initial value -- which keeps
         * PC_RATE × λmax comfortably under the stability bound of 2.
         */
        private const val MAX_RMS_GROWTH = 2.5f
    }

    /** Returns false (and leaves weights untouched) if the stored shape does not match. */
    fun load(input: DataInputStream): Boolean {
        val storedName = input.readUTF()
        val n = input.readInt()
        if (storedName != name || n != weights.size) {
            input.skipBytes(n * 8)
            return false
        }
        for (i in weights.indices) weights[i] = input.readFloat()
        for (i in permanence.indices) permanence[i] = input.readFloat()
        return true
    }
}
