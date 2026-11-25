package io.github.tbeimu.gongxiaozhu.ui

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import io.github.tbeimu.gongxiaozhu.academic.*
import java.io.ByteArrayOutputStream
import java.io.File
import java.time.LocalDateTime
import java.time.ZoneId
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.captureScreenRoboImage
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w393dp-h852dp-xxhdpi")
class AcademicUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val now = LocalDateTime.of(2026, 11, 25, 12, 0)
    private fun record(id: String, start: String = "", end: String = "") =
        ExamRecord(id, fields = ExamFields("示例考试$id", start, end))

    @Test fun offlineAllowsManualExamAndSavesNormalizedFieldsWithRevision() {
        var saved: Triple<String?, ExamFields, Long>? = null
        var editStarts = 0
        compose.setContent { GongXiaoZhuTheme { AcademicContentBody(false,
            AcademicUiState(loading = false, data = AcademicData(revision = 7)),
            AcademicActions(beginEdit = { editStarts++ }, saveExam = { id, fields, revision -> saved = Triple(id, fields, revision) }),
            Modifier.fillMaxSize(), now) } }
        compose.onNodeWithTag("academic_refresh").assertIsNotEnabled()
        compose.onNodeWithTag("academic_add_exam").performScrollTo().assertIsEnabled().performClick()
        compose.onNodeWithTag("save_exam").assertIsNotEnabled()
        compose.onNodeWithTag("exam_name").performTextInput("  示例手动考试  ")
        compose.onNodeWithTag("save_exam").performClick()
        assertEquals(1, editStarts)
        assertNull(saved?.first)
        assertEquals(ExamFields("示例手动考试"), saved?.second)
        assertEquals(7L, saved?.third)
    }

    @Test fun examFiltersKeepUnscheduledSeparateAndUseSuppliedShanghaiSnapshot() {
        val exams = listOf(record("past", "2026-11-24 09:00", "2026-11-24 11:00"),
            record("next", "2026-11-26 09:00"), record("unset"))
        compose.setContent { GongXiaoZhuTheme { AcademicContentBody(false,
            AcademicUiState(loading = false, data = AcademicData(exams = exams)), AcademicActions(), Modifier.fillMaxSize(), now) } }
        compose.onNodeWithTag("exam_filter_UPCOMING").performScrollTo().performClick()
        compose.onNodeWithTag("exam_next").assertExists()
        compose.onNodeWithTag("exam_past").assertDoesNotExist()
        compose.onNodeWithTag("exam_unset").assertDoesNotExist()
        compose.onNodeWithTag("exam_filter_FINISHED").performClick()
        compose.onNodeWithTag("exam_past").assertExists()
        compose.onNodeWithTag("exam_next").assertDoesNotExist()
        compose.onNodeWithTag("exam_filter_UNSET").performClick()
        compose.onNodeWithTag("exam_unset").assertExists()
        compose.onNodeWithTag("exam_past").assertDoesNotExist()
        compose.onAllNodesWithText("时间未定").assertCountEquals(2)
    }

    @Test fun startedExamsWithUnknownEndStayUndeterminedInsteadOfBeingMarkedFinished() {
        val blankEnd = record("unknown-blank", "2026-11-25 09:00")
        val schoolEnd = SchoolExam("fixture-unknown-end", "学校结束时间待通知", "虚构教学班",
            "2026-11-25 10:00", "另行通知", "示例教室")
        val exams = listOf(blankEnd, ExamRecord("unknown-school", schoolEnd, schoolEnd.toExamFields()))
        compose.setContent { GongXiaoZhuTheme { AcademicContentBody(false,
            AcademicUiState(loading = false, data = AcademicData(exams = exams)), AcademicActions(), Modifier.fillMaxSize(), now) } }
        compose.onNodeWithTag("exam_filter_FINISHED").performScrollTo().performClick()
        compose.onNodeWithText("当前筛选下暂无考试。").assertExists()
        compose.onNodeWithTag("exam_unknown-blank").assertDoesNotExist()
        compose.onNodeWithTag("exam_unknown-school").assertDoesNotExist()
        compose.onNodeWithTag("exam_filter_UPCOMING").performClick()
        compose.onNodeWithText("当前筛选下暂无考试。").assertExists()
        compose.onNodeWithTag("exam_filter_UNSET").performClick()
        compose.onNodeWithTag("academic_exams").performScrollToNode(hasTestTag("exam_unknown-blank"))
        compose.onNodeWithTag("exam_unknown-blank").assertExists()
        compose.onNodeWithTag("academic_exams").performScrollToNode(hasTestTag("exam_unknown-school"))
        compose.onNodeWithTag("exam_unknown-school").assertExists()
    }

    @Test fun examFilterAndUnsavedDraftSurviveStateRestoration() {
        val restoration = StateRestorationTester(compose)
        val exams = listOf(record("past", "2026-11-24 09:00"), record("next", "2026-11-26 09:00"))
        restoration.setContent { GongXiaoZhuTheme { AcademicContentBody(false,
            AcademicUiState(loading = false, data = AcademicData(exams = exams)),
            AcademicActions(), Modifier.fillMaxSize(), now) } }
        compose.onNodeWithTag("exam_filter_UPCOMING").performScrollTo().performClick()
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithTag("exam_filter_UPCOMING").assertIsSelected()
        compose.onNodeWithTag("exam_next").assertExists()
        compose.onNodeWithTag("exam_past").assertDoesNotExist()
        compose.onNodeWithTag("academic_add_exam").performScrollTo().performClick()
        compose.onNodeWithTag("exam_name").performTextInput("旋转后保留的考试草稿")
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithTag("exam_name").assertTextContains("旋转后保留的考试草稿")
        compose.onNodeWithTag("save_exam").assertIsEnabled()
        compose.onNodeWithTag("cancel_exam").performClick()
        compose.onNodeWithTag("discard_changes_dialog").assertExists()
        compose.onNodeWithTag("discard_changes").performClick()
        compose.onNodeWithTag("exam_filter_UPCOMING").assertIsSelected()
    }

    @Test fun leavingExamPageReleasesTheGlobalDialogBlocker() {
        var mounted by mutableStateOf(true)
        var dialogVisible = false
        compose.setContent { GongXiaoZhuTheme {
            if (mounted) AcademicContentBody(false, AcademicUiState(loading = false), AcademicActions(),
                Modifier.fillMaxSize(), now, onDialogVisibilityChanged = { dialogVisible = it })
        } }
        compose.runOnIdle { assertFalse(dialogVisible) }
        compose.onNodeWithTag("academic_add_exam").performScrollTo().performClick()
        compose.runOnIdle { assertTrue(dialogVisible) }
        compose.onNodeWithTag("exam_name").performTextInput("待保存的示例考试")
        compose.onNodeWithTag("cancel_exam").performClick()
        compose.onNodeWithTag("discard_changes_dialog").assertExists()
        compose.runOnIdle { assertTrue(dialogVisible); mounted = false }
        compose.runOnIdle { assertFalse(dialogVisible) }
        compose.onNodeWithTag("discard_changes_dialog").assertDoesNotExist()
    }

    @Test fun existingExamDraftWaitsForRestoredVaultBeforeCheckingWhetherTheRecordStillExists() {
        val restoration = StateRestorationTester(compose)
        val savedData = AcademicData(exams = listOf(record("restored")), revision = 8)
        var recreateWithLoadingVault = false
        var vaultReady by mutableStateOf(false)
        restoration.setContent { GongXiaoZhuTheme {
            val loading = recreateWithLoadingVault && !vaultReady
            AcademicContentBody(false, AcademicUiState(loading = loading, data = if (loading) AcademicData() else savedData),
                AcademicActions(), Modifier.fillMaxSize(), now)
        } }
        compose.onNodeWithTag("academic_exams").performScrollToNode(hasTestTag("edit_exam_restored"))
        compose.onNodeWithTag("edit_exam_restored").performClick()
        compose.onNodeWithTag("exam_name").performTextReplacement("尚未保存的原考试修改")
        // A new process restores the page and draft before its encrypted vault finishes loading.
        recreateWithLoadingVault = true
        restoration.emulateSavedInstanceStateRestore()
        compose.runOnIdle { vaultReady = true }
        compose.onNodeWithTag("exam_name").assertTextContains("尚未保存的原考试修改")
        compose.onNodeWithTag("save_exam").assertIsEnabled()
    }

    @Test fun restoredExamDraftUsesTheNewProcessesCompletionCounterAndClosesOnlyAfterItsNextSave() {
        val restoration = StateRestorationTester(compose)
        var newProcess = false
        var completionAfterRestore by mutableStateOf(0L)
        restoration.setContent { GongXiaoZhuTheme { AcademicContentBody(false,
            AcademicUiState(loading = false, editCompleted = if (newProcess) completionAfterRestore else 7L),
            AcademicActions(), Modifier.fillMaxSize(), now) } }
        compose.onNodeWithTag("academic_add_exam").performScrollTo().performClick()
        compose.onNodeWithTag("exam_name").performTextInput("跨进程保留的考试草稿")
        compose.onNodeWithTag("exam_location").performScrollTo().performTextInput("示例楼 301")
        newProcess = true
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithTag("exam_name").performScrollTo().assertTextContains("跨进程保留的考试草稿")
        compose.onNodeWithTag("exam_location").performScrollTo().assertTextContains("示例楼 301")
        compose.onNodeWithTag("save_exam").assertIsEnabled()
        compose.runOnIdle { completionAfterRestore = 1L }
        compose.onNodeWithTag("exam_editor").assertDoesNotExist()
    }

    @Test fun failedLocalDeleteShowsItsResultAndKeepsTheOriginalExamVisible() {
        val exam = record("write-failure")
        val failure = "保存未完成，原教务资料保留，请重试"
        var ui by mutableStateOf(AcademicUiState(loading = false, data = AcademicData(exams = listOf(exam))))
        compose.setContent { GongXiaoZhuTheme { AcademicContentBody(false, ui,
            AcademicActions(deleteExam = { _, _ -> ui = ui.copy(message = failure) }), Modifier.fillMaxSize(), now) } }
        compose.onNodeWithTag("academic_exams").performScrollToNode(hasTestTag("delete_exam_write-failure"))
        compose.onNodeWithTag("delete_exam_write-failure").performClick()
        compose.onNodeWithTag("academic_confirm").performClick()
        compose.onNodeWithTag("academic_exams").performScrollToNode(hasTestTag("academic_message"))
        compose.onNodeWithTag("academic_message").assertTextEquals(failure)
        compose.onNodeWithTag("academic_exams").performScrollToNode(hasTestTag("exam_write-failure"))
        compose.onNodeWithTag("exam_write-failure").assertExists()
    }

    @Test fun schoolHideRequiresConfirmationAndHiddenExamOffersRestore() {
        val school = SchoolExam("fixture-course", "示例学校考试", "虚构教学班", "", "", "示例教室")
        val exam = ExamRecord("school", school, school.toExamFields())
        var deleted: Pair<String, Long>? = null
        var restored: Pair<String, Long>? = null
        var ui by mutableStateOf(AcademicUiState(loading = false, data = AcademicData(exams = listOf(exam), revision = 12)))
        compose.setContent { GongXiaoZhuTheme { AcademicContentBody(false, ui, AcademicActions(
            deleteExam = { id, revision -> deleted = id to revision }, restoreExam = { id, revision -> restored = id to revision }),
            Modifier.fillMaxSize(), now) } }
        compose.onNodeWithTag("academic_exams").performScrollToNode(hasTestTag("delete_exam_school"))
        compose.onNodeWithTag("delete_exam_school").performClick()
        assertNull(deleted)
        compose.onNodeWithText("仅在本机隐藏", substring = true).assertExists()
        compose.onNodeWithTag("academic_confirm").performClick()
        assertEquals("school" to 12L, deleted)
        compose.runOnIdle { ui = ui.copy(data = ui.data.copy(exams = listOf(exam.copy(hidden = true)))) }
        compose.onNodeWithTag("academic_exams").performScrollToNode(hasTestTag("academic_hidden_toggle"))
        compose.onNodeWithTag("academic_hidden_toggle").performClick()
        compose.onNodeWithTag("academic_exams").performScrollToNode(hasTestTag("restore_exam_school"))
        compose.onNodeWithTag("restore_exam_school").performClick()
        compose.onNodeWithText("恢复上次导入内容？").assertExists()
        compose.onNodeWithTag("academic_confirm").performClick()
        assertEquals("school" to 12L, restored)
    }

    @Test fun gradesShowSchoolTextWithoutInventedNumericSummaryAndFilterTerms() {
        val grades = GradeSnapshot(listOf(GradeRecord("示例学位课程", "", "2026秋", "暂未发布", restricted = true)),
            listOf(GradeRecord("示例选修课程", "2", "2025秋", "合格")),
            mapOf("应修学分" to "", "已修学分" to "学校原值"), 1)
        compose.setContent { GongXiaoZhuTheme { AcademicContentBody(true,
            AcademicUiState(loading = false, data = AcademicData(grades = grades)), AcademicActions(), Modifier.fillMaxSize(), now) } }
        compose.onNodeWithTag("academic_grades").performScrollToNode(hasTestTag("grade_0_0"))
        compose.onNodeWithText("暂未发布").assertExists()
        compose.onNodeWithText("成绩受限 · 以学校显示为准").assertExists()
        compose.onNode(hasText("未获取") and hasAnyAncestor(hasTestTag("grade_0_0"))).assertExists()
        compose.onNodeWithText("平均分").assertDoesNotExist()
        compose.onNodeWithText("绩点").assertDoesNotExist()
        compose.onNodeWithTag("academic_grades").performScrollToNode(hasTestTag("grade_term_0"))
        compose.onNodeWithTag("grade_term_0").performClick()
        compose.onNodeWithTag("academic_grades").performScrollToNode(hasText("暂无选修课成绩"))
        compose.onNodeWithText("示例选修课程").assertDoesNotExist()
    }

    @Test fun editedDraftUsesNormalizedDirtyGuardAndCanContinueOrDiscard() {
        var closed = 0
        compose.setContent { GongXiaoZhuTheme { AcademicExamEditor(record("draft"), 9, false, 0, null,
            onDismiss = { closed++ }, onSave = { _, _, _ -> }) } }
        compose.onNodeWithTag("exam_name").performTextReplacement("  示例考试draft  ")
        compose.onNodeWithTag("save_exam").assertIsNotEnabled()
        compose.onNodeWithTag("exam_name").performTextReplacement("修改后的示例考试")
        compose.onNodeWithTag("save_exam").assertIsEnabled()
        compose.onNodeWithTag("cancel_exam").performClick()
        compose.onNodeWithTag("discard_changes_dialog").assertExists()
        assertEquals(0, closed)
        compose.onNodeWithTag("continue_editing").performClick()
        compose.onNodeWithTag("exam_name").assertTextContains("修改后的示例考试")
        compose.onNodeWithTag("cancel_exam").performClick()
        compose.onNodeWithTag("discard_changes").performClick()
        assertEquals(1, closed)
    }

    @Test fun invalidOrReversedTimesCannotSaveButBlankTimesRemainValid() {
        compose.setContent { GongXiaoZhuTheme { AcademicExamEditor(null, 0, false, 0, null, {}, { _, _, _ -> }) } }
        compose.onNodeWithTag("exam_name").performTextInput("示例考试")
        compose.onNodeWithTag("save_exam").assertIsEnabled()
        compose.onNodeWithTag("exam_start").performTextInput("2026-02-30 09:00")
        compose.onNodeWithTag("save_exam").assertIsNotEnabled()
        compose.onNodeWithTag("exam_start").performTextReplacement("2026-11-25 09:00")
        compose.onNodeWithTag("exam_end").performTextInput("2026-11-25 08:00")
        compose.onNodeWithTag("save_exam").assertIsNotEnabled()
        compose.onNodeWithTag("exam_end").performTextReplacement("2026-11-25 10:00")
        compose.onNodeWithTag("save_exam").assertIsEnabled()
    }

    @Test fun blockedStorageHasReloadAndDoesNotOfferDestructiveClear() {
        var reloaded = 0
        compose.setContent { GongXiaoZhuTheme { AcademicContentBody(false, AcademicUiState(loading = false, blocked = true),
            AcademicActions(reload = { reloaded++ }), Modifier.fillMaxSize(), now) } }
        compose.onNodeWithText("重新读取教务资料").performClick()
        assertEquals(1, reloaded)
        compose.onNodeWithTag("academic_add_exam").assertDoesNotExist()
        compose.onNodeWithText("清除", substring = false).assertDoesNotExist()
    }

    @Test fun unchangedSchoolTimeTextCanBePreservedWhileEditingOtherFields() {
        val school = SchoolExam("fixture-unset", "示例未定考试", "示例教学班", "未定", "另行通知", "原地点")
        val exam = ExamRecord("school-unset", school, school.toExamFields())
        var saved: ExamFields? = null
        compose.setContent { GongXiaoZhuTheme { AcademicExamEditor(exam, 5, false, 0, null, {},
            { _, fields, revision -> saved = fields; assertEquals(5L, revision) }) } }
        compose.onNodeWithTag("exam_name").performTextReplacement("修订后的示例考试")
        compose.onNodeWithTag("save_exam").assertIsEnabled().performClick()
        assertEquals(ExamFields("修订后的示例考试", "未定", "另行通知", "原地点"), saved)
        compose.onNodeWithTag("exam_start").performTextReplacement("新的未定内容")
        compose.onNodeWithTag("save_exam").assertIsNotEnabled()
    }

    @Test fun staleEditorKeepsItsOpeningRevisionAndAsksToReopenInsteadOfWriting() {
        var revision by mutableStateOf(5L)
        var writes = 0
        compose.setContent { GongXiaoZhuTheme { AcademicExamEditor(record("stable"), revision, false, 0, null, {},
            { _, _, _ -> writes++ }) } }
        compose.onNodeWithTag("exam_name").performTextReplacement("未保存的本地草稿")
        compose.runOnIdle { revision = 6L }
        compose.onNodeWithTag("save_exam").performClick()
        assertEquals(0, writes)
        compose.onNodeWithTag("exam_save_error").performScrollTo().assertTextEquals("教务资料已变化，请关闭后重新打开考试编辑")
        compose.onNodeWithTag("exam_name").performScrollTo().assertTextContains("未保存的本地草稿")
    }

    @Test fun loginSubmitsOnlyAfterUserActionAndCredentialsDoNotRestore() {
        val image = ByteArrayOutputStream().use { stream ->
            Bitmap.createBitmap(90, 32, Bitmap.Config.ARGB_8888).apply { eraseColor(android.graphics.Color.WHITE) }
                .compress(Bitmap.CompressFormat.PNG, 100, stream)
            stream.toByteArray()
        }
        val page = AcademicLoginPage("fictional-ui-page", image)
        val submitted = mutableListOf<List<String>>()
        val restoration = StateRestorationTester(compose)
        restoration.setContent { GongXiaoZhuTheme { AcademicLoginForm(page, false, null, {}, {},
            { token, username, password, captcha -> submitted += listOf(token, username, password, captcha) }) } }
        compose.onNodeWithTag("academic_username").performTextInput("fictional-student")
        compose.onNodeWithTag("academic_password").performTextInput("fictional-password")
        compose.onNodeWithTag("academic_captcha").performTextInput("1234")
        assertTrue(submitted.isEmpty())
        compose.onNodeWithTag("academic_captcha").performImeAction()
        assertEquals(listOf(listOf("fictional-ui-page", "fictional-student", "fictional-password", "1234")), submitted)
        compose.onNodeWithTag("academic_submit").assertIsNotEnabled()
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithTag("academic_username").assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("")))
        compose.onNodeWithTag("academic_password").assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("")))
        compose.onNodeWithTag("academic_submit").assertIsNotEnabled()
    }

    @Test @Config(qualifiers = "w360dp-h420dp-xxhdpi")
    fun largeFontEditorKeepsFixedActionsAndReadableScrollableFields() {
        compose.setContent { GongXiaoZhuTheme {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 1.8f)) {
                Box(Modifier.width(360.dp).height(420.dp)) {
                    AcademicExamEditor(null, 0, true, 0, "示例保存提示", {}, { _, _, _ -> })
                }
            }
        } }
        compose.onNodeWithTag("cancel_exam").assertIsDisplayed().assertIsNotEnabled()
        compose.onNodeWithTag("save_exam").assertIsDisplayed().assertIsNotEnabled()
        compose.onNodeWithTag("exam_note").performScrollTo().assertIsDisplayed()
    }

    @Test fun retainedSchoolCopyCanBeDeletedWithoutHidingTheCurrentSchoolArrangement() {
        val school = SchoolExam("fixture-retained", "本地保留示例考试", "示例教学班", "", "", "示例教室")
        val exam = ExamRecord("retained", school, school.toExamFields(), retained = true)
        var deleted: Pair<String, Long>? = null
        compose.setContent { GongXiaoZhuTheme { AcademicContentBody(false,
            AcademicUiState(loading = false, data = AcademicData(exams = listOf(exam), revision = 17)),
            AcademicActions(deleteExam = { id, revision -> deleted = id to revision }), Modifier.fillMaxSize(), now) } }
        compose.onNodeWithTag("academic_exams").performScrollToNode(hasTestTag("delete_exam_retained"))
        compose.onNodeWithTag("delete_exam_retained").assertTextEquals("删除").performClick()
        compose.onNodeWithText("仅删除本地保留副本，当前学校安排不受影响。").assertExists()
        assertNull(deleted)
        compose.onNodeWithTag("academic_confirm").assertTextEquals("删除考试").performClick()
        assertEquals("retained" to 17L, deleted)
    }

    private fun previewState(): AcademicUiState {
        val school = SchoolExam("TEST-601", "工程数学（示例）", "虚构教学班 01",
            "2026-12-02 09:00", "2026-12-02 11:00", "示例楼 301")
        val updated = now.atZone(ZoneId.of("Asia/Shanghai")).toInstant().toEpochMilli()
        return AcademicUiState(loading = false, networkAvailable = true, networkLabel = "WiFi · 可手动刷新",
            data = AcademicData(identity = AcademicIdentity("fictional-student-2026", "示例同学"), revision = 3,
                exams = listOf(ExamRecord("fixture-school", school, school.toExamFields().copy(location = "示例楼 302", note = "仅本机修改的示例备注")),
                    ExamRecord("fixture-manual", fields = ExamFields("英语口语（手动示例）", location = "地点待通知"))),
                examUpdatedAt = updated,
                grades = GradeSnapshot(
                    listOf(GradeRecord("工程数学（示例）", "3", "2026-2027 秋", "优秀"),
                        GradeRecord("专业英语（示例）", "2", "2026-2027 秋", "暂未发布", restricted = true)),
                    listOf(GradeRecord("科研方法（示例）", "1", "2025-2026 春", "合格")),
                    mapOf("学位学分" to "18", "选修学分" to "8", "已修学分" to "20", "应修学分" to "26"), updated)))
    }

    @OptIn(ExperimentalRoborazziApi::class)
    private fun screenshot(name: String) {
        compose.waitForIdle()
        val dir = File("build/academic-preview").apply { mkdirs() }
        captureScreenRoboImage(File(dir, "$name.png").absolutePath)
    }

    @Test fun renderHomeWithAcademicCardsForVisualReview() {
        compose.setContent { AcademicPreviewTheme {
            AppShell(Tab.MY, {}, WallpaperState(), remember { SnackbarHostState() }) { padding ->
                MyHomeScreen(AppUiState(loading = false), Modifier.padding(padding), nowProvider = { now }, onOpen = {})
            }
        } }
        compose.onNodeWithTag("my_EXAMS").assertIsDisplayed()
        compose.onNodeWithTag("my_GRADES").assertIsDisplayed()
        screenshot("01-home")
    }

    @Test fun renderSchoolAndManualExamsForVisualReview() {
        compose.setContent { AcademicPreviewTheme {
            AppShell(Tab.MY, {}, WallpaperState(), remember { SnackbarHostState() }) { padding ->
                Column(Modifier.padding(padding)) {
                    PageHeader("考试管理", null)
                    AcademicContentBody(false, previewState(), AcademicActions(), Modifier.weight(1f), now)
                }
            }
        } }
        screenshot("02-exams-header")
        compose.onNodeWithTag("academic_exams").performScrollToNode(hasTestTag("exam_fixture-school"))
        screenshot("03-exams-data")
    }

    @Test fun renderSchoolGradesForVisualReview() {
        compose.setContent { AcademicPreviewTheme {
            AppShell(Tab.MY, {}, WallpaperState(), remember { SnackbarHostState() }) { padding ->
                Column(Modifier.padding(padding)) {
                    PageHeader("成绩管理", null)
                    AcademicContentBody(true, previewState(), AcademicActions(), Modifier.weight(1f), now)
                }
            }
        } }
        screenshot("04-grades-header")
        compose.onNodeWithTag("academic_grades").performScrollToNode(hasTestTag("grade_0_0"))
        screenshot("05-grades-data")
    }

    @Test @Config(qualifiers = "w360dp-h420dp-xxhdpi")
    fun renderSmallScreenLargeFontExamEditorForVisualReview() {
        compose.setContent { AcademicPreviewTheme {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 1.8f)) {
                AcademicExamEditor(record("draft", "2026-12-02 09:00", "2026-12-02 11:00"),
                    3, false, 0, null, {}, { _, _, _ -> })
            }
        } }
        compose.onNodeWithTag("cancel_exam").assertIsDisplayed()
        compose.onNodeWithTag("save_exam").assertIsDisplayed()
        screenshot("06-editor-small-large-font")
        val actions = compose.onNodeWithTag("save_exam").fetchSemanticsNode().boundsInRoot
        compose.onNodeWithTag("exam_note").performScrollTo()
        compose.onNodeWithTag("exam_name").assertIsNotDisplayed()
        assertEquals(actions, compose.onNodeWithTag("save_exam").assertIsDisplayed().fetchSemanticsNode().boundsInRoot)
        screenshot("07-editor-small-large-font-scrolled")
    }

    @Test @Config(qualifiers = "w393dp-h852dp-night-xxhdpi")
    fun renderBothAcademicPagesInDarkModeForVisualReview() = renderAdaptiveAcademicPages("08-dark")

    @Test @Config(qualifiers = "w360dp-h800dp-xxhdpi")
    fun renderBothAcademicPagesWithLargeFontForVisualReview() = renderAdaptiveAcademicPages("09-large-font", 1.8f)

    @Test @Config(qualifiers = "w800dp-h360dp-land-xxhdpi")
    fun renderBothAcademicPagesInLandscapeForVisualReview() = renderAdaptiveAcademicPages("10-landscape")

    private fun renderAdaptiveAcademicPages(prefix: String, fontScale: Float = 1f) {
        var grades by mutableStateOf(false)
        compose.setContent { AcademicPreviewTheme {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                AppShell(Tab.MY, {}, WallpaperState(), remember { SnackbarHostState() }) { padding ->
                    Column(Modifier.padding(padding)) {
                        PageHeader(if (grades) "成绩管理" else "考试管理", null)
                        key(grades) {
                            AcademicContentBody(grades, previewState(), AcademicActions(), Modifier.weight(1f), now)
                        }
                    }
                }
            }
        } }
        screenshot("$prefix-exams-header")
        compose.onNodeWithTag("academic_exams").performScrollToNode(hasTestTag("exam_fixture-school"))
        compose.onNodeWithTag("exam_fixture-school").assertIsDisplayed()
        screenshot("$prefix-exams-data")
        compose.runOnIdle { grades = true }
        screenshot("$prefix-grades-header")
        compose.onNodeWithTag("academic_grades").performScrollToNode(hasTestTag("grade_0_0"))
        compose.onNodeWithTag("grade_0_0").assertIsDisplayed()
        screenshot("$prefix-grades-data")
    }
}

