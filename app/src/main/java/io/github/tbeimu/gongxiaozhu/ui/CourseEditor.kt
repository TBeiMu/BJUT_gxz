package io.github.tbeimu.gongxiaozhu.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitLongPressOrCancellation
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
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
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.isOutOfBounds
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInParent
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.github.tbeimu.gongxiaozhu.schedule.*
import java.time.LocalDate

/** Only normalized, unsaved course fields can be restored; school login credentials never enter this editor. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CourseEditor(context: CourseEditContext, saving: Boolean, error: String?, onDismiss: () -> Unit,
    onSave: (CourseDraft) -> Unit) {
    val course = context.course
    var name by rememberSaveable(context.token) { mutableStateOf(course?.name.orEmpty()) }
    var location by rememberSaveable(context.token) { mutableStateOf(course?.location.orEmpty()) }
    var teacher by rememberSaveable(context.token) { mutableStateOf(course?.teacher.orEmpty()) }
    var nameTouched by rememberSaveable(context.token) { mutableStateOf(false) }
    var weekdays by rememberSaveable(context.token) { mutableStateOf(listOf(context.defaultWeekday)) }
    var selectedWeeks by rememberSaveable(context.token, stateSaver = CourseWeekSelection.Saver) {
        mutableStateOf(CourseWeekSelection.initial(context))
    }
    var selectedLessons by rememberSaveable(context.token, stateSaver = CourseLessonSelection.Saver) {
        mutableStateOf(CourseLessonSelection(course?.lessons ?: listOf(1, 2)))
    }
    var monday by rememberSaveable(context.token) { mutableStateOf(context.firstMonday) }
    var count by rememberSaveable(context.token) { mutableStateOf(context.weekCount.toString()) }
    var countFocused by rememberSaveable(context.token) { mutableStateOf(false) }
    var picker by rememberSaveable(context.token) { mutableStateOf(false) }
    var discardRequested by rememberSaveable(context.token) { mutableStateOf(false) }
    val newTerm = context.creatingTerm
    fun weekCount() = count.toIntOrNull()?.takeIf { it in 1..60 } ?: 0
    val weeks = weekCount()
    val originalWeeks = context.originalArrangements.map { it.week }.distinct()
    val preservesLateLessons = course?.lessons?.any { it !in 1..12 } == true && selectedLessons.lessons == course.lessons
    val validLessons = selectedLessons.lessons.isNotEmpty() &&
        (selectedLessons.lessons.all { it in 1..12 } || preservesLateLessons)
    val valid = name.isNotBlank() && weeks > 0 && weekdays.isNotEmpty() &&
        selectedWeeks.targetWeeks.isNotEmpty() && selectedWeeks.targetWeeks.all {
            it in 1..weeks || it in originalWeeks
        } &&
        (!preservesLateLessons || (selectedWeeks.targetWeeks.all { it in selectedWeeks.sourceWeeks } &&
            weekdays == listOf(context.defaultWeekday))) &&
        validLessons &&
        runCatching { LocalDate.parse(monday).dayOfWeek.value == 1 }.getOrDefault(false)
    fun draft() = CourseDraft(name, location, teacher, weekdays,
        if (course != null) selectedWeeks.sourceWeeks else emptyList(), selectedWeeks.targetWeeks,
        selectedLessons.lessons, monday, weekCount())
    fun confirmWeekCount() { selectedWeeks = selectedWeeks.fitTerm(count.toIntOrNull() ?: 0, originalWeeks) }

    if (context.deleteOnly) {
        CourseDeleteDialog(context, selectedWeeks.sourceWeeks, saving, error, onDismiss,
            { selectedWeeks = selectedWeeks.copy(sourceWeeks = it.distinct().sorted()) }, { onSave(draft()) })
        return
    }

    fun requestDismiss() {
        if (saving) return
        if (courseDraftHasChanges(context, draft())) discardRequested = true else onDismiss()
    }
    fun outsideTap() {
        if (!saving && !courseDraftHasChanges(context, draft())) onDismiss()
    }
    if (discardRequested) {
        DiscardChangesDialog(onContinue = { discardRequested = false }, onDiscard = { if (!saving) onDismiss() })
        return
    }

    if (picker) {
        val dateState = rememberDatePickerState(initialSelectedDateMillis = LocalDate.parse(monday).atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli())
        DatePickerDialog(onDismissRequest = { picker = false }, confirmButton = {
            TextButton(onClick = {
                dateState.selectedDateMillis?.let { monday = java.time.Instant.ofEpochMilli(it).atZone(java.time.ZoneOffset.UTC)
                    .toLocalDate().with(java.time.DayOfWeek.MONDAY).toString() }; picker = false
            }) { Text("确定") }
        }, dismissButton = { TextButton(onClick = { picker = false }) { Text("取消") } }) { DatePicker(dateState) }
        return
    }

    CourseEditorDialog(if (course == null) "添加自定义课程" else "编辑课程", saving, valid,
        ::requestDismiss, ::outsideTap, { onSave(draft()) }) {
        val focus = LocalFocusManager.current
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            CourseField(name, {
                name = it.take(300); nameTouched = true
            }, "课程名", "course_name", !saving, isError = nameTouched && name.isBlank(),
                keyboardActions = KeyboardActions(onNext = { focus.moveFocus(FocusDirection.Next) }))
            if (nameTouched && name.isBlank()) EditorError("请填写课程名")
            CourseField(location, { location = it.take(500) }, "教室（可选）", "course_location", !saving,
                keyboardActions = KeyboardActions(onNext = { focus.moveFocus(FocusDirection.Next) }))
            CourseField(teacher, { teacher = it.take(500) }, "教师（可选）", "course_teacher", !saving,
                imeAction = ImeAction.Done, keyboardActions = KeyboardActions(onDone = { focus.clearFocus() }))
        }
        if (newTerm) {
            EditorSection("学期设置") {
                OutlinedButton(onClick = { focus.clearFocus(); picker = true }, enabled = !saving,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("term_monday"),
                    shape = EditorOptionShape, border = androidx.compose.foundation.BorderStroke(1.dp, gxz.hairline),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = gxz.ink)) {
                    Text("第一周周一 · $monday", style = MaterialTheme.typography.bodyMedium)
                }
                CourseField(count, {
                    count = it.filter(Char::isDigit).take(2)
                }, "学期周数（1–60）", "term_weeks", !saving, isError = weeks == 0,
                    keyboardType = KeyboardType.Number, imeAction = ImeAction.Done,
                    keyboardActions = KeyboardActions(onDone = { confirmWeekCount(); focus.clearFocus() }),
                    onFocusChange = {
                        if (countFocused && !it) confirmWeekCount()
                        countFocused = it
                    })
                if (weeks == 0) EditorError("学期周数须为 1–60")
            }
        } else {
            Text("${context.firstMonday} 起 · ${context.weekCount} 周", style = MaterialTheme.typography.bodySmall, color = gxz.inkSoft)
        }
        EditorSection("星期", if (course?.source == CourseSource.SCHOOL) "单选" else "可多选") {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("周一", "周二", "周三", "周四", "周五", "周六", "周日").forEachIndexed { index, label ->
                    val day = index + 1
                    EditorChoice(label, day in weekdays, !saving, "course_day_$day",
                        role = if (course?.source == CourseSource.SCHOOL) Role.RadioButton else Role.Checkbox) {
                        focus.clearFocus()
                        weekdays = if (course?.source == CourseSource.SCHOOL) listOf(day)
                        else if (day in weekdays) weekdays - day else (weekdays + day).sorted()
                    }
                }
            }
            if (weekdays.isEmpty()) EditorError("请至少选择一个星期")
        }
        EditorSection("节次") {
            EditorSelectionGrid((1..12).toList(), selectedLessons.lessons, !saving, "course_lesson", { "第 $it 节" },
                onClick = { focus.clearFocus(); selectedLessons = selectedLessons.select(it) },
                onDragSelection = { focus.clearFocus(); selectedLessons = CourseLessonSelection(it, changed = true) })
            Text(courseLessonSummary(selectedLessons.lessons), Modifier.testTag("course_lesson_summary"),
                style = MaterialTheme.typography.bodyMedium, color = gxz.inkSoft)
            courseLessonClock(selectedLessons.lessons)?.let {
                Text(it, Modifier.testTag("course_lesson_clock"), style = MaterialTheme.typography.bodyMedium, color = gxz.inkSoft)
            }
            selectedLessons.anchor?.let { anchor ->
                Text("已选第 $anchor 节，再点结束节次", Modifier.testTag("course_lesson_hint"),
                    style = MaterialTheme.typography.bodySmall, color = gxz.inkSoft)
            }
            if (selectedLessons.lessons.isEmpty()) EditorError("请至少选择一个节次")
            else if (!validLessons) EditorError("修改节次请重新选择第 1–12 节")
        }
        if (course != null) {
            val original = context.originalArrangements.ifEmpty {
                listOf(CourseOriginalArrangement(context.defaultWeek, context.defaultWeekday, course.lessons))
            }
            Text(courseOriginalScheduleSummary(original), Modifier.testTag("course_original_schedule"),
                style = MaterialTheme.typography.bodyMedium, color = gxz.inkSoft)
        }
        EditorSection("上课周次", "${selectedWeeks.targetWeeks.size} 周") {
            if (weeks == 0) Text("请先设置有效学期周数", style = MaterialTheme.typography.bodySmall, color = gxz.inkSoft)
            else {
                val availableWeeks = ((1..weeks).toList() + originalWeeks).distinct().sorted()
                EditorWeekChoices(availableWeeks, selectedWeeks.targetWeeks, !saving, "target_week",
                    onChange = { focus.clearFocus(); selectedWeeks = selectedWeeks.withTargets(it) })
                if (selectedWeeks.targetWeeks.isEmpty()) EditorError("请至少选择一个上课周次")
            }
        }
        if (course?.source == CourseSource.SCHOOL) Text("完整刷新后以学校新课表为准",
            style = MaterialTheme.typography.bodySmall, color = gxz.inkSoft)
        error?.let { EditorError(it, Modifier.testTag("course_save_error")) }
    }
}

private val EditorOptionShape = RoundedCornerShape(12.dp)

/** One scroll owner; the header and actions always fit the space above the IME and system bars. */
@Composable
private fun CourseEditorDialog(title: String, saving: Boolean, valid: Boolean, onDismiss: () -> Unit,
    onOutsideTap: () -> Unit, onSave: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    val density = LocalDensity.current
    val currentOutsideTap by rememberUpdatedState(onOutsideTap)
    var surfaceBounds by remember { mutableStateOf<Rect?>(null) }
    var overlayOrigin by remember { mutableStateOf(Offset.Zero) }
    Dialog(onDismissRequest = { if (!saving) onDismiss() },
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false,
            dismissOnBackPress = !saving, dismissOnClickOutside = false)) {
        CompositionLocalProvider(LocalDensity provides density) {
            Box(Modifier.fillMaxSize().testTag("course_editor_overlay")
                .onGloballyPositioned { overlayOrigin = it.positionInRoot() }
                .pointerInput(saving) {
                    if (saving) return@pointerInput
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                        if (surfaceBounds?.contains(down.position + overlayOrigin) != false) return@awaitEachGesture
                        down.consume()
                        while (true) {
                            val change = awaitPointerEvent(PointerEventPass.Initial).changes.singleOrNull() ?: break
                            if (change.id != down.id || change.isConsumed || change.isOutOfBounds(size, extendedTouchPadding) ||
                                (change.position - down.position).getDistance() > viewConfiguration.touchSlop) break
                            if (!change.pressed) {
                                if (surfaceBounds?.contains(change.position + overlayOrigin) == false) {
                                    change.consume()
                                    currentOutsideTap()
                                }
                                break
                            }
                        }
                    }
                }) {
                BoxWithConstraints(Modifier.fillMaxSize().safeDrawingPadding().imePadding(),
                    contentAlignment = Alignment.Center) {
                    val typography = MaterialTheme.typography
                    val compact = maxHeight < with(density) {
                        typography.headlineSmall.lineHeight.toDp() + typography.labelLarge.lineHeight.toDp()
                    } + 208.dp
                    val margin = if (compact) 4.dp else 20.dp
                    val dialogHeight = (maxHeight - margin * 2).coerceAtLeast(0.dp)
                    // Landscape IMEs can leave little more than 100dp: share the fixed action row with the title.
                    Box(Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = margin), contentAlignment = Alignment.Center) {
                        Surface(Modifier.widthIn(max = 560.dp).fillMaxWidth().heightIn(max = dialogHeight)
                            .testTag("course_editor").onGloballyPositioned { surfaceBounds = it.boundsInRoot() },
                            shape = RoundedCornerShape(24.dp), color = gxz.glassStrong) {
                            Column {
                                if (!compact) {
                                    Text(title, Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 20.dp).testTag("course_editor_title"),
                                        style = typography.headlineSmall, color = gxz.ink)
                                    HorizontalDivider(color = gxz.hairline)
                                }
                                Column(Modifier.weight(1f, fill = false).fillMaxWidth().verticalScroll(rememberScrollState())
                                    .padding(horizontal = if (compact) 16.dp else 20.dp, vertical = if (compact) 4.dp else 20.dp)
                                    .testTag("course_editor_body"), verticalArrangement = Arrangement.spacedBy(24.dp), content = content)
                                HorizontalDivider(color = gxz.hairline)
                                Row(Modifier.fillMaxWidth().padding(horizontal = if (compact) 12.dp else 20.dp,
                                    vertical = if (compact) 2.dp else 14.dp).testTag("course_editor_actions"),
                                    horizontalArrangement = Arrangement.spacedBy(if (compact) 8.dp else 12.dp),
                                    verticalAlignment = Alignment.CenterVertically) {
                                    if (compact) Text(title, Modifier.weight(1f).testTag("course_editor_title"),
                                        style = typography.titleMedium, color = gxz.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    TextButton(onClick = onDismiss, enabled = !saving,
                                        modifier = (if (compact) Modifier.widthIn(min = 48.dp).heightIn(min = 48.dp)
                                            else Modifier.weight(1f).heightIn(min = 50.dp)).testTag("cancel_course"),
                                        contentPadding = if (compact) PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                                            else ButtonDefaults.TextButtonContentPadding,
                                        colors = ButtonDefaults.textButtonColors(contentColor = gxz.inkSoft)) { Text("取消") }
                                    Button(onClick = onSave, enabled = valid && !saving,
                                        modifier = (if (compact) Modifier.widthIn(min = 72.dp).heightIn(min = 48.dp)
                                            else Modifier.weight(1.5f).heightIn(min = 50.dp)).testTag("save_course"), shape = EditorOptionShape,
                                        contentPadding = if (compact) PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                                            else ButtonDefaults.ContentPadding,
                                        colors = ButtonDefaults.buttonColors(containerColor = gxz.ink, contentColor = gxz.onInk)) {
                                        Text(if (saving) "保存中" else "保存", style = typography.labelLarge)
                                    }
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
private fun CourseField(value: String, onChange: (String) -> Unit, label: String, tag: String, enabled: Boolean,
    isError: Boolean = false, keyboardType: KeyboardType = KeyboardType.Text, imeAction: ImeAction = ImeAction.Next,
    keyboardActions: KeyboardActions = KeyboardActions.Default, onFocusChange: (Boolean) -> Unit = {}) {
    TextField(value, onChange, Modifier.fillMaxWidth().testTag(tag).onFocusChanged { onFocusChange(it.isFocused) },
        enabled = enabled, singleLine = true,
        label = { Text(label) }, shape = EditorOptionShape, isError = isError, textStyle = MaterialTheme.typography.bodyLarge,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType, imeAction = imeAction), keyboardActions = keyboardActions,
        colors = TextFieldDefaults.colors(focusedContainerColor = gxz.well, unfocusedContainerColor = gxz.well,
            disabledContainerColor = gxz.well, errorContainerColor = gxz.well,
            focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent,
            disabledIndicatorColor = Color.Transparent, errorIndicatorColor = Color.Transparent,
            focusedLabelColor = gxz.inkSoft, unfocusedLabelColor = gxz.inkSoft))
}

@Composable
private fun EditorSection(title: String, detail: String? = null, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = gxz.ink)
            detail?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = gxz.inkSoft) }
        }
        content()
    }
}

