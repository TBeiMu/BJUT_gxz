package io.github.tbeimu.gongxiaozhu.ui

import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.takeWhile

private data class CampusListAnchor(val index: Int, val offset: Int, val viewport: IntSize)
private data class CampusListLayout(val index: Int, val offset: Int, val viewport: IntSize,
    val itemCount: Int, val scrolling: Boolean)

/** Keeps the original restored anchor while the window's first inset measurements settle. */
internal class CampusNetworkListState private constructor(
    val list: LazyListState,
    private var pendingRestore: CampusListAnchor?,
) {
    private var applyingRestore = false
    private var lastApplied: CampusListLayout? = null

    fun discardPendingRestore() { pendingRestore = null; lastApplied = null }

    private fun layout() = CampusListLayout(list.firstVisibleItemIndex, list.firstVisibleItemScrollOffset,
        list.layoutInfo.viewportSize, list.layoutInfo.totalItemsCount, list.isScrollInProgress)

    private suspend fun restoreAfterLayout(value: CampusListLayout) {
        val anchor = pendingRestore ?: return
        if (value.viewport.width <= 0 || value.viewport.height <= 0 || value.itemCount <= 1) return
        val previous = lastApplied
        // A semantics/accessibility scroll can finish between snapshots. Its changed position
        // still distinguishes it from our last measured application in the same viewport.
        if (!applyingRestore && (value.scrolling || (previous != null && value.viewport == previous.viewport &&
                (value.index != previous.index || value.offset != previous.offset)))) {
            discardPendingRestore()
            return
        }
        if (previous?.viewport == value.viewport) return
        applyingRestore = true
        try {
            list.scrollToItem(anchor.index, anchor.offset)
            lastApplied = layout()
            if (pendingRestore === anchor && (value.viewport == anchor.viewport || value.viewport.width != anchor.viewport.width))
                discardPendingRestore()
        } finally { applyingRestore = false }
    }

    @Composable
    fun ObserveRestoration(ready: Boolean) {
        LaunchedEffect(this, ready) {
            if (ready && pendingRestore != null) snapshotFlow { layout() }
                .takeWhile { pendingRestore != null }.collect { restoreAfterLayout(it) }
        }
        LaunchedEffect(this) {
            list.interactionSource.interactions.collect { if (it is DragInteraction.Start) discardPendingRestore() }
        }
    }

    companion object {
        private val Saver = listSaver<CampusNetworkListState, Int>(
            save = { value ->
                val current = value.layout()
                val previous = value.lastApplied
                val externallyMoved = previous != null && previous.viewport == current.viewport &&
                    (previous.index != current.index || previous.offset != current.offset)
                val saved = value.pendingRestore?.takeUnless { externallyMoved }
                    ?: CampusListAnchor(current.index, current.offset, current.viewport)
                listOf(saved.index, saved.offset, saved.viewport.width, saved.viewport.height)
            },
            restore = { saved ->
                val anchor = CampusListAnchor(saved[0], saved[1], IntSize(saved[2], saved[3]))
                CampusNetworkListState(LazyListState(anchor.index, anchor.offset),
                    anchor.takeIf { it.viewport.width > 0 && it.viewport.height > 0 })
            },
        )

        @Composable
        fun remember(): CampusNetworkListState = rememberSaveable(saver = Saver) {
            CampusNetworkListState(LazyListState(), null)
        }
    }
}
