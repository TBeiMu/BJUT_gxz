package io.github.tbeimu.gongxiaozhu.academic

import io.github.tbeimu.gongxiaozhu.data.AesGcmVaultCipher
import io.github.tbeimu.gongxiaozhu.data.VaultKeySource
import io.github.tbeimu.gongxiaozhu.data.VaultStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test
import javax.crypto.spec.SecretKeySpec

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class AcademicControllerTest {
    private class Memory : VaultStore {
        var bytes: ByteArray? = null
        var failWrite = false
        var failClear = false
        var beforeRead: () -> Unit = {}
        var beforeWrite: () -> Unit = {}
        override fun read(): ByteArray? {
            beforeRead()
            return bytes?.clone()
        }
        override fun write(bytes: ByteArray) {
            beforeWrite()
            if (failWrite) error("Fictional write failure")
            this.bytes = bytes.clone()
        }
        override fun clear() { if (failClear) error("Fictional clear failure"); bytes = null }
    }
    private class Session : AcademicSession {
        var submitted = 0; var closes = 0; var examCalls = 0; var gradeCalls = 0
        var failExams = false; var failGrades = false
        var blockOpen = false; var cleanupFailure = false
        var waiting: CompletableDeferred<Unit>? = null
        var examWaiting: CompletableDeferred<Unit>? = null
        var gradeWaiting: CompletableDeferred<Unit>? = null
        var cleanup: CompletableDeferred<Unit>? = null
        var identity = AcademicIdentity("fictional-2026", "示例同学")
        override suspend fun open(): AcademicLoginPage {
            if (blockOpen) awaitCancellation()
            return AcademicLoginPage("fictional-token", byteArrayOf(1))
        }
        override suspend fun refreshCaptcha(token: String) = open()
        override suspend fun submit(token: String, username: String, password: String, captcha: String): AcademicIdentity {
            submitted++
            waiting?.let { withContext(NonCancellable) { it.await() } }
            return identity
        }
        override suspend fun exams(): List<SchoolExam> { examCalls++
            examWaiting?.let { withContext(NonCancellable) { it.await(); if (failExams) throw AcademicException(AcademicProblem.RESPONSE) } }
            if (failExams) throw AcademicException(AcademicProblem.RESPONSE)
            return listOf(SchoolExam("fictional-code", "示例考试", "示例班级", "2026-12-01 09:00", "2026-12-01 11:00", "示例教室")) }
        override suspend fun grades(now: Long): GradeSnapshot { gradeCalls++
            gradeWaiting?.let { withContext(NonCancellable) { it.await(); if (failGrades) throw AcademicException(AcademicProblem.RESPONSE) } }
            if (failGrades) throw AcademicException(AcademicProblem.RESPONSE)
            return GradeSnapshot(listOf(GradeRecord("示例课程", "2", "示例学期", "优秀")), emptyList(), mapOf("已修学分" to "2"), now) }
        override fun close() { closes++ }
        override suspend fun awaitClosed() { if (cleanupFailure) error("fictional cleanup"); cleanup?.await() }
    }
    private class Fixture(scope: TestScope, val session: Session = Session(), route: Boolean = true, immediate: Boolean = false) {
        val store = Memory()
        val credentialStore = Memory()
        val credentials = AcademicCredentialsRepository(credentialStore, AesGcmVaultCipher(object : VaultKeySource {
            private val key = SecretKeySpec(ByteArray(32) { 11 }, "AES")
            override fun encryptionKey() = key
            override fun decryptionKey() = key
            override fun clear() = Unit
        }))
        var defaultUsername = ""
        val dispatcher = if (immediate) UnconfinedTestDispatcher(scope.testScheduler) else StandardTestDispatcher(scope.testScheduler)
        val repository = AcademicRepository(store, AesGcmVaultCipher(object : VaultKeySource {
            private val key = SecretKeySpec(ByteArray(32) { 7 }, "AES")
            override fun encryptionKey() = key
            override fun decryptionKey() = key
            override fun clear() = Unit
        }), dispatcher)
        var created = 0
        var elapsed = 100L
        val routes = MutableStateFlow(if (route) AcademicConnection("network-one", "WiFi") { created++; session } else null)
        private val controllerScope = CoroutineScope(scope.backgroundScope.coroutineContext + dispatcher)
        val controller = AcademicController(controllerScope, repository, routes, dispatcher, { 1000L }, { elapsed },
            credentials, { defaultUsername })
        init { controller.enterForeground() }
        fun start(target: AcademicQueryTarget = AcademicQueryTarget.EXAMS) = controller.start(target)
        fun submit() = controller.submit("fictional-token", "fictional-2026", "fictional-password", "test")
    }

    @Test fun offlineDoesNotOpenSessionButManualExamsRemainAvailable() = runTest {
        val f = Fixture(this, route = false); runCurrent()
        f.start(); runCurrent()
        assertEquals(0, f.created)
        f.controller.saveExam(null, ExamFields("本地考试"), 0); runCurrent()
        assertEquals("本地考试", f.controller.state.value.data.exams.single().fields.name)
        assertNull(f.controller.state.value.data.identity)
    }
    @Test fun explicitLoginReadsOnlyTheSelectedProjectAndDoesNotResubmitCredentials() = runTest {
        val f = Fixture(this); runCurrent(); f.start(); runCurrent()
        assertFalse(f.controller.state.value.busy)
        assertNotNull(f.controller.state.value.login)
        f.submit(); f.submit(); runCurrent()
        val state = f.controller.state.value
        assertEquals(1, f.session.submitted); assertEquals(1, f.session.examCalls); assertEquals(0, f.session.gradeCalls)
        assertEquals(1, state.data.exams.size); assertNull(state.data.grades)
        assertFalse(state.busy); assertNull(state.login)
        val before = state.data.exams
        f.start(AcademicQueryTarget.GRADES); runCurrent(); f.submit(); runCurrent()
        assertEquals(1, f.session.examCalls); assertEquals(1, f.session.gradeCalls)
        assertEquals(before, f.controller.state.value.data.exams)
        assertEquals("优秀", f.controller.state.value.data.grades!!.degree.single().grade)
    }
    @Test fun examFailureKeepsTheExistingGradeSnapshotAndDoesNotQueryGradesAgain() = runTest {
        val f = Fixture(this); runCurrent(); f.start(AcademicQueryTarget.GRADES); runCurrent(); f.submit(); runCurrent()
        val before = f.controller.state.value.data
        f.session.failExams = true; f.start(); runCurrent(); f.submit(); runCurrent()
        assertNull(f.controller.state.value.data.examUpdatedAt)
        assertNotNull(f.controller.state.value.data.grades)
        assertEquals(before, f.controller.state.value.data)
        assertEquals("更新成功", f.controller.state.value.gradeMessage)
        assertEquals(1, f.session.gradeCalls)
        assertEquals("查询失败", f.controller.state.value.examMessage)
    }
    @Test fun failureInGradesPreservesExamSuccessAndOldGradeTimestamp() = runTest {
        val f = Fixture(this); runCurrent(); f.start(); runCurrent(); f.submit(); runCurrent()
        f.start(AcademicQueryTarget.GRADES); runCurrent(); f.submit(); runCurrent()
        val before = f.controller.state.value.data
        f.session.failGrades = true; f.start(AcademicQueryTarget.GRADES); runCurrent(); f.submit(); runCurrent()
        assertEquals(before, f.controller.state.value.data)
        assertEquals("更新成功", f.controller.state.value.examMessage)
        assertEquals(1, f.session.examCalls)
    }
    @Test fun leavingForegroundInvalidatesLateIdentityAndHoldsSlotUntilCleanup() = runTest {
        val f = Fixture(this); f.session.waiting = CompletableDeferred(); f.session.cleanup = CompletableDeferred()
        runCurrent(); f.start(); runCurrent(); f.submit(); runCurrent()
        f.controller.leaveForeground(); runCurrent()
        f.session.waiting!!.complete(Unit); runCurrent()
        assertNull(f.controller.state.value.data.identity)
        assertTrue(f.controller.state.value.busy)
        f.controller.enterForeground(); runCurrent(); f.start(); runCurrent()
        assertEquals(1, f.created)
        f.session.cleanup!!.complete(Unit); runCurrent()
        assertFalse(f.controller.state.value.busy)
        assertNull(f.store.bytes)
    }
    @Test fun networkChangeDropsOldSubmissionWithoutAutomaticRetry() = runTest {
        val f = Fixture(this); f.session.waiting = CompletableDeferred()
        runCurrent(); f.start(); runCurrent(); f.submit(); runCurrent()
        f.routes.value = AcademicConnection("network-two", "移动数据") { f.session }; runCurrent()
        f.session.waiting!!.complete(Unit); runCurrent()
        assertNull(f.controller.state.value.data.identity); assertEquals(1, f.session.submitted)
        assertNull(f.controller.state.value.login); assertFalse(f.controller.state.value.busy)
    }
    @Test fun lateExamFailureCannotOverwriteForegroundCancellationOrStartGradeQuery() = runTest {
        val f = Fixture(this); f.session.examWaiting = CompletableDeferred(); f.session.failExams = true
        runCurrent(); f.start(); runCurrent(); f.submit(); runCurrent()
        assertEquals("正在查询", f.controller.state.value.examMessage)
        f.controller.leaveForeground(); runCurrent()
        f.session.examWaiting!!.complete(Unit); runCurrent()
        assertEquals("已暂停查询，返回后请手动重新登录", f.controller.state.value.message)
        assertNull(f.controller.state.value.examMessage); assertNull(f.controller.state.value.gradeMessage)
        assertEquals(0, f.session.gradeCalls); assertFalse(f.controller.state.value.busy)
    }
    @Test fun lateGradeFailureCannotReplaceNetworkChangeWithOldAggregateResult() = runTest {
        val f = Fixture(this); f.session.gradeWaiting = CompletableDeferred(); f.session.failGrades = true
        runCurrent(); f.start(); runCurrent(); f.submit(); runCurrent()
        f.start(AcademicQueryTarget.GRADES); runCurrent(); f.submit(); runCurrent()
        assertEquals("更新成功", f.controller.state.value.examMessage)
        assertEquals("正在查询", f.controller.state.value.gradeMessage)
        f.routes.value = AcademicConnection("network-two", "移动数据") { f.session }; runCurrent()
        f.session.gradeWaiting!!.complete(Unit); runCurrent()
        assertEquals("网络已变化，请手动重新登录", f.controller.state.value.message)
        assertEquals("更新成功", f.controller.state.value.examMessage); assertNull(f.controller.state.value.gradeMessage)
        assertNull(f.controller.state.value.data.grades); assertFalse(f.controller.state.value.busy)
    }
    @Test fun differentStudentCannotReplaceBindingOrManualExams() = runTest {
        val f = Fixture(this); runCurrent(); f.start(); runCurrent(); f.submit(); runCurrent()
        val before = f.controller.state.value.data
        f.session.identity = AcademicIdentity("fictional-other")
        f.start(); runCurrent(); f.submit(); runCurrent()
        assertEquals(before, f.controller.state.value.data)
        assertEquals(f.session.identity, f.controller.state.value.pendingIdentity)
        f.controller.cancel(); runCurrent()
        assertEquals(before, f.controller.state.value.data)
        assertNull(f.controller.state.value.pendingIdentity)
    }

    @Test fun confirmedAccountChangeCommitsOnlyAfterACompleteResultAndKeepsCustomExams() = runTest {
        val f = Fixture(this); runCurrent()
        f.controller.saveExam(null, ExamFields("自定义示例"), 0); runCurrent()
        f.start(); runCurrent(); f.submit(); runCurrent()
        f.start(AcademicQueryTarget.GRADES); runCurrent(); f.submit(); runCurrent()
        val before = f.controller.state.value.data
        f.session.identity = AcademicIdentity("fictional-other")
        f.start(); runCurrent(); f.submit(); runCurrent()
        assertEquals(before, f.controller.state.value.data)
        f.session.examWaiting = CompletableDeferred()
        f.controller.confirmAccountChange(); f.controller.confirmAccountChange(); runCurrent()
        assertEquals(before, f.controller.state.value.data)
        f.session.examWaiting!!.complete(Unit); runCurrent()
        val after = f.controller.state.value.data
        assertEquals("fictional-other", after.identity!!.studentId)
        assertNull(after.grades)
        assertEquals(before.exams.filter { it.original == null }, after.exams.filter { it.original == null })
        assertEquals(2, f.session.examCalls)
    }

    @Test fun failedOrCancelledAccountChangeLeavesOldDiskAndIdentityIntact() = runTest {
        val f = Fixture(this); runCurrent(); f.start(); runCurrent(); f.submit(); runCurrent()
        val before = f.controller.state.value.data; val bytes = f.store.bytes!!.clone()
        f.session.identity = AcademicIdentity("fictional-other"); f.session.failExams = true
        f.start(); runCurrent(); f.submit(); runCurrent(); f.controller.confirmAccountChange(); runCurrent()
        assertEquals(before, f.controller.state.value.data); assertArrayEquals(bytes, f.store.bytes)
        assertFalse(f.controller.state.value.busy)
    }

    @Test fun cancellingBeforeAtomicAccountChangeKeepsOldSchoolDataAndPassword() = runTest {
        val f = Fixture(this)
        runCurrent()
        f.start()
        runCurrent()
        f.submit()
        runCurrent()
        val before = f.controller.state.value.data
        val oldPassword = AcademicCredentials("fictional-2026", "fictional-2026", "old-fictional-password")
        f.credentials.save(oldPassword)
        f.session.identity = AcademicIdentity("fictional-other")
        f.start(AcademicQueryTarget.GRADES)
        runCurrent()
        f.controller.submit("fictional-token", "fictional-other", "new-fictional-password", "test")
        runCurrent()
        var diskReads = 0
        f.store.beforeRead = {
            if (++diskReads == 2) f.controller.cancel() // During the final disk recheck.
        }
        f.controller.confirmAccountChange()
        runCurrent()
        assertEquals(2, diskReads)
        assertEquals(before, f.repository.load())
        assertEquals(oldPassword, f.credentials.load())
        assertFalse(f.controller.state.value.busy)
        assertFalse(f.controller.state.value.committing)
        assertNull(f.controller.state.value.gradeMessage)
    }

    @Test fun lateCancellationCannotInterruptAccountChangeOrItsRememberedPassword() = runTest {
        val f = Fixture(this)
        runCurrent()
        f.controller.saveExam(null, ExamFields("自定义示例"), 0)
        runCurrent()
        f.start()
        runCurrent()
        f.submit()
        runCurrent()
        f.credentials.save(AcademicCredentials("fictional-2026", "fictional-2026", "old-fictional-password"))
        f.session.identity = AcademicIdentity("fictional-other")
        f.defaultUsername = "fictional-other"
        f.start(AcademicQueryTarget.GRADES)
        runCurrent()
        f.controller.updateLoginDraft(f.controller.state.value.loginDraft!!.copy(rememberPassword = true))
        f.controller.submit("fictional-token", "fictional-other", "new-fictional-password", "test")
        runCurrent()
        var writes = 0
        f.store.beforeWrite = {
            assertTrue(f.controller.state.value.committing)
            f.controller.cancel()
            writes++
        }
        f.credentialStore.beforeWrite = {
            assertTrue(f.controller.state.value.committing)
            assertEquals("成绩导入成功", f.controller.state.value.message)
            f.controller.cancel()
            f.controller.leaveForeground()
            writes++
        }
        f.controller.confirmAccountChange()
        runCurrent()

        val after = f.repository.load()
        assertEquals(2, writes)
        assertEquals(f.session.identity, after.identity)
        assertEquals("优秀", after.grades!!.degree.single().grade)
        assertEquals("自定义示例", after.exams.single { it.original == null }.fields.name)
        assertEquals(1, after.exams.size)
        assertEquals(AcademicCredentials("fictional-other", "fictional-other", "new-fictional-password"), f.credentials.load())
        assertEquals("成绩导入成功", f.controller.state.value.message)
        assertFalse(f.controller.state.value.busy)
        assertFalse(f.controller.state.value.committing)
    }

    @Test fun queryTimeoutKeepsThePreviousSnapshotAndSkipsLocalCommit() = runTest {
        val f = Fixture(this)
        runCurrent()
        f.start()
        runCurrent()
        f.submit()
        runCurrent()
        val before = f.controller.state.value.data
        f.session.examWaiting = CompletableDeferred()
        f.start()
        runCurrent()
        f.submit()
        runCurrent()
        advanceTimeBy(90_001)
        f.session.examWaiting!!.complete(Unit)
        runCurrent()
        assertEquals(before, f.repository.load())
        assertEquals("查询超时，原有资料保留，请重试", f.controller.state.value.message)
        assertFalse(f.controller.state.value.committing)
    }

    @Test fun defaultsFollowTheCurrentCampusUsernameWithoutCampusPasswordAndDraftSurvivesCaptchaRefresh() = runTest {
        val f = Fixture(this); runCurrent(); f.start(); runCurrent()
        assertEquals(AcademicLoginDraft(), f.controller.state.value.loginDraft)
        f.controller.cancel(); runCurrent(); f.defaultUsername = "  fictional-2026  "
        f.start(); runCurrent()
        assertEquals("fictional-2026", f.controller.state.value.loginDraft!!.username)
        assertEquals("", f.controller.state.value.loginDraft!!.password)
        f.controller.updateLoginDraft(f.controller.state.value.loginDraft!!.copy(password = "fictional-secret", captcha = "old", rememberPassword = true))
        f.controller.refreshCaptcha("fictional-token"); runCurrent()
        assertEquals(AcademicLoginDraft("fictional-2026", "fictional-secret", rememberPassword = true), f.controller.state.value.loginDraft)
        f.controller.leaveForeground(); runCurrent()
        assertNull(f.controller.state.value.loginDraft); assertNull(f.credentialStore.bytes)
    }

    @Test fun rememberingIsOptInSharedByBothPagesAndUncheckingClearsItImmediately() = runTest {
        val f = Fixture(this); f.defaultUsername = "fictional-2026"; runCurrent()
        f.start(); runCurrent(); f.submit(); runCurrent(); assertNull(f.credentialStore.bytes)
        f.start(); runCurrent()
        f.controller.updateLoginDraft(f.controller.state.value.loginDraft!!.copy(rememberPassword = true))
        f.submit(); runCurrent()
        assertEquals("fictional-password", f.credentials.load()!!.password)
        f.start(AcademicQueryTarget.GRADES); runCurrent()
        assertEquals("fictional-password", f.controller.state.value.loginDraft!!.password)
        assertTrue(f.controller.state.value.loginDraft!!.rememberPassword)
        f.controller.updateLoginDraft(f.controller.state.value.loginDraft!!.copy(rememberPassword = false)); runCurrent()
        assertNull(f.credentials.load())
        assertEquals("fictional-password", f.controller.state.value.loginDraft!!.password)
        f.controller.cancel(); runCurrent()
    }

    @Test fun changingUsernameCannotCarryAnotherAccountsPasswordOrRememberChoice() = runTest {
        val f = Fixture(this); runCurrent()
        f.credentials.save(AcademicCredentials("fictional-2026", "fictional-2026", "remembered-secret"))
        f.defaultUsername = "fictional-2026"; f.start(); runCurrent()
        f.controller.updateLoginDraft(f.controller.state.value.loginDraft!!.copy(username = "fictional-other"))
        assertEquals("", f.controller.state.value.loginDraft!!.password)
        assertFalse(f.controller.state.value.loginDraft!!.rememberPassword)
        f.controller.cancel(); runCurrent()
        f.defaultUsername = ""; f.start(); runCurrent()
        assertEquals("", f.controller.state.value.loginDraft!!.username)
        assertEquals("", f.controller.state.value.loginDraft!!.password)
    }

    @Test fun failedCredentialWriteDoesNotBlockQueryAndUnbindClearsRememberedCredentials() = runTest {
        val f = Fixture(this); f.defaultUsername = "fictional-2026"; runCurrent(); f.start(); runCurrent()
        f.controller.updateLoginDraft(f.controller.state.value.loginDraft!!.copy(rememberPassword = true))
        f.credentialStore.failWrite = true; f.submit(); runCurrent()
        assertNotNull(f.controller.state.value.data.examUpdatedAt)
        assertFalse(f.controller.state.value.blocked)
        assertEquals("考试导入成功", f.controller.state.value.message)
        assertEquals("教务密码未能保存，请重试", f.controller.state.value.credentialMessage)
        assertNull(f.credentials.load())
        f.credentialStore.failWrite = false
        f.credentials.save(AcademicCredentials("fictional-2026", "fictional-2026", "remembered-secret"))
        f.controller.unbind(f.controller.state.value.data.revision); runCurrent()
        assertNull(f.controller.state.value.data.identity); assertNull(f.credentials.load())
    }

    @Test fun cancellingAfterLoginCannotPersistAFormerTasksPasswordOrQueryResult() = runTest {
        val f = Fixture(this); f.defaultUsername = "fictional-2026"; runCurrent(); f.start(); runCurrent()
        f.controller.updateLoginDraft(f.controller.state.value.loginDraft!!.copy(rememberPassword = true))
        f.session.examWaiting = CompletableDeferred(); f.submit(); runCurrent()
        f.controller.cancel(); runCurrent(); f.session.examWaiting!!.complete(Unit); runCurrent()
        assertNull(f.store.bytes); assertNull(f.credentialStore.bytes)
        assertNull(f.controller.state.value.loginDraft); assertNull(f.controller.state.value.request)
        assertFalse(f.controller.state.value.busy)
    }

    @Test fun corruptPasswordStorageStillAllowsManualImportWithoutReplacingTheDamagedFile() = runTest {
        val f = Fixture(this); val damaged = ByteArray(40) { 1 }; f.credentialStore.bytes = damaged.clone()
        f.defaultUsername = "fictional-2026"; runCurrent(); assertFalse(f.controller.state.value.blocked)
        f.start(); runCurrent(); assertEquals("", f.controller.state.value.loginDraft!!.password)
        f.controller.updateLoginDraft(f.controller.state.value.loginDraft!!.copy(rememberPassword = true))
        f.submit(); runCurrent()
        assertNotNull(f.controller.state.value.data.examUpdatedAt)
        assertFalse(f.controller.state.value.blocked); assertNotNull(f.controller.state.value.credentialMessage)
        assertArrayEquals(damaged, f.credentialStore.bytes)
        f.controller.forgetPassword(); runCurrent(); assertNull(f.credentialStore.bytes)
    }

    @Test fun blockedAcademicStorageStillLetsTheUserClearTheIndependentPassword() = runTest {
        val f = Fixture(this)
        val damaged = byteArrayOf(42)
        f.store.bytes = damaged.clone()
        f.credentials.save(AcademicCredentials("fictional-2026", "fictional-2026", "remembered-secret"))
        runCurrent()
        assertTrue(f.controller.state.value.blocked)
        assertEquals("fictional-2026", f.controller.state.value.rememberedAccount)
        f.credentialStore.failClear = true
        f.controller.forgetPassword()
        runCurrent()
        assertNotNull(f.credentials.load())
        assertEquals("密码未能清除，请重试", f.controller.state.value.credentialMessage)
        f.credentialStore.failClear = false
        f.controller.forgetPassword()
        runCurrent()
        assertNull(f.credentials.load())
        assertNull(f.controller.state.value.rememberedAccount)
        assertEquals("已清除记住的教务密码", f.controller.state.value.message)
        assertTrue(f.controller.state.value.blocked)
        assertArrayEquals(damaged, f.store.bytes)
    }

    @Test fun failedPasswordDeletionCannotClaimUnboundOrClearSchoolData() = runTest {
        val f = Fixture(this); runCurrent(); f.start(); runCurrent(); f.submit(); runCurrent()
        val before = f.controller.state.value.data
        f.credentials.save(AcademicCredentials("fictional-2026", "fictional-2026", "remembered-secret"))
        f.credentialStore.failClear = true
        f.controller.unbind(before.revision); runCurrent()
        assertEquals(before, f.controller.state.value.data); assertNotNull(f.credentials.load())
        assertFalse(f.controller.state.value.blocked); assertFalse(f.controller.state.value.saving)
        assertTrue(f.controller.state.value.message!!.contains("密码未能清除"))
    }
    @Test fun onlyRealExistingEditGetsUndoAndUndoChecksExpiry() = runTest {
        val f = Fixture(this); runCurrent()
        f.controller.saveExam(null, ExamFields("本地考试"), 0); runCurrent()
        assertNull(f.controller.state.value.undo)
        var data = f.controller.state.value.data; val id = data.exams.single().id
        f.controller.saveExam(id, ExamFields("调整考试"), data.revision); runCurrent()
        val token = f.controller.state.value.undo!!.token
        assertEquals(10_000L, f.controller.undoRemaining(token, 10_000))
        f.controller.undo(token); runCurrent()
        assertEquals("本地考试", f.controller.state.value.data.exams.single().fields.name)
        data = f.controller.state.value.data
        f.controller.deleteExam(id, data.revision); runCurrent()
        val expired = f.controller.state.value.undo!!.token
        f.controller.undoRemaining(expired, 10_000); f.elapsed += 10_001
        f.controller.undo(expired); runCurrent()
        assertTrue(f.controller.state.value.data.exams.isEmpty())
    }
    @Test fun invalidDraftDoesNotCloseEditorOrChangeOriginalData() = runTest {
        val f = Fixture(this); runCurrent()
        f.controller.saveExam(null, ExamFields(""), 0); runCurrent()
        assertEquals(0L, f.controller.state.value.editCompleted)
        assertTrue(f.controller.state.value.data.exams.isEmpty())
        assertNotNull(f.controller.state.value.message); assertFalse(f.controller.state.value.saving)
    }
    @Test fun immediateCancellationCannotRepublishBusyAfterTaskFinished() = runTest {
        val session = Session().apply { blockOpen = true }
        val f = Fixture(this, session, immediate = true); runCurrent()
        f.start(); assertTrue(f.controller.state.value.busy)
        f.controller.cancel(); runCurrent()
        assertFalse(f.controller.state.value.busy)
        session.blockOpen = false; f.start(); runCurrent()
        assertEquals(2, f.created); assertNotNull(f.controller.state.value.login)
        f.controller.cancel(); runCurrent()
    }
    @Test fun cleanupFailureKeepsSlotUntilUserRetriesAndCleanupActuallyCompletes() = runTest {
        val f = Fixture(this); runCurrent(); f.start(); runCurrent()
        f.session.cleanupFailure = true; f.controller.cancel(); runCurrent()
        assertTrue(f.controller.state.value.busy)
        f.start(); runCurrent(); assertEquals(1, f.created)
        f.session.cleanupFailure = false; f.controller.cancel(); runCurrent()
        assertFalse(f.controller.state.value.busy)
    }
    @Test fun undoExpiredAfterClickButBeforeIoCannotWrite() = runTest {
        val f = Fixture(this); runCurrent(); f.controller.saveExam(null, ExamFields("原考试"), 0); runCurrent()
        var data = f.controller.state.value.data; val id = data.exams.single().id
        f.controller.saveExam(id, ExamFields("新考试"), data.revision); runCurrent()
        data = f.controller.state.value.data
        val token = f.controller.state.value.undo!!.token
        f.controller.undoRemaining(token, 10_000)
        f.controller.undo(token); f.elapsed += 10_001; runCurrent()
        assertEquals(data, f.controller.state.value.data)
        assertFalse(f.controller.state.value.saving)
    }
    @Test fun undoEnteringBackgroundBeforeIoCannotRestoreOldData() = runTest {
        val f = Fixture(this); runCurrent(); f.controller.saveExam(null, ExamFields("原考试"), 0); runCurrent()
        var data = f.controller.state.value.data; val id = data.exams.single().id
        f.controller.deleteExam(id, data.revision); runCurrent(); data = f.controller.state.value.data
        val token = f.controller.state.value.undo!!.token
        f.controller.undoRemaining(token, 10_000)
        f.controller.undo(token); f.controller.leaveForeground(); runCurrent()
        assertEquals(data, f.controller.state.value.data)
        assertFalse(f.controller.state.value.saving)
    }
}
