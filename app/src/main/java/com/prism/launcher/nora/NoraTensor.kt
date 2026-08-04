package com.prism.launcher.nora

import java.util.concurrent.Callable
import java.util.concurrent.Executors
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Dense 3-D activity map laid out [channel][row][col], row-major.
 *
 * Every cortical region in Nora is a sheet of this shape, because cortex is a sheet: the
 * (row, col) axes are real cortical surface coordinates, not an arbitrary feature vector.
 * That constraint is load-bearing -- it is what makes topographic maps, lateral inhibition,
 * local receptive fields, and the foveal/peripheral distinction expressible at all.
 */
class Tensor3(val c: Int, val h: Int, val w: Int) {

    val plane = h * w
    val data = FloatArray(c * plane)

    inline fun at(ci: Int, y: Int, x: Int): Int = ci * plane + y * w + x

    operator fun get(ci: Int, y: Int, x: Int): Float = data[ci * plane + y * w + x]

    operator fun set(ci: Int, y: Int, x: Int, v: Float) {
        data[ci * plane + y * w + x] = v
    }

    fun zero() = java.util.Arrays.fill(data, 0f)

    fun copyFrom(other: Tensor3) {
        System.arraycopy(other.data, 0, data, 0, min(data.size, other.data.size))
    }

    fun clone(): Tensor3 = Tensor3(c, h, w).also { it.copyFrom(this) }

    fun sameShapeAs(other: Tensor3): Boolean = c == other.c && h == other.h && w == other.w

    fun scale(f: Float) {
        for (i in data.indices) data[i] *= f
    }

    fun addScaled(other: Tensor3, f: Float) {
        val o = other.data
        for (i in data.indices) data[i] += o[i] * f
    }

    /** Element-wise this = a - b. Shapes must match. */
    fun setDifference(a: Tensor3, b: Tensor3) {
        val ad = a.data
        val bd = b.data
        for (i in data.indices) data[i] = ad[i] - bd[i]
    }

    fun rectify() {
        for (i in data.indices) if (data[i] < 0f) data[i] = 0f
    }

    /**
     * Compressive saturation: r = x / (1 + |x| / max), signed.
     *
     * Real neurons have a maximum firing rate. Refractory periods and synaptic depression put a
     * hard ceiling on output no matter how hard a cell is driven, and the approach to that
     * ceiling is smooth rather than a clip. [rectify] only bounded activity from below, which
     * left an unbounded quantity in a recurrent loop -- and an unbounded quantity in a recurrent
     * loop eventually finds a way to run away.
     *
     * Near zero this is the identity, so nothing about normal operation changes. It only bites
     * when a region is being driven implausibly hard, and there it converts a catastrophic
     * divergence into a saturated, recoverable one.
     */
    fun saturate(maxRate: Float) {
        for (i in data.indices) {
            val v = data[i]
            if (!v.isFinite()) {
                NoraHealth.report("non-finite activity reached saturation")
                data[i] = 0f
                continue
            }
            val a = if (v < 0f) -v else v
            data[i] = v / (1f + a / maxRate)
        }
    }

    /** Adds zero-mean Gaussian noise. The stochastic half of a Langevin step. */
    fun addNoise(sigma: Float, rng: java.util.Random) {
        if (sigma <= 0f) return
        for (i in data.indices) {
            data[i] += (rng.nextGaussian() * sigma).toFloat()
        }
    }

    /** Multiplies a contiguous channel range by [gain]. Used by the coarse-to-fine gate. */
    fun scaleChannel(ci: Int, gain: Float) {
        val base = ci * plane
        for (i in 0 until plane) data[base + i] *= gain
    }

    fun maxAbs(): Float {
        var m = 0f
        for (v in data) {
            val a = if (v < 0f) -v else v
            if (a > m) m = a
        }
        return m
    }

    fun mean(): Float {
        if (data.isEmpty()) return 0f
        var s = 0.0
        for (v in data) s += v
        return (s / data.size).toFloat()
    }

    fun l2(): Float {
        var s = 0.0
        for (v in data) s += v.toDouble() * v
        return sqrt(s).toFloat()
    }

    /** Fraction of units whose activity exceeds [thresh] -- used for metabolic/sparsity readouts. */
    fun activeFraction(thresh: Float = 1e-3f): Float {
        var n = 0
        for (v in data) if (v > thresh) n++
        return n.toFloat() / max(1, data.size)
    }