@Composable
private fun EditorChoice(label: String, selected: Boolean, enabled: Boolean, tag: String,
    modifier: Modifier = Modifier, role: Role = Role.Checkbox, description: String? = null, onClick: () -> Unit) {
    val targetColor = if (selected) gxz.ink else gxz.well
    val animatedColor by animateColorAsState(targetColor, tween(if (LocalReducedMotion.current) 0 else Motion.SHORT), label = "课程选项")
    Box(modifier.widthIn(min = 48.dp).heightIn(min = 48.dp).testTag(tag).clip(EditorOptionShape)
        .background(animatedColor.copy(alpha = if (enabled) animatedColor.alpha else animatedColor.alpha * .45f))
        .selectable(selected, enabled = enabled, role = role, onClick = onClick)
        .semantics {
            description?.let { contentDescription = it }
            if (role == Role.Checkbox) toggleableState = if (selected) ToggleableState.On else ToggleableState.Off
        }
        .padding(horizontal = 12.dp, vertical = 6.dp), contentAlignment = Alignment.Center) {
        Text(label, style = MaterialTheme.typography.labelLarge,
            color = (if (selected) gxz.onInk else gxz.inkSoft).copy(alpha = if (enabled) 1f else .45f))
    }
}

/** Long-press an unselected option to add a range; ordinary swipes always allow scrolling. */
@Composable
private fun EditorSelectionGrid(available: List<Int>, selected: List<Int>, enabled: Boolean, tag: String,
    description: (Int) -> String, onClick: (Int) -> Unit, onDragSelection: (List<Int>) -> Unit) {
    val bounds = remember(available) { mutableMapOf<Int, Rect>() }
    val currentSelected by rememberUpdatedState(selected)
    val currentOnDragSelection by rememberUpdatedState(onDragSelection)
    FlowRow(Modifier.fillMaxWidth().testTag(tag + "_grid").pointerInput(available, enabled) {
        if (!enabled) return@pointerInput
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            val start = bounds.entries.firstOrNull { it.value.contains(down.position) }?.key ?: return@awaitEachGesture
            if (start in currentSelected) return@awaitEachGesture
            val held = awaitLongPressOrCancellation(down.id) ?: return@awaitEachGesture
            if (held.id != down.id || currentEvent.changes.count { it.pressed } != 1) return@awaitEachGesture
            val selection = CourseDragSelection(currentSelected, available, start)
            currentOnDragSelection(selection.through(start))
            var previous = start
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                if (!change.pressed) {
                    change.consume()
                    break
                }
                if (change.isConsumed || event.changes.count { it.pressed } != 1) break
                change.consume()
                val value = bounds.entries.firstOrNull { it.value.contains(change.position) }?.key ?: previous
                if (value != previous) {
                    currentOnDragSelection(selection.through(value))
                    previous = value
                }
            }
        }
    }, horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        available.forEach { value ->
            EditorChoice("$value", value in selected, enabled, "${tag}_$value", description = description(value),
                modifier = Modifier.onGloballyPositioned { bounds[value] = it.boundsInParent() }) { onClick(value) }
        }
    }
}