/** Preview-only font injection; no font is bundled in the application or written into the repository. */
@Composable
private fun AcademicPreviewTheme(content: @Composable () -> Unit) {
    val family = remember {
        val fonts = listOf(FontWeight.Normal to "Noto Sans SC (TrueType).otf", FontWeight.Medium to "Noto Sans SC Medium (TrueType).otf",
            FontWeight.Bold to "Noto Sans SC Bold (TrueType).otf").map { (weight, name) -> weight to File("C:/Windows/Fonts", name) }
        val variable = File("C:/Windows/Fonts", "NotoSansSC-VF.ttf")
        when {
            fonts.all { it.second.isFile } -> FontFamily(fonts.map { (weight, file) -> Font(file, weight) })
            variable.isFile -> FontFamily(listOf(FontWeight.Normal, FontWeight.Medium, FontWeight.SemiBold, FontWeight.Bold).map { Font(variable, it) })
            else -> FontFamily.Default
        }
    }
    GongXiaoZhuTheme {
        val t = MaterialTheme.typography
        fun TextStyle.withPreviewFont() = copy(fontFamily = family)
        MaterialTheme(typography = Typography(t.displayLarge.withPreviewFont(), t.displayMedium.withPreviewFont(), t.displaySmall.withPreviewFont(),
            t.headlineLarge.withPreviewFont(), t.headlineMedium.withPreviewFont(), t.headlineSmall.withPreviewFont(),
            t.titleLarge.withPreviewFont(), t.titleMedium.withPreviewFont(), t.titleSmall.withPreviewFont(), t.bodyLarge.withPreviewFont(),
            t.bodyMedium.withPreviewFont(), t.bodySmall.withPreviewFont(), t.labelLarge.withPreviewFont(), t.labelMedium.withPreviewFont(), t.labelSmall.withPreviewFont())) {
            CompositionLocalProvider(LocalTextStyle provides LocalTextStyle.current.withPreviewFont(), content = content)
        }
    }
}
