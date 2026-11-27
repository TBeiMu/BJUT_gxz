package io.github.tbeimu.gongxiaozhu.ui

import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.isOutOfBounds
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.github.tbeimu.gongxiaozhu.academic.*
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

internal data class AcademicActions(
    val start: (AcademicQueryTarget, AcademicQueryIntent) -> Unit = { _, _ -> },
    val reload: () -> Unit = {}, val beginEdit: () -> Unit = {}, val clearMessage: () -> Unit = {},
    val saveExam: (String?, ExamFields, Long) -> Unit = { _, _, _ -> },
    val deleteExam: (String, Long) -> Unit = { _, _ -> }, val restoreExam: (String, Long) -> Unit = { _, _ -> },
    val unbind: (Long) -> Unit = {}, val cancel: () -> Unit = {}, val forgetPassword: () -> Unit = {},
)

@Composable
internal fun AcademicContent(grades: Boolean, state: AcademicUiState, controller: AcademicController,
    modifier: Modifier = Modifier, onDialogVisibilityChanged: (Boolean) -> Unit = {}, onBack: () -> Unit = {}) {
    val now by rememberMyGreetingMoment(::myGreetingNow)
    val actions = remember(controller) { AcademicActions(controller::start, controller::reload, controller::beginEdit,
        controller::clearMessage, controller::saveExam, controller::deleteExam, controller::restoreExam, controller::unbind,
        controller::cancel, controller::forgetPassword) }
    AcademicContentBody(grades, state, actions, modifier, now, onDialogVisibilityChanged, onBack)
}

private const val NewExam = "__new_manual_exam__"

