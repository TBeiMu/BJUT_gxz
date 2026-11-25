package io.github.tbeimu.gongxiaozhu.academic

import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

internal data class AcademicPostBack(val target: String, val argument: String)
internal data class AcademicGrid<T>(val records: List<T>, val next: AcademicPostBack?)

/** Parsers accept only the school's recognized grids, including its explicit empty-result marker. */
internal object AcademicParser {
    private val examHead = listOf("课程编号", "课程名称", "班级名称", "开始时间", "结束时间", "考试地点")
    private val gradeHead = listOf("课程", "课程学分", "选修学期", "成绩")
    private val emptyMessages = setOf("没查询到相关内容", "没有查询到相关内容", "暂无数据", "没有相关记录")
    private val creditLabels = setOf("学位学分", "选修学分", "已修学分", "应修学分")
    private val postBack = Regex("(?:javascript:)?__doPostBack\\(\\s*['\"]([^'\"]+)['\"]\\s*,\\s*['\"]([^'\"]*)['\"]\\s*\\);?")

    fun text(element: Element): String = element.text().replace('\u00a0', ' ').trim()
    fun document(html: String): Document = Jsoup.parse(html)
    fun isLogin(doc: Document): Boolean = doc.select("input#ctl00_txtpassword, input#ctl00_txtusername").isNotEmpty()

    fun identity(doc: Document, expected: String? = null): AcademicIdentity {
        val ids = labeledValues(doc, "学号").filter { it.matches(Regex("[A-Za-z0-9][A-Za-z0-9._-]{2,63}")) }.distinct()
        if (ids.size != 1) fail(AcademicProblem.IDENTITY)
        val id = ids.single()
        if (expected != null && !id.equals(expected.trim(), ignoreCase = true)) fail(AcademicProblem.ACCOUNT_MISMATCH)
        val names = labeledValues(doc, "姓名").filter { it.length <= 100 }.distinct()
        if (names.size > 1) fail(AcademicProblem.IDENTITY)
        return AcademicIdentity(id, names.singleOrNull().orEmpty())
    }

    private fun labeledValues(doc: Document, label: String): List<String> = buildList {
        doc.select("td, th, label, span").filter { it.select("table").isEmpty() }.forEach { cell ->
            val value = text(cell)
            if (value.removeSuffix("：").removeSuffix(":") == label) {
                cell.nextElementSibling()?.let { add(text(it)) }
            } else if (value.startsWith("$label：") || value.startsWith("$label:")) {
                val remainder = value.substring(label.length + 1).trim()
                if (remainder.isNotEmpty() && remainder.length <= 100 && !remainder.contains("姓名") && !remainder.contains("学号")) add(remainder)
            }
        }
    }

    fun exams(doc: Document): AcademicGrid<SchoolExam> = grid(doc, "MainWork_dgData", examHead) { cells ->
        val values = cells.map(::text)
        if (values[0].isBlank() || values[1].isBlank()) fail()
        SchoolExam(values[0], values[1], values[2], values[3], values[4], values[5])
    }

    fun grades(doc: Document, id: String): AcademicGrid<GradeRecord> = grid(doc, id, gradeHead) { cells ->
        val values = cells.map(::text)
        if (values[0].isBlank()) fail()
        val restricted = cells.any { cell ->
            (cell.getAllElements() + cell.parents().takeWhile { it.tagName() != "table" }).any { element ->
                val color = element.attr("color").lowercase()
                val style = element.attr("style").lowercase().replace(" ", "")
                color in setOf("red", "#f00", "#ff0000") ||
                    Regex("(?:^|;)color:(?:red|#f00|#ff0000)(?:;|$)").containsMatchIn(style)
            }
        } || values[3].contains("评价") || values[3].contains("评教")
        GradeRecord(values[0], values[1], values[2], values[3], restricted)
    }

