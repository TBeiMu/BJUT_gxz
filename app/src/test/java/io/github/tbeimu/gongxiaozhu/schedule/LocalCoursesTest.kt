package io.github.tbeimu.gongxiaozhu.schedule

import io.github.tbeimu.gongxiaozhu.data.AesGcmVaultCipher
import java.time.LocalDate
import org.junit.Assert.*
import org.junit.Test

class LocalCoursesTest {
    private fun draft(context: CourseEditContext, name: String = "自定义实验", source: List<Int> = listOf(context.defaultWeek),
        target: List<Int> = listOf(1, 2), day: Int = 2, lessons: List<Int> = listOf(3, 4)) =
        CourseDraft(name, "", "", listOf(day), source, target, lessons, context.firstMonday, context.weekCount)
    private fun add(repo: ScheduleRepository): ScheduleData {
        val context = repo.snapshot().courseEditor()
        return repo.edit(context, draft(context))
    }
    private fun addSixtyWeekCourse(repo: ScheduleRepository, weekdays: List<Int> = (1..7).toList()): ScheduleData {
        val context = repo.snapshot().courseEditor()
        return repo.edit(context, draft(context, target = (1..60).toList(), lessons = listOf(1, 2))
            .copy(weekdays = weekdays, firstMonday = "2026-11-02", weekCount = 60))
    }
    private fun imported(repo: ScheduleRepository, term: ScheduleTerm = demoTerm): ScheduleData {
        repo.begin(demoIdentity, term)
        repo.append(demoWeek(1)); repo.append(demoWeek(2))
        return repo.finish(1)
    }
    @Test fun standaloneCalendarIsEncryptedAndRestoresWithoutSchoolIdentity() {
        val store = ScheduleMemoryStore(); val keys = ScheduleMemoryKeys(); val repo = repository(store, keys)
        val data = add(repo)
        assertNull(data.identity); assertNull(data.complete)
        assertFalse(store.bytes!!.decodeToString().contains("自定义实验"))
        assertEquals(data, ScheduleRepository(store, AesGcmVaultCipher(keys)).load())
        val display = data.display()!!
        assertFalse(display.school); assertEquals(20, display.term.weekCount)
        assertEquals(2, display.weeks.sumOf { it.courses.size })
        assertEquals(CourseSource.CUSTOM, display.weeks[0].courses[0].source)
    }
    @Test fun firstImportAdoptsWeeksAndKeepsOutOfRangeOccurrences() {
        val repo = repository(); val context = repo.snapshot().courseEditor()
        repo.edit(context, draft(context, target = listOf(1, 20)))
        val oldId = repo.snapshot().localTerms.single().courses.single().id
        val data = imported(repo)
        assertEquals(demoWeek(1).dates.first(), data.localTerms.single().firstMonday)
        assertEquals(listOf(1, 20), data.localTerms.single().courses.single().occurrences.map { it.week })
        assertEquals(oldId, data.localTerms.single().courses.single().id)
        assertEquals(1, data.display()!!.weeks.sumOf { w -> w.courses.count { it.source == CourseSource.CUSTOM } })
    }
    @Test fun newSchoolSemesterNeverMovesArchivedCustomCourses() {
        val repo = repository(); add(repo); imported(repo)
        val old = repo.snapshot().localTerms.single()
        val result = imported(repo, demoTerm.copy(term = "2"))
        assertEquals(old, result.localTerms.single())
        assertTrue(result.display()!!.weeks.none { w -> w.courses.any { it.source == CourseSource.CUSTOM } })
        assertTrue(repo.selectLocalTerm(old.id).display()!!.weeks.any { w -> w.courses.any { it.source == CourseSource.CUSTOM } })
    }
    @Test fun failedRefreshAndCancellationPreserveSchoolEditsAndCustomCourses() {
        val repo = repository(); add(repo); imported(repo)
        val context = repo.snapshot().courseEditor(repo.snapshot().complete!!.weeks[0].courses[0])
        val edited = repo.edit(context, draft(context, "调课实验", target = listOf(2), day = 5)).complete
        repo.begin(demoIdentity, demoTerm); repo.append(demoWeek(1))
        assertThrows(ScheduleException::class.java) { repo.finish(2) }
        assertEquals(edited, repo.snapshot().complete)
        val cancelled = repo.discardPending()
        assertEquals(edited, cancelled.complete); assertEquals(1, cancelled.localTerms.single().courses.size)
        val refreshed = imported(repo)
        assertEquals("虚构课程", refreshed.complete!!.weeks[0].courses[0].name)
        assertEquals(1, refreshed.localTerms.single().courses.size)
    }
    @Test fun schoolEditingOnlyReplacesClickedArrangementAndPreservesTargetWeekOriginal() {
        val repo = repository(); imported(repo)
        val c = repo.snapshot().complete!!.weeks[0].courses[0]
        val context = repo.snapshot().courseEditor(c)
        assertEquals(listOf(1, 2), context.availableWeeks)
        val result = repo.edit(context, draft(context, target = listOf(2), day = 3)).complete!!
        assertTrue(result.weeks[0].courses.isEmpty())
        assertEquals(2, result.weeks[1].courses.size)
        assertTrue(result.weeks[1].courses.any { it.date == demoWeek(2).dates[0] && it.lessons == c.lessons })
        assertTrue(result.weeks[1].courses.any { it.date == LocalDate.parse(demoWeek(2).dates[0]).plusDays(2).toString() })
    }
    @Test fun schoolEditingDoesNotDuplicateAnExistingExactTargetOccurrence() {
        val repo = repository(); repo.begin(demoIdentity, demoTerm)
        val source = demoWeek(1).courses.single()
        val targetWeek = demoWeek(2)
        repo.append(demoWeek(1).copy(courses = listOf(source)))
        repo.append(targetWeek.copy(courses = listOf(source.copy(date = targetWeek.dates[2]))))
        val data = repo.finish(1)
        val context = data.courseEditor(source)
        val result = repo.edit(context, draft(context, source.name, target = listOf(2), day = 3, lessons = source.lessons)
            .copy(location = source.location, teacher = source.teacher)).complete!!
        assertTrue(result.weeks[0].courses.isEmpty())
        assertEquals(1, result.weeks[1].courses.count { it.id == source.id && it.date == targetWeek.dates[2] && it.lessons == source.lessons })
    }
    @Test fun schoolMoveWithChangedMetadataPreservesTheEditedTargetAndExistingArrangement() {
        for (field in listOf("name", "location", "teacher")) {
            val store = ScheduleMemoryStore(); val keys = ScheduleMemoryKeys(); val repo = repository(store, keys)
            repo.begin(demoIdentity, demoTerm)
            val source = demoWeek(1).courses.single()
            val targetWeek = demoWeek(2)
            val existing = source.copy(date = targetWeek.dates[2])
            repo.append(demoWeek(1)); repo.append(targetWeek.copy(courses = listOf(existing)))
            val before = repo.finish(1)
            val context = before.courseEditor(source)
            val moved = draft(context, source.name, source = context.availableWeeks, target = listOf(2), day = 3, lessons = source.lessons)
                .copy(location = source.location, teacher = source.teacher)
            val changed = when (field) {
                "name" -> moved.copy(name = "调整后的课程")
                "location" -> moved.copy(location = "调整后的教室")
                else -> moved.copy(teacher = "调整后的教师")
            }
            val edited = repo.editWithUndo(context, changed)
            val result = edited.data
            assertTrue(result.complete!!.weeks[0].courses.isEmpty())
            val targets = result.complete.weeks[1].courses
            assertEquals("changed $field must not disappear", 2, targets.size)
            assertTrue(targets.contains(existing))
            assertTrue(targets.any { it.name == changed.name && it.location == changed.location && it.teacher == changed.teacher })
            assertEquals(result, ScheduleRepository(store, AesGcmVaultCipher(keys)).load())
            assertEquals(before, repo.undo(edited.undo!!.token))
            assertEquals(before, ScheduleRepository(store, AesGcmVaultCipher(keys)).load())
        }
    }
    @Test fun schoolMoveKeepsAnotherSeriesWithTheSameIdAtTheTarget() {
        val repo = repository(); repo.begin(demoIdentity, demoTerm)
        val source = demoWeek(1).courses.single()
        val targetWeek = demoWeek(2)
        val otherSeries = source.copy(name = "同编号的另一门课程", date = targetWeek.dates[2])
        repo.append(demoWeek(1)); repo.append(targetWeek.copy(courses = listOf(otherSeries)))
        val context = repo.finish(1).courseEditor(source)
        val result = repo.edit(context, draft(context, source.name, source = context.availableWeeks,
            target = listOf(2), day = 3, lessons = source.lessons).copy(location = source.location, teacher = source.teacher))
        assertEquals(setOf(source.name, otherSeries.name), result.complete!!.weeks[1].courses.map { it.name }.toSet())
        assertTrue(result.complete.weeks[1].courses.contains(otherSeries))
    }
    @Test fun editorContextListsEveryOriginalWeekAndLessonPattern() {
        val repo = repository(); repo.begin(demoIdentity, demoTerm)
        val mondayCourse = demoWeek(1).courses.single()
        repo.append(demoWeek(1).copy(courses = listOf(mondayCourse)))
        val weekTwoMonday = LocalDate.parse(demoWeek(2).dates.first()).toString()
        repo.append(demoWeek(2).copy(courses = listOf(mondayCourse.copy(lessons = listOf(5, 6), date = weekTwoMonday))))
        val data = repo.finish(1)
        val context = data.courseEditor(mondayCourse)
        assertEquals(listOf(CourseOriginalArrangement(1, 1, listOf(1, 2)),
            CourseOriginalArrangement(2, 1, listOf(5, 6))), context.originalArrangements)
    }
    @Test fun editingFullSchoolWeekRangeRemovesDeselectedWeeksAndLessonsButRetainsOtherPatterns() {
        val repo = repository(); repo.begin(demoIdentity, demoTerm.copy(weekCount = 20))
        (1..20).forEach { number ->
            val week = demoWeek(number)
            val original = week.courses.single().copy(lessons = (9..12).toList())
            repo.append(week.copy(courses = if (number in 10..17) listOf(original,
                original.copy(date = week.dates[2]), original.copy(lessons = listOf(5, 6))) else emptyList()))
        }
        val before = repo.finish(1)
        val source = before.complete!!.weeks[12].courses.first()
        val context = before.courseEditor(source)
        assertEquals((10..17).toList(), context.availableWeeks)
        val result = repo.edit(context, draft(context, source.name, source = context.availableWeeks,
            target = (11..16).toList(), day = 1, lessons = listOf(9, 10)))
        result.complete!!.weeks.forEach { week ->
            assertEquals(if (week.number in 11..16) 1 else 0, week.courses.count { it.date == week.dates[0] && it.lessons == listOf(9, 10) })
            assertFalse(week.courses.any { it.date == week.dates[0] && it.lessons == (9..12).toList() })
            assertEquals(before.complete.weeks[week.number - 1].courses.filter { it.date != week.dates[0] || it.lessons == listOf(5, 6) },
                week.courses.filter { it.date != week.dates[0] || it.lessons == listOf(5, 6) })
        }
    }
    @Test fun editingFullCustomWeekRangePreservesOtherDaysAndIntervalsAfterEncryptedReload() {
        val store = ScheduleMemoryStore(); val keys = ScheduleMemoryKeys(); val repo = repository(store, keys)
        val adding = repo.snapshot().courseEditor()
        repo.edit(adding, draft(adding, target = (10..17).toList(), day = 1, lessons = (9..12).toList())
            .copy(weekdays = listOf(1, 3)))
        val initial = repo.snapshot()
        val source = initial.display()!!.weeks[12].courses.first()
        val context = initial.courseEditor(source)
        val result = repo.edit(context, draft(context, source.name, source = context.availableWeeks,
            target = (11..16).toList(), day = 1, lessons = listOf(9, 10)))
        val course = result.localTerms.single().courses.single()
        assertEquals(source.id, course.id)
        assertEquals((11..16).map { CustomOccurrence(it, 1, listOf(9, 10)) }, course.occurrences.filter { it.weekday == 1 })
        assertEquals((10..17).map { CustomOccurrence(it, 3, (9..12).toList()) }, course.occurrences.filter { it.weekday == 3 })
        assertEquals(result, ScheduleRepository(store, AesGcmVaultCipher(keys)).load())
    }
    @Test fun lateLessonsCanKeepOrShrinkOriginalWeekSeriesButCannotExpandToANewWeek() {
        val repo = repository(); repo.begin(demoIdentity, demoTerm.copy(weekCount = 3))
        (1..3).forEach { number ->
            val week = demoWeek(number)
            repo.append(week.copy(courses = if (number <= 2) week.courses.map { it.copy(lessons = listOf(13, 14)) } else emptyList()))
        }
        val before = repo.finish(1)
        val source = before.complete!!.weeks[0].courses.single()
        val context = before.courseEditor(source)
        val unchanged = draft(context, source.name, source = context.availableWeeks, target = context.availableWeeks, day = 1, lessons = source.lessons)
        assertThrows(CourseValidationException::class.java) { repo.edit(context, unchanged.copy(targetWeeks = listOf(1, 2, 3))) }
        val kept = repo.edit(context, unchanged)
        val edit = kept.courseEditor(kept.complete!!.weeks[0].courses.single())
        val result = repo.edit(edit, unchanged.copy(targetWeeks = listOf(2)))
        assertTrue(result.complete!!.weeks[0].courses.isEmpty())
        assertEquals(listOf(13, 14), result.complete.weeks[1].courses.single().lessons)
    }
    @Test fun editingAnImportedLessonAboveTwelveCanPreserveItButCannotSelectItAnew() {
        val repo = repository(); repo.begin(demoIdentity, demoTerm)
        val original = demoWeek(1).courses.single().copy(lessons = listOf(13, 14))
        repo.append(demoWeek(1).copy(courses = listOf(original))); repo.append(demoWeek(2)); repo.finish(1)
        val context = repo.snapshot().courseEditor(original)
        val unchanged = draft(context, name = "更名课程", target = listOf(context.defaultWeek), day = context.defaultWeekday, lessons = listOf(13, 14))
        assertEquals(listOf(13, 14), repo.edit(context, unchanged).complete!!.weeks[0].courses.single().lessons)
        val updated = repo.snapshot()
        val updatedCourse = updated.complete!!.weeks[0].courses.single()
        val updatedContext = updated.courseEditor(updatedCourse)
        val updatedDraft = draft(updatedContext, name = updatedCourse.name, target = listOf(1), day = 1, lessons = listOf(13, 14))
        assertThrows(CourseValidationException::class.java) { repo.edit(updatedContext, updatedDraft.copy(lessons = listOf(13, 15))) }
        assertThrows(CourseValidationException::class.java) { repo.edit(updatedContext, updatedDraft.copy(targetWeeks = listOf(2))) }
        assertThrows(CourseValidationException::class.java) { repo.edit(updatedContext, updatedDraft.copy(weekdays = listOf(3))) }
    }
    @Test fun deletingOneCustomOccurrenceRetainsOtherWeeksAndStableId() {
        val repo = repository(); add(repo)
        val c = repo.snapshot().display()!!.weeks[0].courses[0]
        val context = repo.snapshot().courseEditor(c, deleteOnly = true)
        val result = repo.edit(context, draft(context))
        val retained = result.localTerms.single().courses.single()
        assertEquals(c.id, retained.id); assertEquals(listOf(2), retained.occurrences.map { it.week })
    }
    @Test fun customPartialEditSplitsScheduleWithoutChangingRemainingMetadata() {
        val repo = repository(); add(repo)
        val c = repo.snapshot().display()!!.weeks[0].courses[0]
        val context = repo.snapshot().courseEditor(c)
        val result = repo.edit(context, draft(context, "调课", target = listOf(3), day = 5))
        assertEquals(2, result.localTerms.single().courses.size)
        assertEquals(listOf(2), result.localTerms.single().courses.find { it.id == c.id }!!.occurrences.map { it.week })
        assertEquals("调课", result.display()!!.weeks[2].courses[0].name)
    }
    @Test fun customEditChangesOnlyClickedOccurrenceAndPreservesTargetWeekOriginal() {
        val repo = repository(); add(repo)
        val source = repo.snapshot().display()!!.weeks[0].courses.single()
        val context = repo.snapshot().courseEditor(source)
        val result = repo.edit(context, draft(context, name = "移动后的安排", target = listOf(2), day = 5))
        val targetMonday = LocalDate.parse(context.firstMonday).plusWeeks(1)
        assertTrue(result.display()!!.weeks[0].courses.none { it.source == CourseSource.CUSTOM })
        assertEquals(2, result.display()!!.weeks[1].courses.size)
        assertTrue(result.display()!!.weeks[1].courses.any { it.date == targetMonday.plusDays(1).toString() && it.lessons == source.lessons })
        assertTrue(result.display()!!.weeks[1].courses.any { it.date == targetMonday.plusDays(4).toString() && it.name == "移动后的安排" })
    }
    @Test fun invalidInputAndStaleDraftNeverOverwriteData() {
        val repo = repository(); val context = repo.snapshot().courseEditor()
        for (bad in listOf(draft(context, name = " "), draft(context, target = emptyList()),
            draft(context, lessons = listOf(21)), draft(context).copy(firstMonday = "2026-11-03"))) {
            assertThrows(CourseValidationException::class.java) { repo.edit(context, bad) }
            assertTrue(repo.snapshot().localTerms.isEmpty())
        }
        add(repo)
        assertThrows(CourseValidationException::class.java) { repo.edit(context, draft(context)) }
    }
    @Test fun failedLocalWritePreservesEncryptedVaultAndAllowsExplicitRetry() {
        val store = ScheduleMemoryStore(); val repo = repository(store)
        add(repo); val before = repo.snapshot(); val bytes = store.bytes!!.copyOf()
        val context = before.courseEditor(); store.failWrite = true
        assertThrows(ScheduleException::class.java) { repo.edit(context, draft(context)) }
        assertEquals(before, repo.snapshot()); assertArrayEquals(bytes, store.bytes)
        store.failWrite = false; repo.edit(context, draft(context))
        assertEquals(2, repo.snapshot().localTerms.single().courses.size)
    }
    @Test fun unbindingKeepsLocalRecordsButDifferentAccountDoesNotAdoptThem() {
        val repo = repository(); add(repo); imported(repo)
        val old = repo.snapshot().localTerms.single()
        assertNull(repo.unbind().identity)
        repo.begin(ScheduleIdentity("another-demo"), demoTerm)
        repo.append(demoWeek(1)); repo.append(demoWeek(2)); val result = repo.finish(2)
        assertEquals(old, result.localTerms.single())
        assertTrue(result.display()!!.weeks.all { w -> w.courses.none { it.source == CourseSource.CUSTOM } })
    }
    @Test fun overflowCustomOccurrenceCanBeManagedAndMovedBackIntoTerm() {
        val repo = repository(); val ctx = repo.snapshot().courseEditor()
        repo.edit(ctx, draft(ctx, target = listOf(20))); val data = imported(repo)
        val term = data.localTerms.single(); val c = term.courses.single()
        val record = ScheduleCourse(c.id, c.name, LocalDate.parse(term.firstMonday).plusWeeks(19).plusDays(1).toString(),
            listOf(3, 4), source = CourseSource.CUSTOM)
        val editor = data.courseEditor(record, weekday = 2, termId = term.id)
        assertEquals(20, editor.defaultWeek); assertEquals(listOf(20), editor.availableWeeks)
        val result = repo.edit(editor, draft(editor, target = listOf(1)))
        assertEquals(1, result.display()!!.weeks[0].courses.count { it.source == CourseSource.CUSTOM })
    }
    @Test fun nonconsecutiveLessonsAndOverlapsArePreserved() {
        val repo = repository(); val ctx = repo.snapshot().courseEditor()
        val data = repo.edit(ctx, draft(ctx, lessons = listOf(1, 3), target = listOf(1)))
        assertEquals(listOf(1, 3), data.display()!!.weeks[0].courses.single().lessons)
        assertEquals(2, courseBlocks(data.display()!!.weeks[0]).size)
    }
    @Test fun oldSerializedVaultDefaultsToNoCustomCalendar() {
        val store = ScheduleMemoryStore(); val keys = ScheduleMemoryKeys()
        val cipher = AesGcmVaultCipher(keys)
        store.bytes = cipher.encrypt("""{"schema":1,"identity":null,"complete":null,"pending":null}""".encodeToByteArray())
        val data = ScheduleRepository(store, cipher).load()
        assertTrue(data.localTerms.isEmpty()); assertNull(data.display())
    }
    @Test fun unrelatedSameNameOrOtherPeriodIsNotChanged() {
        val repo = repository(); repo.begin(demoIdentity, demoTerm)
        val w = demoWeek(1)
        val original = w.courses.single()
        repo.append(w.copy(courses = listOf(original, original.copy(id = "unrelated"), original.copy(lessons = listOf(5, 6)))))
        repo.append(demoWeek(2)); repo.finish(1)
        val editor = repo.snapshot().courseEditor(original, deleteOnly = true)
        val result = repo.edit(editor, draft(editor))
        assertEquals(2, result.complete!!.weeks[0].courses.size)
        assertTrue(result.complete!!.weeks[0].courses.any { it.id == "unrelated" })
        assertTrue(result.complete!!.weeks[0].courses.any { it.lessons == listOf(5, 6) })
    }
    @Test fun manuallyCreatedCalendarAfterSchoolImportNeverAutoAdoptsOnRefresh() {
        val repo = repository(); imported(repo)
        val context = repo.snapshot().courseEditor(newLocalTerm = true)
        val created = repo.edit(context, draft(context)).localTerms.single()
        assertFalse(created.adoptOnImport)
        val refreshed = imported(repo)
        assertEquals(created, refreshed.localTerms.single())
        assertTrue(refreshed.display()!!.weeks.all { w -> w.courses.none { it.source == CourseSource.CUSTOM } })
    }
    @Test fun multipleWeekdaysPersistEverySelectedWeekDayWithoutDuplicates() {
        val store = ScheduleMemoryStore(); val keys = ScheduleMemoryKeys(); val repo = repository(store, keys)
        val context = repo.snapshot().courseEditor()
        val result = repo.edit(context, draft(context, target = listOf(1, 3)).copy(weekdays = listOf(1, 3, 7)))
        val course = result.localTerms.single().courses.single()
        assertEquals(listOf(1 to 1, 1 to 3, 1 to 7, 3 to 1, 3 to 3, 3 to 7),
            course.occurrences.map { it.week to it.weekday })
        val restored = ScheduleRepository(store, AesGcmVaultCipher(keys)).load()
        assertEquals(result, restored)
        assertEquals(6, restored.display()!!.weeks.sumOf { it.courses.size })
        assertEquals(3, restored.display()!!.weeks[0].courses.map { it.date }.distinct().size)
    }
    @Test fun editingOneWeekdayRetainsOtherDaysAndDeduplicatesExistingTargets() {
        val repo = repository(); val context = repo.snapshot().courseEditor()
        repo.edit(context, draft(context).copy(weekdays = listOf(1, 3)))
        val firstMonday = repo.snapshot().display()!!.weeks[0].courses.first()
        val edit = repo.snapshot().courseEditor(firstMonday)
        val changed = repo.edit(edit, draft(edit, target = listOf(1)).copy(weekdays = listOf(1, 3, 5)))
        assertEquals(firstMonday.id, changed.localTerms.single().courses.single().id)
        assertEquals(listOf(1 to 1, 1 to 3, 1 to 5, 2 to 1, 2 to 3),
            changed.localTerms.single().courses.single().occurrences.map { it.week to it.weekday })
        val delete = changed.courseEditor(changed.display()!!.weeks[0].courses.first(), deleteOnly = true)
        val deleted = repo.edit(delete, draft(delete))
        assertEquals(listOf(1 to 3, 1 to 5, 2 to 1, 2 to 3), deleted.localTerms.single().courses.single().occurrences.map { it.week to it.weekday })
    }
    @Test fun customMoveWithChangedMetadataPreservesTheEditedTargetAndExistingArrangement() {
        for (field in listOf("name", "location", "teacher")) {
            val store = ScheduleMemoryStore(); val keys = ScheduleMemoryKeys(); val repo = repository(store, keys)
            val adding = repo.snapshot().courseEditor()
            repo.edit(adding, draft(adding, target = listOf(1, 2), day = 1).copy(weekdays = listOf(1, 3)))
            val before = repo.snapshot()
            val original = before.localTerms.single().courses.single()
            val context = before.courseEditor(before.display()!!.weeks[0].courses.first { LocalDate.parse(it.date).dayOfWeek.value == 1 })
            val moved = draft(context, original.name, source = context.availableWeeks, target = listOf(1, 2), day = 3)
                .copy(location = original.location, teacher = original.teacher)
            val changed = when (field) {
                "name" -> moved.copy(name = "调整后的课程")
                "location" -> moved.copy(location = "调整后的教室")
                else -> moved.copy(teacher = "调整后的教师")
            }
            val edited = repo.editWithUndo(context, changed)
            val result = edited.data
            val courses = result.localTerms.single().courses
            assertEquals("changed $field must not disappear", 2, courses.size)
            assertEquals(original.copy(occurrences = original.occurrences.filter { it.weekday == 3 }), courses.single { it.id == original.id })
            val adjusted = courses.single { it.id != original.id }
            assertEquals(changed.name, adjusted.name); assertEquals(changed.location, adjusted.location); assertEquals(changed.teacher, adjusted.teacher)
            assertEquals(listOf(1, 2).map { CustomOccurrence(it, 3, original.occurrences.first().lessons) }, adjusted.occurrences)
            assertEquals(result, ScheduleRepository(store, AesGcmVaultCipher(keys)).load())
            assertEquals(before, repo.undo(edited.undo!!.token))
            assertEquals(before, ScheduleRepository(store, AesGcmVaultCipher(keys)).load())
        }
    }
    @Test fun emptyInvalidOrDuplicateWeekdaysAreRejectedBeforeWriting() {
        val repo = repository(); val context = repo.snapshot().courseEditor()
        for (days in listOf(emptyList(), listOf(0), listOf(8), listOf(1, 1))) {
            assertThrows(CourseValidationException::class.java) { repo.edit(context, draft(context).copy(weekdays = days)) }
            assertTrue(repo.snapshot().localTerms.isEmpty())
        }
    }
    @Test fun partialEditAtOccurrenceLimitSplitsAndRestoresEveryArrangement() {
        val store = ScheduleMemoryStore(); val keys = ScheduleMemoryKeys(); val repo = repository(store, keys)
        val before = addSixtyWeekCourse(repo)
        val original = before.localTerms.single().courses.single()
        val course = before.display()!!.weeks[0].courses.first()
        val context = before.courseEditor(course)
        val result = repo.edit(context, draft(context, target = listOf(1)).copy(weekdays = listOf(1, 3)))
        val courses = result.localTerms.single().courses
        assertEquals(2, courses.size)
        assertEquals(original.copy(occurrences = original.occurrences.filterNot { it.week == 1 && it.weekday == 1 }),
            courses.single { it.id == original.id })
        val adjusted = courses.single { it.id != original.id }
        assertEquals(listOf(CustomOccurrence(1, 1, listOf(3, 4)), CustomOccurrence(1, 3, listOf(3, 4))), adjusted.occurrences)
        assertEquals(original.name, adjusted.name)
        assertTrue(courses.all { it.occurrences.size <= 420 })
        val restored = ScheduleRepository(store, AesGcmVaultCipher(keys)).load()
        assertEquals(result, restored)
        assertEquals(421, restored.display()!!.weeks.sumOf { it.courses.size })
        val moved = restored.display()!!.weeks[0].courses.first { it.id == adjusted.id }
        val delete = restored.courseEditor(moved, deleteOnly = true)
        val deleted = repo.edit(delete, draft(delete))
        assertEquals(courses.single { it.id == original.id }, deleted.localTerms.single().courses.single { it.id == original.id })
        assertEquals(listOf(CustomOccurrence(1, 3, listOf(3, 4))),
            deleted.localTerms.single().courses.single { it.id == adjusted.id }.occurrences)
    }
    @Test fun sixtyWeekMultiDayAdjustmentRetainsOtherOriginalTimes() {
        val store = ScheduleMemoryStore(); val keys = ScheduleMemoryKeys(); val repo = repository(store, keys)
        val before = addSixtyWeekCourse(repo, listOf(1, 3, 5))
        val original = before.localTerms.single().courses.single()
        val context = before.courseEditor(before.display()!!.weeks[0].courses.first())
        val result = repo.edit(context, draft(context, target = (1..60).toList()).copy(weekdays = (1..7).toList()))
        val courses = result.localTerms.single().courses
        assertEquals(original.copy(occurrences = original.occurrences.drop(1)), courses.single { it.id == original.id })
        assertEquals((1..60).flatMap { week -> (1..7).map { day -> CustomOccurrence(week, day, listOf(3, 4)) } },
            courses.single { it.id != original.id }.occurrences)
        assertEquals(599, result.display()!!.weeks.sumOf { it.courses.size })
        assertEquals(result, ScheduleRepository(store, AesGcmVaultCipher(keys)).load())
    }
    @Test fun capacitySplitDeduplicatesTargetsAlreadyInRetainedRecord() {
        val store = ScheduleMemoryStore(); val keys = ScheduleMemoryKeys()
        val cipher = AesGcmVaultCipher(keys)
        val originalOccurrences = buildList {
            add(CustomOccurrence(1, 1, listOf(1, 2)))
            (2..60).forEach { add(CustomOccurrence(it, 1, listOf(3, 4))) }
            (1..60).forEach { week -> (2..7).forEach { day -> add(CustomOccurrence(week, day, listOf(1, 2))) } }
        }
        val initial = ScheduleData(localTerms = listOf(LocalScheduleTerm(
            id = "capacity-term", firstMonday = "2026-11-02", weekCount = 60,
            courses = listOf(CustomCourse("capacity-course", "自定义实验", occurrences = originalOccurrences)))),
            selectedLocalTermId = "capacity-term")
        store.bytes = cipher.encrypt(kotlinx.serialization.json.Json.encodeToString(ScheduleData.serializer(), initial).encodeToByteArray())
        val repo = repository(store, keys); val before = repo.snapshot()
        val original = before.localTerms.single().courses.single()
        val context = before.courseEditor(before.display()!!.weeks[0].courses.first())
        val result = repo.edit(context, draft(context, target = (1..60).toList(), day = 1, lessons = listOf(3, 4))
            .copy(weekdays = listOf(1, 2)))
        val courses = result.localTerms.single().courses
        val added = courses.single { it.id != original.id }
        assertEquals(listOf(CustomOccurrence(1, 1, listOf(3, 4))) +
            (1..60).map { CustomOccurrence(it, 2, listOf(3, 4)) }, added.occurrences)
        val all = courses.flatMap { it.occurrences }
        assertEquals(480, all.size); assertEquals(all.size, all.distinct().size)
        assertEquals(419, courses.single { it.id == original.id }.occurrences.size)
    }
    @Test fun splitWriteFailurePreservesOriginalVaultAndAllowsExplicitRetry() {
        val store = ScheduleMemoryStore(); val keys = ScheduleMemoryKeys(); val repo = repository(store, keys)
        val before = addSixtyWeekCourse(repo); val bytes = store.bytes!!.copyOf()
        val context = before.courseEditor(before.display()!!.weeks[0].courses.first())
        val adjustment = draft(context, target = listOf(1)).copy(weekdays = listOf(1, 3))
        store.failWrite = true
        assertThrows(ScheduleException::class.java) { repo.edit(context, adjustment) }
        assertEquals(before, repo.snapshot()); assertArrayEquals(bytes, store.bytes)
        assertEquals(before, ScheduleRepository(store, AesGcmVaultCipher(keys)).load())
        store.failWrite = false
        val result = repo.edit(context, adjustment)
        assertEquals(421, result.localTerms.single().courses.sumOf { it.occurrences.size })
        assertEquals(result, ScheduleRepository(store, AesGcmVaultCipher(keys)).load())
    }
    @Test fun splitAtCourseCountLimitReportsValidationAndKeepsOriginalData() {
        val store = ScheduleMemoryStore(); val keys = ScheduleMemoryKeys(); val repo = repository(store, keys)
        val initial = addSixtyWeekCourse(repo)
        val term = initial.localTerms.single(); val original = term.courses.single()
        val full = initial.copy(localTerms = listOf(term.copy(courses = listOf(original) + (1..999).map {
            CustomCourse("fictional-$it", "虚构占位课程", occurrences = listOf(CustomOccurrence(1, 2, listOf(5, 6))))
        })))
        val cipher = AesGcmVaultCipher(keys)
        store.bytes = cipher.encrypt(kotlinx.serialization.json.Json.encodeToString(ScheduleData.serializer(), full).encodeToByteArray())
        val loadedRepo = ScheduleRepository(store, cipher); assertEquals(full, loadedRepo.load())
        val bytes = store.bytes!!.copyOf()
        val context = full.courseEditor(full.display()!!.weeks[0].courses.first { it.id == original.id })
        val error = assertThrows(CourseValidationException::class.java) {
            loadedRepo.edit(context, draft(context, target = listOf(1)).copy(weekdays = listOf(1, 3)))
        }
        assertTrue(error.message!!.contains("本地课程数量已达上限"))
        assertEquals(full, loadedRepo.snapshot()); assertArrayEquals(bytes, store.bytes)
        // An edit that fits the existing record is still allowed at the course count limit.
        val unchanged = loadedRepo.edit(context, draft(context, target = listOf(1), day = 1, lessons = listOf(1, 2)))
        assertEquals(1000, unchanged.localTerms.single().courses.size)
        assertEquals(original, unchanged.localTerms.single().courses.single { it.id == original.id })
    }
    @Test fun oversizedStoredRecordIsStillRejectedWithoutOverwritingVault() {
        val store = ScheduleMemoryStore(); val keys = ScheduleMemoryKeys(); val repo = repository(store, keys)
        val initial = addSixtyWeekCourse(repo)
        val term = initial.localTerms.single(); val original = term.courses.single()
        val invalid = initial.copy(localTerms = listOf(term.copy(courses = listOf(original.copy(
            occurrences = original.occurrences + CustomOccurrence(1, 1, listOf(3, 4)))))))
        val cipher = AesGcmVaultCipher(keys)
        store.bytes = cipher.encrypt(kotlinx.serialization.json.Json.encodeToString(ScheduleData.serializer(), invalid).encodeToByteArray())
        val bytes = store.bytes!!.copyOf()
        assertThrows(ScheduleException::class.java) { ScheduleRepository(store, cipher).load() }
        assertArrayEquals(bytes, store.bytes)
    }
}
