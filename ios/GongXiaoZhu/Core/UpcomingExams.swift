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

    func dateTimeLabel(viewingDay: String) -> String {
        let year = ShanghaiClock.calendar.component(.year, from: start)
        let currentYear = ShanghaiClock.parse(viewingDay).map { ShanghaiClock.calendar.component(.year, from: $0) } ?? year
        let date = ShanghaiClock.format(start, year == currentYear ? "M月d日" : "yyyy年M月d日")
        return date + " " + ShanghaiClock.format(start, "HH:mm")
    }

    func countdownLabel(viewingDay: String) -> String {
        let days = max(0, ShanghaiClock.days(from: viewingDay, to: ShanghaiClock.day(start)) ?? 0)
        return days == 0 ? "加油！" : "剩\(days)天"
    }
}
