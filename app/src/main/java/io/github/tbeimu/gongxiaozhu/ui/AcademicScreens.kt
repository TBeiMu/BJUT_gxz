package io.github.tbeimu.gongxiaozhu.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.SecureFlagPolicy
import io.github.tbeimu.gongxiaozhu.academic.*
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** UI callbacks keep rendering testable without opening a network session or an account vault. */
internal data class AcademicActions(
    val start: () -> Unit = {},
    val reload: () -> Unit = {},
    val beginEdit: () -> Unit = {},
    val clearMessage: () -> Unit = {},
    val saveExam: (String?, ExamFields, Long) -> Unit = { _, _, _ -> },
    val deleteExam: (String, Long) -> Unit = { _, _ -> },
    val restoreExam: (String, Long) -> Unit = { _, _ -> },
    val unbind: (Long) -> Unit = {},
    val cancel: () -> Unit = {},
)

@Composable
internal fun AcademicContent(grades: Boolean, state: AcademicUiState, controller: AcademicController,
    modifier: Modifier = Modifier, onDialogVisibilityChanged: (Boolean) -> Unit = {}) {
    val now by rememberMyGreetingMoment(::myGreetingNow)
    val actions = remember(controller) { AcademicActions(controller::start, controller::reload, controller::beginEdit,
        controller::clearMessage, controller::saveExam, controller::deleteExam, controller::restoreExam, controller::unbind, controller::cancel) }
    AcademicContentBody(grades, state, actions, modifier, now, onDialogVisibilityChanged)
}

private enum class ExamFilter(val title: String) { ALL("全部"), UPCOMING("待考"), FINISHED("已结束"), UNSET("未定") }
private const val NewExam = "__new_manual_exam__"
private val AcademicTimeFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

