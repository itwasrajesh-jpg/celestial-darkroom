package com.celestial.latent.ui

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/*
 * The mascot's moves (114a): a rule-for-rule port of the approved timing (mascot/vector3/scripts/render3.py:
 * idle, wave + jump v3, shoot, oops) and of the placement proof's breathing and tap jump
 * (mascot/placement/scripts/mm2.py). A pose is a bag of named values, as the Python's dict is, so
 * the blend between two poses (mm2._mix) follows the same rules key by key.
 */

/** One frame's pose: named numbers, flags, words and short tuples (DoubleArray), as render3.py's dicts. */
class MascotPose(val m: HashMap<String, Any> = HashMap()) {
    fun d(k: String, def: Double) = (m[k] as Double?) ?: def
    fun dn(k: String) = m[k] as Double?
    fun v(k: String) = m[k] as DoubleArray?
    fun s(k: String, def: String) = (m[k] as String?) ?: def
    fun b(k: String, def: Boolean = false) = (m[k] as Boolean?) ?: def
    @Suppress("UNCHECKED_CAST") fun thumbs() = (m["thumbs"] as List<DoubleArray>?) ?: emptyList()
    operator fun set(k: String, v: Any) { m[k] = v }
    fun copy() = MascotPose(HashMap(m))
}

enum class MascotScene { HELLO, LIFT, OOPS }

object MascotMoves {
    private fun ease(x0: Double): Double { val x = min(max(x0, 0.0), 1.0); return x * x * (3 - 2 * x) }
    private fun lerp(a: Double, b: Double, k: Double) = a + (b - a) * k
    private fun lerp2(a: DoubleArray, b: DoubleArray, k: Double) = doubleArrayOf(lerp(a[0], b[0], k), lerp(a[1], b[1], k))
    private fun d2(x: Double, y: Double) = doubleArrayOf(x, y)
    private fun pmod(a: Double, b: Double) = ((a % b) + b) % b   // Python's % for floats

    private fun blinkAt(t: Double, every: Double = 2.7, off: Double = 0.4): Double {
        val ph = pmod(t + off, every)
        return if (ph < 0.16) max(0.0, 1 - abs(ph - 0.08) / 0.08) else 0.0
    }

    private fun rest() = MascotPose().apply {
        this["handL"] = d2(-0.74, -0.52); this["handR"] = d2(0.74, -0.52); this["angL"] = PI / 2 + 0.25; this["angR"] = PI / 2 - 0.25; this["cameraHip"] = true
    }

    fun idle(t: Double) = rest().apply {
        this["bob"] = 0.02 * sin(t * 2.4); this["squash"] = 0.012 * sin(t * 2.4)
        this["blink"] = blinkAt(t); this["look"] = 0.03 * sin(t * 0.7); this["petal"] = 0.05 * sin(t * 2.4 + 0.8)
    }

    private val REST_FK_R: DoubleArray = run {
        val e = elbowOf(0.5, -1.1, 0.74, -0.52, 1.0, 0.7)
        doubleArrayOf(atan2(e[1] + 1.1, e[0] - 0.5), atan2(e[3] - e[1], e[2] - e[0]))
    }

    /* ---- the jump at the end of the wave, measured frame by frame from Celestial's reference clip (24 fps) ---- */
    private const val PX = 412.0
    private const val F0 = 8
    val JUMP_LEN = (66 - F0) / 24.0

    private fun sample(arr: DoubleArray, x: Double): Double {
        val f = x * 24.0
        if (f <= 0) return arr[0]
        if (f >= arr.size - 1) return arr[arr.size - 1]
        val k = f.toInt(); val u = f - k; return arr[k] * (1 - u) + arr[k + 1] * u
    }
    private fun table(f: Double, pts: DoubleArray): Double {
        if (f <= pts[0]) return pts[1]
        var i = 0
        while (i + 3 < pts.size) {
            val a = pts[i]; val va = pts[i + 1]; val b = pts[i + 2]; val vb = pts[i + 3]
            if (f <= b) return va + (vb - va) * (f - a) / (b - a)
            i += 2
        }
        return pts[pts.size - 1]
    }
    private val HAPPY = doubleArrayOf(26.0, 0.0, 27.0, 0.45, 28.0, 1.0, 49.0, 1.0, 50.0, 0.45, 51.0, 0.0)

