package io.github.tbeimu.gongxiaozhu.ui

import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import io.github.tbeimu.gongxiaozhu.data.AccountSummary
import io.github.tbeimu.gongxiaozhu.data.AppSettings
import io.github.tbeimu.gongxiaozhu.data.NetworkMode

internal enum class CampusNetworkSection { CONNECTION, ACCOUNTS }

/** One scroll owner for connection status, saved accounts and optional connection settings. */
@Composable
internal fun CampusNetworkContent(state: AppUiState, modifier: Modifier,
    entrySection: CampusNetworkSection = CampusNetworkSection.CONNECTION, entryRequest: Long = 0L,
    onAdd: () -> Unit, onEdit: (String) -> Unit, onDefault: (String) -> Unit,
    onDelete: (AccountSummary) -> Unit, onLogin: () -> Unit, onCancelLogin: () -> Unit,
    onSettings: (AppSettings) -> Unit, onReload: () -> Unit, onReset: () -> Unit) {
    val restoration = CampusNetworkListState.remember()
    val list = restoration.list
    var handledRequest by rememberSaveable { mutableLongStateOf(Long.MIN_VALUE) }
    var settingsExpanded by rememberSaveable { mutableStateOf(false) }
    val ready = !state.loading && !state.storageBlocked
    restoration.ObserveRestoration(ready && handledRequest == entryRequest)
    LaunchedEffect(entryRequest, ready) {
        if (ready && handledRequest != entryRequest) {
            restoration.discardPendingRestore()
            list.scrollToItem(if (entrySection == CampusNetworkSection.ACCOUNTS) 1 else 0)
            handledRequest = entryRequest
        }
    }
    fun userAction(action: () -> Unit) { restoration.discardPendingRestore(); action() }

    LazyColumn(modifier.fillMaxSize().testTag("campus_network_list"), state = list,
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)) {
        if (!ready) {
            item(key = "storage-recovery") { CampusStorageRecovery(state, { userAction(onReload) }, { userAction(onReset) }) }
        } else {
            item(key = "connection") {
                CampusConnectionPanel(state, { userAction(onLogin) }, { userAction(onCancelLogin) },
                    { settings -> userAction { onSettings(settings) } }, restoration::discardPendingRestore,
                    Modifier.testTag("campus_connection"))
            }
            item(key = "accounts") {
                Column(Modifier.fillMaxWidth().testTag("campus_accounts"), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("账号管理", Modifier.padding(horizontal = 8.dp), style = MaterialTheme.typography.titleMedium, color = gxz.ink)
                    CtaButton("添加账号", { userAction(onAdd) }, Modifier.testTag("campus_add_account"), enabled = !state.busy)
                    Text("已保存 ${state.data.accounts.size} 个账号", Modifier.padding(start = 8.dp, top = 6.dp),
                        style = MaterialTheme.typography.labelMedium, color = gxz.inkFaint)
                }
            }
            if (state.data.accounts.isEmpty()) item(key = "empty-accounts") {
                GlassCard {
                    Text("还没有账号", style = MaterialTheme.typography.titleLarge)
                    Text("添加校园网账号和密码，后续自动登录将使用默认账号。",
                        style = MaterialTheme.typography.bodyMedium, color = gxz.inkSoft)
                }
            }
            items(state.data.accounts, key = { "account/${it.id}" }) { account ->
                CampusAccountCard(account, account.id == state.data.defaultAccountId, !state.busy,
                    Modifier.animateItem(fadeInSpec = tween(Motion.MEDIUM), placementSpec = Motion.snappy(),
                        fadeOutSpec = tween(Motion.SHORT)), { id -> userAction { onEdit(id) } },
                    { id -> userAction { onDefault(id) } }, { selected -> userAction { onDelete(selected) } })
            }
            item(key = "default-account-note") {
                Text("切换默认账号不会退出当前网络会话。", Modifier.padding(horizontal = 8.dp),
                    style = MaterialTheme.typography.bodySmall, color = gxz.inkFaint)
            }
            item(key = "connection-settings") {
                CampusConnectionSettings(state.data.settings, settingsExpanded, !state.busy,
                    onToggle = { userAction { settingsExpanded = !settingsExpanded } },
                    onSettings = { settings -> userAction { onSettings(settings) } })
            }
        }
    }
}