@Composable
internal fun AcademicContentBody(grades: Boolean, state: AcademicUiState, actions: AcademicActions,
    modifier: Modifier = Modifier, now: LocalDateTime = myGreetingNow(), onDialogVisibilityChanged: (Boolean) -> Unit = {},
    onBack: () -> Unit = {}) {
    var editing by rememberSaveable { mutableStateOf<String?>(null) }
    var selectedExam by rememberSaveable { mutableStateOf<String?>(null) }
    var selectedGrade by rememberSaveable { mutableStateOf<String?>(null) }
    var detailsRevision by rememberSaveable { mutableLongStateOf(0L) }
    var showAccount by rememberSaveable { mutableStateOf(false) }
    var pendingAction by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingId by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingRevision by rememberSaveable { mutableLongStateOf(0L) }
    var addMenu by rememberSaveable { mutableStateOf(false) }
    var termMenu by rememberSaveable { mutableStateOf(false) }
    var showHidden by rememberSaveable { mutableStateOf(false) }
    var term by rememberSaveable { mutableStateOf<String?>(null) }
    val data = state.data
    val target = if (grades) AcademicQueryTarget.GRADES else AcademicQueryTarget.EXAMS
    val canEdit = !state.loading && !state.blocked && !state.saving && !state.busy && state.request == null && !state.credentialBusy
    val hidden = examListItems(data.exams.filter { it.hidden }, now)
    val shown = examListItems(data.exams.filterNot { it.hidden }, now)
    fun confirm(action: String, exam: ExamRecord? = null) {
        pendingAction = action; pendingId = exam?.id; pendingRevision = data.revision
    }
    fun edit(exam: ExamRecord?) {
        selectedExam = null; actions.beginEdit(); actions.clearMessage(); editing = exam?.id ?: NewExam
    }
    ReportDialogVisibility(editing != null || selectedExam != null || selectedGrade != null || showAccount ||
        pendingAction != null || addMenu || termMenu, onDialogVisibilityChanged)
    LaunchedEffect(state.blocked, state.request) {
        if (state.request != null) editing = null
        if (state.blocked || state.request != null) {
            selectedExam = null; selectedGrade = null; showAccount = false
            pendingAction = null; addMenu = false; termMenu = false
        }
    }
    LaunchedEffect(data.revision) {
        if (detailsRevision != data.revision) { selectedExam = null; selectedGrade = null }
    }
    val editorId = editing
    if (editorId != null) {
        key(editorId) {
            val recordId = editorId.takeUnless { it == NewExam }
            val record = data.exams.firstOrNull { it.id == recordId }
            AcademicExamEditor(record, data.revision, state.saving, state.editCompleted, state.message,
                { editing = null }, actions.saveExam, recordId = recordId, storageBlocked = state.blocked,
                loading = state.loading, onReload = actions.reload)
        }
    }
    if (pendingAction == null) {
        data.exams.firstOrNull { it.id == selectedExam }?.let { exam ->
            AcademicExamDetails(exam, canEdit, { selectedExam = null }, { edit(exam) },
                { confirm("delete", exam) }, { confirm("restore", exam) })
        }
        val gradeParts = selectedGrade?.split('/')
        val gradeGroup = gradeParts?.getOrNull(0)?.toIntOrNull()
        val gradeIndex = gradeParts?.getOrNull(1)?.toIntOrNull()
        val record = when (gradeGroup) { 0 -> data.grades?.degree; 1 -> data.grades?.elective; else -> null }?.getOrNull(gradeIndex ?: -1)
        if (record != null) AcademicGradeDetails(record, if (gradeGroup == 0) "学位课" else "选修课") { selectedGrade = null }
        if (showAccount) AcademicAccountDialog(state, { showAccount = false }, actions.forgetPassword) { confirm("unbind") }
    }
    if (pendingAction != null) {
        val exam = data.exams.firstOrNull { it.id == pendingId }
        val action = pendingAction
        AlertDialog(onDismissRequest = { if (!state.saving) pendingAction = null },
            modifier = Modifier.testTag("academic_confirmation"),
            title = { Text(when (action) {
                "delete" -> if (exam?.original != null && !exam.retained) "隐藏这场考试？" else "删除这场考试？"
                "restore" -> "恢复上次导入内容？"; else -> "解除教务账号绑定？"
            }) },
            text = { Text(when (action) {
                "delete" -> when {
                    exam?.retained == true -> "仅删除本地保留副本，当前学校安排不受影响。"
                    exam?.original != null -> "仅在本机隐藏“${exam.fields.name}”，不会修改学校记录。可在已隐藏考试中恢复。"
                    else -> "删除自定义考试“${exam?.fields?.name.orEmpty()}”。"
                }
                "restore" -> "恢复上次导入的内容，移除本地修改或隐藏标记。"
                else -> "移除学校考试、成绩及本地修改，清除记住的教务密码。自定义考试保留。"
            }) },
            confirmButton = { TextButton(onClick = {
                when (action) {
                    "delete" -> pendingId?.let { actions.deleteExam(it, pendingRevision) }
                    "restore" -> pendingId?.let { actions.restoreExam(it, pendingRevision) }
                    else -> actions.unbind(pendingRevision)
                }; pendingAction = null; selectedExam = null; showAccount = false
            }, enabled = canEdit && (action == "unbind" || exam != null), modifier = Modifier.testTag("academic_confirm")) {
                Text(when (action) { "delete" -> if (exam?.original != null && !exam.retained) "隐藏考试" else "删除考试"
                    "restore" -> "恢复内容"; else -> "解除绑定" })
            } },
            dismissButton = { TextButton(onClick = { pendingAction = null }, enabled = !state.saving) { Text("取消") } })
    }
    Column(modifier.fillMaxSize()) {
        AcademicPageHeader(target, state, actions, canEdit, addMenu, { addMenu = it }, onBack,
            onAdd = { edit(null) }, onAccount = { showAccount = true })
        LazyColumn(Modifier.weight(1f).fillMaxWidth().testTag(if (grades) "academic_grades" else "academic_exams"),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (state.loading) item(key = "loading") {
                Box(Modifier.fillMaxWidth().heightIn(min = 180.dp), contentAlignment = Alignment.Center) {
                    BrandLoader(Modifier.size(38.dp).testTag("academic_loading"))
                }
            } else if (state.blocked) item(key = "blocked") {
                GlassCard {
                    val canRecover = !state.busy && !state.saving && !state.credentialBusy
                    Text("教务资料暂不可用，原资料未被清除。", Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.error)
                    CtaButton("重新读取教务资料", actions.reload, enabled = canRecover)
                    state.credentialMessage?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    }
                    if (state.rememberedAccount != null || state.credentialMessage != null || state.credentialBusy) {
                        TextButton(actions.forgetPassword, enabled = canRecover,
                            modifier = Modifier.testTag("academic_forget_password")) {
                            Text(if (state.credentialBusy) "正在清除密码" else "清除教务密码")
                        }
                    }
                }
            } else {
                item(key = "metadata") { AcademicMetadata(grades, state, now) }
                if (state.credentialMessage != null && state.request == null) item(key = "credential_message") {
                    Text(state.credentialMessage, Modifier.fillMaxWidth().padding(horizontal = 4.dp), style = MaterialTheme.typography.bodySmall, color = gxz.ink)
                }
                if (!grades) {
                    if (shown.isEmpty()) item(key = "empty_exams") { AcademicEmptyState(AppGlyph.EXAM,
                        "还没有考试安排", "轻点右上角 ＋ 导入或添加") }
                    items(shown, key = { "exam/${it.record.id}" }) { item ->
                        AcademicExamCard(item, now) { detailsRevision = data.revision; selectedExam = item.record.id }
                    }
                    if (hidden.isNotEmpty()) {
                        item(key = "hidden_toggle") { TextButton({ showHidden = !showHidden }, Modifier.testTag("academic_hidden_toggle")) {
                            Text("${if (showHidden) "收起" else "已隐藏考试"} · ${hidden.size}")
                        } }
                        if (showHidden) items(hidden, key = { "hidden/${it.record.id}" }) { item ->
                            AcademicExamCard(item, now) { detailsRevision = data.revision; selectedExam = item.record.id }
                        }
                    }
                } else {
                    val snapshot = data.grades
                    if (snapshot == null) item(key = "empty_grades") {
                        AcademicEmptyState(AppGlyph.GRADES, "成绩等待导入", "轻点右上角 ＋ 导入")
                    } else {
                        item(key = "credits") { AcademicCreditsCard(snapshot.credits) }
                        val terms = (snapshot.degree + snapshot.elective).map { it.term }.distinct().sortedDescending()
                        val actualTerm = term?.takeIf { it in terms }
                        item(key = "term_filter") { Box {
                            TextButton({ termMenu = true }, Modifier.testTag("grade_term_filter")) {
                                Text(actualTerm?.ifBlank { "学期未提供" } ?: "全部学期")
                                Spacer(Modifier.width(6.dp)); LineGlyph(AppGlyph.CHEVRON, Modifier.size(12.dp))
                            }
                            DropdownMenu(termMenu, { termMenu = false }, shape = TileShape) {
                                DropdownMenuItem({ Text("全部学期") }, { term = null; termMenu = false }, modifier = Modifier.testTag("grade_term_all"))
                                terms.forEachIndexed { index, value -> DropdownMenuItem({ Text(value.ifBlank { "学期未提供" }) },
                                    { term = value; termMenu = false }, modifier = Modifier.testTag("grade_term_$index")) }
                            }
                        } }
                        listOf("学位课" to snapshot.degree, "选修课" to snapshot.elective).forEachIndexed { group, (title, records) ->
                            val filtered = records.withIndex().filter { actualTerm == null || it.value.term == actualTerm }
                            item(key = "grade_title_$group") { AcademicSectionLabel(title, "${filtered.size} 门") }
                            if (filtered.isEmpty()) item(key = "grade_empty_$group") {
                                Text("暂无${title}成绩", Modifier.fillMaxWidth().padding(horizontal = 8.dp), style = MaterialTheme.typography.bodyMedium, color = gxz.ink)
                            }
                            items(filtered, key = { "grade/$group/${it.index}" }) { (index, grade) ->
                                AcademicGradeCard(grade, "grade_${group}_$index") { detailsRevision = data.revision; selectedGrade = "$group/$index" }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AcademicPageHeader(target: AcademicQueryTarget, state: AcademicUiState, actions: AcademicActions, canEdit: Boolean,
    expanded: Boolean, onExpand: (Boolean) -> Unit, onBack: () -> Unit, onAdd: () -> Unit, onAccount: () -> Unit) {
    val hasSnapshot = if (target == AcademicQueryTarget.EXAMS) state.data.examUpdatedAt != null else state.data.grades != null
    val operation: @Composable RowScope.() -> Unit = {
        val active = state.request?.takeIf { it.target == target }
        val canCancel = state.request != null && state.login == null && state.pendingIdentity == null && !state.committing
        val cancelOnRefresh = canCancel && active?.intent == AcademicQueryIntent.IMPORT
        val cancelOnAdd = canCancel && !cancelOnRefresh
        Box(Modifier.size(48.dp).testTag("academic_refresh_slot")) {
            PlainIconButton({ if (cancelOnRefresh) actions.cancel() else actions.start(target, AcademicQueryIntent.REFRESH) },
                Modifier.size(48.dp).testTag(if (cancelOnRefresh) "academic_cancel_query" else "academic_refresh")
                    .semantics { contentDescription = if (cancelOnRefresh) "取消查询" else "刷新研究生${target.title}" },
                cancelOnRefresh || canEdit && hasSnapshot && state.networkAvailable) {
                if (state.busy && active?.intent == AcademicQueryIntent.REFRESH) BrandLoader(Modifier.size(22.dp).testTag("academic_refresh_loading"))
                else if (cancelOnRefresh) LineGlyph(AppGlyph.PLUS, Modifier.size(20.dp).rotate(45f))
                else LineGlyph(AppGlyph.REFRESH, Modifier.size(20.dp))
            }
        }
        Box(Modifier.size(48.dp).testTag("academic_add_slot")) {
            PlainIconButton({ if (cancelOnAdd) actions.cancel() else onExpand(true) },
                Modifier.size(48.dp).testTag(if (cancelOnAdd) "academic_cancel_query" else "academic_add")
                .semantics { contentDescription = if (cancelOnAdd) "取消查询" else if (target == AcademicQueryTarget.EXAMS) "导入或添加考试" else "导入成绩" },
                cancelOnAdd || canEdit) {
                if (state.busy && active?.intent == AcademicQueryIntent.IMPORT) BrandLoader(Modifier.size(22.dp).testTag("academic_import_loading"))
                else if (cancelOnAdd) LineGlyph(AppGlyph.PLUS, Modifier.size(20.dp).rotate(45f))
                else LineGlyph(AppGlyph.PLUS, Modifier.size(20.dp))
            }
            DropdownMenu(expanded, { onExpand(false) }, Modifier.widthIn(max = 320.dp).testTag("academic_add_menu"),
                shape = TileShape, containerColor = MaterialTheme.colorScheme.surface) {
                DropdownMenuItem(text = { Column { Text("导入本科生${target.title}"); Text("暂未开放", style = MaterialTheme.typography.bodySmall) } },
                    onClick = {}, enabled = false, leadingIcon = { LineGlyph(AppGlyph.DOWNLOAD, Modifier.size(20.dp)) },
                    modifier = Modifier.testTag("academic_import_undergraduate"))
                DropdownMenuItem(text = { Text("导入研究生${target.title}") }, onClick = { onExpand(false); actions.start(target, AcademicQueryIntent.IMPORT) },
                    enabled = canEdit && state.networkAvailable, leadingIcon = { LineGlyph(AppGlyph.DOWNLOAD, Modifier.size(20.dp)) },
                    modifier = Modifier.testTag("academic_import_graduate"))
                if (target == AcademicQueryTarget.EXAMS) {
                    HorizontalDivider(Modifier.padding(horizontal = 12.dp), color = gxz.hairline)
                    DropdownMenuItem({ Text("添加自定义考试") }, { onExpand(false); onAdd() }, enabled = canEdit,
                        leadingIcon = { LineGlyph(AppGlyph.PLUS, Modifier.size(20.dp), gxz.brandAccent) }, modifier = Modifier.testTag("academic_add_exam"))
                }
                if (state.data.identity != null || state.rememberedAccount != null || state.credentialMessage != null) {
                    HorizontalDivider(Modifier.padding(horizontal = 12.dp), color = gxz.hairline)
                    DropdownMenuItem({ Text("教务账号") }, { onExpand(false); onAccount() },
                        leadingIcon = { LineGlyph(AppGlyph.PERSON, Modifier.size(20.dp)) }, modifier = Modifier.testTag("academic_account"))
                }
            }
        }
    }
    BoxWithConstraints(Modifier.fillMaxWidth().padding(horizontal = 8.dp).testTag("academic_toolbar")) {
        val titleWidth = with(LocalDensity.current) { MaterialTheme.typography.titleLarge.fontSize.toDp() } * 4
        val narrow = maxWidth < titleWidth + 156.dp
        Column {
            Row(Modifier.fillMaxWidth().heightIn(min = 64.dp), verticalAlignment = Alignment.CenterVertically) {
                RoundIconButton(onBack, Modifier.size(48.dp).semantics { contentDescription = "返回" }) { LineGlyph(AppGlyph.BACK, Modifier.size(20.dp)) }
                Text("${target.title}管理", Modifier.weight(1f).padding(start = 6.dp).semantics { heading() },
                    style = MaterialTheme.typography.titleLarge, color = gxz.ink)
                if (!narrow) operation()
            }
            if (narrow) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically, content = operation)
        }
    }
}

@Composable
private fun AcademicMetadata(grades: Boolean, state: AcademicUiState, now: LocalDateTime) {
    val updatedAt = if (grades) state.data.grades?.updatedAt else state.data.examUpdatedAt
    val status = if (grades) state.gradeMessage else state.examMessage
    val target = if (grades) AcademicQueryTarget.GRADES else AcademicQueryTarget.EXAMS
    val style = MaterialTheme.typography.bodySmall
    val density = LocalDensity.current
    val measurer = rememberTextMeasurer()
    val lineHeight = with(density) { style.lineHeight.toDp() }
    val statusWidth = with(density) { maxOf(style.fontSize.toDp() * 4,
        listOf("更新成功", "查询失败", "查询中", "离线").maxOf { measurer.measure(it, style).size.width }.toDp()) } + 2.dp
    val dateText = updatedAt?.let { "更新于 ${academicUpdatedAt(it, now)}" } ?: "尚未导入"
    val text = when {
        state.request?.target == target && state.busy -> when {
            !state.committing -> "查询中"
            status == "正在查询" -> "保存中"
            else -> status.orEmpty()
        }
        !state.networkAvailable -> "离线"
        else -> status.orEmpty()
    }
    BoxWithConstraints(Modifier.fillMaxWidth().padding(horizontal = 4.dp)) {
        val dateWidth = with(density) { measurer.measure("更新于 8888-88-88 88:88", style).size.width.toDp() }
        val statusContent: @Composable () -> Unit = {
            Text(text, Modifier.fillMaxWidth(), style = style, color = if (text == "查询失败") MaterialTheme.colorScheme.error else gxz.ink)
        }
        if (maxWidth >= dateWidth + 12.dp + statusWidth) Row(Modifier.fillMaxWidth().heightIn(min = lineHeight),
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(dateText, Modifier.weight(1f), style = style, color = gxz.ink)
            Box(Modifier.width(statusWidth), contentAlignment = Alignment.TopEnd) { statusContent() }
        } else {
            val dateHeight = with(density) { measurer.measure("更新于 8888-88-88 88:88", style,
                constraints = Constraints(maxWidth = maxWidth.roundToPx())).size.height.toDp() }
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(dateText, Modifier.fillMaxWidth().heightIn(min = dateHeight), style = style, color = gxz.ink)
                Box(Modifier.fillMaxWidth().heightIn(min = lineHeight)) { statusContent() }
            }
        }
    }
}

@Composable
private fun AcademicSectionLabel(title: String, count: String) {
    val countWidth = with(LocalDensity.current) { MaterialTheme.typography.labelMedium.fontSize.toDp() } * count.length
    Row(Modifier.fillMaxWidth().padding(start = 8.dp, end = 8.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, color = gxz.ink)
        Text(count, Modifier.widthIn(min = countWidth), style = MaterialTheme.typography.labelMedium, textAlign = TextAlign.End, color = gxz.ink)
    }
}

@Composable
private fun AcademicInfoCard(tag: String, onClick: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    Column(Modifier.fillMaxWidth().pressScale(interaction, .987f).glass(gxz).clickable(interaction, LocalIndication.current,
        role = Role.Button, onClickLabel = "查看详情", onClick = onClick).testTag(tag).padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp), content = content)
}

@Composable
private fun AcademicExamCard(item: ExamListItem, now: LocalDateTime, onClick: () -> Unit) {
    val exam = item.record
    val start = item.start
    val titleColor = if (item.finished) gxz.inkSoft else gxz.ink
    val badge = when { exam.hidden -> "已隐藏"; exam.original == null -> "自定义"; exam.retained -> "本地保留"; exam.modified -> "已修改"; else -> null }
    AcademicInfoCard("exam_${exam.id}", onClick) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val narrow = maxWidth < 240.dp * LocalDensity.current.fontScale
            val information: @Composable ColumnScope.() -> Unit = {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
                    Text(exam.fields.name, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, color = titleColor)
                    if (badge != null) {
                        val badgeWidth = with(LocalDensity.current) { MaterialTheme.typography.labelSmall.fontSize.toDp() } * badge.length + 14.dp
                        Text(badge, Modifier.widthIn(min = badgeWidth).background(gxz.well, RoundedCornerShape(6.dp)).padding(horizontal = 7.dp, vertical = 3.dp),
                            style = MaterialTheme.typography.labelSmall, color = gxz.inkSoft)
                    }
                }
                Text(examTimeSummary(item, now, includeDate = narrow || start == null), Modifier.fillMaxWidth(), style = MaterialTheme.typography.bodyMedium, color = gxz.inkSoft)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.Top) {
                    LineGlyph(AppGlyph.LOCATION, Modifier.padding(top = 2.dp).size(16.dp), gxz.inkSoft)
                    Text(exam.fields.location.ifBlank { "地点未定" }, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, color = gxz.inkSoft)
                }
            }
            if (narrow) Column(verticalArrangement = Arrangement.spacedBy(8.dp), content = information)
            else Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.width(56.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(1.dp)) {
                    if (start != null) {
                        Text(if (start.year == now.year) "${start.monthValue}月" else "${start.year}/${start.monthValue}", Modifier.fillMaxWidth(),
                            style = MaterialTheme.typography.labelMedium, textAlign = TextAlign.Center, color = gxz.inkSoft)
                        Text(start.dayOfMonth.toString().padStart(2, '0'), Modifier.fillMaxWidth(), style = MaterialTheme.typography.headlineMedium, textAlign = TextAlign.Center, color = titleColor)
                        Text("周${"一二三四五六日"[start.dayOfWeek.value - 1]}", Modifier.fillMaxWidth(), style = MaterialTheme.typography.labelMedium, textAlign = TextAlign.Center, color = gxz.inkSoft)
                    } else LineGlyph(AppGlyph.CALENDAR, Modifier.size(26.dp), gxz.inkSoft)
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp), content = information)
            }
        }
    }
}

