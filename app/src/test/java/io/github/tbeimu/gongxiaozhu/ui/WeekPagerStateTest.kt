package io.github.tbeimu.gongxiaozhu.ui

import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.runtime.MonotonicFrameClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.roundToInt

@OptIn(ExperimentalCoroutinesApi::class)
class WeekPagerStateTest {
    private fun TestScope.pager(initial: Int = 2, count: Int = 6, selected: (Int) -> Unit = {}): WeekPagerState {
        val clock = object : MonotonicFrameClock {
            override suspend fun <R> withFrameNanos(onFrame: (Long) -> R): R {
                delay(16)
                return onFrame(testScheduler.currentTime * 1_000_000)
            }
        }
        return WeekPagerState(initial, count, CoroutineScope(coroutineContext + clock), selected).apply { resize(400f) }
    }

    @Test fun releaseCommitsImmediatelyWhileAnimationStillHasDistanceToTravel() = runTest {
        val selected = mutableListOf<Int>()
        val pager = pager(selected = selected::add)
        pager.beginGesture(); pager.dragBy(-140f)
        assertEquals(2, pager.week)
        assertEquals(2.35f, pager.visualPosition, .001f)
        assertTrue(selected.isEmpty())
        pager.endGesture(0f)
        assertEquals(3, pager.week)
        assertEquals(listOf(3), selected)
        assertEquals(2.35f, pager.visualPosition, .001f)
        assertTrue(pager.settling)
        advanceUntilIdle()
        assertEquals(3f, pager.visualPosition, .001f)
        assertFalse(pager.moving)
    }

    @Test fun slowReleaseUsesQuarterPageAndShortDragReturnsWithoutSelecting() = runTest {
        for ((distance, expected) in listOf(-60f to 2, -100f to 3, 60f to 2, 100f to 1)) {
            val selected = mutableListOf<Int>()
            val pager = pager(selected = selected::add)
            pager.beginGesture(); pager.dragBy(distance); pager.endGesture(0f)
            assertEquals(expected, pager.week)
            assertEquals(if (expected == 2) emptyList<Int>() else listOf(expected), selected)
            advanceUntilIdle()
            assertEquals(expected.toFloat(), pager.visualPosition, .001f)
        }
    }

    @Test fun fastFlingUsesDensityAdjustedThresholdAndReleaseDirection() = runTest {
        for ((speed, expected) in listOf(-374f to 2, -375f to 3, 375f to 1)) {
            val pager = pager().apply { resize(400f, 375f) }
            pager.beginGesture(); pager.dragBy(-20f); pager.endGesture(speed)
            assertEquals(expected, pager.week)
            advanceUntilIdle()
        }
    }

    @Test fun manyImmediateFlicksKeepTouchedPositionAndAdvanceExactlyOnePerRelease() = runTest {
        val selected = mutableListOf<Int>()
        val pager = pager(initial = 1, count = 9, selected = selected::add)
        repeat(6) { n ->
            val before = pager.visualPosition
            pager.beginGesture()
            assertEquals(before, pager.visualPosition, .001f)
            pager.dragBy(-20f)
            assertEquals(before + .05f, pager.visualPosition, .001f)
            pager.endGesture(-1500f)
            assertEquals(n + 2, pager.week)
        }
        assertEquals((2..7).toList(), selected)
        advanceUntilIdle()
        assertEquals(7f, pager.visualPosition, .001f)
        assertFalse(pager.moving)
    }

    @Test fun retouchPausesAnimationAndEachReverseFlickHasItsOwnTarget() = runTest {
        val pager = pager()
        pager.beginGesture(); pager.dragBy(-140f); pager.endGesture(-1000f)
        advanceTimeBy(64)
        val before = pager.visualPosition
        pager.beginGesture()
        advanceTimeBy(500)
        assertEquals(before, pager.visualPosition, .001f)
        pager.dragBy(30f); pager.endGesture(1000f)
        assertEquals(2, pager.week)
        advanceUntilIdle()
        assertEquals(2f, pager.visualPosition, .001f)
    }

    @Test fun retouchWithoutMovementKeepsAcceptedTarget() = runTest {
        val pager = pager()
        pager.beginGesture(); pager.dragBy(-140f); pager.endGesture(0f)
        advanceTimeBy(48)
        pager.beginGesture(); pager.endGesture(1000f)
        assertEquals(3, pager.week)
        advanceUntilIdle()
        assertEquals(3f, pager.visualPosition, .001f)
    }

