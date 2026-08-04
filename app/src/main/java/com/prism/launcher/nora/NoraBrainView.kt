package com.prism.launcher.nora

import android.content.Context
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.RectF
import android.graphics.SurfaceTexture
import android.util.AttributeSet
import android.view.TextureView
import android.view.View
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/**
 * Live connectome visualization: a lateral view of the brain with Nora's regions lit by their
 * actual activity and signal traffic animated along the real pathways.
 *
 * Runs on a dedicated render thread, so it never touches the main thread and cannot block
 * training. It reads [NoraTelemetry] through a lock-free snapshot; if the brain is mid-update
 * the renderer simply draws slightly stale numbers rather than waiting.
 *
 * TextureView rather than SurfaceView, for two concrete reasons: a SurfaceView's surface is a
 * separate window layer, so it neither clips to the rounded card it sits in nor scrolls in sync
 * with the ScrollView above it. TextureView composites in-window and does both, while still
 * supporting off-main-thread rendering through lockCanvas. It costs more per frame, which at
 * 30 fps over a small view is a price worth paying.
 *
 * The anatomy is real. Regions sit approximately where they sit in a left-lateral view of a
 * human brain -- V1 at the occipital pole, the ventral stream running forward along the
 * temporal lobe to IT and the anterior temporal hub, MT at the occipito-temporal junction with
 * MST dorsal to it heading into parietal cortex, thalamus central, hippocampus deep in the
 * medial temporal lobe. Areas Nora does not implement (frontal, motor, cerebellum, brainstem)
 * are drawn as context and stay dark, which is itself informative: it shows how much of a brain
 * this is not.
 */
class NoraBrainView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : TextureView(context, attrs), TextureView.SurfaceTextureListener {

    // ── Anatomy ─────────────────────────────────────────────────────────────

    /**
     * Positions are in a normalized 0..1 box over a brain facing LEFT: x=0 is the frontal pole,
     * x=1 is the occipital pole, y=0 is dorsal (top).
     */
    private class Node(
        val region: NoraTelemetry.Region?,
        val label: String,
        val x: Float,
        val y: Float,
        val radius: Float,
        val implemented: Boolean
    )

    private val nodes = listOf(
        // Implemented -- these light up.
        Node(NoraTelemetry.Region.RETINA, "Retina", 0.055f, 0.62f, 0.030f, true),
        Node(NoraTelemetry.Region.LGN, "LGN", 0.505f, 0.585f, 0.028f, true),
        Node(NoraTelemetry.Region.V1, "V1", 0.905f, 0.560f, 0.040f, true),
        Node(NoraTelemetry.Region.V2, "V2", 0.830f, 0.505f, 0.035f, true),
        Node(NoraTelemetry.Region.V4, "V4", 0.790f, 0.660f, 0.034f, true),
        Node(NoraTelemetry.Region.IT, "IT", 0.605f, 0.750f, 0.036f, true),
        Node(NoraTelemetry.Region.MT, "MT/V5", 0.760f, 0.410f, 0.031f, true),
        Node(NoraTelemetry.Region.MST, "MST", 0.665f, 0.320f, 0.029f, true),
        Node(NoraTelemetry.Region.ATL, "ATL", 0.330f, 0.760f, 0.032f, true),
        Node(NoraTelemetry.Region.HIPPOCAMPUS, "Hippocampus", 0.640f, 0.640f, 0.030f, true),

        // Context -- drawn, never lit. Nora has none of these.
        Node(null, "Prefrontal", 0.135f, 0.400f, 0.034f, false),
        Node(null, "Motor", 0.375f, 0.245f, 0.030f, false),
        Node(null, "Somatosensory", 0.475f, 0.235f, 0.030f, false),
        Node(null, "Parietal", 0.630f, 0.215f, 0.032f, false),
        Node(null, "Broca", 0.215f, 0.575f, 0.026f, false),
        Node(null, "Auditory", 0.545f, 0.690f, 0.028f, false),
        Node(null, "Wernicke", 0.700f, 0.855f, 0.026f, false),
        Node(null, "Cerebellum", 0.855f, 0.880f, 0.038f, false),
        Node(null, "Brainstem", 0.640f, 0.945f, 0.026f, false)
    )