private fun examTimeSummary(item: ExamListItem, now: LocalDateTime, includeDate: Boolean): String {
    val fields = item.record.fields
    val start = item.start
    val end = item.end
    val time = DateTimeFormatter.ofPattern("HH:mm")
    if (start == null) return listOf(fields.start.ifBlank { "时间未定" }, fields.end).filter { it.isNotBlank() }.joinToString(" – ")
    val date = DateTimeFormatter.ofPattern(if (start.year == now.year) "M月d日" else "yyyy年M月d日")
    if (item.finished) return (if (includeDate) "${start.format(date)} · " else "") + "已结束"
    val first = (if (includeDate) "${start.format(date)} · " else "") + start.format(time)
    val last = when {
        end == null -> fields.end.ifBlank { "结束待定" }
        start.toLocalDate() == end.toLocalDate() -> end.format(time)
        else -> end.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))
    }
    return "$first – $last"
}

@Composable
private fun AcademicGradeCard(grade: GradeRecord, tag: String, onClick: () -> Unit) {
    AcademicInfoCard(tag, onClick) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val inline = maxWidth >= 250.dp * LocalDensity.current.fontScale && grade.grade.length <= 6
            val course: @Composable ColumnScope.() -> Unit = {
                Text(grade.course.ifBlank { "课程名称未提供" }, Modifier.fillMaxWidth(), style = MaterialTheme.typography.titleMedium, color = gxz.ink)
                Text(listOf("${grade.credits.ifBlank { "未获取" }} 学分", grade.term.ifBlank { "学期未提供" }).joinToString(" · "),
                    Modifier.fillMaxWidth(), style = MaterialTheme.typography.bodyMedium, color = gxz.inkSoft)
            }
            if (inline) Row(horizontalArrangement = Arrangement.spacedBy(18.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp), content = course)
                Text(grade.grade.ifBlank { "未获取" }, Modifier.width(110.dp), style = MaterialTheme.typography.headlineSmall, color = gxz.ink, textAlign = TextAlign.End)
            } else Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                course(); Text(grade.grade.ifBlank { "未获取" }, Modifier.fillMaxWidth(), style = MaterialTheme.typography.headlineSmall, color = gxz.ink)
            }
        }
        if (grade.restricted) Text("成绩受限", Modifier.fillMaxWidth(), style = MaterialTheme.typography.labelMedium, color = gxz.inkSoft)
    }
}

