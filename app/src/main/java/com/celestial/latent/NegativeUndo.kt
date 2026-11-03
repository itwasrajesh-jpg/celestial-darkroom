package com.celestial.latent

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.celestial.latent.develop.Recipe

// ---------------------------------------------------------------------------------------------
// 110b: NEGATIVE's undo and redo (approved proof design-critique/layers-fixes-proof/undo/2-undo-and-redo.jpg).
// Every change made in NEGATIVE is a step: a film picked, a slider let go of, NO FILM, Reset to film,
// a layer's own look. The darkroom watches what NEGATIVE shapes and, once it has been still for a
// moment ([SETTLE_MS], so one slider drag is one step), files the state it left behind. Kept while the
// photo is open, at most [MAX] steps. PRINT's own changes are not steps here: PRINT has its own undo.
// ---------------------------------------------------------------------------------------------

/**
 * What NEGATIVE shapes, as one value: the whole photo's recipe and look, each layer's own NEGATIVE
 * settings (by layer id; their areas and LAYERS sliders are PRINT's), and whether the photo has no film.
 * [fresh] is what NO FILM switched off, carried so undoing NO FILM can bring it back; it is not compared.
 */
internal class NegSnap(
    val recipe: Recipe,
    val lookId: String,
    val looks: Map<Int, Pair<Recipe?, String?>>,
    val noFilm: Boolean,
    val fresh: Any?,
) {
    fun same(o: NegSnap?): Boolean = o != null && o.recipe == recipe && o.lookId == lookId && o.looks == looks && o.noFilm == noFilm
}

internal class NegHistory {
    private val back = ArrayDeque<NegSnap>()
    private val ahead = ArrayDeque<NegSnap>()
    /** The state as it last settled: what an undo leaves behind, to be redone. */
    private var settled: NegSnap? = null
    /** The next settle is the state an undo or redo put back, not a new change. */
    private var restoring = false
    /** Read by the chips, so they follow the history. */
    var tick by mutableStateOf(0)
        private set

    val canUndo: Boolean get() { tick; return back.isNotEmpty() }
    val canRedo: Boolean get() { tick; return ahead.isNotEmpty() }

    /**
     * NEGATIVE has been still for a moment in state [s]. A change from the last settled state becomes a
     * step when [record] (the change was made in NEGATIVE); otherwise (PRINT changed the recipe, the
     * photo just opened) it only moves the starting point.
     */
    fun settle(s: NegSnap, record: Boolean) {
        val was = settled
        settled = s
        if (restoring) { restoring = false; return }
        if (!record || was == null || was.same(s)) return
        back.addLast(was)
        while (back.size > MAX) back.removeFirst()
        ahead.clear()
        tick++
    }

    /** The state one step back, with the one it leaves (to restore from); null when there is none. */
    fun undo(): Pair<NegSnap, NegSnap?>? {
        val to = back.removeLastOrNull() ?: return null
        val from = settled
        if (from != null) ahead.addLast(from)
        settled = to; restoring = true; tick++
        return to to from
    }

    /** The step an undo took back, again. */
    fun redo(): Pair<NegSnap, NegSnap?>? {
        val to = ahead.removeLastOrNull() ?: return null
        val from = settled
        if (from != null) back.addLast(from)
        settled = to; restoring = true; tick++
        return to to from
    }

    companion object {
        const val MAX = 30
        /** How long NEGATIVE must stay still for a change to count as one step (a drag is one step). */
        const val SETTLE_MS = 450L
    }
}
