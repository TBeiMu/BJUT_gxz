package io.github.tbeimu.gongxiaozhu.ui

import android.os.SystemClock
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.SnackbarVisuals
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.platform.LocalAccessibilityManager
import io.github.tbeimu.gongxiaozhu.schedule.CourseUndo
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** Owned by the ViewModel so rotation keeps the original display deadline. */
internal class CourseUndoTimer(private val now: () -> Long = SystemClock::elapsedRealtime) {
    private var token: String? = null
    private var startedAt = 0L
    private var duration = 0L
    fun remaining(action: String, timeout: Long): Long {
        if (token != action) {
            token = action
            startedAt = now()
            duration = timeout.coerceAtLeast(0L)
        }
        return (duration - (now() - startedAt).coerceAtLeast(0L)).coerceAtLeast(0L)
    }
    fun clear() { token = null }
}

@Composable
internal fun CourseUndoFeedback(model: AppViewModel, undo: CourseUndo?, snackbar: SnackbarHostState) {
    val accessibility = LocalAccessibilityManager.current
    LaunchedEffect(model, undo?.token, snackbar) {
        val action = undo ?: return@LaunchedEffect
        val visuals = object : SnackbarVisuals {
            override val message = if (action.deleted) "已删除 ${action.deletedCount} 次安排" else "课程已更新"
            override val actionLabel = "撤销"
            override val withDismissAction = false
            // The deadline below owns dismissal, including after the host is recreated.
            override val duration = SnackbarDuration.Indefinite
        }
        coroutineScope {
            val showing = async { snackbar.showSnackbar(visuals) }
            val displayed = snapshotFlow { snackbar.currentSnackbarData }.first { it?.visuals === visuals }!!
            // Material3 Long duration, extended with the same accessibility recommendation as SnackbarHost.
            val recommended = accessibility?.calculateRecommendedTimeoutMillis(10_000L,
                containsIcons = true, containsText = true, containsControls = true) ?: 10_000L
            val remaining = model.courseUndoRemaining(action.token, recommended) ?: 0L
            val timeout = launch { delay(remaining); displayed.dismiss() }
            val result = showing.await()
            timeout.cancel()
            if (result == SnackbarResult.ActionPerformed) model.undoCourse(action.token)
            else model.dismissCourseUndo(action.token)
        }
        // Cancellation during recreation removes this host entry, but keeps the token and deadline.
    }
}