    private class Jump(val bob: Double, val squash: Double, val legY: Double, val legSpread: Double, val smile: Double, val mouthOpen: Double, val happyEyes: Double)

    /** Pose values for the jump, x seconds after the crouch starts: the body follows the clip's lens path. */
    private fun jump(x: Double): Jump {
        val d = sample(JUMP_D, x); val v = (sample(JUMP_D, x + 1 / 48.0) - sample(JUMP_D, x - 1 / 48.0)) * 24
        val dl = sample(JUMP_D, x - 1.5 / 24)
        val sq = if (d > 0) min(0.14, d / 0.92 * 0.8) else -min(0.07, 0.03 * abs(v))
        val chest0 = -0.92
        val bob = (chest0 + d) / (1 - sq) - chest0
        val f = x * 24
        fun r(a: Double, b: Double) = ease((f - a) / (b - a))
        return Jump(bob, sq, min(0.0, min(d, dl)) * 0.95, 0.16 * max(0.0, -d) / 0.45,
            (0.35 + 0.35 * r(11.0, 18.0)) * r(1.5, 2.5) * (1 - r(11.5, 12.5)),
            r(12.0, 16.0) * (1 - r(42.0, 49.5)),
            table(f, HAPPY))
    }

    /* ---- both-hands camera grip and the elbows during the jump ---- */
    private val GRIP_CAM = d2(-0.05, -0.92); private const val GRIP_Z = 0.22
    private fun sh(side: Char) = if (side == 'L') d2(-0.5, -1.1) else d2(0.5, -1.1)
    private fun pole0(side: Char) = if (side == 'L') doubleArrayOf(-0.75, 1.0, -0.25) else doubleArrayOf(0.75, 1.0, -0.25)
    private fun gripHand(side: Char, cam: DoubleArray = GRIP_CAM) = d2(cam[0] + (if (side == 'R') 0.34 else -0.34), cam[1] + 0.06)
    private fun setGrip(p: MascotPose) {
        p["cameraHip"] = false; p["cameraHand"] = GRIP_CAM; p["cameraScale"] = 1.0; p["cameraAng"] = -0.06
        p["strapEnd"] = d2(-0.55, -0.92); p.m.remove("fkR")
        for (side in "LR") { p["hand$side"] = gripHand(side); p["z$side"] = GRIP_Z; p["front$side"] = true; p["kind$side"] = "mitt" }
        p["angL"] = -0.2; p["angR"] = PI + 0.2
    }

    /** the reference clip's elbow-tip movement (units, from its place at the crouch), x seconds after the crouch starts */
    private fun elbowRef(side: Char, x: Double): DoubleArray {
        val e = if (side == 'R') ARM_ER else ARM_EL; val nf = e.size / 2
        val f = min(max(x * 24 + F0, 0.0), (nf - 1).toDouble()); val k = f.toInt(); val u = f - k; val k2 = min(k + 1, nf - 1)
        return DoubleArray(2) { i -> (e[2 * k + i] * (1 - u) + e[2 * k2 + i] * u - e[2 * F0 + i]) / PX }
    }
    private val EL0 = mapOf('L' to sh('L').let { s -> elbow3(s[0], s[1], gripHand('L')[0], gripHand('L')[1], GRIP_Z, pole0('L')) },
                            'R' to sh('R').let { s -> elbow3(s[0], s[1], gripHand('R')[0], gripHand('R')[1], GRIP_Z, pole0('R')) })
    private const val SHRUG = 0.06
    private const val ARM_STRETCH = 0.6; private const val WING_GAIN = 1.0; private const val POLE_Z = 0.2

    /** the grip pose as joint angles + seen-from-front bone lengths, so the wave blends into it with no jump */
    private fun gripFk(side: Char = 'R'): DoubleArray {
        val e = EL0.getValue(side); val s = sh(side)
        return doubleArrayOf(atan2(e[1] - s[1], e[0] - s[0]), atan2(e[3] - e[1], e[2] - e[0]), hypot(e[0] - s[0], e[1] - s[1]), hypot(e[2] - e[0], e[3] - e[1]))
    }

    const val JUMP_AT = 2.95