@Composable
private fun CampusConnectionPanel(state: AppUiState, onLogin: () -> Unit, onCancelLogin: () -> Unit,
    onSettings: (AppSettings) -> Unit, onInteraction: () -> Unit, modifier: Modifier) {
    val palette = gxz
    GlassCard(modifier) {
        state.data.defaultAccount?.let {
            Text("当前账号：${it.label} · ${maskUsername(it.username)}",
                style = MaterialTheme.typography.bodySmall, color = palette.inkSoft)
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            GlyphChip(AppGlyph.WIFI, tint = if (state.login.wifiConnected) palette.positive else palette.inkFaint)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(if (state.login.wifiConnected) "WiFi 已连接" else "等待连接 WiFi", style = MaterialTheme.typography.titleMedium)
                Text(state.login.message, Modifier.testTag("login_status"),
                    style = MaterialTheme.typography.bodySmall, color = palette.inkSoft)
            }
            if (state.login.running) BrandLoader(Modifier.size(34.dp), MarkMotion.SIGNAL)
        }
        if (state.login.running) {
            Text("可切换到后台或锁屏；划掉 App 会停止本次认证。",
                style = MaterialTheme.typography.bodySmall, color = palette.inkSoft)
            OutlinedButton(onClick = onCancelLogin, modifier = Modifier.testTag("cancel_login")) { Text("取消认证") }
        }
        LoginDiagnostics(state.login, onInteraction)
        LoginNotificationPermission(onInteraction)
        if (state.login.needsChoice) FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)) {
            OutlinedButton(onClick = { onSettings(state.data.settings.copy(networkMode = NetworkMode.PUBLIC_WIFI)) },
                enabled = !state.busy) { Text("公共 WiFi") }
            OutlinedButton(onClick = { onSettings(state.data.settings.copy(networkMode = NetworkMode.DORM_WIFI)) },
                enabled = !state.busy) { Text("宿舍 WiFi") }
        }
        CtaButton(when { state.login.running -> "正在认证…"; state.login.completed -> "检查联网状态"; else -> "登录 / 重试" },
            onLogin, Modifier.testTag("login_button"), enabled = !state.busy && !state.login.running)
    }
}

@Composable
private fun CampusAccountCard(account: AccountSummary, isDefault: Boolean, enabled: Boolean, modifier: Modifier,
    onEdit: (String) -> Unit, onDefault: (String) -> Unit, onDelete: (AccountSummary) -> Unit) {
    val palette = gxz
    GlassCard(modifier.testTag("campus_account_${account.id}"),
        padding = PaddingValues(start = 20.dp, end = 20.dp, top = 18.dp, bottom = 8.dp), spacing = 6.dp) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(account.label, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            if (isDefault) Pill("默认")
        }
        Text(maskUsername(account.username), color = palette.inkSoft, style = MaterialTheme.typography.bodyMedium,
            fontFamily = FontFamily.Monospace)
        Text("密码已加密保存", style = MaterialTheme.typography.bodySmall, color = palette.inkFaint)
        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)) {
            TextButton(onClick = { onEdit(account.id) }, enabled = enabled) { Text("编辑") }
            TextButton(onClick = { onDefault(account.id) }, enabled = enabled && !isDefault) { Text("设为默认") }
            TextButton(onClick = { onDelete(account) }, enabled = enabled) { Text("删除", color = MaterialTheme.colorScheme.error) }
        }
    }
}

@Composable
private fun CampusConnectionSettings(settings: AppSettings, expanded: Boolean, enabled: Boolean,
    onToggle: () -> Unit, onSettings: (AppSettings) -> Unit) {
    val palette = gxz
    GlassCard(padding = PaddingValues(start = 20.dp, end = 14.dp, top = 12.dp, bottom = 12.dp), spacing = 8.dp) {
        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(MaterialTheme.shapes.small)
            .clickable(role = Role.Button, onClick = onToggle).testTag("campus_settings_toggle")
            .semantics { stateDescription = if (expanded) "已展开" else "已收起" },
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("连接设置", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
            LineGlyph(AppGlyph.CHEVRON, Modifier.size(20.dp).graphicsLayer { rotationZ = if (expanded) 90f else 0f }, palette.inkSoft)
        }
        if (expanded) Column(Modifier.fillMaxWidth().testTag("campus_settings"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            HorizontalDivider(Modifier.padding(end = 6.dp, bottom = 4.dp), thickness = .7.dp, color = palette.hairline)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("打开 App 自动登录", style = MaterialTheme.typography.titleSmall)
                    Text("失败后可手动重试", style = MaterialTheme.typography.bodySmall, color = palette.inkSoft)
                }
                Switch(checked = settings.autoLogin, enabled = enabled,
                    onCheckedChange = { onSettings(settings.copy(autoLogin = it)) },
                    modifier = Modifier.semantics { contentDescription = "打开 App 自动登录" })
            }
            HorizontalDivider(Modifier.padding(end = 6.dp, top = 4.dp, bottom = 4.dp), thickness = .7.dp, color = palette.hairline)
            Text("网络模式", style = MaterialTheme.typography.titleSmall)
            Column(Modifier.selectableGroup()) {
                NetworkMode.entries.forEach { mode ->
                    val chosen = settings.networkMode == mode
                    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(MaterialTheme.shapes.small)
                        .selectable(selected = chosen, enabled = enabled, role = Role.RadioButton,
                            onClick = { onSettings(settings.copy(networkMode = mode)) })
                        .semantics { contentDescription = "网络模式：${mode.title}" }, verticalAlignment = Alignment.CenterVertically) {
                        Text(mode.title, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                        RadioButton(selected = chosen, onClick = null, enabled = enabled, modifier = Modifier.padding(end = 12.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun CampusStorageRecovery(state: AppUiState, onReload: () -> Unit, onReset: () -> Unit) {
    GlassCard(Modifier.testTag("campus_storage_recovery")) {
        Text(if (state.loading) "正在读取账号…" else "账号存储暂不可用", style = MaterialTheme.typography.titleMedium)
        if (!state.loading) {
            Text("可以重试读取，或确认清除后重新录入。课表和应用更新可独立使用。",
                style = MaterialTheme.typography.bodyMedium, color = gxz.inkSoft)
            CtaButton("重试读取", onReload, enabled = !state.busy)
            TextButton(onClick = onReset, enabled = !state.busy, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                Text("清除本机账号并重新录入")
            }
        }
    }
}