    private val nodeByRegion = HashMap<NoraTelemetry.Region, Node>().apply {
        for (n in nodes) n.region?.let { put(it, n) }
    }

    /** A stable per-region hue, drawn once at construction. The user asked for random colours. */
    private val regionHue = FloatArray(NoraTelemetry.Region.values().size).also { arr ->
        val rng = Random(System.nanoTime())
        // Golden-angle spacing off a random start: random-looking, but no two regions ever
        // land close enough in hue to be confusable.
        var h = rng.nextFloat() * 360f
        for (i in arr.indices) {
            arr[i] = h % 360f
            h += 137.508f
        }
    }

    // ── Render state ────────────────────────────────────────────────────────

    private val activityRaw = FloatArray(NoraTelemetry.Region.values().size)
    private val activitySmoothed = FloatArray(NoraTelemetry.Region.values().size)
    private val activityPeak = FloatArray(NoraTelemetry.Region.values().size) { 1e-3f }

    private val ascendingRaw = FloatArray(NoraTelemetry.Pathway.values().size)
    private val descendingRaw = FloatArray(NoraTelemetry.Pathway.values().size)
    private val ascendingPeak = FloatArray(NoraTelemetry.Pathway.values().size) { 1e-3f }
    private val descendingPeak = FloatArray(NoraTelemetry.Pathway.values().size) { 1e-3f }

    /** Travelling signal packets, one pool per pathway per direction. */
    private val packetPhase = FloatArray(NoraTelemetry.Pathway.values().size * PACKETS_PER_PATH * 2)

    private var thread: RenderThread? = null
    private var darkTheme = true

    // ── Paints (allocated once; nothing allocates inside the draw loop) ─────

