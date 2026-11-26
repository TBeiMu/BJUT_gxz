package io.github.tbeimu.gongxiaozhu.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.isOutOfBounds
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.SecureFlagPolicy
import io.github.tbeimu.gongxiaozhu.academic.*

private fun normalizedExam(fields: ExamFields) = fields.copy(name = fields.name.trim(), start = fields.start.trim(),
    end = fields.end.trim(), location = fields.location.trim(), note = fields.note.trim().replace("\r\n", "\n").replace('\r', '\n'))

@Composable
internal fun AcademicExamEditor(record: ExamRecord?, revision: Long, saving: Boolean, editCompleted: Long, error: String?,
    onDismiss: () -> Unit, onSave: (String?, ExamFields, Long) -> Unit, recordId: String? = record?.id,
    storageBlocked: Boolean = false, loading: Boolean = false, onReload: () -> Unit = {}) {
    val original = record?.fields ?: ExamFields("")
    val baselineName = rememberSaveable { original.name }; val baselineStart = rememberSaveable { original.start }
    val baselineEnd = rememberSaveable { original.end }; val baselineLocation = rememberSaveable { original.location }
    val baselineNote = rememberSaveable { original.note }; val expectedRevision = rememberSaveable { revision }
    val expectedId = rememberSaveable { recordId.orEmpty() }.ifBlank { null }
    var completionAtOpen by rememberSaveable { mutableLongStateOf(editCompleted) }
    var name by rememberSaveable { mutableStateOf(original.name) }; var start by rememberSaveable { mutableStateOf(original.start) }
    var end by rememberSaveable { mutableStateOf(original.end) }; var location by rememberSaveable { mutableStateOf(original.location) }
    var note by rememberSaveable { mutableStateOf(original.note) }; var discard by rememberSaveable { mutableStateOf(false) }
    var staleSaveAttempted by rememberSaveable { mutableStateOf(false) }
    val fields = normalizedExam(ExamFields(name, start, end, location, note))
    val baseline = normalizedExam(ExamFields(baselineName, baselineStart, baselineEnd, baselineLocation, baselineNote))
    val dirty = fields != baseline
    val parsedStart = fields.start.takeIf { it.isNotBlank() }?.let(::parseAcademicExamTime)
    val parsedEnd = fields.end.takeIf { it.isNotBlank() }?.let(::parseAcademicExamTime)
    val invalidTime = when {
        fields.start != baseline.start && fields.start.isNotEmpty() && parsedStart == null -> "开始时间格式应为 yyyy-MM-dd HH:mm"
        fields.end != baseline.end && fields.end.isNotEmpty() && parsedEnd == null -> "结束时间格式应为 yyyy-MM-dd HH:mm"
        parsedStart != null && parsedEnd != null && parsedEnd.isBefore(parsedStart) -> "结束时间不能早于开始时间"
        else -> null
    }
    val currentSaving by rememberUpdatedState(saving)
    val close by rememberUpdatedState(onDismiss)
    LaunchedEffect(editCompleted) {
        if (editCompleted < completionAtOpen) completionAtOpen = editCompleted
        else if (editCompleted > completionAtOpen) onDismiss()
    }
    fun latestDraft() = normalizedExam(ExamFields(name, start, end, location, note))
    fun dismiss() { if (!currentSaving) { if (latestDraft() != baseline) discard = true else close() } }
    if (discard) {
        DiscardChangesDialog(onContinue = { discard = false }, onDiscard = { if (!saving) onDismiss() })
        return
    }
    val validDraft = fields.name.isNotBlank() && invalidTime == null && (recordId == null || dirty)
    AcademicEditorDialog(if (recordId == null) "添加自定义考试" else "编辑考试", saving,
        valid = !loading && (storageBlocked || validDraft), onDismiss = ::dismiss,
        onOutsideTap = { if (!currentSaving && latestDraft() == baseline) close() }, onSave = {
            when {
                storageBlocked -> onReload()
                revision != expectedRevision || (expectedId != null && record == null) -> staleSaveAttempted = true
                else -> onSave(expectedId, latestDraft(), expectedRevision)
            }
        }, confirmLabel = if (storageBlocked) "重新读取" else "保存",
        confirmTag = if (storageBlocked) "exam_reload" else "save_exam", loading = loading) {
        if (storageBlocked) {
            Text("草稿已保留。请重新读取教务资料。", color = MaterialTheme.colorScheme.error,
                modifier = Modifier.testTag("exam_storage_error"))
        }
        OutlinedTextField(name, { name = it.take(300) }, Modifier.fillMaxWidth().testTag("exam_name"),
            enabled = !saving, label = { Text("考试名称") }, singleLine = true)
        OutlinedTextField(start, { start = it.take(32) }, Modifier.fillMaxWidth().testTag("exam_start"),
            enabled = !saving, label = { Text("开始时间（可留空）") }, placeholder = { Text("yyyy-MM-dd HH:mm") }, singleLine = true)
        OutlinedTextField(end, { end = it.take(32) }, Modifier.fillMaxWidth().testTag("exam_end"),
            enabled = !saving, label = { Text("结束时间（可留空）") }, placeholder = { Text("yyyy-MM-dd HH:mm") }, singleLine = true)
        invalidTime?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("exam_time_error")) }
        Text("时间按上海时区记录；未确定时请留空。", style = MaterialTheme.typography.bodySmall, color = gxz.inkSoft)
        OutlinedTextField(location, { location = it.take(500) }, Modifier.fillMaxWidth().testTag("exam_location"),
            enabled = !saving, label = { Text("考试地点（可选）") })
        OutlinedTextField(note, { note = it.take(1000) }, Modifier.fillMaxWidth().testTag("exam_note"),
            enabled = !saving, label = { Text("备注（可选）") })
        if (record?.original != null) Text("修改仅保存在本机，不写回学校系统。", style = MaterialTheme.typography.bodySmall, color = gxz.inkSoft)
        val saveError = when {
            storageBlocked -> null
            staleSaveAttempted || error == "教务资料已变化，请重新操作" || error == "考试记录已变化，请重新打开编辑" ->
                "教务资料已变化，请关闭后重新打开考试编辑"
            else -> error
        }
        saveError?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("exam_save_error")) }
    }
}