@Composable
internal fun AcademicContentBody(grades: Boolean, state: AcademicUiState, actions: AcademicActions,
    modifier: Modifier = Modifier, now: LocalDateTime = myGreetingNow(), onDialogVisibilityChanged: (Boolean) -> Unit = {}) {
    var editing by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingId by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingAction by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingRevision by rememberSaveable { mutableLongStateOf(0L) }
    var examFilter by rememberSaveable { mutableStateOf(ExamFilter.ALL) }
    var showHidden by rememberSaveable { mutableStateOf(false) }
    var term by rememberSaveable { mutableStateOf<String?>(null) }
    val canEdit = !state.loading && !state.blocked && !state.saving && !state.busy
    val data = state.data
    val hidden = data.exams.filter { it.hidden }
    val shown = data.exams.filterNot { it.hidden }.filter { exam ->
        val start = parseAcademicExamTime(exam.fields.start)
        val end = parseAcademicExamTime(exam.fields.end)?.takeUnless { start != null && it.isBefore(start) }
        when (examFilter) {
            ExamFilter.ALL -> true
            ExamFilter.UPCOMING -> start != null && (end?.isBefore(now) == false || end == null && !start.isBefore(now))
            ExamFilter.FINISHED -> start != null && end?.isBefore(now) == true
            ExamFilter.UNSET -> start == null || end == null && start.isBefore(now)
        }
    }.sortedWith(compareBy<ExamRecord> { parseAcademicExamTime(it.fields.start) ?: LocalDateTime.MAX }.thenBy { it.fields.name })
    fun confirm(action: String, exam: ExamRecord? = null) {
        pendingId = exam?.id; pendingRevision = data.revision; pendingAction = action
    }
    ReportDialogVisibility(editing != null || pendingAction != null, onDialogVisibilityChanged)
    LaunchedEffect(state.blocked) { if (state.blocked) { editing = null; pendingAction = null } }
    val editRecord = data.exams.firstOrNull { it.id == editing }
    LaunchedEffect(editing, data.revision, state.loading) {
        if (!state.loading && editing != null && editing != NewExam && editRecord == null) editing = null
    }
    if (editing != null && (editing == NewExam || editRecord != null)) key(editing) {
        AcademicExamEditor(editRecord, data.revision, state.saving, state.editCompleted, state.message,
            onDismiss = { editing = null }, onSave = actions.saveExam)
    }
    if (pendingAction != null) {
        val exam = data.exams.firstOrNull { it.id == pendingId }
        val action = pendingAction
        AlertDialog(onDismissRequest = { if (!state.saving) pendingAction = null },
            modifier = Modifier.testTag("academic_confirmation"),
            properties = DialogProperties(dismissOnBackPress = !state.saving, dismissOnClickOutside = !state.saving),
            title = { Text(when (action) { "delete" -> if (exam?.original != null && !exam.retained) "隐藏这场考试？" else "删除这场考试？"
                "restore" -> "恢复上次导入内容？"; else -> "解除教务账号绑定？" }) },
            text = { Text(when (action) {
                "delete" -> when {
                    exam?.retained == true -> "仅删除本地保留副本，当前学校安排不受影响。"
                    exam?.original != null -> "仅在本机隐藏“${exam.fields.name}”，不会修改学校记录。可在已隐藏考试中恢复。"
                    else -> "删除本机手动添加的“${exam?.fields?.name.orEmpty()}”。"
                }
                "restore" -> "使用上次成功导入的学校内容，移除这场考试的本地修改或隐藏标记。"
                else -> "移除本机学校考试、成绩及其本地修改，保留手动添加的考试。不会影响课表或校园网账号。"
            }) },
            confirmButton = { TextButton(onClick = {
                when (action) {
                    "delete" -> pendingId?.let { actions.deleteExam(it, pendingRevision) }
                    "restore" -> pendingId?.let { actions.restoreExam(it, pendingRevision) }
                    else -> actions.unbind(pendingRevision)
                }; pendingAction = null
            }, enabled = canEdit && (action == "unbind" || exam != null), modifier = Modifier.testTag("academic_confirm")) {
                Text(if (action == "delete") { if (exam?.original != null && !exam.retained) "隐藏考试" else "删除考试" }
                    else if (action == "restore") "恢复内容" else "解除绑定")
            } },
            dismissButton = { TextButton(onClick = { pendingAction = null }, enabled = !state.saving) { Text("取消") } })
    }
    LazyColumn(modifier.fillMaxSize().testTag(if (grades) "academic_grades" else "academic_exams"),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item(key = "identity") {
            GlassCard(spacing = 12.dp) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    GlyphChip(if (grades) AppGlyph.GRADES else AppGlyph.EXAM, Modifier.size(42.dp))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(if (data.identity == null) "教务资料" else "已绑定教务账号",
                            style = MaterialTheme.typography.labelMedium, color = gxz.inkSoft)
                        Text(data.identity?.let { it.name.ifBlank { it.studentId } } ?: "尚未绑定教务账号",
                            style = MaterialTheme.typography.titleMedium, modifier = Modifier.testTag("academic_identity"), softWrap = true)
                        data.identity?.takeIf { it.name.isNotBlank() }?.let {
                            Text(it.studentId, style = MaterialTheme.typography.bodySmall, color = gxz.inkSoft, softWrap = true)
                        }
                    }
                }
                if (data.identity == null && !state.blocked) Text(if (grades) "登录后导入考试与成绩，之后可离线查看。"
                    else "登录后导入考试与成绩，也可先添加手动考试。",
                    style = MaterialTheme.typography.bodyMedium, color = gxz.inkSoft, softWrap = true)
                if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
                if (state.blocked) {
                    Text("教务资料暂不可用，原资料未被清除。", Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.error, softWrap = true)
                    CtaButton("重新读取教务资料", actions.reload, enabled = !state.loading && !state.saving)
                } else {
                    val updatedAt = if (grades) data.grades?.updatedAt else data.examUpdatedAt
                    val status = if (grades) state.gradeMessage else state.examMessage
                    updatedAt?.let { Text("更新于 ${academicUpdatedAt(it)}", style = MaterialTheme.typography.bodySmall, color = gxz.inkSoft) }
                    status?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = gxz.inkSoft, softWrap = true) }
                    if (state.busy) {
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                        TextButton(onClick = actions.cancel, modifier = Modifier.testTag("academic_cancel_query")) { Text("取消查询") }
                    }
                    state.networkLabel?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = gxz.inkSoft) }
                    if (!state.networkAvailable && !state.loading) Text("当前无可用网络，可查看本机资料。",
                        style = MaterialTheme.typography.bodySmall, color = gxz.inkSoft, softWrap = true)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        CtaButton(if (data.identity == null) "登录并导入考试与成绩" else "刷新考试与成绩", actions.start,
                            Modifier.weight(1f).testTag("academic_refresh"), enabled = canEdit && state.networkAvailable)
                        if (data.identity != null) TextButton(onClick = { confirm("unbind") }, enabled = canEdit,
                            modifier = Modifier.heightIn(min = 48.dp).testTag("academic_unbind")
                                .semantics { contentDescription = "更换或解除教务绑定" }) {
                            Text("解绑", softWrap = false)
                        }
                    }
                }
            }
        }
        if (state.message != null && editing == null && state.login == null) item(key = "message") {
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(gxz.well).padding(16.dp)
                .testTag("academic_message").semantics(mergeDescendants = true) {}, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                LineGlyph(AppGlyph.INFO, Modifier.size(18.dp), gxz.inkSoft)
                Text(state.message, style = MaterialTheme.typography.bodyMedium, color = gxz.inkSoft)
            }
        }
        if (!state.blocked) {
            if (!grades) {
                item(key = "exam_tools") {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        AcademicExamToolbar(data.exams.count { !it.hidden }, canEdit) {
                            actions.beginEdit(); actions.clearMessage(); editing = NewExam
                        }
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            ExamFilter.entries.forEach { option -> FilterChip(selected = option == examFilter,
                                onClick = { examFilter = option }, label = { Text(option.title) },
                                modifier = Modifier.testTag("exam_filter_${option.name}")) }
                        }
                    }
                }
                if (shown.isEmpty()) item(key = "no_exams") { AcademicEmptyState(AppGlyph.EXAM,
                    if (data.exams.none { !it.hidden }) "还没有考试安排" else "暂无匹配的考试",
                    if (data.exams.none { !it.hidden }) "暂无考试，可导入学校安排或手动添加。" else "当前筛选下暂无考试。") }
                items(shown, key = { "exam/${it.id}" }) { exam ->
                    AcademicExamCard(exam, canEdit, onEdit = { actions.beginEdit(); actions.clearMessage(); editing = exam.id },
                        onDelete = { confirm("delete", exam) }, onRestore = { confirm("restore", exam) })
                }
                if (hidden.isNotEmpty()) {
                    item(key = "hidden_toggle") { TextButton(onClick = { showHidden = !showHidden },
                        modifier = Modifier.testTag("academic_hidden_toggle")) { Text("${if (showHidden) "收起" else "查看"}已隐藏考试（${hidden.size}）") } }
                    if (showHidden) items(hidden, key = { "hidden/${it.id}" }) { exam ->
                        AcademicExamCard(exam, canEdit, onEdit = {}, onDelete = {}, onRestore = { confirm("restore", exam) })
                    }
                }
            } else {
                val snapshot = data.grades
                if (snapshot == null) item(key = "no_grades") { AcademicEmptyState(AppGlyph.GRADES,
                    "成绩等待导入", "暂无成绩，请登录教务系统导入。") }
                else {
                    item(key = "credits") { AcademicCreditsCard(snapshot.credits) }
                    val terms = (snapshot.degree + snapshot.elective).map { it.term }.distinct().sortedDescending()
                    item(key = "term_filter") { FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        FilterChip(term == null || term !in terms, { term = null }, { Text("全部学期") }, modifier = Modifier.testTag("grade_term_all"))
                        terms.forEachIndexed { index, value -> FilterChip(term == value, { term = value }, { Text(value.ifBlank { "学期未提供" }) },
                            modifier = Modifier.testTag("grade_term_$index")) }
                    } }
                    val actualTerm = term?.takeIf { it in terms }
                    listOf("学位课" to snapshot.degree, "选修课" to snapshot.elective).forEachIndexed { group, (title, records) ->
                        val filtered = records.filter { actualTerm == null || it.term == actualTerm }
                        item(key = "grade_title_$group") { SectionLabel(title, trailing = "${filtered.size} 门") }
                        if (filtered.isEmpty()) item(key = "grade_empty_$group") { Text("暂无${title}成绩", Modifier.padding(horizontal = 8.dp),
                            style = MaterialTheme.typography.bodyMedium, color = gxz.inkSoft) }
                        items(filtered.size, key = { "grade/$group/$it" }) { index -> AcademicGradeCard(filtered[index], "grade_${group}_$index") }
                    }
                }
            }
        }
    }
}