@Composable
private fun AcademicCreditsCard(credits: Map<String, String>) {
    GlassCard(spacing = 16.dp, padding = PaddingValues(18.dp)) {
        val labels = listOf("已修学分", "应修学分", "学位学分", "选修学分")
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val columns = if (maxWidth >= 230.dp * LocalDensity.current.fontScale) 2 else 1
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                labels.chunked(columns).forEach { row ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                        row.forEach { label -> Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(label, Modifier.fillMaxWidth(), style = MaterialTheme.typography.labelMedium, color = gxz.inkSoft)
                            Text(credits[label].orEmpty().ifBlank { "未获取" }, Modifier.fillMaxWidth(), style = MaterialTheme.typography.headlineSmall, color = gxz.ink)
                        } }
                    }
                }
            }
        }
    }
}

@Composable
private fun AcademicEmptyState(glyph: AppGlyph, title: String, description: String) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 44.dp), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        LineGlyph(glyph, Modifier.size(32.dp), gxz.inkFaint)
        Text(title, Modifier.fillMaxWidth(), style = MaterialTheme.typography.titleMedium, color = gxz.ink, textAlign = TextAlign.Center)
        Text(description, Modifier.fillMaxWidth(), style = MaterialTheme.typography.bodyMedium, color = gxz.ink, textAlign = TextAlign.Center)
    }
}

