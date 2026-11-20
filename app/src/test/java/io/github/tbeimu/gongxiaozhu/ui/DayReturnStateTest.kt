package io.github.tbeimu.gongxiaozhu.ui

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

@OptIn(ExperimentalCoroutinesApi::class)
class DayReturnStateTest {
    private fun TestScope.state(): DayReturnState {
        val clock = object : MonotonicFrameClock {
            override suspend fun <R> withFrameNanos(onFrame: (Long) -> R): R {
                delay(16)
                return onFrame(testScheduler.currentTime * 1_000_000)
            }
        }
        return DayReturnState(CoroutineScope(coroutineContext + clock))
    }

    @Test fun returnStartsFromTheDisplayedPositionAndMovesInBothDirections() = runTest {
        for ((from, target) in listOf(2f to 9, 9f to 2)) {
            val state = state()
            state.animateTo(from, target)
            assertEquals(from, state.position, .001f)
            assertTrue(state.running)
            advanceTimeBy(160)
            assertTrue(state.position > minOf(from, target.toFloat()))
            assertTrue(state.position < maxOf(from, target.toFloat()))
            val intermediate = state.position
            advanceTimeBy(96)
            assertTrue(if (from < target) state.position > intermediate else state.position < intermediate)
            advanceUntilIdle()
            assertEquals(target.toFloat(), state.position, .001f)
            assertFalse(state.running)
        }
    }

    @Test fun adjacentDateRetainsAVisibleAnimation() = runTest {
        val state = state()
        state.animateTo(4f, 5)
        advanceTimeBy(240)
        assertTrue(state.running)
        assertTrue(state.position > 4f && state.position < 5f)
        advanceUntilIdle()
        assertEquals(5f, state.position, .001f)
    }

    @Test fun semesterWideReturnFinishesWithinTheBoundedDuration() = runTest {
        val state = state()
        state.animateTo(419f, 0)
        advanceTimeBy(320)
        assertTrue(state.running)
        assertTrue(state.position > 0f && state.position < 419f)
        // One frame starts the animation, and one frame can straddle the 640 ms deadline.
        advanceTimeBy(352)
        assertFalse(state.running)
        assertEquals(0f, state.position, .001f)
    }

    @Test fun cancellingPreservesTheTouchedPositionAndStopsOldFrames() = runTest {
        val state = state()
        state.animateTo(17f, 2)
        advanceTimeBy(160)
        val before = state.position
        state.cancel()
        assertFalse(state.running)
        assertEquals(before, state.position, .001f)
        advanceUntilIdle()
        assertEquals(before, state.position, .001f)
    }

    @Test fun newReturnOwnsThePositionAndOldCompletionCannotOverwriteIt() = runTest {
        val state = state()
        state.animateTo(17f, 2)
        advanceTimeBy(160)
        val before = state.position
        state.animateTo(before, 20)
        assertEquals(before, state.position, .001f)
        advanceUntilIdle()
        assertEquals(20f, state.position, .001f)
        assertFalse(state.running)
    }

    @Test fun reducedMotionAndSameDateFinishWithoutStartingAnAnimation() = runTest {
        val state = state()
        state.animateTo(12f, 2, reducedMotion = true)
        assertEquals(2f, state.position, .001f)
        assertFalse(state.running)
        state.animateTo(2f, 2)
        assertFalse(state.running)
        advanceUntilIdle()
        assertEquals(2f, state.position, .001f)
    }

    @Test fun enablingReducedMotionDuringReturnDiscardsTheOldAnimation() = runTest {
        val state = state()
        state.animateTo(12f, 2)
        advanceTimeBy(160)
        state.animateTo(state.position, 2, reducedMotion = true)
        assertEquals(2f, state.position, .001f)
        assertFalse(state.running)
        advanceUntilIdle()
        assertEquals(2f, state.position, .001f)
    }

    @Test fun disposalStopsFramesAndFutureRequests() = runTest {
        val state = state()
        state.animateTo(12f, 2)
        advanceTimeBy(160)
        val before = state.position
        state.dispose()
        state.animateTo(1f, 20)
        advanceUntilIdle()
        assertFalse(state.running)
        assertEquals(before, state.position, .001f)
    }
}