@Composable
private fun AcademicExamToolbar(count: Int, enabled: Boolean, onAdd: () -> Unit) {
    val heading: @Composable () -> Unit = {
        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text("考试安排", style = MaterialTheme.typography.titleMedium, color = gxz.ink, softWrap = true)
            Text("共 $count 场", style = MaterialTheme.typography.bodySmall, color = gxz.inkSoft, softWrap = true)
        }
    }
    val button: @Composable () -> Unit = {
        OutlinedButton(onClick = onAdd, enabled = enabled, modifier = Modifier.heightIn(min = 48.dp).testTag("academic_add_exam"),
            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp)) {
            LineGlyph(AppGlyph.PLUS, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("添加手动考试")
        }
    }
    BoxWithConstraints(Modifier.fillMaxWidth().padding(start = 8.dp)) {
        if (maxWidth >= 300.dp * LocalDensity.current.fontScale) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) { heading() }; button()
            }
        } else Column(verticalArrangement = Arrangement.spacedBy(10.dp)) { heading(); button() }
    }
}

@Composable
private fun AcademicExamCard(exam: ExamRecord, enabled: Boolean, onEdit: () -> Unit, onDelete: () -> Unit, onRestore: () -> Unit) {
    val school = exam.original
    val modified = exam.modified
    val date = parseAcademicExamTime(exam.fields.start)?.toLocalDate()
    GlassCard(Modifier.testTag("exam_${exam.id}"), spacing = 14.dp) {
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.widthIn(min = 54.dp).clip(RoundedCornerShape(14.dp)).background(gxz.well)
                .padding(horizontal = 10.dp, vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                if (date == null) {
                    LineGlyph(AppGlyph.CALENDAR, Modifier.padding(vertical = 5.dp).size(24.dp), gxz.inkSoft)
                    Text("待定", style = MaterialTheme.typography.labelSmall, color = gxz.inkSoft)
                } else {
                    Text("${date.monthValue}月", style = MaterialTheme.typography.labelSmall, color = gxz.inkSoft)
                    Text(date.dayOfMonth.toString().padStart(2, '0'), style = MaterialTheme.typography.headlineSmall, color = gxz.ink)
                    Text("周${listOf("一", "二", "三", "四", "五", "六", "日")[date.dayOfWeek.value - 1]}",
                        style = MaterialTheme.typography.labelSmall, color = gxz.inkSoft)
                }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                Text(exam.fields.name, Modifier.fillMaxWidth(), style = MaterialTheme.typography.titleMedium, softWrap = true)
                Text(when { exam.hidden -> "已隐藏 · 学校考试"; school == null -> "手动添加"; exam.retained -> "本地保留"
                    modified -> "本地修改"; else -> "学校考试" },
                    Modifier.clip(RoundedCornerShape(6.dp)).background(gxz.well).padding(horizontal = 7.dp, vertical = 3.dp),
                    style = MaterialTheme.typography.labelMedium, color = gxz.inkSoft)
            }
        }
        BoxWithConstraints(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(gxz.well).padding(12.dp)) {
            val wide = maxWidth >= 290.dp * LocalDensity.current.fontScale
            if (wide) Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                AcademicValue("开始", exam.fields.start.ifBlank { "时间未定" }, Modifier.weight(1f))
                AcademicValue("结束", exam.fields.end.ifBlank { "时间未定" }, Modifier.weight(1f))
            } else Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                AcademicValue("开始", exam.fields.start.ifBlank { "时间未定" })
                AcademicValue("结束", exam.fields.end.ifBlank { "时间未定" })
            }
        }
        AcademicDetailRow("地点", exam.fields.location)
        if (school != null) {
            if (school.code.isNotBlank()) AcademicDetailRow("课程编号", school.code)
            if (school.className.isNotBlank()) AcademicDetailRow("教学班", school.className)
        }
        if (exam.fields.note.isNotBlank()) AcademicDetailRow("备注", exam.fields.note)
        if (exam.retained) Text("刷新后未能可靠关联到学校新安排，当前保留本机内容。", Modifier.fillMaxWidth(),
            style = MaterialTheme.typography.bodySmall, color = gxz.inkSoft, softWrap = true)
        HorizontalDivider(color = gxz.hairline)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            if (!exam.hidden) {
                TextButton(onClick = onEdit, enabled = enabled, modifier = Modifier.testTag("edit_exam_${exam.id}")) { Text("编辑") }
                TextButton(onClick = onDelete, enabled = enabled, modifier = Modifier.testTag("delete_exam_${exam.id}")) { Text(if (school == null || exam.retained) "删除" else "隐藏") }
            }
            if (school != null && (modified || exam.retained || exam.hidden)) TextButton(onClick = onRestore, enabled = enabled,
                modifier = Modifier.testTag("restore_exam_${exam.id}")) { Text("恢复上次导入") }
        }
    }
}