@Composable
private fun AcademicDetailsDialog(tag: String, title: String, onDismiss: () -> Unit,
    actions: @Composable FlowRowScope.() -> Unit = {}, content: @Composable ColumnScope.() -> Unit) {
    var bounds by remember { mutableStateOf<Rect?>(null) }
    var origin by remember { mutableStateOf(Offset.Zero) }
    val dismiss by rememberUpdatedState(onDismiss)
    Dialog(onDismiss, DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        BoxWithConstraints(Modifier.fillMaxSize().safeDrawingPadding().imePadding().testTag("academic_details_overlay")
            .onGloballyPositioned { origin = it.positionInRoot() }
            .pointerInput(Unit) {
                // The full-screen dialog owns the blank area; only a stationary outside tap closes it.
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    if (bounds?.contains(down.position + origin) != false) return@awaitEachGesture
                    down.consume()
                    while (true) {
                        val change = awaitPointerEvent(PointerEventPass.Initial).changes.singleOrNull() ?: break
                        if (change.id != down.id || change.isConsumed || change.isOutOfBounds(size, extendedTouchPadding) ||
                            (change.position - down.position).getDistance() > viewConfiguration.touchSlop) break
                        if (!change.pressed) {
                            if (bounds?.contains(change.position + origin) == false) { change.consume(); dismiss() }
                            break
                        }
                    }
                }
            }, contentAlignment = Alignment.Center) {
            val margin = if (maxHeight < 360.dp) 8.dp else 20.dp
            Surface(Modifier.padding(horizontal = 20.dp, vertical = margin).widthIn(max = 560.dp).fillMaxWidth()
                .heightIn(max = (maxHeight - margin * 2).coerceAtLeast(0.dp)).testTag(tag)
                .onGloballyPositioned { bounds = it.boundsInRoot() },
                shape = RoundedCornerShape(24.dp), color = gxz.glassStrong) {
                Column {
                    Column(Modifier.weight(1f, false).verticalScroll(rememberScrollState()).padding(20.dp).testTag("academic_details_body"),
                        verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        Text(title, Modifier.fillMaxWidth().semantics { heading() }, style = MaterialTheme.typography.headlineSmall, color = gxz.ink)
                        content()
                    }
                    HorizontalDivider(color = gxz.hairline)
                    FlowRow(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.End), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        actions()
                        TextButton(onDismiss, Modifier.heightIn(min = 48.dp).testTag("academic_close_details")) { Text("关闭") }
                    }
                }
            }
        }
    }
}