    /** Hello: three waves from the elbow, the hand comes down onto the camera, then the jump at [jumpAt]. */
    fun wave(t: Double, jumpAt: Double = JUMP_AT): MascotPose {
        val p = idle(t)
        val up = ease((t - 0.25) / 0.45)
        val ph = max(0.0, t - 0.55); val amp = 0.42 * ease(ph / 0.3) * (1 - ease((t - 2.35) / 0.3))
        val rock = amp * sin(ph * 2 * PI * 1.55)
        val upF = ease((t - 0.2) / 0.4)
        val a1 = lerp(REST_FK_R[0], Math.toRadians(-12.0), up) + 0.12 * rock
        val a2 = lerp(REST_FK_R[1], Math.toRadians(-88.0), upF) + rock
        val hang = lerp(PI / 2 - 0.25, a2 + 0.25 * rock, upF)
        val g = ease((t - 2.5) / 0.45)
        val gf = gripFk('R'); val ga2 = if (gf[1] > 0) gf[1] - 2 * PI else gf[1]
        p["fkR"] = doubleArrayOf(lerp(a1, gf[0], g), lerp(a2, ga2, ease((t - 2.55) / 0.4)), lerp(ARM[0], gf[2], g), lerp(ARM[1], gf[3], g))
        p["handR"] = d2(0.0, 0.0); p["frontR"] = true
        p["kindR"] = if (upF > 0.5 && g < 0.6) "open" else "mitt"
        p["angR"] = lerp(hang, PI + 0.2 - 2 * PI, g)
        p["wiggle"] = sin(t * 18) * up * (1 - g)
        p["tilt"] = (-0.07 * up + 0.04 * rock * up) * (1 - g)
        val cam = lerp2(d2(-0.32, -0.95), GRIP_CAM, g)
        p["cameraHip"] = false; p["cameraHand"] = cam; p["cameraAng"] = lerp(-0.08, -0.06, g); p["strapEnd"] = lerp2(d2(-0.62, -0.95), d2(-0.55, -0.92), g)
        p["handL"] = lerp2(d2(-0.64, -0.92), gripHand('L'), g); p["angL"] = lerp(-0.3, -0.2, g); p["frontL"] = true
        p["zL"] = GRIP_Z; p["poleL"] = pole0('L')
        if (g >= 0.95) {
            val hl = gripHand('L'); val hr = gripHand('R')
            p["thumbs"] = listOf(doubleArrayOf(hl[0] + 0.05, hl[1] + 0.03, -0.5), doubleArrayOf(hl[0] + 0.06, hl[1] + 0.11, -0.2),
                doubleArrayOf(hr[0] - 0.05, hr[1] + 0.03, 0.5), doubleArrayOf(hr[0] - 0.06, hr[1] + 0.11, 0.2))
        }
        p["smile"] = ease((t - 0.45) / 0.3) * (1 - ease((t - 2.65) / 0.3))
        if (t >= jumpAt) {
            val x = t - jumpAt; val j = jump(x)
            p["bob"] = j.bob; p["squash"] = j.squash; p["legY"] = j.legY; p["legSpread"] = j.legSpread
            p["petal"] = p.d("petal", 0.0) + 0.5 * j.bob; p["blink"] = 0.0
            p["smile"] = j.smile; p["mouthOpen"] = j.mouthOpen; p["happyEyes"] = j.happyEyes
            setGrip(p)
            // "chicken wings", as in the clip: hands stay on the camera's sides, the elbows follow the clip's elbow path,
            // the arms stretch a little while it jumps
            val eR = elbowRef('R', x); val fR = min(1.0, max(0.0, max(-eR[1] / 0.42, eR[0] / 0.24)))
            for (side in "LR") {
                val sg = if (side == 'R') 1 else -1
                val e = elbowRef(side, x)
                val f = if (side == 'R') fR else min(1.0, max(0.0, max(-e[1] / 0.42, -e[0] / 0.24) * 2.5))
                val h = gripHand(side); p["hand$side"] = d2(h[0] + sg * 0.03 * f, h[1] + 0.02 * f)
                p["shrug$side"] = SHRUG * f; p["stretch$side"] = 1 + ARM_STRETCH * f
                val e0 = EL0.getValue(side); val s0 = sh(side)
                val sx = s0[0] + sg * SHRUG * f; val sy = s0[1] - 0.7 * SHRUG * f
                val want = d2(e0[0] + WING_GAIN * e[0] - sx, e0[1] + WING_GAIN * e[1] - sy)
                val rest = d2(e0[0] - s0[0], e0[1] - s0[1])
                p["pole$side"] = doubleArrayOf(lerp(rest[0], want[0], f), lerp(rest[1], want[1], f), lerp(e0[4], POLE_Z, f))
            }
            val hl = p.v("handL")!!; val hr = p.v("handR")!!
            p["thumbs"] = listOf(doubleArrayOf(hl[0] + 0.05, hl[1] + 0.03, -0.5), doubleArrayOf(hl[0] + 0.06, hl[1] + 0.11, -0.2),
                doubleArrayOf(hr[0] - 0.05, hr[1] + 0.03, 0.5), doubleArrayOf(hr[0] - 0.06, hr[1] + 0.11, 0.2))
            p["angR"] = lerp(PI + 0.2, PI / 2 + 0.55, fR); p["angL"] = lerp(-0.2, PI / 2 - 0.55, 0.8 * fR)
            p["legSpread"] = 0.42 * max(0.0, -sample(JUMP_D, x)) / 0.45
        }
        return p
    }

