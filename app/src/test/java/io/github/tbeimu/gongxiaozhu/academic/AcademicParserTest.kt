package io.github.tbeimu.gongxiaozhu.academic

import org.junit.Assert.*
import org.junit.Test

class AcademicParserTest {
    @Test fun preservesAllExamFieldsAndRepeatedRows() {
        val records = AcademicParser.exams(AcademicParser.document(academicExamFixture(examRow() + examRow()))).records
        assertEquals(2, records.size)
        assertEquals("TEST-001", records.first().code)
        assertEquals("虚构班", records.first().className)
        assertEquals("2099-12-01 09:00:00", records.first().start)
        assertEquals(records.first(), records.last())
    }

    @Test fun onlyRecognizedEmptyGridCountsAsSuccessfulEmptyResult() {
        assertTrue(AcademicParser.exams(AcademicParser.document(academicExamFixture("<tr><td colspan='6'>没查询到相关内容</td></tr>"))).records.isEmpty())
        listOf("", "<tr><td colspan='6'>服务器维护</td></tr>").forEach { row ->
            assertEquals(AcademicProblem.RESPONSE, assertThrows(AcademicException::class.java) {
                AcademicParser.exams(AcademicParser.document(academicExamFixture(row)))
            }.problem)
        }
    }

    @Test fun unknownHeadersAndMissingFieldsAreRejected() {
        val html = academicExamFixture().replace("考试地点</td>", "未知字段</td>")
        assertThrows(AcademicException::class.java) { AcademicParser.exams(AcademicParser.document(html)) }
    }

    @Test fun identityMustComeFromSchoolLabeledStudentNumber() {
        val doc = AcademicParser.document(academicGradeFixture())
        assertEquals("S209900001", AcademicParser.identity(doc, "s209900001").studentId)
        assertEquals("虚构同学", AcademicParser.identity(doc).name)
        assertEquals(AcademicProblem.ACCOUNT_MISMATCH, assertThrows(AcademicException::class.java) { AcademicParser.identity(doc, "other-account") }.problem)
        assertEquals(AcademicProblem.IDENTITY, assertThrows(AcademicException::class.java) {
            AcademicParser.identity(AcademicParser.document("<input name='uid' value='S209900001'>"))
        }.problem)
    }

    @Test fun gradeClassificationsAndEvaluationRestrictionArePreserved() {
        val html = academicGradeFixture(gradeRow("示例必修", "<font color='red'>参加教学评价后查看</font>"))
        val doc = AcademicParser.document(html)
        val degree = AcademicParser.grades(doc, "MainWork_dgData").records.single()
        assertTrue(degree.restricted)
        assertEquals("参加教学评价后查看", degree.grade)
        assertEquals("未公布", AcademicParser.grades(doc, "MainWork_Datagrid1").records.single().grade)
        assertEquals(mapOf("学位学分" to "3", "选修学分" to "未获取", "已修学分" to "3", "应修学分" to "30"), AcademicParser.credits(doc))
    }

    @Test fun creditSummaryDoesNotFlattenAnUnrecognizedMatrixOrInventZero() {
        val doc = AcademicParser.document("""<table><tr><td>类型</td><td>应修学分</td><td>已修学分</td></tr>
            <tr><td>学位学分</td><td>18</td><td></td></tr><tr><td>选修学分</td><td>10</td><td>4</td></tr></table>""")
        assertThrows(AcademicException::class.java) { AcademicParser.credits(doc) }
    }

    @Test fun pagerUsesCurrentGridAndDynamicEventTarget() {
        val parsed = AcademicParser.exams(AcademicParser.document(academicExamFixture(pager = academicPager(6))))
        assertEquals(AcademicPostBack("ctl00${'$'}MainWork${'$'}dgData", "Page${'$'}2"), parsed.next)
        val bad = academicExamFixture(pager = academicPager(6).replace("ctl00${'$'}MainWork${'$'}dgData", "otherForm"))
        assertThrows(AcademicException::class.java) { AcademicParser.exams(AcademicParser.document(bad)) }
    }

    @Test fun incompletePagerAndAmbiguousRecordAreRejected() {
        val html = academicExamFixture(pager = "<tr><td colspan='6'>第1页 共3页</td></tr>")
        assertThrows(AcademicException::class.java) { AcademicParser.exams(AcademicParser.document(html)) }
        val unknown = academicExamFixture("<tr><td colspan='2'>未知行</td></tr>")
        assertThrows(AcademicException::class.java) { AcademicParser.exams(AcademicParser.document(unknown)) }
    }

    @Test fun evaluationRestrictionInheritedFromRowIsNotLost() {
        val row = gradeRow(grade = "暂不可查看").replace("<tr>", "<tr style='color:Red;'>")
        val degree = AcademicParser.grades(AcademicParser.document(academicGradeFixture(row)), "MainWork_dgData").records.single()
        assertTrue(degree.restricted)
        assertEquals("暂不可查看", degree.grade)
    }

    @Test fun blankCreditSummaryValueStaysMissing() {
        val html = academicGradeFixture().replace("<td>应修学分</td><td>30</td>", "<td>应修学分</td><td></td>")
        assertFalse(AcademicParser.credits(AcademicParser.document(html)).containsKey("应修学分"))
    }
}