@Composable
private fun EditorWeekChoices(available: List<Int>, selected: List<Int>, enabled: Boolean, tag: String,
    onChange: (List<Int>) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            CourseWeekPreset.entries.forEach { preset ->
                val choice = preset.select(available)
                EditorChoice(preset.label, (choice.isNotEmpty() || preset == CourseWeekPreset.CLEAR) && selected.toSet() == choice.toSet(),
                    enabled && (choice.isNotEmpty() || preset == CourseWeekPreset.CLEAR), tag + "_" + preset.name.lowercase(),
                    role = Role.Button) { onChange(choice) }
            }
        }
        EditorSelectionGrid(available, selected, enabled, tag, { "第 $it 周" },
            onClick = { week -> onChange(if (week in selected) selected - week else (selected + week).sorted()) },
            onDragSelection = onChange)
        Text(courseWeekSummary(selected), Modifier.testTag(tag + "_summary"), style = MaterialTheme.typography.bodySmall, color = gxz.inkSoft)
    }
}

@Composable
private fun EditorError(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
}

/** Deletion retains its original confirmation and single-occurrence defaults. */
@Composable
private fun CourseDeleteDialog(context: CourseEditContext, sources: List<Int>, saving: Boolean, error: String?,
    onDismiss: () -> Unit, onSources: (List<Int>) -> Unit, onSave: () -> Unit) {
    val course = context.course
    val arrangements = courseDeletionArrangements(context, sources)
    AlertDialog(onDismissRequest = { if (!saving) onDismiss() }, modifier = Modifier.testTag("course_editor"),
        properties = DialogProperties(dismissOnBackPress = !saving, dismissOnClickOutside = !saving),
        title = { Text("删除课程") }, text = {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("删除“${course?.name}”的以下安排？")
                Text(courseDeletionSummary(arrangements), Modifier.testTag("course_delete_summary"),
                    style = MaterialTheme.typography.bodyMedium, color = gxz.inkSoft)
                Text("删除周次", style = MaterialTheme.typography.titleSmall)
                DeleteWeekChoices(context.availableWeeks, sources, !saving, "source_week", onSources)
                if (course?.source == CourseSource.SCHOOL) Text("完整刷新后以学校新课表为准",
                    style = MaterialTheme.typography.bodySmall, color = gxz.inkSoft)
                error?.let { EditorError(it) }
            }
        }, confirmButton = {
            TextButton(onClick = onSave, enabled = arrangements.isNotEmpty() && !saving, modifier = Modifier.testTag("save_course")) {
                Text(if (saving) "正在保存" else "删除这 ${arrangements.size} 次安排")
            }
        }, dismissButton = { TextButton(onClick = onDismiss, enabled = !saving) { Text("取消") } })
}

