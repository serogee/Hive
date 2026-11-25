package io.github.serogee.hive.ui.stacks

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitLongPressOrCancellation
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.LazyListItemInfo
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import io.github.serogee.hive.core.model.QuestionId

/** Scrolls and reorders the questions in the editor's list. Each question's key is its ID. */
@Stable
class QuestionListState(
    val list: LazyListState,
    /** How far below the top an opened question stops, so that the one above it stays within reach. */
    private val leeway: Int,
    /** How many list items come before the first question. */
    private val itemsBefore: Int,
    private val questionIds: () -> List<QuestionId>,
    private val onMove: (QuestionId, Int) -> Unit,
) {
    var draggedId by mutableStateOf<QuestionId?>(null)
        private set

    /** How far the dragged question is from its place in the list. */
    var dragOffset by mutableFloatStateOf(0f)
        private set

    /** Puts a question's top near the top of the screen. */
    suspend fun scrollToTop(id: QuestionId) {
        val index = questionIds().indexOf(id)
        if (index >= 0) list.animateScrollToItem(itemsBefore + index, -leeway)
    }

    /** Moves a question with [move], and keeps it at the same place on the screen. */
    fun moveInPlace(id: QuestionId, offset: Int, move: () -> Unit) {
        val item = item(id)
        val target = questionIds().indexOf(id) + offset
        move()
        if (item != null && target in questionIds().indices) list.requestScrollToItem(itemsBefore + target, -item.offset)
    }

    fun startDrag(id: QuestionId) {
        draggedId = id
        dragOffset = 0f
    }

    fun dragBy(delta: Float) {
        dragOffset += delta
        swapIfOverNeighbor()
    }

    fun endDrag() {
        draggedId = null
        dragOffset = 0f
    }

    /** Scrolls while the dragged question is at an edge of the list. */
    suspend fun scrollAtEdges() {
        val item = draggedId?.let(::item) ?: return
        val top = item.offset + dragOffset
        val bottom = top + item.size
        val start = list.layoutInfo.viewportStartOffset
        val end = list.layoutInfo.viewportEndOffset
        val step = when {
            top < start -> (top - start).coerceAtLeast(-MAX_STEP)
            bottom > end -> (bottom - end).coerceAtMost(MAX_STEP)
            else -> return
        }
        dragOffset += list.scrollBy(step)
        swapIfOverNeighbor()
    }

    private fun swapIfOverNeighbor() {
        val id = draggedId ?: return
        val own = item(id) ?: return
        val ids = questionIds()
        // The list has not been laid out since the last swap.
        if (own.index - itemsBefore != ids.indexOf(id)) return

        val center = own.offset + dragOffset + own.size / 2f
        val other = list.layoutInfo.visibleItemsInfo.firstOrNull { item ->
            item.index != own.index && item.index - itemsBefore in ids.indices && center >= item.offset && center <= item.offset + item.size
        } ?: return
        val newOffset = if (other.index > own.index) other.offset + other.size - own.size else other.offset

        // Without this, the list would follow its first visible item to the item's new place.
        if (own.index == list.firstVisibleItemIndex || other.index == list.firstVisibleItemIndex) {
            list.requestScrollToItem(list.firstVisibleItemIndex, list.firstVisibleItemScrollOffset)
        }
        onMove(id, other.index - itemsBefore)
        dragOffset += own.offset - newOffset
    }

    private fun item(id: QuestionId): LazyListItemInfo? = list.layoutInfo.visibleItemsInfo.firstOrNull { it.key == id.value }

    private companion object {
        /** The most pixels to scroll in one frame. */
        const val MAX_STEP = 24f
    }
}

/** A tap calls [onTap]. Holding calls [onDragStart], and then each movement calls [onDrag]. */
fun Modifier.tapOrHoldToDrag(
    key: Any,
    onTap: () -> Unit,
    onDragStart: () -> Unit,
    onDrag: (Float) -> Unit,
    onDragEnd: () -> Unit,
): Modifier = pointerInput(key) {
    awaitEachGesture {
        val down = awaitFirstDown()
        down.consume()
        if (awaitLongPressOrCancellation(down.id) == null) {
            // Null is either a release before the hold, or a gesture that another element took.
            val released = currentEvent.changes.any { it.id == down.id && it.changedToUp() }
            if (released) {
                currentEvent.changes.forEach { it.consume() }
                onTap()
            }
            return@awaitEachGesture
        }
        onDragStart()
        try {
            drag(down.id) { change ->
                onDrag(change.positionChange().y)
                change.consume()
            }
        } finally {
            onDragEnd()
        }
    }
}