    @Test fun longDragStillSelectsOnlyOneNeighborAndEdgesNeverWrap() = runTest {
        for (initial in 1..5) for (direction in listOf(-1, 1)) {
            val pager = pager(initial, 5)
            pager.beginGesture(); repeat(10) { pager.dragBy(direction * 400f) }; pager.endGesture(direction * 2000f)
            val expected = (initial - direction).coerceIn(1, 5)
            assertEquals(expected, pager.week)
            advanceUntilIdle()
            assertEquals(expected.toFloat(), pager.visualPosition, .001f)
        }
    }

    @Test fun edgeResistanceIsBoundedAndRetouchDoesNotJumpDuringRebound() = runTest {
        val pager = pager(1, 1)
        pager.beginGesture(); pager.dragBy(50f)
        assertEquals(.975f, pager.visualPosition, .001f)
        pager.dragBy(500f)
        assertEquals(.94f, pager.visualPosition, .001f)
        pager.dragBy(-120f)
        assertEquals(1f, pager.visualPosition, .001f)
        pager.dragBy(100f); pager.endGesture(0f)
        advanceTimeBy(48)
        val before = pager.visualPosition
        pager.beginGesture()
        assertEquals(before, pager.visualPosition, .001f)
        pager.dragBy(-1f)
        assertTrue(pager.visualPosition > before)
        assertTrue(pager.visualPosition < before + .005f)
        pager.endGesture(0f); advanceUntilIdle()
        assertEquals(1f, pager.visualPosition, .001f)
    }

    @Test fun reversalBeforeReleaseUsesNetGestureDistance() = runTest {
        val pager = pager()
        pager.beginGesture(); pager.dragBy(-140f); pager.dragBy(140f); pager.endGesture(0f)
        advanceUntilIdle()
        assertEquals(2, pager.week)
        assertFalse(pager.moving)
    }

    @Test fun touchCancellationDiscardsUnreleasedDragButKeepsEarlierAcceptedWeek() = runTest {
        val selected = mutableListOf<Int>()
        val pager = pager(selected = selected::add)
        pager.beginGesture(); pager.dragBy(-140f)
        pager.interactions.emit(DragInteraction.Cancel(DragInteraction.Start()))
        pager.endGesture(0f)
        assertEquals(2, pager.week)
        assertTrue(selected.isEmpty())
        pager.beginGesture(); pager.dragBy(-140f); pager.endGesture(0f)
        pager.beginGesture(); pager.dragBy(-140f)
        pager.interactions.tryEmit(DragInteraction.Cancel(DragInteraction.Start()))
        pager.endGesture(0f); advanceUntilIdle()
        assertEquals(3, pager.week)
        assertEquals(3f, pager.visualPosition, .001f)
        assertEquals(listOf(3), selected)
    }

    @Test fun cancelledAnimationCannotOverrideExplicitSelectionOrNewDrag() = runTest {
        val pager = pager()
        pager.beginGesture(); pager.dragBy(-140f); pager.endGesture(0f)
        advanceTimeBy(48)
        pager.select(5)
        pager.beginGesture(); pager.dragBy(30f); pager.endGesture(1000f)
        advanceUntilIdle()
        assertEquals(4, pager.week)
        assertEquals(4f, pager.visualPosition, .001f)
        assertFalse(pager.moving)
    }

    @Test fun resizeCancelAndDisposalRetainOnlyReleasedSelection() = runTest {
        val pager = pager()
        pager.beginGesture(); pager.dragBy(-140f); pager.resize(500f)
        assertEquals(2f, pager.visualPosition, .001f)
        pager.beginGesture(); pager.dragBy(-140f); pager.endGesture(0f)
        assertEquals(3, pager.week)
        pager.cancel(); advanceUntilIdle()
        assertEquals(3f, pager.visualPosition, .001f)
        pager.beginGesture(); pager.dragBy(-140f); pager.dispose()
        pager.endGesture(-1000f); pager.select(5); advanceUntilIdle()
        assertEquals(3, pager.week)
        assertFalse(pager.moving)
    }

    @Test fun returnCommitsOnceAndContinuouslyMovesInEitherDirection() = runTest {
        for ((start, target) in listOf(7 to 2, 2 to 7, 3 to 2)) {
            val selected = mutableListOf<Int>()
            val pager = pager(start, count = 8, selected = selected::add)
            pager.animateSelect(target)
            assertEquals(target, pager.week)
            assertEquals(start.toFloat(), pager.visualPosition, .001f)
            assertEquals(listOf(target), selected)
            assertTrue(pager.settling)
            advanceTimeBy(160)
            val first = pager.visualPosition
            assertTrue(first > minOf(start, target) && first < maxOf(start, target))
            advanceTimeBy(80)
            val second = pager.visualPosition
            assertTrue(if (target < start) second < first else second > first)
            advanceUntilIdle()
            assertEquals(target.toFloat(), pager.visualPosition, .001f)
            assertEquals(listOf(target), selected)
            assertFalse(pager.moving)
        }
    }