@Composable
private fun DeleteWeekChoices(available: List<Int>, selected: List<Int>, enabled: Boolean, tag: String, onChange: (List<Int>) -> Unit) {
    Column {
        TextButton(onClick = { onChange(if (selected.toSet() == available.toSet()) emptyList() else available) }, enabled = enabled,
            modifier = Modifier.testTag("${tag}_all")) { Text(if (selected.toSet() == available.toSet()) "取消全选" else "全选周次") }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) { available.forEach { week ->
            FilterChip(week in selected, { onChange(if (week in selected) selected - week else (selected + week).sorted()) },
                { Text("$week") }, enabled = enabled, modifier = Modifier.testTag("${tag}_$week"))
        } }
    }
}

@Composable
internal fun ScheduleAddButton(expanded: Boolean, enabled: Boolean, onExpand: (Boolean) -> Unit, onImport: () -> Unit,
    onAdd: () -> Unit) {
    Box {
        PlainIconButton({ onExpand(true) }, Modifier.testTag("import_schedule").semantics { contentDescription = "添加课表" }, enabled) {
            LineGlyph(AppGlyph.PLUS, Modifier.size(20.dp))
        }
        DropdownMenu(expanded, { onExpand(false) }, modifier = Modifier.widthIn(max = 320.dp).testTag("schedule_add_menu"), shape = TileShape,
            containerColor = androidx.compose.material3.MaterialTheme.colorScheme.surface) {
            DropdownMenuItem(text = { Column { Text("导入本科生课表"); Text("暂未开放", style = MaterialTheme.typography.bodySmall) } },
                onClick = {}, enabled = false, leadingIcon = { LineGlyph(AppGlyph.DOWNLOAD, Modifier.size(20.dp)) }, modifier = Modifier.testTag("import_undergraduate"))
            DropdownMenuItem(text = { Text("导入研究生课表") }, onClick = { onExpand(false); onImport() },
                enabled = enabled, leadingIcon = { LineGlyph(AppGlyph.DOWNLOAD, Modifier.size(20.dp)) }, modifier = Modifier.testTag("import_graduate"))
            HorizontalDivider(Modifier.padding(horizontal = 12.dp))
            DropdownMenuItem(text = { Text("添加自定义课程") }, onClick = { onExpand(false); onAdd() }, enabled = enabled,
                leadingIcon = { LineGlyph(AppGlyph.PLUS, Modifier.size(20.dp), gxz.accent) }, modifier = Modifier.testTag("add_custom_course"))
        }
    }
}