@Composable
private fun AcademicGradeCard(grade: GradeRecord, tag: String) {
    GlassCard(Modifier.testTag(tag), spacing = 14.dp) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val inline = maxWidth >= 280.dp * LocalDensity.current.fontScale &&
                (grade.grade.length <= 2 || grade.grade.matches(Regex("[0-9]{1,3}(\\.[0-9]{1,2})?")))
            if (inline) Row(horizontalArrangement = Arrangement.spacedBy(18.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(grade.course.ifBlank { "课程名称未提供" }, Modifier.weight(1f).fillMaxWidth(), style = MaterialTheme.typography.titleMedium, softWrap = true)
                Column(Modifier.widthIn(min = 62.dp, max = 104.dp).clip(RoundedCornerShape(12.dp)).background(gxz.well)
                    .padding(horizontal = 12.dp, vertical = 10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("成绩", style = MaterialTheme.typography.labelSmall, color = gxz.inkSoft)
                    Text(grade.grade.ifBlank { "未获取" }, style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
                }
            } else Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(grade.course.ifBlank { "课程名称未提供" }, Modifier.fillMaxWidth(), style = MaterialTheme.typography.titleMedium, softWrap = true)
                AcademicValue("成绩", grade.grade)
            }
        }
        HorizontalDivider(color = gxz.hairline)
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            if (maxWidth >= 260.dp * LocalDensity.current.fontScale) Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                AcademicValue("学分", grade.credits, Modifier.weight(.65f))
                AcademicValue("学期", grade.term, Modifier.weight(1.35f))
            } else Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                AcademicValue("学分", grade.credits)
                AcademicValue("学期", grade.term)
            }
        }
        if (grade.restricted) Text("成绩受限 · 以学校显示为准", Modifier.fillMaxWidth(),
            style = MaterialTheme.typography.bodyMedium, color = gxz.inkSoft, softWrap = true)
    }
}

