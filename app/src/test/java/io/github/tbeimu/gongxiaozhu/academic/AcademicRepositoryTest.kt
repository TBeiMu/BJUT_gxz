package io.github.tbeimu.gongxiaozhu.academic

import io.github.tbeimu.gongxiaozhu.data.AesGcmVaultCipher
import io.github.tbeimu.gongxiaozhu.data.VaultCipher
import io.github.tbeimu.gongxiaozhu.data.VaultKeySource
import io.github.tbeimu.gongxiaozhu.data.VaultStore
import java.io.IOException
import java.time.LocalDateTime
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class AcademicRepositoryTest {
    @Test fun accountSwitchAndSelectedResultAreAtomicAndKeepCustomExamsOnly() = runBlocking {
        val f = Fixture(); f.start(bound = true)
        f.repository.saveExams(identity, listOf(exam), 1000)
        f.repository.saveGrades(identity, grades())
        val before = f.repository.saveExam(null, ExamFields("自定义示例"), f.repository.state.value!!.revision)
        val other = AcademicIdentity("fictional-other")
        val result = AcademicQueryResult.Grades(grades(3000))
        expectProblem(AcademicProblem.ACCOUNT_MISMATCH) { f.repository.commitQuery(other, result, before.revision) }
        assertEquals(before, f.repository.load())
        f.store.failWrite = true
        expectProblem(AcademicProblem.STORAGE) { f.repository.commitQuery(other, result, before.revision, true) }
        f.store.failWrite = false
        assertEquals(before, f.repository.load())
        val after = f.repository.commitQuery(other, result, before.revision, true)
        assertEquals(other, after.identity); assertNull(after.examUpdatedAt)
        assertEquals(before.exams.filter { it.original == null }, after.exams)
        assertEquals(grades(3000), after.grades)
        assertEquals(after, f.repository.load())
    }

    @Test fun selectedQueryPreservesOtherProjectAndCannotCommitAgainstAStaleRevision() = runBlocking {
        val f = Fixture(); val empty = f.start()
        val first = f.repository.commitQuery(identity, AcademicQueryResult.Exams(listOf(exam), 1000), empty.revision)
        val second = f.repository.commitQuery(identity, AcademicQueryResult.Grades(grades()), first.revision)
        assertEquals(first.exams, second.exams); assertEquals(first.examUpdatedAt, second.examUpdatedAt)
        assertTrue(runCatching { f.repository.commitQuery(identity, AcademicQueryResult.Exams(emptyList(), 3000), first.revision) }
            .exceptionOrNull() is AcademicValidationException)
        assertEquals(second, f.repository.load())
        val third = f.repository.commitQuery(identity, AcademicQueryResult.Exams(emptyList(), 3000), second.revision)
        assertTrue(third.exams.isEmpty()); assertEquals(second.grades, third.grades)
    }
    private class Store : VaultStore {
        var bytes: ByteArray? = null
        var failRead = false
        var failWrite = false
        var reads = 0
        var writes = 0
        var clears = 0
        var beforeRead: (() -> Unit)? = null
        override fun read(): ByteArray? { reads++; beforeRead?.invoke(); if (failRead) throw IOException("Fictional read failure"); return bytes?.copyOf() }
        override fun write(bytes: ByteArray) { writes++; if (failWrite) throw IOException("Fictional atomic failure"); this.bytes = bytes.copyOf() }
        override fun clear() { clears++; bytes = null }
    }
    private class Keys : VaultKeySource {
        var key: SecretKey? = null
        var clears = 0
        override fun encryptionKey(): SecretKey = key ?: KeyGenerator.getInstance("AES").apply { init(256) }.generateKey().also { key = it }
        override fun decryptionKey(): SecretKey = key ?: error("Fictional missing key")
        override fun clear() { clears++; key = null }
    }
    private class Fixture {
        val store = Store()
        val keys = Keys()
        val cipher = AesGcmVaultCipher(keys)
        val repository = AcademicRepository(store, cipher, Dispatchers.Unconfined)
        suspend fun start(bound: Boolean = false): AcademicData {
            val empty = repository.load()
            return if (bound) repository.bind(identity, empty.revision) else empty
        }
    }
    private companion object {
        val identity = AcademicIdentity("fictional-student", "虚构同学")
        val exam = SchoolExam("FICTIONAL-101", "虚构考试", "虚构班级", "2026-12-01 08:00", "2026-12-01 10:00", "虚构教室")
        fun grades(time: Long = 2000) = GradeSnapshot(listOf(GradeRecord("虚构学位课", "2.0", "2026秋季", "优秀")),
            listOf(GradeRecord("虚构选修课", "", "", "", restricted = true)), mapOf("已修学分" to "2.0", "应修学分" to ""), time)
    }
    private suspend fun expectProblem(problem: AcademicProblem, action: suspend () -> Unit) {
        try { action(); fail("Expected fixed academic problem") }
        catch (error: AcademicException) { assertEquals(problem, error.problem); assertEquals(problem.hint, error.message) }
    }
    private suspend fun expectValidation(action: suspend () -> Unit) {
        try { action(); fail("Expected a fixed editing validation message") }
        catch (error: AcademicValidationException) { assertFalse(error.message.isNullOrBlank()) }
    }
    private suspend fun expectCancelled(action: suspend () -> Unit) {
        try { action(); fail("Expected retired task cancellation") } catch (_: CancellationException) { }
    }

    @Test fun manualExamsWorkWithoutLoginAndSurviveReload() = runBlocking {
        val f = Fixture(); assertNull(f.repository.state.value)
        val empty = f.start(); assertEquals(AcademicData(), empty)
        val saved = f.repository.saveExam(null, ExamFields("  虚构线下考试  ", note = "说明\r\n第二行"), empty.revision)
        assertNull(saved.identity); assertEquals(1, saved.exams.size); assertEquals(1L, saved.revision)
        assertEquals("虚构线下考试", saved.exams.single().fields.name)
        assertEquals("说明\n第二行", saved.exams.single().fields.note)
        assertNull(saved.exams.single().original); assertFalse(saved.exams.single().modified)
        assertEquals(saved, AcademicRepository(f.store, f.cipher, Dispatchers.Unconfined).load())
    }

    @Test fun encryptionUsesFreshIvAndModelsDoNotRevealData() = runBlocking {
        val f = Fixture(); val initial = f.start(true)
        val first = f.repository.saveExams(identity, listOf(exam), 1000, initial.revision)
        val envelope = f.store.bytes!!.copyOf()
        assertFalse(envelope.toString(Charsets.ISO_8859_1).contains(identity.studentId))
        val record = first.exams.single()
        val second = f.repository.saveExam(record.id, record.fields.copy(note = "虚构备注"), first.revision)
        assertFalse(envelope.copyOfRange(4, 16).contentEquals(f.store.bytes!!.copyOfRange(4, 16)))
        for (value in listOf(identity, exam, exam.toExamFields(), record, grades(), grades().degree.single(), second)) {
            assertFalse(value.toString().contains("虚构")); assertFalse(value.toString().contains(identity.studentId))
        }
        assertEquals(second, AcademicRepository(f.store, f.cipher, Dispatchers.Unconfined).load())
    }

    @Test fun unchangedSchoolRecordPreservesLocalOverrideAndStableLocalId() = runBlocking {
        val f = Fixture(); f.start(true)
        val imported = f.repository.saveExams(identity, listOf(exam), 1000)
        val record = imported.exams.single()
        val fields = record.fields.copy(location = "自选复习教室", note = "带证件")
        val edited = f.repository.saveExam(record.id, fields, imported.revision)
        val refreshed = f.repository.saveExams(identity, listOf(exam), 2000, edited.revision)
        assertEquals(record.id, refreshed.exams.single().id)
        assertEquals(fields, refreshed.exams.single().fields)
        assertEquals(exam, refreshed.exams.single().original)
        assertTrue(refreshed.exams.single().modified); assertFalse(refreshed.exams.single().retained)
        assertEquals(2000L, refreshed.examUpdatedAt)
    }

    @Test fun sourceChangesDoNotGuessAnIdAndRetainEditedOldRecord() = runBlocking {
        val f = Fixture(); f.start(true)
        val imported = f.repository.saveExams(identity, listOf(exam), 1000)
        val old = imported.exams.single()
        val edited = f.repository.saveExam(old.id, old.fields.copy(note = "保留我的备注"), imported.revision)
        val manual = f.repository.saveExam(null, ExamFields("独立考试"), edited.revision)
        val changed = exam.copy(location = "学校新教室")
        val refreshed = f.repository.saveExams(identity, listOf(changed), 2000)
        assertEquals(3, refreshed.exams.size)
        val retained = refreshed.exams.single { it.id == old.id }
        assertTrue(retained.retained); assertEquals(exam, retained.original); assertTrue(retained.modified)
        assertEquals("保留我的备注", retained.fields.note)
        assertNotEquals(old.id, refreshed.exams.single { it.original == changed }.id)
        assertEquals(manual.exams.last(), refreshed.exams.single { it.original == null })
    }

    @Test fun duplicateRowsRemainDistinctAndNeverInheritAnotherRowsOverride() = runBlocking {
        val f = Fixture(); f.start(true)
        val imported = f.repository.saveExams(identity, listOf(exam, exam), 1000)
        assertEquals(2, imported.exams.map { it.id }.distinct().size)
        val editedId = imported.exams.first().id
        f.repository.saveExam(editedId, imported.exams.first().fields.copy(note = "只修改这一条"), imported.revision)
        val refreshed = f.repository.saveExams(identity, listOf(exam, exam), 2000)
        assertEquals(3, refreshed.exams.size)
        assertEquals(2, refreshed.exams.count { !it.retained && !it.modified })
        assertTrue(refreshed.exams.single { it.id == editedId }.retained)
        assertEquals("只修改这一条", refreshed.exams.single { it.id == editedId }.fields.note)
    }

    @Test fun courseCodeOrNameAloneNeverMergesDifferentExamOccurrences() = runBlocking {
        val f = Fixture(); f.start(true)
        val other = exam.copy(className = "另一个班", start = "2026-12-02 08:00", end = "2026-12-02 10:00")
        val imported = f.repository.saveExams(identity, listOf(exam, other), 1000)
        val first = imported.exams.single { it.original == exam }
        f.repository.saveExam(first.id, first.fields.copy(name = "我的考试名称"), imported.revision)
        val refreshed = f.repository.saveExams(identity, listOf(other, exam), 2000)
        assertEquals(2, refreshed.exams.size)
        assertEquals(first.id, refreshed.exams.single { it.original == exam }.id)
        assertEquals("我的考试名称", refreshed.exams.single { it.original == exam }.fields.name)
        assertFalse(refreshed.exams.single { it.original == other }.modified)
    }

    @Test fun successfulEmptyRefreshRemovesOnlyUnmodifiedSchoolRecords() = runBlocking {
        val f = Fixture(); f.start(true)
        val second = exam.copy(code = "FICTIONAL-102")
        val imported = f.repository.saveExams(identity, listOf(exam, second), 1000)
        val first = imported.exams.first()
        f.repository.saveExam(first.id, first.fields.copy(note = "保留"), imported.revision)
        val refreshed = f.repository.saveExams(identity, emptyList(), 2000)
        assertEquals(1, refreshed.exams.size); assertEquals(first.id, refreshed.exams.single().id)
        assertTrue(refreshed.exams.single().retained); assertEquals(2000L, refreshed.examUpdatedAt)
    }

    @Test fun schoolDeletionIsAHiddenTombstoneWhileManualDeletionRemovesRecord() = runBlocking {
        val f = Fixture(); f.start(true)
        val imported = f.repository.saveExams(identity, listOf(exam), 1000)
        val schoolId = imported.exams.single().id
        val hidden = f.repository.deleteExam(schoolId, imported.revision)
        assertTrue(hidden.exams.single().hidden)
        val refreshed = f.repository.saveExams(identity, listOf(exam), 2000)
        assertTrue(refreshed.exams.single().hidden); assertEquals(schoolId, refreshed.exams.single().id)
        val missing = f.repository.saveExams(identity, emptyList(), 3000)
        assertTrue(missing.exams.single().hidden); assertTrue(missing.exams.single().retained)
        val restored = f.repository.restoreExam(schoolId, missing.revision)
        assertFalse(restored.exams.single().hidden); assertFalse(restored.exams.single().modified)
        assertEquals(exam.toExamFields(), restored.exams.single().fields)
        val manual = f.repository.saveExam(null, ExamFields("手动添加"), restored.revision)
        val removed = f.repository.deleteExam(manual.exams.last().id, manual.revision)
        assertEquals(restored.exams, removed.exams)
    }

    @Test fun restoringSchoolOriginalClearsAllLocalOverrides() = runBlocking {
        val f = Fixture(); f.start(true)
        val imported = f.repository.saveExams(identity, listOf(exam), 1000)
        val record = imported.exams.single()
        val changed = f.repository.saveExam(record.id, ExamFields("改名", location = "改地点", note = "说明"), imported.revision)
        assertTrue(changed.exams.single().modified)
        val restored = f.repository.restoreExam(record.id, changed.revision)
        assertEquals(exam.toExamFields(), restored.exams.single().fields); assertFalse(restored.exams.single().modified)
    }

    @Test fun deletingRetainedLocalCopyLeavesCurrentSchoolAndManualExamsUntouched() = runBlocking {
        val f = Fixture(); f.start(true)
        val imported = f.repository.saveExams(identity, listOf(exam), 1000)
        val original = imported.exams.single()
        val edited = f.repository.saveExam(original.id, original.fields.copy(note = "我的本地修改"), imported.revision)
        f.repository.saveExam(null, ExamFields("独立手动考试"), edited.revision)
        val changed = exam.copy(location = "学校新安排")
        val refreshed = f.repository.saveExams(identity, listOf(changed), 2000)
        val retained = refreshed.exams.single { it.id == original.id }
        assertTrue(retained.retained)
        val remaining = refreshed.exams.filterNot { it.id == retained.id }
        val removed = f.repository.deleteExam(retained.id, refreshed.revision)
        assertEquals(remaining, removed.exams)
        assertEquals(1, removed.exams.count { it.original == changed && !it.hidden })
        assertEquals(1, removed.exams.count { it.original == null })
        val next = f.repository.saveExams(identity, listOf(changed), 3000)
        assertEquals(remaining, next.exams); assertFalse(next.exams.any { it.id == retained.id })
    }

    @Test fun noChangeDoesNotWriteAndStaleDraftCannotOverwriteNewData() = runBlocking {
        val f = Fixture(); val empty = f.start()
        val saved = f.repository.saveExam(null, ExamFields("虚构考试"), empty.revision)
        val writes = f.store.writes
        assertEquals(saved, f.repository.saveExam(saved.exams.single().id, ExamFields("  虚构考试  "), saved.revision))
        assertEquals(writes, f.store.writes)
        expectValidation { f.repository.saveExam(saved.exams.single().id, ExamFields("过期草稿"), empty.revision) }
        assertEquals(saved, f.repository.state.value)
    }

    @Test fun differentSchoolAccountNeverSilentlyRebindsOrSavesItsData() = runBlocking {
        val f = Fixture(); val before = f.start(true); val bytes = f.store.bytes!!.copyOf()
        val other = AcademicIdentity("another-fictional-student")
        expectProblem(AcademicProblem.ACCOUNT_MISMATCH) { f.repository.bind(other, before.revision) }
        expectProblem(AcademicProblem.ACCOUNT_MISMATCH) { f.repository.saveExams(other, listOf(exam), 1000) }
        expectProblem(AcademicProblem.ACCOUNT_MISMATCH) { f.repository.saveGrades(other, grades()) }
        assertArrayEquals(bytes, f.store.bytes); assertEquals(before, f.repository.state.value)
    }

    @Test fun unbindingClearsOnlySchoolInformationAndKeepsManualExams() = runBlocking {
        val f = Fixture(); f.start(true)
        val imported = f.repository.saveExams(identity, listOf(exam), 1000)
        f.repository.saveExam(imported.exams.single().id, imported.exams.single().fields.copy(note = "学校覆盖"), imported.revision)
        val withManual = f.repository.saveExam(null, ExamFields("本地考试"), f.repository.state.value!!.revision)
        val withGrades = f.repository.saveGrades(identity, grades())
        val unbound = f.repository.unbind(withGrades.revision)
        assertNull(unbound.identity); assertNull(unbound.grades); assertNull(unbound.examUpdatedAt)
        assertEquals(listOf(withManual.exams.last()), unbound.exams)
        assertEquals(0, f.keys.clears + f.store.clears)
    }

    @Test fun gradesPreserveOriginalTextRestrictionsAndMissingValues() = runBlocking {
        val f = Fixture(); f.start(true)
        val saved = f.repository.saveGrades(identity, grades())
        val restored = AcademicRepository(f.store, f.cipher, Dispatchers.Unconfined).load()
        assertEquals(saved.grades, restored.grades)
        assertEquals("优秀", restored.grades!!.degree.single().grade)
        assertTrue(restored.grades.elective.single().restricted)
        assertEquals("", restored.grades.elective.single().credits)
        assertEquals("", restored.grades.credits["应修学分"]); assertFalse(restored.grades.credits.containsKey("学位学分"))
    }

    @Test fun malformedAndOversizedDiskNeverOverwriteOrClearPreviousData() = runBlocking {
        val f = Fixture(); val saved = f.start(true)
        val good = f.store.bytes!!.copyOf()
        for (bad in listOf(byteArrayOf(1, 2, 3), ByteArray(ACADEMIC_MAX_BYTES + 1),
            f.cipher.encrypt("{\"schema\":2}".encodeToByteArray()),
            f.cipher.encrypt("{\"password\":\"forbidden\"}".encodeToByteArray()))) {
            f.store.bytes = bad.copyOf()
            expectProblem(AcademicProblem.STORAGE) { f.repository.load() }
            assertArrayEquals(bad, f.store.bytes); assertEquals(saved, f.repository.state.value)
        }
        f.store.bytes = good; f.store.failRead = true
        expectProblem(AcademicProblem.STORAGE) { f.repository.load() }
        assertEquals(0, f.store.clears + f.keys.clears)
    }

    @Test fun missingKeyCannotRegenerateAKeyToOverwriteExistingVault() = runBlocking {
        val f = Fixture(); val saved = f.start(true); val bytes = f.store.bytes!!.copyOf()
        f.keys.key = null
        expectProblem(AcademicProblem.STORAGE) { f.repository.saveExam(null, ExamFields("本地考试"), saved.revision) }
        assertNull(f.keys.key); assertArrayEquals(bytes, f.store.bytes); assertEquals(saved, f.repository.state.value)
    }

    @Test fun failedAtomicWritePreservesDiskMemoryAndRevision() = runBlocking {
        val f = Fixture(); val before = f.start(true); val bytes = f.store.bytes!!.copyOf()
        f.store.failWrite = true
        expectProblem(AcademicProblem.STORAGE) { f.repository.saveExam(null, ExamFields("未保存"), before.revision) }
        assertArrayEquals(bytes, f.store.bytes); assertEquals(before, f.repository.state.value)
    }

    @Test fun externalEncryptedWritePreventsLocalEditAndUndoOverwritingIt() = runBlocking {
        val f = Fixture(); val before = f.start()
        val saved = f.repository.saveExam(null, ExamFields("第一份考试"), before.revision)
        val other = AcademicRepository(f.store, f.cipher, Dispatchers.Unconfined)
        other.load(); val external = other.saveExam(null, ExamFields("另一份考试"), saved.revision)
        val bytes = f.store.bytes!!.copyOf()
        expectProblem(AcademicProblem.STORAGE) { f.repository.restoreSnapshot(saved, before) }
        expectProblem(AcademicProblem.STORAGE) { f.repository.saveExam(null, ExamFields("过期写入"), saved.revision) }
        assertArrayEquals(bytes, f.store.bytes); assertEquals(external, other.state.value)
    }

    @Test fun undoChecksMemoryAndCreatesANewRevision() = runBlocking {
        val f = Fixture(); val before = f.start()
        val saved = f.repository.saveExam(null, ExamFields("新增考试"), before.revision)
        val restored = f.repository.restoreSnapshot(saved, before)
        assertTrue(restored.exams.isEmpty()); assertEquals(saved.revision + 1, restored.revision)
        expectValidation { f.repository.restoreSnapshot(saved, before) }
    }

    @Test fun undoRetiredWhileWaitingForTheRepositoryLockCannotWrite() = runBlocking {
        val f = Fixture(); val before = f.start()
        val saved = f.repository.saveExam(null, ExamFields("仅可短时撤销"), before.revision)
        val acquired = CountDownLatch(1); val release = CountDownLatch(1)
        val current = AtomicBoolean(true)
        f.store.beforeRead = {
            f.store.beforeRead = null
            acquired.countDown()
            check(release.await(5, TimeUnit.SECONDS))
        }
        val blocker = async(Dispatchers.Default) {
            f.repository.saveExam(saved.exams.single().id, saved.exams.single().fields, saved.revision)
        }
        try {
            assertTrue(acquired.await(5, TimeUnit.SECONDS))
            val pending = async(start = CoroutineStart.UNDISPATCHED) {
                f.repository.restoreSnapshot(saved, before) { current.get() }
            }
            assertFalse(pending.isCompleted)
            current.set(false)
            release.countDown()
            assertEquals(saved, blocker.await())
            expectCancelled { pending.await() }
            assertEquals(saved, f.repository.state.value)
            assertEquals(1, f.store.writes)
        } finally { release.countDown() }
    }

    @Test fun invalidLocalDatesAndNamesKeepPreviousData() = runBlocking {
        val f = Fixture(); val before = f.start()
        for (fields in listOf(ExamFields(" "), ExamFields("考试", start = "2026-02-30 08:00"),
            ExamFields("考试", start = "2026-12-01 10:00", end = "2026-12-01 08:00"),
            ExamFields("考试", start = "明天上午"), ExamFields("考试", note = "x".repeat(2001)))) {
            expectValidation { f.repository.saveExam(null, fields, before.revision) }
        }
        assertEquals(before, f.repository.state.value); assertEquals(0, f.store.writes)
        assertEquals(LocalDateTime.of(2026, 12, 1, 8, 0), parseAcademicExamTime("2026/12/1 8:00"))
        assertNull(parseAcademicExamTime("2026-02-30 08:00")); assertNull(parseAcademicExamTime(""))
    }

    @Test fun invalidSchoolRowsAndUnknownSummaryKeysKeepSuccessfulSnapshots() = runBlocking {
        val f = Fixture(); f.start(true)
        val saved = f.repository.saveExams(identity, listOf(exam), 1000); val bytes = f.store.bytes!!.copyOf()
        expectProblem(AcademicProblem.RESPONSE) { f.repository.saveExams(identity, listOf(exam.copy(start = "x".repeat(65))), 2000) }
        expectProblem(AcademicProblem.RESPONSE) { f.repository.saveExams(identity, listOf(exam.copy(end = "2026-12-01 07:00")), 2000) }
        expectProblem(AcademicProblem.RESPONSE) { f.repository.saveExams(identity, listOf(exam.copy(name = "")), 2000) }
        expectProblem(AcademicProblem.RESPONSE) { f.repository.saveGrades(identity, grades().copy(credits = mapOf("秘密字段" to "内容"))) }
        assertEquals(saved, f.repository.state.value); assertArrayEquals(bytes, f.store.bytes)
    }

    @Test fun schoolUnknownTimesRemainOriginalTextAcrossReloadAndRefresh() = runBlocking {
        val f = Fixture(); f.start(true)
        val pending = exam.copy(start = "未定", end = "另行通知")
        val saved = f.repository.saveExams(identity, listOf(pending), 1000)
        val record = saved.exams.single()
        assertEquals(pending, record.original); assertEquals("未定", record.fields.start)
        assertEquals("另行通知", record.fields.end); assertNull(parseAcademicExamTime(record.fields.start))
        assertEquals(saved, AcademicRepository(f.store, f.cipher, Dispatchers.Unconfined).load())
        val refreshed = f.repository.saveExams(identity, listOf(pending), 2000)
        assertEquals(record.id, refreshed.exams.single().id); assertFalse(refreshed.exams.single().modified)
    }

    @Test fun schoolTimeTextMayBeKeptWhileOtherFieldsAreEditedButNotInvented() = runBlocking {
        val f = Fixture(); f.start(true)
        val pending = exam.copy(start = "未定", end = "待安排")
        val imported = f.repository.saveExams(identity, listOf(pending), 1000)
        val record = imported.exams.single()
        val edited = f.repository.saveExam(record.id, record.fields.copy(location = "我的教室", note = "待确认"), imported.revision)
        assertTrue(edited.exams.single().modified); assertEquals("未定", edited.exams.single().fields.start)
        expectValidation { f.repository.saveExam(record.id, edited.exams.single().fields.copy(start = "明天上午"), edited.revision) }
        expectValidation { f.repository.saveExam(record.id, edited.exams.single().fields.copy(end = "未定"), edited.revision) }
        val timed = f.repository.saveExam(record.id, edited.exams.single().fields.copy(start = "2026-12-02 09:00"), edited.revision)
        assertEquals("待安排", timed.exams.single().fields.end)
        val restored = f.repository.restoreExam(record.id, timed.revision)
        assertEquals(pending.toExamFields(), restored.exams.single().fields); assertFalse(restored.exams.single().modified)
    }

    @Test fun persistedSchoolOverridesCannotIntroduceUnrecognizedNewTimeText() = runBlocking {
        val f = Fixture(); f.start(true)
        val saved = f.repository.saveExams(identity, listOf(exam.copy(start = "未定", end = "未定")), 1000)
        val invalid = saved.copy(exams = saved.exams.map { it.copy(fields = it.fields.copy(start = "新的不合法时间")) })
        f.store.bytes = f.cipher.encrypt(Json.encodeToString(invalid).encodeToByteArray(), requireExistingKey = true)
        val bytes = f.store.bytes!!.copyOf()
        expectProblem(AcademicProblem.STORAGE) { f.repository.load() }
        assertEquals(saved, f.repository.state.value); assertArrayEquals(bytes, f.store.bytes)
    }

    @Test fun oversizedValidSnapshotPreservesPreviousVault() = runBlocking {
        val f = Fixture(); val before = f.start(true); val bytes = f.store.bytes!!.copyOf()
        val large = (1..2000).map { exam.copy(code = "EXAM-$it", name = "名".repeat(300), className = "班".repeat(300), location = "地".repeat(500)) }
        expectProblem(AcademicProblem.STORAGE) { f.repository.saveExams(identity, large, 1000) }
        assertEquals(before, f.repository.state.value); assertArrayEquals(bytes, f.store.bytes)
    }

    @Test fun retiredNetworkTasksCannotBindOrReadOrWrite() = runBlocking {
        val f = Fixture(); val before = f.start(); val reads = f.store.reads
        expectCancelled { f.repository.bind(identity, before.revision) { false } }
        expectCancelled { f.repository.saveExams(identity, listOf(exam), 1000, isCurrent = { false }) }
        expectCancelled { f.repository.saveGrades(identity, grades(), isCurrent = { false }) }
        assertEquals(reads, f.store.reads); assertEquals(0, f.store.writes); assertNull(f.keys.key)
    }

    @Test fun retirementDuringEncryptionCannotCommitAnOldBindingOrQuery() = runBlocking {
        val f = Fixture(); val before = f.start(); var current = true
        val cipher = object : VaultCipher by f.cipher {
            override fun encrypt(plaintext: ByteArray, requireExistingKey: Boolean): ByteArray =
                f.cipher.encrypt(plaintext, requireExistingKey).also { current = false }
        }
        val repository = AcademicRepository(f.store, cipher, Dispatchers.Unconfined); repository.load()
        expectCancelled { repository.bind(identity, before.revision) { current } }
        assertNull(f.store.bytes); assertEquals(before, repository.state.value); assertEquals(0, f.store.writes)
    }

    @Test fun externalWriteDuringEncryptionIsRecheckedBeforeCommit() = runBlocking {
        val f = Fixture(); val before = f.start(true)
        val external = before.copy(identity = identity.copy(name = "另一处更新"), revision = before.revision + 1)
        val externalBytes = f.cipher.encrypt(Json.encodeToString(external).encodeToByteArray(), requireExistingKey = true)
        val cipher = object : VaultCipher by f.cipher {
            override fun encrypt(plaintext: ByteArray, requireExistingKey: Boolean): ByteArray =
                f.cipher.encrypt(plaintext, requireExistingKey).also { f.store.bytes = externalBytes.copyOf() }
        }
        val repository = AcademicRepository(f.store, cipher, Dispatchers.Unconfined); repository.load()
        expectProblem(AcademicProblem.STORAGE) { repository.saveExam(null, ExamFields("过期编辑"), before.revision) }
        assertArrayEquals(externalBytes, f.store.bytes); assertEquals(before, repository.state.value)
    }
}