    /** Wraps in x (polar angle is circular), clamps in y (eccentricity is not). */
    fun sampleWrapClamp(ci: Int, y: Int, x: Int): Float {
        val yy = if (y < 0) 0 else if (y >= h) h - 1 else y
        var xx = x % w
        if (xx < 0) xx += w
        return data[ci * plane + yy * w + xx]
    }
}

/**
 * Fixed worker pool for channel-parallel inner loops.
 *
 * Deliberately never nested: only the innermost operator (a convolution, a normalization pass)
 * ever calls into this, so a task can never block waiting on another task from the same pool.
 */
object Par {

    private val cores = max(2, Runtime.getRuntime().availableProcessors())

    private val pool = Executors.newFixedThreadPool(cores) { r ->
        Thread(r, "nora-worker").apply {
            isDaemon = true
            priority = Thread.NORM_PRIORITY - 1
        }
    }

    /** Runs body(i) for i in 0 until n, split across the pool in contiguous chunks. */
    fun forRange(n: Int, body: (Int) -> Unit) {
        if (n <= 0) return
        if (n == 1 || cores == 1) {
            for (i in 0 until n) body(i)
            return
        }
        val chunks = min(cores, n)
        val per = (n + chunks - 1) / chunks
        val tasks = ArrayList<Callable<Unit>>(chunks)
        var start = 0
        while (start < n) {
            val lo = start
            val hi = min(n, lo + per)
            tasks.add(Callable {
                for (i in lo until hi) body(i)
            })
            start = hi
        }
        try {
            for (f in pool.invokeAll(tasks)) f.get()
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        } catch (e: Exception) {
            // A worker threw. Re-run serially so the caller sees the real exception in context
            // rather than an ExecutionException from an anonymous pool thread.
            for (i in 0 until n) body(i)
        }
    }
}

object NoraMath {

    /** Bilinear sample of a single channel, wrapping x and clamping y. */
    fun bilinearWrapClamp(t: Tensor3, ci: Int, fy: Float, fx: Float): Float {
        val y0 = kotlin.math.floor(fy).toInt()
        val x0 = kotlin.math.floor(fx).toInt()
        val ty = fy - y0
        val tx = fx - x0
        val v00 = t.sampleWrapClamp(ci, y0, x0)
        val v01 = t.sampleWrapClamp(ci, y0, x0 + 1)
        val v10 = t.sampleWrapClamp(ci, y0 + 1, x0)
        val v11 = t.sampleWrapClamp(ci, y0 + 1, x0 + 1)
        val a = v00 + (v01 - v00) * tx
        val b = v10 + (v11 - v10) * tx
        return a + (b - a) * ty
    }

    /**
     * Naka-Rushton contrast response: r = c^n / (c^n + s^n).
     * Rodieck (1965) / Naka & Rushton (1966); the saturating nonlinearity every retinal and
     * cortical stage shares.
     */
    fun nakaRushton(c: Float, s: Float, n: Float = 2f): Float {
        if (c <= 0f) return 0f
        val cn = Math.pow(c.toDouble(), n.toDouble()).toFloat()
        val sn = Math.pow(max(1e-6f, s).toDouble(), n.toDouble()).toFloat()
        return cn / (cn + sn)
    }

    /** In-place separable Gaussian blur over one channel, wrapping x and clamping y. */
    fun blurChannel(t: Tensor3, ci: Int, sigma: Float, scratch: FloatArray) {
        if (sigma <= 0.01f) return
        val radius = max(1, (sigma * 3f).toInt())
        val kernel = FloatArray(2 * radius + 1)
        var sum = 0f
        for (i in -radius..radius) {
            val v = kotlin.math.exp(-(i * i) / (2f * sigma * sigma))
            kernel[i + radius] = v
            sum += v
        }
        for (i in kernel.indices) kernel[i] /= sum

        val base = ci * t.plane
        // Horizontal (wrapping)
        for (y in 0 until t.h) {
            for (x in 0 until t.w) {
                var acc = 0f
                for (k in -radius..radius) {
                    var xx = (x + k) % t.w
                    if (xx < 0) xx += t.w
                    acc += t.data[base + y * t.w + xx] * kernel[k + radius]
                }
                scratch[y * t.w + x] = acc
            }
        }
        // Vertical (clamping)
        for (y in 0 until t.h) {
            for (x in 0 until t.w) {
                var acc = 0f
                for (k in -radius..radius) {
                    val yy = min(t.h - 1, max(0, y + k))
                    acc += scratch[yy * t.w + x] * kernel[k + radius]
                }
                t.data[base + y * t.w + x] = acc
            }
        }
    }
}
