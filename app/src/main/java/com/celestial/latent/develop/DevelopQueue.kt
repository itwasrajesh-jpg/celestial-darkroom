package com.celestial.latent.develop

import android.content.Context
import android.net.Uri
import android.util.Log
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * Develops captures in the background, one at a time, so a shot never blocks the shutter.
 * Single shots only — a 16-frame burst is not auto-developed.
 */
object DevelopQueue {

    /** [first]: when set, [source] is the second exposure of a double, made onto this frame. */
    data class Job(val source: Uri, val recipe: Recipe, val isRaw: Boolean, val first: Uri? = null)

    /**
     * Full-resolution develop, run on the same single thread as auto-develop so two heavy
     * engine runs can never overlap — overlapping them makes both crawl.
     */
    /**
     * Full-resolution develop on the same single thread as auto-develop. The returned handle
     * cancels it: a job that has not started is dropped, and one already running is abandoned
     * (the engine cannot be interrupted mid-frame, so its result is simply discarded).
     */
    class Running internal constructor() {
        @Volatile internal var cancelled = false
        fun cancel() { cancelled = true }
    }

    fun submitFull(context: Context, source: Uri, isRaw: Boolean, recipe: Recipe, upscale: Float = 1f,
                   exposureMap: ExposureMap? = null, softenMask: ExposureMap? = null, pairFirst: Uri? = null,
                   framing: Framing = Framing(), fogMask: ExposureMap? = null, fogLook: FogLook = FogLook(),
                   raysMask: ExposureMap? = null, raysLook: RaysLook = RaysLook(),
                   extraLights: List<RaysLook> = emptyList(),
                   onStatus: (String) -> Unit, onDone: (Uri?) -> Unit): Running {
        val handle = Running()
        pending.incrementAndGet(); onChanged()
        onStatus("queued")
        pool.execute {
            val app = context.applicationContext
            var out: Uri? = null
            // The full develop takes its turn for the engine like every other render: two at once
            // compete for the same memory, and a preview or zoom tile starting mid-develop could
            // push a full-size develop over the edge.
            var holdsLane = false
            try {
                if (handle.cancelled) { Log.i("Latent", "full develop cancelled before it started"); return@execute }
                onStatus("waiting for the engine")
                holdsLane = acquireLane(120)
                if (handle.cancelled) { Log.i("Latent", "full develop cancelled while waiting"); return@execute }
                onStatus("starting")
                out = Develop.developFull(app, source, isRaw, recipe, upscale = upscale, exposureMap = exposureMap, softenMask = softenMask, pairFirst = pairFirst, framing = framing, fogMask = fogMask, fogLook = fogLook, raysMask = raysMask, raysLook = raysLook, extraLights = extraLights) { m -> if (!handle.cancelled) onStatus(m) }
                if (handle.cancelled) { Log.i("Latent", "full develop finished after cancel; result discarded"); out = null }
            } catch (t: Throwable) {
                Log.e("Latent", "full develop failed", t)
                if (!handle.cancelled) onStatus("failed: ${t.message}")
            } finally {
                if (holdsLane) engineLane.release()
                pending.decrementAndGet(); onChanged()
                if (!handle.cancelled) onDone(out) else onDone(null)
            }
        }
        return handle
    }

    private val pool = Executors.newSingleThreadExecutor { r -> Thread(r, "latent-develop").apply { priority = Thread.MIN_PRIORITY } }

    /**
     * Only one engine may run at a time: each instance loads the film data and allocates a
     * full-resolution buffer, so two at once compete for memory and stall. The darkroom takes
     * this while it renders a preview; background developing waits its turn.
     */
    val engineLane = java.util.concurrent.Semaphore(1, true)

    /**
     * Waits for the lane, but never forever: a lost permit must not stop developing for good.
     * Returns true if it was acquired (and so must be released).
     */
    fun acquireLane(waitSeconds: Long = 45): Boolean = try {
        val got = engineLane.tryAcquire(waitSeconds, java.util.concurrent.TimeUnit.SECONDS)
        if (!got) android.util.Log.w("Latent", "engine lane not free after ${waitSeconds}s; running anyway")
        got
    } catch (t: InterruptedException) { false }
    private val pending = AtomicInteger(0)

    /** Number of photos waiting or being developed right now. */
    val queued: Int get() = pending.get()

    @Volatile var onChanged: () -> Unit = {}
    @Volatile var onDeveloped: (Uri) -> Unit = {}

    fun submit(context: Context, job: Job) {
        pending.incrementAndGet(); onChanged()
        pool.execute {
            // Same single thread as full-size work, so the lane is only about the darkroom preview.
            val holdsLane = acquireLane(120)
            try {
                // each photo's own framing (turned, straightened, widescreen) goes with it, and a
                // photo with its own recipe (a cinema shot) develops with that, not the shared one
                val framing = Framings.load(context.applicationContext, job.source)
                val recipe = com.celestial.latent.PhotoRecipes.load(context.applicationContext, job.source) ?: job.recipe
                val out = if (job.isRaw)
                              Develop.developFull(context.applicationContext, job.source, isRaw = true, recipe = recipe,
                                  pairFirst = job.first, framing = framing)
                          else Develop.developJpeg(context.applicationContext, job.source, recipe)
                onDeveloped(out)
            } catch (t: Throwable) {
                Log.e("Latent", "develop failed for ${job.source}", t)
            } finally {
                if (holdsLane) engineLane.release()
                pending.decrementAndGet(); onChanged()
            }
        }
    }
}