    /** How far the camera is raised (0 chest, 1 eye): a quick lift with a small overshoot, a hold, a calmer way down. */
    private fun liftCurve(t: Double, up: Double = 0.35, upLen: Double = 0.5, down: Double = 2.35, downLen: Double = 0.6): Double {
        val x = (t - up) / upLen
        var k = if (x <= 0) 0.0 else if (x >= 1) 1.0 else { val c = 1.4; 1 + (c + 1) * (x - 1) * (x - 1) * (x - 1) + c * (x - 1) * (x - 1) }
        val y = (t - down) / downLen
        if (y > 0) k *= 1 - ease(y)
        return k
    }

    /** App help: the camera comes up to the eye, shutter + flash, back down. Arms solved in 3D. */
    fun shoot(t: Double): MascotPose {
        val p = idle(t); val k = liftCurve(t); val kk = min(max(k, 0.0), 1.0)
        val arc = sin(PI * kk)
        p["cameraHip"] = false; p["strapEnd"] = lerp2(d2(-0.55, -0.92), d2(-0.4, -1.5), kk)
        val cam = lerp2(d2(-0.05, -0.92), d2(0.02, -1.62), k); val cs = lerp(1.0, 1.2, kk)
        p["cameraHand"] = cam; p["cameraScale"] = cs; p["cameraAng"] = lerp(-0.06, 0.0, kk)
        val spread = 0.34 * cs + 0.05 * arc
        p["handL"] = d2(cam[0] - spread, cam[1] + 0.06); p["handR"] = d2(cam[0] + spread, cam[1] + 0.06)
        val z = 0.22 + 0.14 * kk + 0.08 * arc; p["zL"] = z; p["zR"] = z
        p["angL"] = -0.2; p["angR"] = PI + 0.2
        p["poleL"] = doubleArrayOf(-0.75, 1.0, -0.25); p["poleR"] = doubleArrayOf(0.75, 1.0, -0.25)
        p["frontL"] = true; p["frontR"] = true
        val s = t - 1.55
        p["flash"] = if (s >= 0 && s < 0.25) max(0.0, 1 - s / 0.25) else 0.0
        if (s >= 0) { p["squash"] = 0.045 * exp(-s * 9) * cos(s * 22); p["bob"] = p.d("bob", 0.0) + 0.03 * exp(-s * 9) * sin(s * 22) }
        p["blink"] = if (s >= 0.0 && s < 0.12) 1.0 else if (kk > 0.3) 0.0 else p.d("blink", 0.0)
        return p
    }

    /** The crash screen: camera dropped, scratching its head, sheepish. */
    fun oops(t: Double): MascotPose {
        val p = idle(t); p["cameraHip"] = false; p["strap"] = false
        p["sheepish"] = true; p["tilt"] = 0.12 + 0.02 * sin(t * 3)
        p["handR"] = d2(0.6, -1.98 + 0.04 * sin(t * 10)); p["frontR"] = true; p["poleR"] = d2(1.0, 0.2); p["angR"] = -PI / 2 - 0.9
        p["squash"] = 0.04 * exp(-t * 3) * cos(t * 14)
        p["sweat"] = ease(t / 0.4); p["blush"] = 0.6
        p["cameraFloor"] = d2(0.95, -0.2 - 0.04 * abs(sin(t * 6)) * exp(-t * 1.5)); p["cameraFloorAng"] = 0.45 + 0.15 * sin(t * 6) * exp(-t * 1.5)
        return p
    }

