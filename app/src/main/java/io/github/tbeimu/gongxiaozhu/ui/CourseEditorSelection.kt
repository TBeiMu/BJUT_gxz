package io.github.tbeimu.gongxiaozhu.ui

import androidx.compose.runtime.saveable.mapSaver
import io.github.tbeimu.gongxiaozhu.schedule.CourseEditContext
import io.github.tbeimu.gongxiaozhu.schedule.CourseOriginalArrangement
import io.github.tbeimu.gongxiaozhu.schedule.bjutLessonTime

/** A first tap is already a valid single lesson; the second closes the inclusive range. */
internal data class CourseLessonSelection(
    val lessons: List<Int>,
    val anchor: Int? = null,
    val changed: Boolean = false,
) {
    fun select(lesson: Int): CourseLessonSelection {
        require(lesson in 1..12)
        return when {
            anchor != null -> CourseLessonSelection((minOf(anchor, lesson)..maxOf(anchor, lesson)).toList(), changed = true)
            lesson in lessons -> CourseLessonSelection(lessons - lesson, changed = true)
            else -> CourseLessonSelection(listOf(lesson), lesson, true)
        }
    }

    companion object {
        val Saver = mapSaver(
            save = { value: CourseLessonSelection -> mapOf(
                "lessons" to ArrayList(value.lessons), "anchor" to (value.anchor ?: 0), "changed" to value.changed,
            ) },
            restore = { value -> CourseLessonSelection(
                (value["lessons"] as List<*>).filterIsInstance<Int>(),
                (value["anchor"] as Int).takeIf { it in 1..12 }, value["changed"] as Boolean,
            ) },
        )
    }
}

/** The resulting weeks initially match every occurrence in the edited weekday/lesson series. */
internal data class CourseWeekSelection(
    val sourceWeeks: List<Int>,
    val targetWeeks: List<Int>,
) {
    fun withTargets(selected: List<Int>): CourseWeekSelection =
        copy(targetWeeks = selected.distinct().sorted())

    fun fitTerm(weekCount: Int, preservedWeeks: List<Int> = sourceWeeks): CourseWeekSelection =
        if (weekCount in 1..60) copy(targetWeeks = targetWeeks.filter { it in 1..weekCount || it in preservedWeeks }) else this

    companion object {
        fun initial(context: CourseEditContext): CourseWeekSelection {
            val clicked = listOf(context.defaultWeek)
            val sources = when {
                context.course == null -> emptyList()
                context.deleteOnly -> clicked
                else -> context.availableWeeks.ifEmpty { clicked }.distinct().sorted()
            }
            return CourseWeekSelection(sources, if (context.course == null) clicked else sources)
        }

        val Saver = mapSaver(
            save = { value: CourseWeekSelection -> mapOf(
                "sources" to ArrayList(value.sourceWeeks), "targets" to ArrayList(value.targetWeeks),
            ) },
            restore = { value -> CourseWeekSelection(
                (value["sources"] as List<*>).filterIsInstance<Int>(),
                (value["targets"] as List<*>).filterIsInstance<Int>(),
            ) },
        )
    }
}

/** Dragging only adds a range; selected options are removed by explicit taps. */
internal class CourseDragSelection(selected: List<Int>, private val available: List<Int>, private val anchor: Int) {
    private val original = selected.filter { it in available }.toSet()
    private val selecting = anchor !in original

    init { require(anchor in available) }

    fun through(value: Int): List<Int> {
        require(value in available)
        val range = available.filter { it in minOf(anchor, value)..maxOf(anchor, value) }.toSet()
        return (if (selecting) original + range else original).sorted()
    }
}

internal enum class CourseWeekPreset(val label: String) {
    ALL("全部"), ODD("单周"), EVEN("双周"), CLEAR("清空");

    fun select(available: List<Int>): List<Int> = available.distinct().sorted().filter {
        when (this) {
            ALL -> true
            ODD -> it % 2 == 1
            EVEN -> it % 2 == 0
            CLEAR -> false
        }
    }
}

private fun numberRanges(numbers: List<Int>): List<String> {
    val sorted = numbers.distinct().sorted()
    if (sorted.isEmpty()) return emptyList()
    val ranges = mutableListOf<String>()
    var start = sorted.first()
    var end = start
    fun append() { ranges += if (start == end) "$start" else "$start–$end" }
    sorted.drop(1).forEach { number ->
        if (number == end + 1) end = number else { append(); start = number; end = number }
    }
    append()
    return ranges
}

internal fun courseWeekSummary(weeks: List<Int>): String {
    val ranges = numberRanges(weeks)
    return when {
        ranges.isEmpty() -> "未选择周次"
        ranges.size <= 4 -> "第 " + ranges.joinToString("、") + " 周"
        else -> "第 " + ranges.take(3).joinToString("、") + " 等 " + weeks.distinct().size + " 周"
    }
}

internal fun courseLessonSummary(lessons: List<Int>): String =
    if (lessons.isEmpty()) "未选择节次" else "第 " + numberRanges(lessons).joinToString("、") + " 节"

internal fun courseOriginalScheduleSummary(arrangements: List<CourseOriginalArrangement>): String {
    val grouped = arrangements.groupBy { it.weekday to it.lessons.distinct().sorted() }
        .map { (key, values) -> key to values.map { it.week }.distinct().sorted() }
        .sortedWith(compareBy({ it.first.first }, { it.first.second.firstOrNull() ?: 0 }))
    val days = listOf("一", "二", "三", "四", "五", "六", "日")
    return "原有安排：" + grouped.joinToString("；") { (key, weeks) ->
        "第 " + numberRanges(weeks).joinToString("、") + " 周 · 周" + days[key.first - 1] + " · " + courseLessonSummary(key.second)
    }
}

/** A continuous time interval must not imply teaching during gaps or invent times after lesson 12. */
internal fun courseLessonClock(lessons: List<Int>): String? {
    val sorted = lessons.distinct().sorted()
    if (sorted.isEmpty() || sorted.zipWithNext().any { (a, b) -> b != a + 1 }) return null
    val start = bjutLessonTime(sorted.first()) ?: return null
    val end = bjutLessonTime(sorted.last()) ?: return null
    return start.start + "–" + end.end
}