    fun credits(doc: Document): Map<String, String> {
        val result = linkedMapOf<String, String>()
        doc.select("tr").forEach { row ->
            val cells = row.children().filter { it.tagName() in setOf("td", "th") }
            if (cells.any { it.select("table").isNotEmpty() }) return@forEach
            val values = cells.map(::text)
            if (values.firstOrNull() !in creditLabels || values.size < 2) return@forEach
            // The school's two summary rows contain alternating label/value pairs, not a matrix.
            if (values.size % 2 != 0 || values.indices.filter { it % 2 == 0 }.any { values[it] !in creditLabels }) fail()
            values.chunked(2).forEach { (label, value) ->
                if (value in creditLabels) fail()
                if (value.isNotBlank()) {
                    if (value.length > 100 || (result[label] != null && result[label] != value)) fail()
                    result[label] = value
                }
            }
        }
        return result
    }

    private fun <T> grid(doc: Document, id: String, head: List<String>, record: (List<Element>) -> T): AcademicGrid<T> {
        if (isLogin(doc)) fail(AcademicProblem.EXPIRED)
        val table = doc.getElementById(id) ?: fail()
        val rows = table.select("tr").filter { it.closest("table") === table }
        val first = rows.firstOrNull() ?: fail()
        fun cells(row: Element) = row.children().filter { it.tagName() in setOf("td", "th") }
        if (cells(first).map(::text) != head) fail()
        val records = mutableListOf<T>()
        var explicitEmpty = false
        var pager: Element? = null
        rows.drop(1).forEach { row ->
            val fields = cells(row)
            if (fields.size == head.size && fields.all { (it.attr("colspan").toIntOrNull() ?: 1) == 1 }) {
                if (fields.any { text(it).length > 1000 }) fail()
                records += record(fields)
            } else if (fields.size == 1 && text(row) in emptyMessages) {
                explicitEmpty = true
            } else if (fields.size == 1 && fields[0].attr("colspan").toIntOrNull() == head.size) {
                if (pager != null) fail()
                pager = fields[0]
            } else fail()
        }
        if (records.isEmpty() && !explicitEmpty) fail()
        if (records.isNotEmpty() && explicitEmpty) fail()
        return AcademicGrid(records, pager?.let { nextPage(it, id) })
    }

    private fun nextPage(pager: Element, id: String): AcademicPostBack? {
        val links = pager.select("a[href]")
        fun action(link: Element): AcademicPostBack {
            val parsed = postBack.matchEntire(link.attr("href").trim()) ?: fail()
            val target = parsed.groupValues[1]
            val expected = "ctl00\$MainWork\$${id.removePrefix("MainWork_")}"
            if (target != expected && !target.startsWith("$expected$")) fail()
            val argument = parsed.groupValues[2]
            if (target.length > 200 || argument.length > 100) fail()
            return AcademicPostBack(target, argument)
        }
        links.firstOrNull { text(it) in setOf("下一页", "下页", ">", "›") }?.let { return action(it) }
        val numbered = links.mapNotNull { a -> text(a).toIntOrNull()?.let { it to a } }
        val current = pager.select("span").mapNotNull { text(it).toIntOrNull() }.distinct().singleOrNull()
            ?: Regex("第\\s*(\\d+)\\s*页").find(text(pager))?.groupValues?.get(1)?.toIntOrNull()
        if (numbered.isNotEmpty()) {
            if (current == null) fail()
            numbered.firstOrNull { it.first == current + 1 }?.let { return action(it.second) }
            if (numbered.any { it.first > current }) fail()
        }
        val total = Regex("共\\s*(\\d+)\\s*页").find(text(pager))?.groupValues?.get(1)?.toIntOrNull()
        if (total != null && (current == null || current < total)) fail()
        if (links.any { text(it) !in setOf("首页", "上一页", "上页", "末页", "尾页", "<", "<<", ">>", "«", "»") && text(it).toIntOrNull() == null }) fail()
        return null
    }

    private fun fail(problem: AcademicProblem = AcademicProblem.RESPONSE): Nothing = throw AcademicException(problem)
}