    @Test fun returnDurationIsBoundedEvenAcrossTheWholeSemester() = runTest {
        assertEquals(440, scheduleReturnDuration(1f))
        assertEquals(465, scheduleReturnDuration(2f))
        assertEquals(640, scheduleReturnDuration(59f))
        val pager = pager(initial = 60, count = 60)
        pager.animateSelect(1)
        advanceTimeBy(320)
        assertTrue(pager.visualPosition > 1f && pager.visualPosition < 60f)
        advanceTimeBy(380)
        assertEquals(1f, pager.visualPosition, .001f)
        assertFalse(pager.moving)
    }

    @Test fun newGestureTakesOverTheVisibleWeekDuringALongReturnWithoutJumping() = runTest {
        val pager = pager(initial = 12, count = 12)
        pager.animateSelect(1)
        advanceTimeBy(160)
        val before = pager.visualPosition
        val nearest = before.roundToInt()
        assertTrue(nearest > 2)
        pager.beginGesture()
        assertEquals(before, pager.visualPosition, .001f)
        assertEquals(1, pager.week)
        advanceTimeBy(800)
        assertEquals(before, pager.visualPosition, .001f)
        pager.dragBy(-20f)
        assertEquals(nearest, pager.week)
        pager.endGesture(-1500f)
        val expected = (nearest + 1).coerceAtMost(12)
        assertEquals(expected, pager.week)
        advanceUntilIdle()
        assertEquals(expected.toFloat(), pager.visualPosition, .001f)
        assertFalse(pager.moving)
    }

    @Test fun stillTouchDuringReturnKeepsTheAcceptedTargetAndResumesWithoutJumping() = runTest {
        val selected = mutableListOf<Int>()
        val pager = pager(initial = 12, count = 12, selected = selected::add)
        pager.animateSelect(1)
        advanceTimeBy(160)
        val before = pager.visualPosition
        pager.beginGesture()
        advanceTimeBy(800)
        assertEquals(before, pager.visualPosition, .001f)
        assertEquals(1, pager.week)
        assertEquals(listOf(1), selected)
        pager.endGesture(0f)
        assertEquals(before, pager.visualPosition, .001f)
        assertEquals(1, pager.week)
        advanceUntilIdle()
        assertEquals(1f, pager.visualPosition, .001f)
        assertFalse(pager.moving)
        assertEquals(listOf(1), selected)
    }

    @Test fun returnStartsFromTheCurrentDraggedPositionAndNewSelectionWins() = runTest {
        val pager = pager(initial = 4)
        pager.beginGesture(); pager.dragBy(-140f)
        val before = pager.visualPosition
        pager.animateSelect(1)
        assertEquals(before, pager.visualPosition, .001f)
        advanceTimeBy(80)
        pager.select(5)
        advanceUntilIdle()
        assertEquals(5, pager.week)
        assertEquals(5f, pager.visualPosition, .001f)
        assertFalse(pager.moving)
    }

    @Test fun cancelResizeAndDisposeKeepTheAcceptedReturnTargetAndDiscardOldCallbacks() = runTest {
        for (action in 0..2) {
            val pager = pager(initial = 6)
            pager.animateSelect(1)
            advanceTimeBy(80)
            when (action) {
                0 -> pager.cancel()
                1 -> pager.resize(500f)
                else -> pager.dispose()
            }
            advanceUntilIdle()
            assertEquals(1, pager.week)
            assertEquals(1f, pager.visualPosition, .001f)
            assertFalse(pager.moving)
        }
    }

    @Test fun reducedMotionReturnsImmediatelyAndAReplacementReturnOwnsTheAnimation() = runTest {
        val pager = pager(initial = 6)
        pager.animateSelect(1, reducedMotion = true)
        assertEquals(1f, pager.visualPosition, .001f)
        assertFalse(pager.moving)
        pager.animateSelect(6)
        advanceTimeBy(80)
        val before = pager.visualPosition
        pager.animateSelect(3)
        assertEquals(before, pager.visualPosition, .001f)
        advanceUntilIdle()
        assertEquals(3, pager.week)
        assertEquals(3f, pager.visualPosition, .001f)
        assertFalse(pager.moving)
    }
}