    /* ---- the placement proof's additions (mm2.py) ---- */

    /** after a jump has settled, the idle breathing and blinking come back */
    private fun breathe(p: MascotPose, t: Double, x: Double): MascotPose {
        val k = min(max((x - JUMP_LEN) / 0.4, 0.0), 1.0)
        if (k > 0) {
            val i = idle(t)
            p["bob"] = p.d("bob", 0.0) + k * i.d("bob", 0.0); p["squash"] = p.d("squash", 0.0) + k * i.d("squash", 0.0)
            p["blink"] = if (k > 0.5) i.d("blink", 0.0) else 0.0
            p["petal"] = p.d("petal", 0.0) + k * 0.05 * sin(t * 2.4 + 0.8)
        }
        return p
    }

    /** mm2._mix: numbers and same-length tuples blend; anything else switches half way. */
    private fun mix(a: MascotPose, b: MascotPose, k: Double): MascotPose {
        val out = b.copy()
        for (key in a.m.keys + b.m.keys) {
            val va = a.m[key]; val vb = b.m[key]
            if (va is Double && vb is Double) out[key] = va + (vb - va) * k
            else if (va is DoubleArray && vb is DoubleArray && va.size == vb.size) out[key] = DoubleArray(va.size) { va[it] + (vb[it] - va[it]) * k }
            else if (vb == null && k < 0.5) { if (va != null) out[key] = va }
            else if (k < 0.5 && va != null) out[key] = va
        }
        if (out.m.containsKey("fkR") && k >= 0.5 && !b.m.containsKey("fkR")) out.m.remove("fkR")
        return out
    }

    private fun base(scene: MascotScene, t: Double) = when (scene) {
        MascotScene.HELLO -> breathe(wave(t), t, t - JUMP_AT)
        MascotScene.LIFT -> shoot(t)
        MascotScene.OOPS -> oops(t)
    }

    /** The pose [t] seconds after the screen opened; [tap] = when the last tap's jump began (eases in over 0.2 s). */
    fun pose(scene: MascotScene, t: Double, tap: Double? = null): MascotPose {
        if (tap != null && t >= tap) return mix(base(scene, t), breathe(wave(t, tap), t, t - tap), ease((t - tap) / 0.2))
        return base(scene, t)
    }

    /** When a tap may start a jump: after the opening moment has played, and not during another jump. */
    fun canJump(scene: MascotScene, t: Double, tap: Double?): Boolean = when (scene) {
        MascotScene.OOPS -> false
        MascotScene.HELLO -> t > JUMP_AT + JUMP_LEN + 0.4 && (tap == null || t - tap > JUMP_LEN + 0.4)
        MascotScene.LIFT -> t > 3.0 && (tap == null || t - tap > JUMP_LEN + 0.4)
    }

