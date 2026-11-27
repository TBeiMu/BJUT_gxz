import Foundation

/// Derived from the current local snapshot; never persisted and never starts a query.
struct UpcomingExamOverview {
    let exams: [UpcomingExam]

    static func make(records: [ExamRecord], from day: String, now: Date) -> Self? {
        guard let first = ShanghaiClock.parse(day),
              let limit = ShanghaiClock.calendar.date(byAdding: .day, value: 30, to: first) else { return nil }
        let matches = records.compactMap { record -> UpcomingExam? in
            guard !record.hidden, let start = ShanghaiClock.examTime(record.fields.start),
                  start >= first, start < limit else { return nil }
            let end = ShanghaiClock.examTime(record.fields.end).flatMap { $0 >= start ? $0 : nil }
            guard end.map({ $0 >= now }) ?? (start >= now) else { return nil }
            return UpcomingExam(record: record, start: start)
        }.sorted { a, b in
            if a.start != b.start { return a.start < b.start }
            if a.record.fields.name != b.record.fields.name { return a.record.fields.name < b.record.fields.name }
            return a.record.id < b.record.id
        }
        guard !matches.isEmpty else { return nil }
        return Self(exams: Array(matches.prefix(3)))
    }
}

struct UpcomingExam: Identifiable {
    let record: ExamRecord
    let start: Date
    var id: String { record.id }

    func timeLabel(now: Date) -> String {
        let year = ShanghaiClock.calendar.component(.year, from: start)
        let currentYear = ShanghaiClock.calendar.component(.year, from: now)
        let date = ShanghaiClock.format(start, year == currentYear ? "M月d日" : "yyyy年M月d日")
        let days = max(0, ShanghaiClock.days(from: ShanghaiClock.day(now), to: ShanghaiClock.day(start)) ?? 0)
        let reminder = days == 0 ? "加油！" : "剩\(days)天"
        return date + " " + ShanghaiClock.format(start, "HH:mm") + " · " + reminder
    }
}
