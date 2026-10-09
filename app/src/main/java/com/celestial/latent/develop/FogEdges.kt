package com.celestial.latent.develop

import java.nio.FloatBuffer
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Fog that follows fine edges (60d). An atmosphere's thickness is worked out on the mask's grid
 * (128 cells on the long edge, about 32 px a cell on a 4080 px photo) and laid over the photo
 * smoothly, so thin dark things against a far bright sky (palm fronds, leaves, wires) share cells
 * with the sky: the sky right beside them got too little fog and showed as a bright, clear outline.
 *
 * The fix looks at the photo again: within each small area the laid-over thickness is explained
 * as a straight-line function of the photo's brightness (a guided filter, the same edge-keeping
 * filter as GuidedClean, guided by the square root of linear light), so each pixel takes the fog
 * of what it looks like — sky-bright pixels the sky's fog, frond-dark pixels the palm's.
 * Proved in Python (fog-edges/scripts/edges.py) on Celestial's palm: the sky beside the fronds
 * got 38% of the open sky's fog before and 57% after; no change where there is no sky.
 *
 * One pass down the picture: each stage keeps only the rows its window needs (rings of 2r+2 rows),
 * so it reads every row once and needs about 7·(2r+2) rows of working memory (~11 MB at 4080 px).
 */
object FogEdges {
    private val LUM = floatArrayOf(0.2880f, 0.7119f, 0.0001f)     // linear ProPhoto luminance
    /** How far each pixel looks for its explanation, in mask cells (reach 2, as chosen on the proof). */
    const val REACH = 2f
    const val EPS = 0.02f * 0.02f

    /** The window radius in pixels for a picture [w]×[h] carrying a mask of [mask]'s grid. */
    fun radius(w: Int, h: Int, mask: ExposureMap): Int =
        max(1, (REACH * max(w, h) / max(mask.width, mask.height).toFloat()).roundToInt())

    /**
     * Refines a thickness laid over the picture. [thick] fills row y's laid-over thickness; [emit]
     * receives every refined row once, top to bottom. [f] (interleaved linear RGB) is read only as
     * the guide, and row y is emitted only after the last row that needs it has been read, so [emit]
     * may change [f] in place.
     */
    fun refine(f: FloatBuffer, w: Int, h: Int, r: Int, eps: Float,
               thick: (Int, FloatArray) -> Unit, emit: (Int, FloatArray) -> Unit) {
        val n = 2 * r + 2                                  // ring size: a window plus the row leaving it
        val pre = DoubleArray(w + 1)
        fun hbox(src: FloatArray, dst: FloatArray) {
            pre[0] = 0.0; for (x in 0 until w) pre[x + 1] = pre[x] + src[x]
            for (x in 0 until w) { val a = max(0, x - r); val b = min(w, x + r + 1); dst[x] = ((pre[b] - pre[a]) / (b - a)).toFloat() }
        }
        val g = f.duplicate(); val rgb = FloatArray(w * 3); val prow = FloatArray(w); val tmp = FloatArray(w)
        // the photo's rows: brightness s, and the horizontal box averages of s, p, s·p, s·s
        val s = Array(n) { FloatArray(w) }
        val ms = Array(n) { FloatArray(w) }; val mp = Array(n) { FloatArray(w) }
        val msp = Array(n) { FloatArray(w) }; val mss = Array(n) { FloatArray(w) }
        var read = 0
        fun readRow() {
            val k = read % n; val sk = s[k]
            g.position(read * w * 3); g.get(rgb)
            for (x in 0 until w) sk[x] = sqrt(max(LUM[0] * rgb[x * 3] + LUM[1] * rgb[x * 3 + 1] + LUM[2] * rgb[x * 3 + 2], 0f))
            thick(read, prow)
            hbox(sk, ms[k]); hbox(prow, mp[k])
            for (x in 0 until w) tmp[x] = sk[x] * prow[x]
            hbox(tmp, msp[k])
            for (x in 0 until w) tmp[x] = sk[x] * sk[x]
            hbox(tmp, mss[k])
            read++
        }
        // a and b per row, box-averaged across
        val A = Array(n) { FloatArray(w) }; val B = Array(n) { FloatArray(w) }
        val S1 = DoubleArray(w); val S2 = DoubleArray(w); val S3 = DoubleArray(w); val S4 = DoubleArray(w)
        val T1 = DoubleArray(w); val T2 = DoubleArray(w)
        var lo1 = 0; var hi1 = -1; var lo2 = 0; var hi2 = -1        // rows in each vertical window, inclusive
        val row = FloatArray(w)
        fun output(y: Int) {
            val nLo = max(0, y - r); val nHi = min(h - 1, y + r)
            while (lo2 < nLo) { val k = lo2 % n; for (x in 0 until w) { T1[x] -= A[k][x]; T2[x] -= B[k][x] }; lo2++ }
            while (hi2 < nHi) { hi2++; val k = hi2 % n; for (x in 0 until w) { T1[x] += A[k][x]; T2[x] += B[k][x] } }
            val cnt = (hi2 - lo2 + 1).toDouble(); val sk = s[y % n]
            for (x in 0 until w) row[x] = max(0.0, T1[x] / cnt * sk[x] + T2[x] / cnt).toFloat()
            emit(y, row)
        }
        for (j in 0 until h) {
            val nLo = max(0, j - r); val nHi = min(h - 1, j + r)
            // leave first, then read: the new row takes the ring slot of a row already left
            while (lo1 < nLo) { val k = lo1 % n; for (x in 0 until w) { S1[x] -= ms[k][x]; S2[x] -= mp[k][x]; S3[x] -= msp[k][x]; S4[x] -= mss[k][x] }; lo1++ }
            while (hi1 < nHi) {
                hi1++; while (read <= hi1) readRow()
                val k = hi1 % n; for (x in 0 until w) { S1[x] += ms[k][x]; S2[x] += mp[k][x]; S3[x] += msp[k][x]; S4[x] += mss[k][x] }
            }
            val cnt = (hi1 - lo1 + 1).toDouble(); val k = j % n; val aj = A[k]; val bj = B[k]
            for (x in 0 until w) {
                val mI = S1[x] / cnt; val mP = S2[x] / cnt
                val cov = S3[x] / cnt - mI * mP; val v = max(S4[x] / cnt - mI * mI, 0.0)
                val a = cov / (v + eps)
                aj[x] = a.toFloat(); bj[x] = (mP - a * mI).toFloat()
            }
            hbox(aj, tmp); tmp.copyInto(aj); hbox(bj, tmp); tmp.copyInto(bj)
            if (j >= r) output(j - r)
        }
        for (y in max(0, h - r) until h) output(y)
    }
}
