package com.celestial.latent.camera

import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToLong

/**
 * An HDR bracket (86b): the user sets the darkest exposure (for the sky) and the brightest (for the
 * shadows) by hand, picks how many frames, and the frames in between step evenly in stops. The app
 * never chooses a shutter or ISO of its own: when both ends share a shutter only ISO steps, when
 * both share an ISO only the shutter steps, otherwise both step, each evenly.
 */
object Bracket {
    val FRAME_CHOICES = listOf(3, 4, 5, 7)

    class Step(val shutterNs: Long, val iso: Int) {
        val exposure get() = shutterNs.toDouble() * iso
    }

    enum class Mode(val label: String) {
        ISO("SHUTTER FIXED · ISO STEPS"),
        SHUTTER("ISO FIXED · SHUTTER STEPS"),
        BOTH("BOTH STEP"),
        SAME("SAME EXPOSURE"),
    }

    /** The two ends in order, darkest first (the user may set them either way round). */
    fun ends(aNs: Long, aIso: Int, bNs: Long, bIso: Int): Pair<Step, Step> {
        val a = Step(aNs, aIso); val b = Step(bNs, bIso)
        return if (a.exposure <= b.exposure) a to b else b to a
    }

    fun mode(dark: Step, bright: Step): Mode = when {
        dark.shutterNs == bright.shutterNs && dark.iso == bright.iso -> Mode.SAME
        dark.shutterNs == bright.shutterNs -> Mode.ISO
        dark.iso == bright.iso -> Mode.SHUTTER
        else -> Mode.BOTH
    }

    /** Stops between the two ends. */
    fun stops(dark: Step, bright: Step) = ln(bright.exposure / dark.exposure) / ln(2.0)

    /**
     * [n] frames from [dark] to [bright], shutter and ISO each stepped evenly in stops (log steps), and
     * snapped to the third-stop numbers a camera shows when one is within a sixth of a stop. The ends
     * are exactly what the user set.
     */
    fun plan(dark: Step, bright: Step, n: Int): List<Step> = List(n) { i ->
        when (i) {
            0 -> dark
            n - 1 -> bright
            else -> {
                val t = i.toDouble() / (n - 1)
                val ns = dark.shutterNs * (bright.shutterNs.toDouble() / dark.shutterNs).pow(t)
                val iso = dark.iso * (bright.iso.toDouble() / dark.iso).pow(t)
                Step(snapShutter(ns), snapIso(iso))
            }
        }
    }

    private val SHUTTER_SECONDS = listOf(8000, 6400, 5000, 4000, 3200, 2500, 2000, 1600, 1250, 1000, 800, 640, 500, 400, 320, 250, 200, 160, 125,
        100, 80, 60, 50, 40, 30, 25, 20, 15, 13, 10, 8, 6, 5, 4).map { 1.0 / it } + listOf(0.3, 0.4, 0.5, 0.6, 0.8, 1.0, 1.3, 1.6, 2.0)
    private val ISO_LADDER = listOf(25, 32, 40, 50, 64, 80, 100, 125, 160, 200, 250, 320, 400, 500, 640, 800, 1000, 1250, 1600, 2000, 2500,
        3200, 4000, 5000, 6400, 8000, 10000, 12800)
    private const val SIXTH = 1.0 / 6

    internal fun snapShutter(ns: Double): Long {
        val s = ns / 1e9
        val near = SHUTTER_SECONDS.minByOrNull { abs(ln(it / s)) }!!
        return if (abs(ln(near / s) / ln(2.0)) <= SIXTH) (near * 1e9).roundToLong() else ns.roundToLong()
    }

    internal fun snapIso(iso: Double): Int {
        val near = ISO_LADDER.minByOrNull { abs(ln(it / iso)) }!!
        return if (abs(ln(near / iso) / ln(2.0)) <= SIXTH) near else Math.round(iso).toInt()
    }

    /** About how long the frames take: each at least one 30 fps frame, longer shutters their own length. */
    fun seconds(plan: List<Step>) = plan.sumOf { maxOf(33_333_333L, it.shutterNs) } / 1e9

    fun shutterLabel(ns: Long) = ControlMath.shutterLabel(ns)

    /** "4 frames · 1/500 s · ISO 50 to 800 · about 0.1 s" */
    fun planLine(plan: List<Step>): String {
        val d = plan.first(); val b = plan.last()
        val s = if (d.shutterNs == b.shutterNs) "${shutterLabel(d.shutterNs)} s" else "${shutterLabel(d.shutterNs)} to ${shutterLabel(b.shutterNs)} s"
        val i = if (d.iso == b.iso) "ISO ${d.iso}" else "ISO ${d.iso} to ${b.iso}"
        val t = seconds(plan)
        return "${plan.size} frames · $s · $i · about ${if (t < 10) "%.1f".format(java.util.Locale.US, t) else "%.0f".format(java.util.Locale.US, t)} s"
    }

    /** The label under one frame of the ladder: what changes in it. */
    fun stepLabel(step: Step, mode: Mode): String = when (mode) {
        Mode.ISO -> "${step.iso}"
        Mode.SHUTTER -> shutterLabel(step.shutterNs)
        else -> "${shutterLabel(step.shutterNs)}·${step.iso}"
    }
}