@Composable
private fun AcademicValue(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(label, Modifier.fillMaxWidth(), style = MaterialTheme.typography.labelMedium, color = gxz.inkSoft, softWrap = true)
        Text(value.ifBlank { "未获取" }, Modifier.fillMaxWidth(), style = MaterialTheme.typography.bodyMedium, color = gxz.ink, softWrap = true)
    }
}

@Composable
private fun AcademicDetailRow(label: String, value: String) {
    val labelWidth = with(LocalDensity.current) { MaterialTheme.typography.labelMedium.fontSize.toDp() } * 4 + 4.dp
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(label, Modifier.width(labelWidth), style = MaterialTheme.typography.bodySmall, color = gxz.inkSoft, softWrap = true)
        Text(value.ifBlank { "未获取" }, Modifier.weight(1f).fillMaxWidth(), style = MaterialTheme.typography.bodyMedium, color = gxz.ink, softWrap = true)
    }
}

@Composable
private fun AcademicCreditsCard(credits: Map<String, String>) {
    GlassCard(spacing = 16.dp) {
        Text("学校学分信息", Modifier.fillMaxWidth(), style = MaterialTheme.typography.titleMedium, softWrap = true)
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val columns = if (maxWidth >= 250.dp * LocalDensity.current.fontScale) 2 else 1
            val labels = listOf("已修学分", "应修学分", "学位学分", "选修学分")
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (columns == 1) labels.forEach { label ->
                    CreditMetric(label, credits[label].orEmpty(), Modifier.fillMaxWidth())
                } else labels.chunked(2).forEach { row ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        row.forEach { label -> CreditMetric(label, credits[label].orEmpty(), Modifier.weight(1f)) }
                    }
                }
            }
        }
    }
}

