package io.github.tbeimu.gongxiaozhu.ui

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.abs

/** Only the displayed day index moves; the selected date and its restoration belong to the page. */
internal class DayReturnState(private val scope: CoroutineScope) {
    var position by mutableFloatStateOf(0f)
        private set
    var running by mutableStateOf(false)
        private set
    private var animation: Job? = null
    private var epoch = 0
    private var disposed = false

    fun animateTo(from: Float, target: Int, reducedMotion: Boolean = false) {
        if (disposed) return
        cancel()
        position = from
        if (reducedMotion || abs(from - target) < .001f) {
            position = target.toFloat()
            return
        }
        val token = epoch
        running = true
        animation = scope.launch {
            try {
                animate(from, target.toFloat(), animationSpec = tween(
                    scheduleReturnDuration(abs(from - target)), easing = FastOutSlowInEasing)) { value, _ ->
                    if (!disposed && epoch == token) position = value
                }
                if (!disposed && epoch == token) position = target.toFloat()
            } finally {
                if (epoch == token) { running = false; animation = null }
            }
        }
    }

    /** A new gesture can continue from this exact position rather than jumping to the target. */
    fun cancel() {
        epoch++
        animation?.cancel()
        animation = null
        running = false
    }

    fun dispose() { cancel(); disposed = true }
}
