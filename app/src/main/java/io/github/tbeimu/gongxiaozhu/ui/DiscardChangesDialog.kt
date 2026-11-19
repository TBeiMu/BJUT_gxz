package io.github.tbeimu.gongxiaozhu.ui

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.SecureFlagPolicy

/** Holds no draft data; callers retain their editor state while this replaces its dialog. */
@Composable
internal fun DiscardChangesDialog(onContinue: () -> Unit, onDiscard: () -> Unit, secure: Boolean = false) {
    AlertDialog(onDismissRequest = onContinue, modifier = Modifier.testTag("discard_changes_dialog"),
        properties = DialogProperties(securePolicy = if (secure) SecureFlagPolicy.SecureOn else SecureFlagPolicy.Inherit),
        title = { Text("放弃未保存的修改？") },
        confirmButton = { TextButton(onClick = onDiscard, modifier = Modifier.testTag("discard_changes")) { Text("放弃修改") } },
        dismissButton = { TextButton(onClick = onContinue, modifier = Modifier.testTag("continue_editing")) { Text("继续编辑") } })
}
