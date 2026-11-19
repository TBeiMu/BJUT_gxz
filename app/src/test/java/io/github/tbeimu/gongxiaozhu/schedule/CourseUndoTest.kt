package io.github.tbeimu.gongxiaozhu.schedule

import io.github.tbeimu.gongxiaozhu.data.AesGcmVaultCipher
import io.github.tbeimu.gongxiaozhu.data.VaultStore
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class CourseUndoRepositoryTest {
    private fun draft(context: CourseEditContext, name: String = "虚构课程",
        weeks: List<Int> = context.availableWeeks.ifEmpty { listOf(1, 2) },
        lessons: List<Int> = listOf(1, 2)) = CourseDraft(name, "", "", listOf(context.defaultWeekday),
        context.availableWeeks, weeks, lessons, context.firstMonday, context.weekCount)

    private fun school(repo: ScheduleRepository): ScheduleData {
        repo.begin(demoIdentity, demoTerm); repo.append(demoWeek(1)); repo.append(demoWeek(2))
        return repo.finish(100)
    }

    private fun custom(repo: ScheduleRepository): ScheduleData {
        val context = repo.snapshot().courseEditor()
        return repo.edit(context, draft(context).copy(weekdays = listOf(1), firstMonday = "2026-11-02"))
    }

    @Test fun schoolEditAndDeletionUndoRestoreExactArrangementsAndAreSingleUse() {
        for (delete in listOf(false, true)) {
            val store = ScheduleMemoryStore(); val keys = ScheduleMemoryKeys(); val repo = repository(store, keys)
            val before = school(repo)
            val course = before.complete!!.weeks[0].courses.single()
            val context = before.courseEditor(course, deleteOnly = delete)
            val result = repo.editWithUndo(context, draft(context, weeks = listOf(2), lessons = listOf(9, 10, 11, 12)))
            val action = result.undo!!
            assertEquals(delete, action.deleted)
            assertEquals(if (delete) 2 else 0, action.deletedCount)
            assertNotEquals(before, result.data)
            assertEquals(before, repo.undo(action.token))
            assertEquals(before, ScheduleRepository(store, AesGcmVaultCipher(keys)).load())
            assertThrows(CourseValidationException::class.java) { repo.undo(action.token) }
        }
    }

    @Test fun splitCustomEditUndoRestoresOriginalIdOccurrencesAndRecordOrder() {
        val repo = repository()
        val context = repo.snapshot().courseEditor()
        val before = repo.edit(context, draft(context, weeks = (1..60).toList()).copy(
            weekdays = (1..7).toList(), firstMonday = "2026-11-02", weekCount = 60))
        val original = before.localTerms.single().courses.single()
        val editor = before.courseEditor(before.display()!!.weeks[0].courses.first())
        val result = repo.editWithUndo(editor, draft(editor, weeks = listOf(1), lessons = listOf(3, 4))
            .copy(sourceWeeks = listOf(1), weekdays = listOf(1, 3)))
        assertEquals(2, result.data.localTerms.single().courses.size)
        val restored = repo.undo(result.undo!!.token)
        assertEquals(before, restored)
        assertEquals(original, restored.localTerms.single().courses.single())
    }

    @Test fun creationAndAnUnchangedExistingCourseDoNotOfferUndo() {
        val repo = repository()
        val creating = repo.snapshot().courseEditor()
        assertNull(repo.editWithUndo(creating, draft(creating)).undo)
        val before = repo.snapshot()
        val course = before.display()!!.weeks[0].courses.first()
        val context = before.courseEditor(course)
        val result = repo.editWithUndo(context, draft(context))
        assertEquals(before, result.data)
        assertNull(result.undo)
    }

    @Test fun unchangedSchoolEditPreservesSchoolTextCourseOrderAndEncryptedBytes() {
        val store = ScheduleMemoryStore(); val repo = repository(store)
        repo.begin(demoIdentity, demoTerm)
        for (number in 1..2) {
            val week = demoWeek(number)
            val course = week.courses.single().copy(timeText = "学校原时间", weeksText = "第 1—2 周")
            repo.append(week.copy(courses = listOf(course, course.copy(id = "other-course", name = "另一门课"))))
        }
        val before = repo.finish(100); val bytes = store.bytes!!.copyOf()
        val course = before.complete!!.weeks[0].courses.first()
        val context = before.courseEditor(course)
        val unchanged = CourseDraft(" ${course.name} ", " ${course.location} ", " ${course.teacher} ",
            listOf(context.defaultWeekday), context.availableWeeks, context.availableWeeks.reversed(),
            course.lessons.reversed(), context.firstMonday, context.weekCount)
        val result = repo.editWithUndo(context, unchanged)
        assertSame(before, result.data); assertNull(result.undo); assertArrayEquals(bytes, store.bytes)
        assertSame(before, repo.edit(context, unchanged)); assertArrayEquals(bytes, store.bytes)
    }

    @Test fun unchangedCustomEditKeepsTheRecordInItsOriginalPositionWithoutWriting() {
        val store = ScheduleMemoryStore(); val repo = repository(store)
        custom(repo)
        val adding = repo.snapshot().courseEditor()
        val before = repo.edit(adding, draft(adding, name = "第二门课"))
        val bytes = store.bytes!!.copyOf()
        val course = before.display()!!.weeks[0].courses.first { it.name == "虚构课程" }
        val context = before.courseEditor(course)
        val result = repo.editWithUndo(context, draft(context))
        assertSame(before, result.data); assertNull(result.undo)
        assertEquals(listOf("虚构课程", "第二门课"), result.data.localTerms.single().courses.map { it.name })
        assertArrayEquals(bytes, store.bytes)
    }

    @Test fun anyLaterWriteReloadUnbindingOrClearExpiresUndo() {
        val changes: List<(ScheduleRepository) -> Unit> = listOf(
            { it.selectLocalTerm(it.snapshot().localTerms.single().id) },
            { it.begin(demoIdentity, demoTerm) },
            { it.load() },
            { it.unbind() },
            { it.clear() },
        )
        for (change in changes) {
            val repo = repository(); val before = custom(repo)
            val context = before.courseEditor(before.display()!!.weeks[0].courses.first())
            val result = repo.editWithUndo(context, draft(context, name = "调整课程"))
            change(repo)
            val afterChange = repo.snapshot()
            assertThrows(CourseValidationException::class.java) { repo.undo(result.undo!!.token) }
            assertEquals(afterChange, repo.snapshot())
        }
    }

    @Test fun failedEditAndUndoPreserveTheVaultAndAnUndoCanBeExplicitlyRetried() {
        val store = ScheduleMemoryStore(); val repo = repository(store)
        val before = custom(repo)
        val context = before.courseEditor(before.display()!!.weeks[0].courses.first())
        val oldBytes = store.bytes!!.copyOf()
        store.failWrite = true
        assertThrows(ScheduleException::class.java) { repo.editWithUndo(context, draft(context, name = "调整课程")) }
        assertEquals(before, repo.snapshot()); assertArrayEquals(oldBytes, store.bytes)
        store.failWrite = false
        val result = repo.editWithUndo(context, draft(context, name = "调整课程"))
        val bytes = store.bytes!!.copyOf()
        store.failWrite = true
        assertThrows(ScheduleException::class.java) { repo.undo(result.undo!!.token) }
        assertEquals(result.data, repo.snapshot()); assertArrayEquals(bytes, store.bytes)
        store.failWrite = false
        assertEquals(before, repo.undo(result.undo!!.token))
    }

    @Test fun undoRechecksTheEncryptedFileAndNeverOverwritesAnExternalWrite() {
        val store = ScheduleMemoryStore(); val keys = ScheduleMemoryKeys(); val repo = repository(store, keys)
        val before = custom(repo)
        val context = before.courseEditor(before.display()!!.weeks[0].courses.first())
        val result = repo.editWithUndo(context, draft(context, name = "调整课程"))
        val other = ScheduleRepository(store, AesGcmVaultCipher(keys)); other.load()
        val external = other.selectLocalTerm(null)
        val bytes = store.bytes!!.copyOf()
        assertThrows(ScheduleException::class.java) { repo.undo(result.undo!!.token) }
        assertArrayEquals(bytes, store.bytes)
        assertEquals(external, ScheduleRepository(store, AesGcmVaultCipher(keys)).load())
        assertThrows(CourseValidationException::class.java) { other.undo(result.undo!!.token) }
    }

    @Test fun dismissingAnOldTokenCannotDismissTheNewAction() {
        val repo = repository(); val before = custom(repo)
        val firstContext = before.courseEditor(before.display()!!.weeks[0].courses.first())
        val first = repo.editWithUndo(firstContext, draft(firstContext, name = "第一次调整"))
        val nextContext = first.data.courseEditor(first.data.display()!!.weeks[0].courses.first())
        val second = repo.editWithUndo(nextContext, draft(nextContext, name = "第二次调整"))
        repo.dismissCourseUndo(first.undo!!.token)
        assertEquals(first.data, repo.undo(second.undo!!.token))
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class CourseUndoControllerTest {
    private fun add(repo: ScheduleRepository): ScheduleData {
        val context = repo.snapshot().courseEditor()
        return repo.edit(context, CourseDraft("虚构课程", "", "", listOf(1), emptyList(), listOf(1, 2),
            listOf(1, 2), "2026-11-02", context.weekCount))
    }
    private fun adjustment(context: CourseEditContext) = CourseDraft("调整课程", "", "", listOf(1),
        context.availableWeeks, context.availableWeeks, listOf(3, 4), context.firstMonday, context.weekCount)

    @Test fun startingWithoutNetworkOrCancellingPendingImportRetiresThePreviousCourseAction() = runTest {
        for (cancel in listOf(false, true)) {
            val repo = repository(); add(repo)
            if (cancel) repo.begin(demoIdentity, demoTerm)
            val before = repo.snapshot()
            val controller = ScheduleController(backgroundScope, repo, StandardTestDispatcher(testScheduler)); runCurrent()
            controller.enterForeground()
            val context = before.courseEditor(before.display()!!.weeks[0].courses.first())
            var action: CourseUndo? = null
            val edit = launch { action = controller.editCourse(context, adjustment(context)) }; runCurrent(); edit.join()
            assertNotNull(action)
            if (cancel) controller.cancelImport() else controller.start()
            runCurrent()
            assertNull(controller.state.value.courseUndo)
            assertThrows(CourseValidationException::class.java) { repo.undo(action!!.token) }
            if (cancel) assertNull(controller.state.value.data.pending)
        }
    }

    @Test fun undoUsesTheSameTaskSlotAndRejectsBackgroundAndExpiredActions() = runTest {
        val repo = repository(); val before = add(repo)
        val controller = ScheduleController(backgroundScope, repo, StandardTestDispatcher(testScheduler)); runCurrent()
        controller.enterForeground()
        val context = before.courseEditor(before.display()!!.weeks[0].courses.first())
        var action: CourseUndo? = null
        val edit = launch { action = controller.editCourse(context, adjustment(context)) }; runCurrent(); edit.join()
        assertEquals(action, controller.state.value.courseUndo)
        val undo = launch(start = CoroutineStart.UNDISPATCHED) { controller.undoCourse(action!!.token) }
        var duplicateRejected = false
        val duplicate = launch(start = CoroutineStart.UNDISPATCHED) {
            try { controller.undoCourse(action!!.token) } catch (_: CourseValidationException) { duplicateRejected = true }
        }
        runCurrent(); undo.join(); duplicate.join()
        assertTrue(duplicateRejected)
        assertEquals(before, controller.state.value.data); assertNull(controller.state.value.courseUndo)
        assertThrows(CourseValidationException::class.java) { repo.undo(action!!.token) }
        val again = launch { action = controller.editCourse(context, adjustment(context)) }; runCurrent(); again.join()
        val token = action!!.token
        controller.leaveForeground(); runCurrent()
        assertNull(controller.state.value.courseUndo)
        var rejected = false
        val expired = launch { try { controller.undoCourse(token) } catch (_: CourseValidationException) { rejected = true } }
        runCurrent(); expired.join()
        assertTrue(rejected); assertThrows(CourseValidationException::class.java) { repo.undo(token) }
    }

    @Test fun editFinishingAfterBackgroundDoesNotPublishOrRetainUndo() = runTest {
        val memory = ScheduleMemoryStore()
        var onWrite: (() -> Unit)? = null
        val store = object : VaultStore {
            override fun read() = memory.read()
            override fun write(bytes: ByteArray) { memory.write(bytes); onWrite?.invoke() }
            override fun clear() = memory.clear()
        }
        val repo = ScheduleRepository(store, AesGcmVaultCipher(ScheduleMemoryKeys())).also { it.load() }
        val before = add(repo)
        val controller = ScheduleController(backgroundScope, repo, StandardTestDispatcher(testScheduler)); runCurrent()
        controller.enterForeground()
        val context = before.courseEditor(before.display()!!.weeks[0].courses.first())
        onWrite = { controller.leaveForeground() }
        var action: CourseUndo? = CourseUndo("not-completed", false)
        val edit = launch { action = controller.editCourse(context, adjustment(context)) }; runCurrent(); edit.join()
        assertNull(action); assertNull(controller.state.value.courseUndo)
        assertEquals("调整课程", repo.snapshot().localTerms.single().courses.single().name)
        controller.enterForeground(); runCurrent()
        assertNull(controller.state.value.courseUndo)
    }

    @Test fun staleDismissalCannotRemoveANewerControllerAction() = runTest {
        val repo = repository(); val before = add(repo)
        val controller = ScheduleController(backgroundScope, repo, StandardTestDispatcher(testScheduler)); runCurrent()
        controller.enterForeground()
        val firstContext = before.courseEditor(before.display()!!.weeks[0].courses.first())
        var first: CourseUndo? = null
        val edit = launch { first = controller.editCourse(firstContext, adjustment(firstContext)) }; runCurrent(); edit.join()
        val after = controller.state.value.data
        val secondContext = after.courseEditor(after.display()!!.weeks[0].courses.first())
        var second: CourseUndo? = null
        val editAgain = launch { second = controller.editCourse(secondContext, adjustment(secondContext).copy(name = "再次调整")) }
        runCurrent(); editAgain.join()
        controller.dismissCourseUndo(first!!.token); runCurrent()
        assertEquals(second, controller.state.value.courseUndo)
        val undo = launch { controller.undoCourse(second!!.token) }; runCurrent(); undo.join()
        assertEquals(after, controller.state.value.data)
    }

    @Test fun cancelledEditKeepsACompletedDiskWriteVisibleWithoutOfferingUndo() = runTest {
        val memory = ScheduleMemoryStore()
        var onWrite: (() -> Unit)? = null
        val store = object : VaultStore {
            override fun read() = memory.read()
            override fun write(bytes: ByteArray) { memory.write(bytes); onWrite?.invoke() }
            override fun clear() = memory.clear()
        }
        val repo = ScheduleRepository(store, AesGcmVaultCipher(ScheduleMemoryKeys())).also { it.load() }
        val before = add(repo)
        val controller = ScheduleController(backgroundScope, repo, StandardTestDispatcher(testScheduler)); runCurrent()
        controller.enterForeground()
        val context = before.courseEditor(before.display()!!.weeks[0].courses.first())
        lateinit var edit: Job
        var completed = false
        onWrite = { edit.cancel() }
        edit = launch { controller.editCourse(context, adjustment(context)); completed = true }
        runCurrent(); edit.join()
        assertTrue(edit.isCancelled); assertFalse(completed)
        assertEquals(repo.snapshot(), controller.state.value.data)
        assertEquals("调整课程", controller.state.value.data.localTerms.single().courses.single().name)
        assertNull(controller.state.value.courseUndo); assertFalse(controller.state.value.busy)
    }
}