    /** The resting pose shown, still, when the phone's animations are off: mid-breath, eyes open, everything settled. */
    const val STILL_T = 13.09
    // ref_jump_track.json: the camera lens height per clip frame 8..66, in units below its rest (+ = lower)
    private val JUMP_D = doubleArrayOf(0.0042378312154049734, 0.012384895445499852, 0.022505587985972065, 0.03401838460652667, 0.04571613367602395, 0.05649509130375918, 0.06503601523323392, 0.07011574481942993, 0.06973851034664101, 0.05102719487394118, 0.001285984269753119, -0.07109205919517178, -0.16357683191999006, -0.24981758404902993, -0.32663092008527067, -0.38224236244851906, -0.42348345107920377, -0.4461735382435984, -0.4499342139086074, -0.4497847151320662, -0.44495403010046963, -0.4324922769349731, -0.381614407667573, -0.2943337345650644, -0.1741141111102065, -0.03281319336220882, 0.14207034325406337, 0.16737666671210866, 0.17889285914566602, 0.18024283599071678, 0.17231450343293486, 0.14678970268909566, 0.09121806422054701, 0.030772620281379327, -0.026955508024001654, -0.07338049687744772, -0.10942674034670188, -0.13431873632733196, -0.14706038754718673, -0.15062625114541273, -0.14866897519376404, -0.14567341016236673, -0.13402522086620403, -0.11525702944258798, -0.0864629898069159, -0.05539531058575758, -0.024360824946117448, 0.0055398127153542314, 0.026957751411620982, 0.04085171637947148, 0.04423770666352891, 0.04354708815677955, 0.04051423448021848, 0.034512789220655156, 0.025159306994899537, 0.016337912748122272, 0.009449140238755881, 0.0029456055008775595, 0.0005743807117967451)
    // ref_arm_track.json: the elbow tips per clip frame (px, x/y pairs), right then left
    private val ARM_ER = doubleArrayOf(362.9460285132383, 51.33910386965374, 362.84285714285716, 51.295918367346985, 362.79531568228106, 51.28615071283093, 362.79531568228106, 51.28615071283093, 362.7734693877551, 51.25510204081638, 362.7734693877551, 51.25510204081638, 362.7734693877551, 51.25510204081638, 362.7734693877551, 51.25510204081638, 362.96558704453446, 51.59311740890689, 362.8532934131737, 51.2365269461078, 362.87651821862346, 51.06680161943325, 363.05780933062886, 53.32352941176475, 362.8711967545639, 51.50405679513187, 362.8066132264529, 53.06312625250496, 362.87625754527164, 51.54426559356136, 362.94129554655865, 51.45141700404861, 363.2264957264957, 53.606837606837644, 367.8305439330544, 39.315899581589974, 381.82371134020616, 33.809278350515456, 401.4153225806451, 31.629032258064512, 424.9224137931035, 1.7327586206896513, 442.13948497854074, -16.736051502145926, 455.15848670756645, -54.088957055214735, 459.7101167315175, -82.17704280155641, 461.02653061224487, -104.18571428571431, 460.410569105691, -102.83739837398372, 460.288, -116.28800000000001, 459.30081300813004, -122.34959349593498, 459.6930184804928, -122.33983572895278, 459.1779796900054, -120.47407803313735, 459.51284796573873, -122.43576017130619, 460.8640167364017, -114.39539748953973, 461.6952941176471, -122.92588235294119, 460.393023255814, -102.14186046511622, 456.59387755102046, -76.19387755102036, 440.5538302277432, -14.620082815735032, 408.21721311475414, 15.635245901639337, 376.3357289527721, 39.07905544147843, 352.9837398373984, 57.34552845528458, 344.0758835758836, 49.86174636174633, 350.99377593361, 47.757261410788374, 368.61764705882354, 47.66078431372546, 389.2751479289941, 27.444773175542423, 410.4548254620123, 17.5718685831622, 429.6217038539554, -6.577079107505085, 443.1970954356847, -12.321576763485496, 450.2037037037037, -30.07201646090533, 453.32304526748976, -40.60288065843622, 454.5, -48.40927835051548, 454.43762993762994, -46.64345114345116, 453.46714579055447, -38.442505133470206, 449.5666666666667, -28.17500000000001, 441.3762886597938, -16.03814432989691, 431.4718309859155, -6.838028169014137, 417.97454175152745, 11.37576374745413, 402.78453608247423, 29.056701030927798, 388.5041152263375, 29.232510288065896, 376.61475409836066, 41.50819672131149, 365.3971193415638, 51.11316872427983, 358.5041753653445, 49.397703549060566, 354.2832310838446, 49.647239263803726, 355.31404958677683, 59.11983471074382, 356.2061224489796, 57.97346938775513, 358.2344398340249, 57.607883817427364, 360.37136929460576, 55.44605809128632, 362.37654320987656, 55.125514403292186, 363.28278688524586, 53.10245901639348, 363.3562628336756, 51.22689938398355, 363.3118609406953, 51.187116564417124, 363.3118609406953, 51.187116564417124, 363.3118609406953, 51.187116564417124, 363.5162271805274, 51.11663286004057, 363.4756097560976, 51.101626016260184, 363.47137014314933, 51.25255623721887, 363.4633401221996, 51.35539714867616, 363.4918699186992, 51.382113821138205, 363.25918367346935, 51.55306122448985, 363.25918367346935, 51.55306122448985, 363.2281947261663, 51.72515212981739, 363.36317907444663, 51.562374245472824, 363.3906882591093, 51.39068825910931, 363.3906882591093, 51.39068825910931, 363.3906882591093, 51.39068825910931, 363.3906882591093, 51.39068825910931)
    private val ARM_EL = doubleArrayOf(-363.0539714867617, 25.33910386965374, -363.15714285714284, 25.295918367346985, -363.20468431771894, 25.28615071283093, -363.20468431771894, 25.28615071283093, -363.2265306122449, 25.255102040816382, -363.2265306122449, 25.255102040816382, -363.2265306122449, 25.255102040816382, -363.2265306122449, 27.255102040816382, -361.03441295546554, 17.593117408906892, -359.1467065868263, 21.2365269461078, -357.12348178137654, 33.06680161943325, -352.94219066937114, 13.323529411764753, -351.1288032454361, 17.504056795131874, -348.1933867735471, 19.063126252504958, -345.12374245472836, 17.544265593561363, -345.05870445344135, 21.451417004048608, -343.7735042735043, 23.606837606837644, -345.1694560669456, 21.315899581589974, -350.17628865979384, 37.809278350515456, -356.5846774193549, 37.62903225806451, -364.0775862068965, 23.73275862068965, -371.86051502145926, 9.263948497854074, -377.84151329243355, 1.9110429447852653, -384.2898832684825, -4.177042801556411, -387.97346938775513, -20.18571428571431, -390.589430894309, -26.837398373983717, -392.712, -24.28800000000001, -392.69918699186996, -30.349593495934982, -392.3069815195072, -30.339835728952778, -392.8220203099946, -30.474078033137346, -392.48715203426127, -28.435760171306185, -392.1359832635983, -28.395397489539732, -391.3047058823529, -24.925882352941187, -390.606976744186, -24.141860465116224, -386.40612244897954, -18.193877551020364, -380.4461697722568, -0.6200828157350315, -371.78278688524586, 19.635245901639337, -362.6642710472279, 27.07905544147843, -355.0162601626016, 37.34552845528458, -349.9241164241164, 31.861746361746327, -348.00622406639, 31.757261410788374, -350.38235294117646, 41.66078431372546, -354.7248520710059, 37.44477317554242, -358.5451745379877, 33.5718685831622, -362.3782961460446, 33.422920892494915, -366.8029045643153, 23.678423236514504, -368.7962962962963, 15.927983539094669, -370.67695473251024, 13.39711934156378, -372.5, 11.590721649484522, -372.56237006237006, 11.35654885654884, -371.53285420944553, 11.557494866529794, -370.4333333333333, 15.824999999999989, -370.6237113402062, 15.961855670103091, -368.5281690140845, 17.161971830985863, -367.02545824847255, 21.37576374745413, -364.21546391752577, 25.056701030927798, -362.4958847736625, 27.232510288065896, -360.38524590163934, 29.508196721311492, -358.6028806584362, 21.11316872427983, -358.4958246346555, 33.397703549060566, -358.7167689161554, 33.647239263803726, -358.68595041322317, 33.11983471074382, -358.7938775510204, 31.97346938775513, -358.7655601659751, 19.607883817427364, -360.62863070539424, 29.446058091286318, -362.62345679012344, 29.125514403292186, -362.71721311475414, 27.10245901639348, -362.6437371663244, 25.22689938398355, -362.6881390593047, 25.187116564417124, -362.6881390593047, 25.187116564417124, -362.6881390593047, 25.187116564417124, -362.4837728194726, 25.116632860040568, -362.5243902439024, 25.101626016260184, -362.52862985685067, 25.25255623721887, -362.5366598778004, 25.35539714867616, -362.5081300813008, 25.382113821138205, -362.74081632653065, 25.553061224489852, -362.74081632653065, 25.553061224489852, -362.7718052738337, 25.72515212981739, -362.63682092555337, 25.562374245472824, -362.6093117408907, 25.39068825910931, -362.6093117408907, 25.39068825910931, -362.6093117408907, 25.39068825910931, -362.6093117408907, 25.39068825910931)
}