@Composable
private fun CreditMetric(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier.clip(RoundedCornerShape(14.dp)).background(gxz.well).padding(14.dp).fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, Modifier.fillMaxWidth(), style = MaterialTheme.typography.labelMedium, color = gxz.inkSoft, softWrap = true)
        Text(value.ifBlank { "未获取" }, Modifier.fillMaxWidth(), style = MaterialTheme.typography.headlineSmall, color = gxz.ink, softWrap = true)
    }
}

@Composable
private fun AcademicEmptyState(glyph: AppGlyph, title: String, description: String) {
    GlassCard(padding = PaddingValues(horizontal = 24.dp, vertical = 30.dp), spacing = 10.dp) {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            GlyphChip(glyph, Modifier.size(44.dp))
            Text(title, Modifier.fillMaxWidth(), style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center, softWrap = true)
            Text(description, Modifier.fillMaxWidth(), style = MaterialTheme.typography.bodyMedium, color = gxz.inkSoft, textAlign = TextAlign.Center, softWrap = true)
        }
    }
}

private fun academicUpdatedAt(value: Long): String = Instant.ofEpochMilli(value).atZone(ZoneId.of("Asia/Shanghai")).format(AcademicTimeFormat)
private fun normalizedExam(fields: ExamFields) = fields.copy(name = fields.name.trim(), start = fields.start.trim(),
    end = fields.end.trim(), location = fields.location.trim(), note = fields.note.trim().replace("\r\n", "\n").replace('\r', '\n'))

@Composable
internal fun AcademicExamEditor(record: ExamRecord?, revision: Long, saving: Boolean, editCompleted: Long, error: String?,
    onDismiss: () -> Unit, onSave: (String?, ExamFields, Long) -> Unit) {
    val original = record?.fields ?: ExamFields("")
    val baselineName = rememberSaveable { original.name }; val baselineStart = rememberSaveable { original.start }
    val baselineEnd = rememberSaveable { original.end }; val baselineLocation = rememberSaveable { original.location }
    val baselineNote = rememberSaveable { original.note }; val expectedRevision = rememberSaveable { revision }
    val expectedId by rememberSaveable { mutableStateOf(record?.id) }
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
    AcademicEditorDialog(if (record == null) "添加手动考试" else "编辑考试", saving,
        valid = fields.name.isNotBlank() && invalidTime == null && (record == null || dirty), onDismiss = ::dismiss,
        onOutsideTap = { if (!currentSaving && latestDraft() == baseline) close() }, onSave = {
            if (revision != expectedRevision) staleSaveAttempted = true else onSave(expectedId, latestDraft(), expectedRevision)
        }) {
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
        val saveError = if (staleSaveAttempted || error == "教务资料已变化，请重新操作" || error == "考试记录已变化，请重新打开编辑")
            "教务资料已变化，请关闭后重新打开考试编辑" else error
        saveError?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("exam_save_error")) }
    }
}

