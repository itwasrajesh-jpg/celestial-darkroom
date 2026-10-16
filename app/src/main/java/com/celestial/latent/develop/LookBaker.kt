package com.celestial.latent.develop

import android.content.Context
import android.util.Log
import com.spectrafilm.engine.LinearImage
import com.spectrafilm.engine.SpektraEngine
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Bakes the current recipe into a small colour table for the viewfinder.
 *
 * The engine renders the film's colour and tone into a 3D lookup table, which a shader can
 * apply to every preview frame. Spatial effects — grain, halation, diffusion, glare — cannot
 * live in a table and are absent from the live view by design; they arrive on development.
 *
 * A baked table carries no exposure, so the caller must scale the image by [gainFor] first,
 * which is the same gain the engine's own auto-exposure would apply. Without it the preview
 * sits in the film's toe and looks darker and flatter than the developed file.
 */
object LookBaker {

    const val SIZE = 33

    data class Look(val table: FloatArray, val size: Int, val gain: Float, val recipeKey: String)

    @Volatile private var cached: Look? = null

    /** Forget the cached look, so the next bake reflects edits made in the darkroom. */
    fun invalidate() { cached = null }

    /** The look for [recipe], reusing the last one when nothing that affects colour changed. */
    /**
     * Meters [frame] (linear RGB, from the live preview) through the engine, so the viewfinder's
     * brightness follows the scene rather than a stand-in. Falls back to 1.0 if it fails.
     */
    fun gainForFrame(context: Context, recipe: Recipe, frame: FloatArray, w: Int, h: Int): Float {
        // Metering is optional and runs every couple of seconds, so it never waits: if a develop
        // is using the engine, this round is skipped rather than running a second one alongside.
        if (!DevelopQueue.engineLane.tryAcquire()) return 0f
        return try {
            val buf = ByteBuffer.allocateDirect(w * h * 3 * 4).order(ByteOrder.nativeOrder())
            buf.asFloatBuffer().put(frame)
            LinearImage(buf, w, h).use { img ->
                Develop.engineFor(context).use { e -> e.exposureGain(img, Develop.sanitised(recipe).toParams()) }
            }
        } catch (t: Throwable) {
            Log.w("Latent", "scene metering failed; keeping the previous gain", t)
            0f
        } finally {
            DevelopQueue.engineLane.release()
        }
    }

    /** What the film would choose for this image — used by the darkroom's Auto level button. */
    fun gainForSource(context: Context, recipe: Recipe, source: Develop.Source): Float {
        if (!DevelopQueue.engineLane.tryAcquire()) return 0f
        return try {
            Develop.engineFor(context).use { e ->
                e.exposureGain(source.image, Develop.sanitised(recipe).copy(autoExposure = true).toParams())
            }
        } catch (t: Throwable) {
            Log.w("Latent", "auto level failed", t); 0f
        } finally { DevelopQueue.engineLane.release() }
    }

    fun bake(context: Context, recipe: Recipe): Look? {
        val key = colourKey(recipe)
        cached?.let { if (it.recipeKey == key) return it }
        // The engine must not run twice at once: baking waits for any develop in progress.
        val holdsLane = DevelopQueue.acquireLane(30)
        return try {
            val t0 = System.nanoTime()
            val r = Develop.sanitised(recipe)
            val look = Develop.engineFor(context).use { engine ->
                // Latent's own output spaces are applied after the engine, so the table gets the
                // same treatment — otherwise the viewfinder would show sRGB while the file saved
                // in Rec.709 or P3, and the two would not match.
                val ourSpace = if (OutputSpace.isOurs(r.outputColorSpace)) r.outputColorSpace else ""
                val params = (if (ourSpace.isEmpty()) r else r.copy(outputColorSpace = OutputSpace.ENGINE_SRGB)).toParams()
                val cube = engine.bakeCubeLut(params, SIZE)
                val table = (parseCube(cube, SIZE) ?: return null).also { OutputSpace.convertTable(it, ourSpace) }
                // With the film levelling turned off, the camera's own exposure is the exposure:
                // the preview applies no gain of its own.
                Look(table, SIZE, if (r.autoExposure) gainFor(engine, r) else 1f, key)
            }
            Log.i("Latent", "look baked for ${recipe.film} in ${(System.nanoTime() - t0) / 1_000_000} ms (gain ${"%.2f".format(look.gain)})")
            cached = look
            look
        } catch (t: Throwable) {
            Log.e("Latent", "look bake failed for ${recipe.film}", t)
            null
        } finally {
            if (holdsLane) DevelopQueue.engineLane.release()
        }
    }

    /**
     * The exposure gain the engine would apply. Metered on a small neutral ramp rather than the
     * live frame: the viewfinder needs one steady number, not one that jumps with every frame.
     */
    private fun gainFor(engine: SpektraEngine, recipe: Recipe): Float = try {
        val n = 64
        val buf = ByteBuffer.allocateDirect(n * n * 3 * 4).order(ByteOrder.nativeOrder())
        val f = buf.asFloatBuffer()
        for (y in 0 until n) for (x in 0 until n) {
            val v = (x + y).toFloat() / (2f * (n - 1))
            f.put(v); f.put(v); f.put(v)
        }
        LinearImage(buf, n, n).use { img -> engine.exposureGain(img, recipe.toParams()) }
    } catch (t: Throwable) {
        Log.w("Latent", "exposure gain unavailable; using 1.0", t)
        1f
    }

    /** Everything that changes colour. Spatial settings are absent — a table cannot hold them. */
    private fun colourKey(r: Recipe): String = listOf(
        r.film, r.paper, r.exposureEv, r.pushStops, r.filmContrast, r.printExposure, r.printContrast,
        r.yFilterShift, r.mFilterShift, r.yFilterNeutral, r.mFilterNeutral, r.preflash,
        r.dir, r.dirAmount, r.dirSameLayer, r.dirInterLayer,
        r.scanFilm, r.autoExposure, r.unsharpAmount, r.whiteCorrection, r.blackCorrection,
        r.filterUvAmount, r.filterUvNm, r.filterUvWidth,
        r.filterIrAmount, r.filterIrNm, r.filterIrWidth,
        r.meteringMethod, r.hanatosWindow, r.hanatosSurface,
        r.printExposureCompensation, r.normalizePrintExposure,
        r.outputColorSpace, r.outputGamutCompress, r.inputGamutCompress, r.rgbToRaw,
    ).joinToString("|")

    /** Reads an Adobe .cube: LUT_3D_SIZE then size³ RGB triples, blue slowest. */
    internal fun parseCube(text: String, expected: Int): FloatArray? {
        val values = FloatArray(expected * expected * expected * 3)
        var i = 0
        for (line in text.lineSequence()) {
            val s = line.trim()
            if (s.isEmpty() || s.startsWith("#") || s[0].isLetter()) continue
            val parts = s.split(' ', '\t').filter { it.isNotBlank() }
            if (parts.size < 3) continue
            val r = parts[0].toFloatOrNull() ?: continue
            val g = parts[1].toFloatOrNull() ?: continue
            val b = parts[2].toFloatOrNull() ?: continue
            if (i + 2 >= values.size) break
            values[i++] = r; values[i++] = g; values[i++] = b
        }
        if (i < values.size) {
            Log.e("Latent", "look table short: got ${i / 3} of ${values.size / 3} entries")
            return null
        }
        return values
    }
}
