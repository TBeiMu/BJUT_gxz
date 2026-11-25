package io.github.tbeimu.gongxiaozhu.ui

import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.SnackbarVisuals
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.platform.LocalAccessibilityManager
import io.github.tbeimu.gongxiaozhu.academic.AcademicController
import io.github.tbeimu.gongxiaozhu.academic.AcademicUndo
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch

/** Consume before showing: rotation cannot replay an already presented query result. */
@Composable
internal fun AcademicQueryFeedback(controller: AcademicController, snackbar: SnackbarHostState, unobstructed: Boolean) {
    LaunchedEffect(controller, snackbar, unobstructed) {
        if (!unobstructed) return@LaunchedEffect
        controller.state.map { it.message }.filterNotNull().collect { message ->
            controller.clearMessage()
            snackbar.showSnackbar(message)
        }
    }
}

/** The controller owns the deadline, so rotation and a covered host cannot renew undo. */
@Composable
internal fun AcademicUndoFeedback(controller: AcademicController, undo: AcademicUndo?,
    snackbar: SnackbarHostState, unobstructed: Boolean) {
    val accessibility = LocalAccessibilityManager.current
    val canShow = rememberUpdatedState(unobstructed)
    LaunchedEffect(controller, undo?.token, snackbar) {
        val action = undo ?: return@LaunchedEffect
        snapshotFlow { canShow.value }.first { it }
        val visuals = object : SnackbarVisuals {
            override val message = "本机考试已更新"
            override val actionLabel = "撤销"
            override val withDismissAction = false
            override val duration = SnackbarDuration.Indefinite
        }
        coroutineScope {
            val showing = async { snackbar.showSnackbar(visuals) }
            val displayed = snapshotFlow { snackbar.currentSnackbarData?.takeIf { canShow.value && it.visuals === visuals } }
                .first { it != null }!!
            val timeout = accessibility?.calculateRecommendedTimeoutMillis(10_000,
                containsIcons = true, containsText = true, containsControls = true) ?: 10_000
            val remaining = controller.undoRemaining(action.token, timeout) ?: 0L
            val timer = launch { delay(remaining); displayed.dismiss() }
            val result = showing.await()
            timer.cancel()
            if (result == SnackbarResult.ActionPerformed) controller.undo(action.token)
            else controller.dismissUndo(action.token)
        }
    }
}