/** Same tap/slop rule as the course editor: an outside swipe never dismisses a draft. */
@Composable
private fun AcademicEditorDialog(title: String, saving: Boolean, valid: Boolean, onDismiss: () -> Unit,
    onOutsideTap: () -> Unit, onSave: () -> Unit, secure: Boolean = false, confirmLabel: String = "保存",
    dialogTag: String = "exam_editor", confirmTag: String = "save_exam", cancelTag: String = "cancel_exam",
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
                        if (!compact) Text(title, Modifier.padding(20.dp), style = MaterialTheme.typography.headlineSmall)
                        Column(Modifier.weight(1f, false).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 8.dp)
                            .testTag("exam_editor_body"), verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
                        HorizontalDivider(color = gxz.hairline)
                        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = if (compact) 2.dp else 12.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            if (compact) Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                            TextButton(onClick = onDismiss, enabled = !saving, modifier = Modifier.heightIn(min = 48.dp).testTag(cancelTag)) { Text("取消") }
                            Button(onClick = onSave, enabled = valid && !saving, modifier = Modifier.heightIn(min = 48.dp).testTag(confirmTag)) {
                                Text(if (saving) "保存中" else confirmLabel)
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
    ReportDialogVisibility(page != null, onDialogVisibilityChanged)
    if (page != null) AcademicLoginForm(page, state.busy, state.message, controller::cancel, controller::refreshCaptcha, controller::submit)
}

@Composable
internal fun AcademicLoginForm(page: AcademicLoginPage, busy: Boolean, message: String?, onCancel: () -> Unit,
    onRefreshCaptcha: (String) -> Unit, onSubmit: (String, String, String, String) -> Unit) {
    // Credentials are intentionally memory-only; recreation asks the user to type them again.
    var username by remember(page.token) { mutableStateOf("") }
    var password by remember(page.token) { mutableStateOf("") }
    var captcha by remember(page.token, page.captcha) { mutableStateOf("") }
    var submitted by remember(page.token, page.captcha) { mutableStateOf(false) }
    val focus = LocalFocusManager.current
    val bitmap = remember(page.token, page.captcha) { BitmapFactory.decodeByteArray(page.captcha, 0, page.captcha.size)?.asImageBitmap() }
    val canSubmit = !busy && !submitted && username.isNotBlank() && password.isNotEmpty() && captcha.isNotBlank() && bitmap != null
    fun submit() { if (canSubmit) { submitted = true; focus.clearFocus(); onSubmit(page.token, username.trim(), password, captcha.trim()) } }
    LaunchedEffect(busy) { if (!busy) submitted = false }
    AcademicEditorDialog("研究生教务登录", saving = false, valid = canSubmit, onDismiss = onCancel,
        onOutsideTap = {}, onSave = ::submit, secure = true, confirmLabel = if (busy) "正在查询" else "登录并导入",
        dialogTag = "academic_login", confirmTag = "academic_submit", cancelTag = "academic_cancel_login") {
            Text("使用研究生教务系统账号。仅查询考试与成绩，不保存密码。", style = MaterialTheme.typography.bodySmall, color = gxz.inkSoft)
            OutlinedTextField(username, { username = it.take(128) }, Modifier.fillMaxWidth().testTag("academic_username"), enabled = !busy,
                singleLine = true, label = { Text("学号 / 账号") }, keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next))
            OutlinedTextField(password, { password = it.take(1024) }, Modifier.fillMaxWidth().testTag("academic_password"), enabled = !busy,
                singleLine = true, label = { Text("密码") }, visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next, keyboardType = KeyboardType.Password, autoCorrectEnabled = false))
            if (bitmap != null) Box(Modifier.fillMaxWidth().height(72.dp), contentAlignment = Alignment.Center) {
                Image(bitmap, contentDescription = "教务登录验证码", modifier = Modifier.size(160.dp, 64.dp))
            }
            else Text("验证码图片暂时无法显示，请刷新。", color = MaterialTheme.colorScheme.error)
            TextButton(onClick = { if (!busy) { captcha = ""; submitted = false; onRefreshCaptcha(page.token) } }, enabled = !busy,
                modifier = Modifier.testTag("academic_refresh_captcha")) { Text("更换验证码") }
            OutlinedTextField(captcha, { captcha = it.take(16) }, Modifier.fillMaxWidth().testTag("academic_captcha"), enabled = !busy,
                singleLine = true, label = { Text("验证码") }, keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { submit() }))
            message?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error) }
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
    }
}
