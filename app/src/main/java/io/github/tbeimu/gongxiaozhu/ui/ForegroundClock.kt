package io.github.tbeimu.gongxiaozhu.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleStartEffect
import java.time.LocalDateTime
import java.time.ZoneId

internal fun shanghaiNow(): LocalDateTime = LocalDateTime.now(ZoneId.of("Asia/Shanghai"))

/** One wall-clock snapshot on entry and clock events. No receiver remains while stopped. */
@Composable
internal fun rememberForegroundMoment(now: () -> LocalDateTime): State<LocalDateTime> {
    val context = LocalContext.current
    val provider by rememberUpdatedState(now)
    val moment = remember { mutableStateOf(now()) }
    LifecycleStartEffect(context) {
        moment.value = provider()
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) { moment.value = provider() }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_TIME_TICK)
            addAction(Intent.ACTION_TIME_CHANGED)
            addAction(Intent.ACTION_TIMEZONE_CHANGED)
            addAction(Intent.ACTION_DATE_CHANGED)
        }
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        onStopOrDispose { context.unregisterReceiver(receiver) }
    }
    return moment
}