    private val outlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.5f
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val sulcusPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.4f
        strokeCap = Paint.Cap.ROUND
    }
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val nodePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val pathPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val packetPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }
    private val captionPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.LEFT }

    private val brainOutline = Path()
    private val sulci = Path()
    private val scratchPath = Path()
    private val scratchRect = RectF()

    /** Guards the geometry paths, which the main thread rebuilds on resize. */
    private val geometryLock = Any()

    init {
        surfaceTextureListener = this
        isOpaque = false
        isFocusable = false
    }

    fun setDarkTheme(dark: Boolean) {
        darkTheme = dark
    }

    override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
        synchronized(geometryLock) { buildBrainGeometry(width.toFloat(), height.toFloat()) }
        thread = RenderThread().also {
            it.running = true
            it.start()
        }
    }

    override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {
        synchronized(geometryLock) { buildBrainGeometry(width.toFloat(), height.toFloat()) }
    }

    /**
     * Returning true hands the SurfaceTexture back to the framework for release. The render
     * thread must be fully stopped first, or it can call lockCanvas on a texture that is
     * already being torn down.
     */
    override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
        stopThread()
        return true
    }

    override fun onSurfaceTextureUpdated(surface: SurfaceTexture) = Unit

    private fun stopThread() {
        thread?.let {
            it.running = false
            try {
                it.join(1500)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
        thread = null
    }

    override fun onDetachedFromWindow() {
        stopThread()
        super.onDetachedFromWindow()
    }

    /**
     * Switching to the Log tab sets this view GONE, and backgrounding the app hides the window
     * -- neither detaches the view, so without this the render thread would keep drawing a
     * brain nobody is looking at while training fights it for cores.
     */
    @Volatile
    private var renderEnabled = true

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        updateRenderEnabled()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        updateRenderEnabled()
    }

    private fun updateRenderEnabled() {
        renderEnabled = visibility == View.VISIBLE && windowVisibility == View.VISIBLE
    }

    // ── Geometry ────────────────────────────────────────────────────────────

    /**
     * A recognizable left-lateral cerebrum: rounded frontally, bulging at the parietal crown,
     * tapering to the occipital pole, with the temporal lobe hooking forward underneath.
     * Plus a separate cerebellum and brainstem.
     */
    private fun buildBrainGeometry(w: Float, h: Float) {
        val pad = min(w, h) * 0.06f
        val bw = w - pad * 2
        val bh = h - pad * 2
        fun px(nx: Float) = pad + nx * bw
        fun py(ny: Float) = pad + ny * bh

        brainOutline.reset()
        // Cerebral cortex.
        brainOutline.moveTo(px(0.06f), py(0.52f))
        brainOutline.cubicTo(px(0.04f), py(0.30f), px(0.20f), py(0.13f), px(0.40f), py(0.13f))
        brainOutline.cubicTo(px(0.62f), py(0.12f), px(0.82f), py(0.20f), px(0.93f), py(0.38f))
        brainOutline.cubicTo(px(1.00f), py(0.50f), px(0.99f), py(0.63f), px(0.92f), py(0.70f))
        brainOutline.cubicTo(px(0.86f), py(0.76f), px(0.78f), py(0.76f), px(0.74f), py(0.74f))
        // Temporal lobe, hooking forward and down.
        brainOutline.cubicTo(px(0.72f), py(0.86f), px(0.60f), py(0.92f), px(0.46f), py(0.88f))
        brainOutline.cubicTo(px(0.34f), py(0.85f), px(0.26f), py(0.78f), px(0.24f), py(0.70f))
        brainOutline.cubicTo(px(0.16f), py(0.68f), px(0.08f), py(0.62f), px(0.06f), py(0.52f))
        brainOutline.close()

        sulci.reset()
        // Central sulcus -- the landmark separating frontal from parietal.
        sulci.moveTo(px(0.42f), py(0.155f))
        sulci.cubicTo(px(0.40f), py(0.32f), px(0.36f), py(0.44f), px(0.30f), py(0.55f))
        // Lateral (Sylvian) fissure -- the one that defines the temporal lobe.
        sulci.moveTo(px(0.19f), py(0.665f))
        sulci.cubicTo(px(0.34f), py(0.63f), px(0.52f), py(0.60f), px(0.70f), py(0.63f))
        // Parieto-occipital sulcus.
        sulci.moveTo(px(0.79f), py(0.245f))
        sulci.cubicTo(px(0.78f), py(0.36f), px(0.81f), py(0.44f), px(0.86f), py(0.50f))
        // Calcarine sulcus -- V1 lives on its banks.
        sulci.moveTo(px(0.86f), py(0.50f))
        sulci.cubicTo(px(0.90f), py(0.53f), px(0.93f), py(0.55f), px(0.96f), py(0.56f))

        // Cerebellum.
        scratchRect.set(px(0.76f), py(0.78f), px(0.97f), py(0.97f))
        brainOutline.addArc(scratchRect, -40f, 260f)
        // Brainstem.
        brainOutline.moveTo(px(0.60f), py(0.86f))
        brainOutline.cubicTo(px(0.62f), py(0.93f), px(0.64f), py(0.98f), px(0.68f), py(1.00f))

        labelPaint.textSize = min(w, h) * 0.030f
        captionPaint.textSize = min(w, h) * 0.032f
    }

    private fun nodeX(n: Node, w: Float, h: Float): Float {
        val pad = min(w, h) * 0.06f
        return pad + n.x * (w - pad * 2)
    }

    private fun nodeY(n: Node, w: Float, h: Float): Float {
        val pad = min(w, h) * 0.06f
        return pad + n.y * (h - pad * 2)
    }

    // ── Render thread ───────────────────────────────────────────────────────

    private inner class RenderThread : Thread("nora-visualizer") {

        @Volatile
        var running = false

        init {
            // Below normal so the visualizer can never win a core away from a training step.
            priority = Thread.MIN_PRIORITY + 2
            isDaemon = true
        }

        override fun run() {
            var lastFrame = System.nanoTime()
            while (running) {
                if (!renderEnabled) {
                    try {
                        sleep(200)
                    } catch (e: InterruptedException) {
                        Thread.currentThread().interrupt()
                        running = false
                    }
                    // Reset the clock so the first visible frame doesn't jump the animation
                    // forward by however long the tab was hidden.
                    lastFrame = System.nanoTime()
                    continue
                }

                val now = System.nanoTime()
                val dt = ((now - lastFrame) / 1_000_000_000.0).toFloat().coerceIn(0f, 0.1f)
                lastFrame = now

                var canvas: Canvas? = null
                try {
                    canvas = lockCanvas()
                    if (canvas != null) {
                        synchronized(geometryLock) {
                            update(dt)
                            renderFrame(canvas)
                        }
                    }
                } catch (e: Exception) {
                    // Surface torn down underneath us. Nothing to recover from; the loop
                    // exits on the next `running` check.
                } finally {
                    if (canvas != null) {
                        try {
                            unlockCanvasAndPost(canvas)
                        } catch (e: Exception) { /* surface already gone */ }
                    }
                }

                // ~30 fps is plenty for this and leaves the CPU to training.
                val elapsedMs = (System.nanoTime() - now) / 1_000_000
                val sleep = FRAME_MS - elapsedMs
                if (sleep > 0) {
                    try {
                        sleep(sleep)
                    } catch (e: InterruptedException) {
                        Thread.currentThread().interrupt()
                        running = false
                    }
                }
            }
        }
    }

    // ── Simulation ──────────────────────────────────────────────────────────

    private fun update(dt: Float) {
        NoraTelemetry.readActivity(activityRaw)
        NoraTelemetry.readAscending(ascendingRaw)
        NoraTelemetry.readDescending(descendingRaw)

        // Auto-scaling: each channel tracks its own decaying peak, so regions whose raw units
        // differ by orders of magnitude still render on a comparable brightness scale.
        for (i in activityRaw.indices) {
            activityPeak[i] = max(activityPeak[i] * 0.995f, activityRaw[i])
            val target = if (activityPeak[i] > 1e-6f) activityRaw[i] / activityPeak[i] else 0f
            activitySmoothed[i] += (target - activitySmoothed[i]) * min(1f, dt * 6f)
        }
        for (i in ascendingRaw.indices) {
            ascendingPeak[i] = max(ascendingPeak[i] * 0.995f, ascendingRaw[i])
            descendingPeak[i] = max(descendingPeak[i] * 0.995f, descendingRaw[i])
        }

        // Advance travelling packets. Speed tracks traffic, so a busy pathway visibly streams.
        val pathways = NoraTelemetry.Pathway.values()
        for (p in pathways.indices) {
            val up = normalized(ascendingRaw[p], ascendingPeak[p])
            val down = normalized(descendingRaw[p], descendingPeak[p])
            for (k in 0 until PACKETS_PER_PATH) {
                val upIdx = (p * PACKETS_PER_PATH + k) * 2
                packetPhase[upIdx] = (packetPhase[upIdx] + dt * (0.25f + up * 0.85f)) % 1f
                packetPhase[upIdx + 1] = (packetPhase[upIdx + 1] + dt * (0.20f + down * 0.70f)) % 1f
            }
        }
    }

    private fun normalized(v: Float, peak: Float): Float =
        if (peak > 1e-6f) (v / peak).coerceIn(0f, 1f) else 0f

    // ── Drawing ─────────────────────────────────────────────────────────────

    private fun renderFrame(canvas: Canvas) {
        val w = canvas.width.toFloat()
        val h = canvas.height.toFloat()
        if (brainOutline.isEmpty) buildBrainGeometry(w, h)

        val bg = if (darkTheme) 0xFF0B0B0F.toInt() else 0xFFF7F7FA.toInt()
        canvas.drawColor(bg, PorterDuff.Mode.SRC)

        val idle = !NoraTelemetry.live
        val contextColor = if (darkTheme) 0x33FFFFFF else 0x22000000
        val outlineColor = if (darkTheme) 0x66FFFFFF else 0x44000000

        // Brain outline and major sulci.
        outlinePaint.color = outlineColor
        outlinePaint.maskFilter = null
        canvas.drawPath(brainOutline, outlinePaint)
        sulcusPaint.color = contextColor
        canvas.drawPath(sulci, sulcusPaint)

        // Pathways under the nodes.
        val pathways = NoraTelemetry.Pathway.values()
        for (p in pathways.indices) {
            drawPathway(canvas, w, h, pathways[p], p, idle)
        }

        // Nodes.
        for (n in nodes) drawNode(canvas, w, h, n, idle)

        drawCaption(canvas, w, h)
    }

    private fun drawPathway(
        canvas: Canvas, w: Float, h: Float,
        pathway: NoraTelemetry.Pathway, index: Int, idle: Boolean
    ) {
        val from = nodeByRegion[pathway.from] ?: return
        val to = nodeByRegion[pathway.to] ?: return
        val x0 = nodeX(from, w, h)
        val y0 = nodeY(from, w, h)
        val x1 = nodeX(to, w, h)
        val y1 = nodeY(to, w, h)

        // Bow each connection so overlapping pathways stay separable.
        val mx = (x0 + x1) * 0.5f
        val my = (y0 + y1) * 0.5f
        val dx = x1 - x0
        val dy = y1 - y0
        val len = hypot(dx, dy).coerceAtLeast(1f)
        val bow = len * 0.14f
        val cx = mx - dy / len * bow
        val cy = my + dx / len * bow

        scratchPath.reset()
        scratchPath.moveTo(x0, y0)
        scratchPath.quadTo(cx, cy, x1, y1)

        val up = if (idle) 0f else normalized(ascendingRaw[index], ascendingPeak[index])
        val down = if (idle) 0f else normalized(descendingRaw[index], descendingPeak[index])
        val busy = max(up, down)

        pathPaint.maskFilter = null
        pathPaint.color = blendAlpha(
            if (darkTheme) 0xFFFFFF else 0x000000,
            (if (darkTheme) 30 else 26) + (busy * 70).toInt()
        )
        pathPaint.strokeWidth = 1.6f + busy * 2.2f
        canvas.drawPath(scratchPath, pathPaint)

        if (idle) return

        // Travelling packets. Ascending carries prediction error and runs from -> to;
        // descending carries predictions and runs the other way. Drawing both is the point:
        // the two-way traffic IS the architecture.
        val upHue = regionHue[pathway.to.ordinal]
        val downHue = regionHue[pathway.from.ordinal]
        for (k in 0 until PACKETS_PER_PATH) {
            val base = (index * PACKETS_PER_PATH + k) * 2
            val offset = k.toFloat() / PACKETS_PER_PATH

            if (up > 0.04f) {
                val t = (packetPhase[base] + offset) % 1f
                drawPacket(canvas, x0, y0, cx, cy, x1, y1, t, upHue, up)
            }
            if (down > 0.04f) {
                val t = (packetPhase[base + 1] + offset) % 1f
                drawPacket(canvas, x1, y1, cx, cy, x0, y0, t, downHue, down * 0.8f)
            }
        }
    }

    private fun drawPacket(
        canvas: Canvas,
        ax: Float, ay: Float, bx: Float, by: Float, cx2: Float, cy2: Float,
        t: Float, hue: Float, intensity: Float
    ) {
        // Quadratic Bezier evaluation.
        val u = 1f - t
        val x = u * u * ax + 2f * u * t * bx + t * t * cx2
        val y = u * u * ay + 2f * u * t * by + t * t * cy2
        // Fade in and out at the endpoints so packets don't pop.
        val edge = min(t, 1f - t) * 4f
        val alpha = (intensity * min(1f, edge) * 235f).toInt().coerceIn(0, 255)
        if (alpha < 6) return
        packetPaint.color = hsv(hue, 0.55f, 1f, alpha)
        canvas.drawCircle(x, y, 2.2f + intensity * 2.0f, packetPaint)
    }

    private fun drawNode(canvas: Canvas, w: Float, h: Float, n: Node, idle: Boolean) {
        val cx = nodeX(n, w, h)
        val cy = nodeY(n, w, h)
        val r = n.radius * min(w, h)

        if (!n.implemented || n.region == null) {
            // Context region: outline only, never lit.
            nodePaint.maskFilter = null
            nodePaint.strokeWidth = 1.4f
            nodePaint.color = if (darkTheme) 0x30FFFFFF else 0x25000000
            canvas.drawCircle(cx, cy, r, nodePaint)
            labelPaint.color = if (darkTheme) 0x50FFFFFF else 0x50000000
            canvas.drawText(n.label, cx, cy + r + labelPaint.textSize * 1.15f, labelPaint)
            return
        }

        val a = if (idle) 0f else activitySmoothed[n.region.ordinal].coerceIn(0f, 1f)
        val hue = regionHue[n.region.ordinal]

        // Glow: brightness scales with activity, exactly as asked. Drawn as a blurred fill
        // behind the outline so the ring itself stays crisp.
        if (a > 0.02f) {
            glowPaint.maskFilter = BlurMaskFilter(r * (0.7f + a * 1.1f), BlurMaskFilter.Blur.NORMAL)
            glowPaint.color = hsv(hue, 0.60f, 1f, (a * 165).toInt().coerceIn(0, 255))
            canvas.drawCircle(cx, cy, r * (0.85f + a * 0.35f), glowPaint)
        }

        fillPaint.maskFilter = null
        fillPaint.color = hsv(hue, 0.45f, if (darkTheme) 0.35f else 0.95f, (28 + a * 90).toInt())
        canvas.drawCircle(cx, cy, r, fillPaint)

        nodePaint.maskFilter = null
        nodePaint.strokeWidth = 1.8f + a * 2.4f
        nodePaint.color = hsv(hue, 0.55f, 1f, (95 + a * 160).toInt().coerceIn(0, 255))
        canvas.drawCircle(cx, cy, r, nodePaint)

        labelPaint.color = if (darkTheme) {
            blendAlpha(0xFFFFFF, (140 + a * 115).toInt())
        } else {
            blendAlpha(0x000000, (150 + a * 105).toInt())
        }
        canvas.drawText(n.label, cx, cy + r + labelPaint.textSize * 1.15f, labelPaint)
    }

    private fun drawCaption(canvas: Canvas, w: Float, h: Float) {
        val pad = min(w, h) * 0.05f
        captionPaint.color = if (darkTheme) 0xA0FFFFFF.toInt() else 0xA0000000.toInt()
        val line = if (NoraTelemetry.live) {
            "%s · ACh %.2f · NE %.2f · DA %.2f".format(
                NoraTelemetry.phase,
                NoraTelemetry.acetylcholine,
                NoraTelemetry.noradrenaline,
                NoraTelemetry.dopamine
            )
        } else {
            "idle — start training to see her think"
        }
        canvas.drawText(line, pad, h - pad * 0.5f, captionPaint)
    }

    // ── Colour helpers ──────────────────────────────────────────────────────

    private val hsvScratch = FloatArray(3)

    private fun hsv(hue: Float, sat: Float, value: Float, alpha: Int): Int {
        hsvScratch[0] = hue
        hsvScratch[1] = sat
        hsvScratch[2] = value
        return (alpha.coerceIn(0, 255) shl 24) or (Color.HSVToColor(hsvScratch) and 0x00FFFFFF)
    }

    private fun blendAlpha(rgb: Int, alpha: Int): Int =
        (alpha.coerceIn(0, 255) shl 24) or (rgb and 0x00FFFFFF)

    companion object {
        private const val PACKETS_PER_PATH = 3
        private const val FRAME_MS = 33L
    }
}