/** Same tap/slop rule as the course editor: an outside swipe never dismisses a draft. */
@Composable
private fun AcademicEditorDialog(title: String, saving: Boolean, valid: Boolean, onDismiss: () -> Unit,
    onOutsideTap: () -> Unit, onSave: () -> Unit, secure: Boolean = false, confirmLabel: String = "保存",
    dialogTag: String = "exam_editor", confirmTag: String = "save_exam", cancelTag: String = "cancel_exam",
    loading: Boolean = false,
    content: @Composable ColumnScope.() -> Unit) {
    var bounds by remember { mutableStateOf<Rect?>(null) }; var origin by remember { mutableStateOf(Offset.Zero) }
    val outside by rememberUpdatedState(onOutsideTap)
    val density = LocalDensity.current
    Dialog(onDismissRequest = { if (!saving) onDismiss() },
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false,
            securePolicy = if (secure) SecureFlagPolicy.SecureOn else SecureFlagPolicy.Inherit,
            dismissOnBackPress = !saving, dismissOnClickOutside = false)) {
        Box(Modifier.fillMaxSize().testTag("exam_editor_overlay").onGloballyPositioned { origin = it.positionInRoot() }
            .pointerInput(saving) {
                if (saving) return@pointerInput
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    if (bounds?.contains(down.position + origin) != false) return@awaitEachGesture
                    down.consume()
                    while (true) {
                        val change = awaitPointerEvent(PointerEventPass.Initial).changes.singleOrNull() ?: break
                        if (change.id != down.id || change.isConsumed || change.isOutOfBounds(size, extendedTouchPadding) ||
                            (change.position - down.position).getDistance() > viewConfiguration.touchSlop) break
                        if (!change.pressed) { if (bounds?.contains(change.position + origin) == false) { change.consume(); outside() }; break }
                    }
                }
            }) {
            BoxWithConstraints(Modifier.fillMaxSize().safeDrawingPadding().imePadding(), contentAlignment = Alignment.Center) {
                val compact = maxHeight < with(density) { MaterialTheme.typography.headlineSmall.lineHeight.toDp() } + 260.dp
                val margin = if (compact) 4.dp else 20.dp
                Surface(Modifier.padding(horizontal = 20.dp, vertical = margin).widthIn(max = 560.dp).fillMaxWidth()
                    .heightIn(max = (maxHeight - margin * 2).coerceAtLeast(0.dp)).testTag(dialogTag)
                    .onGloballyPositioned { bounds = it.boundsInRoot() }, shape = RoundedCornerShape(24.dp), color = gxz.glassStrong) {
                    Column {
                        if (!compact) Text(title, Modifier.fillMaxWidth().padding(20.dp), style = MaterialTheme.typography.headlineSmall)
                        Column(Modifier.weight(1f, false).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 8.dp)
                            .testTag("exam_editor_body"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            if (compact) Text(title, Modifier.fillMaxWidth(), style = MaterialTheme.typography.titleMedium)
                            content()
                        }
                        HorizontalDivider(color = gxz.hairline)
                        FlowRow(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = if (compact) 2.dp else 12.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            TextButton(onClick = onDismiss, enabled = !saving, modifier = Modifier.heightIn(min = 48.dp).testTag(cancelTag)) { Text("取消") }
                            Button(onClick = onSave, enabled = valid && !saving, modifier = Modifier.heightIn(min = 48.dp).testTag(confirmTag)) {
                                Box(contentAlignment = Alignment.Center) {
                                    Text(if (saving) "保存中" else confirmLabel, Modifier.alpha(if (loading) 0f else 1f))
                                    if (loading) BrandLoader(Modifier.size(20.dp))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun AcademicLoginDialog(state: AcademicUiState, controller: AcademicController,
    onDialogVisibilityChanged: (Boolean) -> Unit = {}) {
    val page = state.login
    ReportDialogVisibility(page != null || state.pendingIdentity != null, onDialogVisibilityChanged)
    val target = state.request?.target ?: AcademicQueryTarget.EXAMS
    val actionLabel = "${if (state.request?.intent == AcademicQueryIntent.REFRESH) "刷新" else "导入"}${target.title}"
    if (page != null) AcademicLoginForm(page, state.busy, state.message, controller::cancel, controller::refreshCaptcha, controller::submit,
        draft = state.loginDraft, onDraftChange = controller::updateLoginDraft, actionLabel = actionLabel,
        credentialBusy = state.credentialBusy, credentialMessage = state.credentialMessage)
    state.pendingIdentity?.let { identity ->
        AlertDialog(onDismissRequest = controller::cancel, modifier = Modifier.testTag("academic_switch_account"),
            title = { Text("更换教务账号？") },
            text = { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("${state.data.identity?.studentId.orEmpty()} → ${identity.studentId}")
                Text("导入成功后替换两页的学校资料，自定义考试保留。失败或取消时保留原资料。")
            } },
            confirmButton = { TextButton(controller::confirmAccountChange, modifier = Modifier.testTag("academic_confirm_switch")) { Text("确认更换") } },
            dismissButton = { TextButton(controller::cancel) { Text("取消") } })
    }
}

@Composable
internal fun AcademicLoginForm(page: AcademicLoginPage, busy: Boolean, message: String?, onCancel: () -> Unit,
    onRefreshCaptcha: (String) -> Unit, onSubmit: (String, String, String, String) -> Unit,
    draft: AcademicLoginDraft? = null, onDraftChange: ((AcademicLoginDraft) -> Unit)? = null,
    actionLabel: String = "导入考试", credentialBusy: Boolean = false, credentialMessage: String? = null) {
    // The real controller owns a rotation-stable memory draft; isolated previews use local memory only.
    var localDraft by remember { mutableStateOf(AcademicLoginDraft()) }
    val current = draft ?: localDraft
    fun change(value: AcademicLoginDraft) { if (onDraftChange != null) onDraftChange(value) else localDraft = value }
    var passwordVisible by remember { mutableStateOf(false) }
    LaunchedEffect(current.username) { passwordVisible = false }
    var submitted by remember(page.token, page.captcha) { mutableStateOf(false) }
    val focus = LocalFocusManager.current
    val bitmap = remember(page.token, page.captcha) { BitmapFactory.decodeByteArray(page.captcha, 0, page.captcha.size)?.asImageBitmap() }
    val enabled = !busy && !credentialBusy
    val canSubmit = enabled && !submitted && current.username.isNotBlank() && current.password.isNotEmpty() && current.captcha.isNotBlank() && bitmap != null
    fun submit() { if (canSubmit) { submitted = true; focus.clearFocus(); onSubmit(page.token, current.username.trim(), current.password, current.captcha.trim()) } }
    LaunchedEffect(busy) { if (!busy) submitted = false }
    AcademicEditorDialog("研究生教务登录", saving = false, valid = canSubmit, onDismiss = onCancel,
        onOutsideTap = {}, onSave = ::submit, secure = true, confirmLabel = actionLabel, loading = busy || credentialBusy,
        dialogTag = "academic_login", confirmTag = "academic_submit", cancelTag = "academic_cancel_login") {
            OutlinedTextField(current.username, { change(current.copy(username = it.take(128))) }, Modifier.fillMaxWidth().testTag("academic_username"), enabled = enabled,
                singleLine = true, label = { Text("学号") }, keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next))
            OutlinedTextField(current.password, { change(current.copy(password = it.take(512))) }, Modifier.fillMaxWidth().testTag("academic_password"), enabled = enabled,
                singleLine = true, label = { Text("密码") }, visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon = { TextButton({ passwordVisible = !passwordVisible }, enabled = enabled,
                    modifier = Modifier.semantics { contentDescription = if (passwordVisible) "隐藏密码" else "显示密码" }) { Text(if (passwordVisible) "隐藏" else "显示") } },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next, keyboardType = KeyboardType.Password, autoCorrectEnabled = false))
            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(current.rememberPassword, enabled = enabled, role = Role.Checkbox,
                onValueChange = { change(current.copy(rememberPassword = it)) }).testTag("academic_remember_password"),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Checkbox(current.rememberPassword, null, enabled = enabled)
                Text("记住密码", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            }
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                val captchaField: @Composable (Modifier) -> Unit = { fieldModifier ->
                    OutlinedTextField(current.captcha, { change(current.copy(captcha = it.take(32))) }, fieldModifier.testTag("academic_captcha"), enabled = enabled,
                        singleLine = true, label = { Text("验证码") }, keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { submit() }))
                }
                val challenge: @Composable () -> Unit = {
                    Box(Modifier.size(124.dp, 64.dp).clip(RoundedCornerShape(10.dp)).background(gxz.well)
                        .clickable(enabled = enabled, role = Role.Button, onClickLabel = "更换验证码") {
                            change(current.copy(captcha = "")); submitted = false; onRefreshCaptcha(page.token)
                        }.testTag("academic_refresh_captcha"), contentAlignment = Alignment.Center) {
                        if (busy) BrandLoader(Modifier.size(24.dp))
                        else if (bitmap != null) Image(bitmap, "教务登录验证码，点击更换", Modifier.padding(6.dp).fillMaxSize())
                        else Text("重新加载", style = MaterialTheme.typography.bodySmall, color = gxz.inkSoft)
                    }
                }
                if (maxWidth >= 260.dp * LocalDensity.current.fontScale) Row(horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically) { captchaField(Modifier.weight(1f)); challenge() }
                else Column(verticalArrangement = Arrangement.spacedBy(12.dp)) { captchaField(Modifier.fillMaxWidth()); challenge() }
            }
            message?.let { Text(it, Modifier.fillMaxWidth(), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error) }
            credentialMessage?.let { Text(it, Modifier.fillMaxWidth(), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error) }
    }
}