@Composable
private fun AcademicValue(label: String, value: String) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, Modifier.fillMaxWidth(), style = MaterialTheme.typography.labelMedium, color = gxz.inkSoft)
        Text(value.ifBlank { "未获取" }, Modifier.fillMaxWidth(), style = MaterialTheme.typography.bodyLarge, color = gxz.ink)
    }
}

@Composable
private fun AcademicExamDetails(exam: ExamRecord, enabled: Boolean, onDismiss: () -> Unit, onEdit: () -> Unit,
    onDelete: () -> Unit, onRestore: () -> Unit) {
    AcademicDetailsDialog("academic_exam_details", exam.fields.name, onDismiss, actions = {
        if (!exam.hidden) {
            TextButton(onEdit, Modifier.heightIn(min = 48.dp).testTag("edit_exam_${exam.id}"), enabled) { Text("编辑") }
            TextButton(onDelete, Modifier.heightIn(min = 48.dp).testTag("delete_exam_${exam.id}"), enabled) {
                Text(if (exam.original == null || exam.retained) "删除" else "隐藏")
            }
        }
        if (exam.original != null && (exam.modified || exam.retained || exam.hidden))
            TextButton(onRestore, Modifier.heightIn(min = 48.dp).testTag("restore_exam_${exam.id}"), enabled) { Text("恢复上次导入") }
    }) {
        AcademicValue("开始", exam.fields.start.ifBlank { "时间未定" })
        AcademicValue("结束", exam.fields.end.ifBlank { "时间未定" })
        AcademicValue("地点", exam.fields.location.ifBlank { "地点未定" })
        exam.original?.let { school ->
            if (school.code.isNotBlank()) AcademicValue("课程编号", school.code)
            if (school.className.isNotBlank()) AcademicValue("教学班", school.className)
        }
        if (exam.fields.note.isNotBlank()) AcademicValue("备注", exam.fields.note)
        Text(when { exam.hidden -> "已隐藏的学校考试"; exam.original == null -> "自定义考试"
            exam.retained -> "刷新后未能关联学校新安排，已保留本机内容。"
            exam.modified -> "已在本机修改，学校记录不受影响。"; else -> "研究生学校考试" },
            modifier = Modifier.fillMaxWidth(), style = MaterialTheme.typography.bodySmall, color = gxz.inkSoft)
    }
}

@Composable
private fun AcademicGradeDetails(grade: GradeRecord, group: String, onDismiss: () -> Unit) {
    AcademicDetailsDialog("academic_grade_details", grade.course.ifBlank { "课程名称未提供" }, onDismiss) {
        AcademicValue("成绩", grade.grade); AcademicValue("学分", grade.credits)
        AcademicValue("学期", grade.term); AcademicValue("课程类别", group)
        if (grade.restricted) Text("成绩受限 · 以学校显示为准", Modifier.fillMaxWidth(), style = MaterialTheme.typography.bodyMedium, color = gxz.inkSoft)
    }
}

@Composable
private fun AcademicAccountDialog(state: AcademicUiState, onDismiss: () -> Unit, onForget: () -> Unit, onUnbind: () -> Unit) {
    AcademicDetailsDialog("academic_account_dialog", "教务账号", onDismiss, actions = {
        if (state.rememberedAccount != null || state.credentialMessage != null) TextButton(onForget,
            Modifier.heightIn(min = 48.dp).testTag("academic_forget_password"), !state.credentialBusy && !state.saving) {
            if (state.credentialBusy) BrandLoader(Modifier.size(20.dp)) else Text("清除密码")
        }
        if (state.data.identity != null) TextButton(onUnbind, Modifier.heightIn(min = 48.dp).testTag("academic_unbind"),
            !state.credentialBusy && !state.saving) { Text("解除绑定") }
    }) {
        state.data.identity?.let {
            AcademicValue("学号", it.studentId)
            if (it.name.isNotBlank()) AcademicValue("姓名", it.name)
        }
        Text(if (state.rememberedAccount != null) "已记住教务密码" else "未记住教务密码", Modifier.fillMaxWidth(), style = MaterialTheme.typography.bodyMedium, color = gxz.inkSoft)
        state.credentialMessage?.let { Text(it, Modifier.fillMaxWidth(), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error) }
    }
}

private fun academicUpdatedAt(value: Long, now: LocalDateTime): String {
    val date = Instant.ofEpochMilli(value).atZone(ZoneId.of("Asia/Shanghai"))
    return date.format(DateTimeFormatter.ofPattern(if (date.year == now.year) "MM-dd HH:mm" else "yyyy-MM-dd HH:mm"))
}
