import Foundation

actor AccountRepository {
    private let file = VaultFile(name: "accounts.vault", keyName: "accounts.v1", limit: 1024 * 1024)
    private var value: AccountData?
    private var encoded: Data?
    func load() throws -> AccountData {
        value = nil
        let bytes = try file.read()
        let data = try bytes.map { try ModelCoding.decode(AccountData.self, $0) } ?? AccountData()
        try validate(data); value = data; encoded = bytes
        return data
    }
    private func validate(_ data: AccountData) throws {
        guard data.schema == 1, data.accounts.count <= 100, Set(data.accounts.map(\.id)).count == data.accounts.count,
              Set(data.accounts.map(\.username)).count == data.accounts.count,
              data.defaultAccountID == nil || data.accounts.contains(where: { $0.id == data.defaultAccountID }) else { throw AppError.storage }
        for account in data.accounts {
            guard !account.id.isEmpty, account.id.count <= 128, !account.username.trimmed.isEmpty,
                  account.username.count <= 128, account.label.count <= 80, !account.password.isEmpty,
                  account.password.count <= 256, account.nameLookupVersion >= 0 else { throw AppError.storage }
        }
    }
    private func current() throws -> AccountData {
        guard let value else { throw AppError.storage }
        guard try file.read() == encoded else { self.value = nil; throw AppError.conflict }
        return value
    }
    private func write(_ next: AccountData, lease: OperationLease? = nil) throws -> AccountData {
        _ = try current(); try validate(next); try lease?.requireValid()
        let bytes = try ModelCoding.encode(next)
        guard try file.read() == encoded else { throw AppError.conflict }
        try lease?.requireValid(); try file.save(bytes)
        encoded = bytes; value = next
        return next
    }
    func save(_ draft: CampusAccount, original: CampusAccount?) throws -> AccountData {
        var next = try current()
        if let original, next.accounts.first(where: { $0.id == original.id }) != original { throw AppError.conflict }
        var account = draft
        account.username = draft.username.trimmed
        account.label = draft.label.trimmed.isEmpty ? account.username : draft.label.trimmed
        guard !account.username.isEmpty, !account.password.isEmpty, account.username.count <= 128,
              account.label.count <= 80, account.password.count <= 256 else { throw AppError.validation("请填写有效账号和密码，备注最多 80 字") }
        guard !next.accounts.contains(where: { $0.username == account.username && $0.id != account.id }) else { throw AppError.validation("该账号已经保存，请编辑已有账号") }
        account.customLabel = draft.label.trimmed.isEmpty ? false : (original?.label == account.label && original?.username == account.username ? original!.customLabel : true)
        account.nameLookupPending = original.map { $0.hasNameOpportunity && $0.username == account.username && $0.password == account.password && !account.customLabel } ?? draft.label.trimmed.isEmpty
        account.nameLookupVersion = max(original?.nameLookupVersion ?? 0, AppInfo.build)
        if let index = next.accounts.firstIndex(where: { $0.id == account.id }) { next.accounts[index] = account }
        else { next.accounts.append(account) }
        if next.defaultAccountID == nil { next.defaultAccountID = account.id }
        return try write(next)
    }
    func credentials(_ id: String) throws -> CampusAccount {
        guard let account = try current().accounts.first(where: { $0.id == id }) else { throw AppError.conflict }
        return account
    }
    func delete(_ id: String) throws -> AccountData {
        var next = try current(); next.accounts.removeAll { $0.id == id }
        if next.defaultAccountID == id { next.defaultAccountID = next.accounts.first?.id }
        return try write(next)
    }
    func setDefault(_ id: String) throws -> AccountData {
        var next = try current(); guard next.accounts.contains(where: { $0.id == id }) else { throw AppError.conflict }
        next.defaultAccountID = id; return try write(next)
    }
    func settings(_ settings: CampusSettings) throws -> AccountData { var next = try current(); next.settings = settings; return try write(next) }
    func beginNameLookup(_ id: String, lease: OperationLease) throws -> CampusAccount? {
        try lease.requireValid(); var next = try current()
        guard let index = next.accounts.firstIndex(where: { $0.id == id && $0.hasNameOpportunity }) else { return nil }
        let account = next.accounts[index]
        next.accounts[index].nameLookupPending = false; next.accounts[index].nameLookupVersion = AppInfo.build
        do { _ = try write(next, lease: lease) } catch {
            if try file.read() == encoded { throw AppError.validation("姓名暂未保存，校园网账号仍可使用") }
            throw AppError.storage
        }
        return account
    }
    func fillName(_ identity: SchoolIdentity, account: CampusAccount? = nil, lease: OperationLease) throws -> AccountData {
        try lease.requireValid(); var next = try current()
        let name = identity.name.trimmed
        guard !name.isEmpty, name != identity.studentID, name.count <= 80,
              !name.unicodeScalars.contains(where: { CharacterSet.controlCharacters.contains($0) }) else { return next }
        guard let index = next.accounts.firstIndex(where: {
            $0.username == identity.studentID && $0.canFillName && (account == nil || ($0.id == account!.id && $0.password == account!.password))
        }) else { return next }
        next.accounts[index].label = name; next.accounts[index].nameLookupPending = false
        next.accounts[index].nameLookupVersion = max(next.accounts[index].nameLookupVersion, AppInfo.build)
        do { return try write(next, lease: lease) } catch {
            if try file.read() == encoded { throw AppError.validation("姓名暂未保存，校园网账号仍可使用") }
            throw AppError.storage
        }
    }
    func reset() throws -> AccountData { try file.reset(); value = AccountData(); encoded = nil; return value! }
}

struct DisplaySchedule: Sendable {
    var key: String
    var title: String
    var weeks: [ScheduleWeek]
    var localTermID: String?
    var school: Bool
    var firstMonday: String { weeks.first?.dates.first ?? ShanghaiClock.monday() }
    func currentWeek(_ today: String = ShanghaiClock.day()) -> Int? { weeks.first(where: { $0.dates.contains(today) })?.number }
}
extension ScheduleData {
    func display(localID: String? = nil) -> DisplaySchedule? {
        let selected = localTerms.first { $0.id == (localID ?? selectedLocalTermID) }
        let school = complete.flatMap { complete in selected == nil || (selected?.studentID == complete.identity.studentID && selected?.schoolKey == complete.term.key) ? complete : nil }
        let local = school.flatMap { school in localTerms.first { $0.studentID == school.identity.studentID && $0.schoolKey == school.term.key } } ?? selected ?? (school == nil ? localTerms.last : nil)
        guard school != nil || local != nil else { return nil }
        let count = school?.term.weekCount ?? local!.weekCount
        let monday = school?.weeks.first?.dates.first ?? local!.firstMonday
        let custom: [Course] = local?.courses.flatMap { course in course.occurrences.filter { $0.week <= count }.compactMap { occurrence in
            guard let date = ShanghaiClock.addDays((occurrence.week - 1) * 7 + occurrence.weekday - 1, to: monday) else { return nil }
            return Course(id: course.id, name: course.name, date: date, lessons: occurrence.lessons, location: course.location, teacher: course.teacher,
                          weeksText: "第 \(occurrence.week) 周", source: .custom)
        } } ?? []
        let weeks = (1...count).map { number in
            let dates = (0..<7).compactMap { ShanghaiClock.addDays((number - 1) * 7 + $0, to: monday) }
            return ScheduleWeek(number: number, dates: dates, courses: (school?.weeks.first(where: { $0.number == number })?.courses ?? []) + custom.filter { dates.contains($0.date) })
        }
        return DisplaySchedule(key: school.map { "school/\($0.identity.studentID)/\($0.term.key)" } ?? "local/\(local!.id)",
                               title: school?.term.title ?? local!.title, weeks: weeks, localTermID: local?.id, school: school != nil)
    }
}

struct CourseEditContext: Sendable, Identifiable {
    var id = UUID().uuidString
    var expected: ScheduleData
    var localTermID: String?
    var course: Course?
    var availableWeeks: [Int]
    var week: Int
    var weekday: Int
    var firstMonday: String
    var weekCount: Int
    var creatingTerm: Bool
    var deleteOnly: Bool
    var arrangements: [LocalOccurrence]
}
struct CourseDraft: Equatable, Sendable {
    var name: String
    var location: String
    var teacher: String
    var weekdays: [Int]
    var weeks: [Int]
    var lessons: [Int]
    var firstMonday: String
    var weekCount: Int
    var normalized: CourseDraft {
        var draft = self
        draft.name = name.trimmed; draft.location = location.trimmed; draft.teacher = teacher.trimmed
        draft.weekdays = Set(weekdays).sorted(); draft.weeks = Set(weeks).sorted(); draft.lessons = Set(lessons).sorted()
        return draft
    }
}
struct UndoReceipt: Identifiable, Sendable { var id: String; var message: String; var academic: Bool }

actor ScheduleRepository {
    private let file = VaultFile(name: "schedule.vault", keyName: "schedule.v1", limit: 4 * 1024 * 1024)
    private var value: ScheduleData?
    private var encoded: Data?
    private var undo: (id: String, before: ScheduleData, after: Data, deadline: Date?)?
    func load() throws -> ScheduleData {
        undo = nil; value = nil
        let bytes = try file.read(); let data = try bytes.map { try ModelCoding.decode(ScheduleData.self, $0) } ?? ScheduleData()
        try validate(data); value = data; encoded = bytes; return data
    }
    private func current(_ expected: ScheduleData? = nil) throws -> ScheduleData {
        guard let value else { throw AppError.storage }
        guard try file.read() == encoded, expected == nil || expected == value else { throw AppError.conflict }
        return value
    }
    private func write(_ next: ScheduleData, lease: OperationLease? = nil) throws -> ScheduleData {
        _ = try current(); try validate(next); try lease?.requireValid()
        let bytes = try ModelCoding.encode(next)
        guard try file.read() == encoded else { throw AppError.conflict }
        try lease?.requireValid(); try file.save(bytes); value = next; encoded = bytes
        return next
    }
    private func validate(_ data: ScheduleData) throws {
        guard data.schema == 1, data.localTerms.count <= 100, Set(data.localTerms.map(\.id)).count == data.localTerms.count,
              data.selectedLocalTermID == nil || data.localTerms.contains(where: { $0.id == data.selectedLocalTermID }) else { throw AppError.storage }
        if let complete = data.complete {
            try ScheduleParser.validateTerm(complete.term)
            guard complete.weeks.count == complete.term.weekCount else { throw AppError.storage }
            for week in complete.weeks { try ScheduleParser.validateWeek(week) }
        }
        if let pending = data.pending { try ScheduleParser.validateTerm(pending.term); for week in pending.weeks { try ScheduleParser.validateWeek(week) } }
        for term in data.localTerms {
            guard ShanghaiClock.weekday(term.firstMonday) == 1, (1...60).contains(term.weekCount), term.courses.count <= 1000,
                  Set(term.courses.map(\.id)).count == term.courses.count, (term.schoolKey == nil) == (term.studentID == nil) else { throw AppError.storage }
            for course in term.courses {
                guard !course.name.isEmpty, course.name.count <= 300, course.location.count <= 500, course.teacher.count <= 500,
                      !course.occurrences.isEmpty, course.occurrences.count <= 420,
                      Set(course.occurrences).count == course.occurrences.count else { throw AppError.storage }
                for occurrence in course.occurrences {
                    guard (1...60).contains(occurrence.week), (1...7).contains(occurrence.weekday), !occurrence.lessons.isEmpty,
                          occurrence.lessons.count <= 20, occurrence.lessons.allSatisfy({ (1...20).contains($0) }),
                          Set(occurrence.lessons).count == occurrence.lessons.count else { throw AppError.storage }
                }
            }
        }
    }
    func begin(_ identity: SchoolIdentity, term: ScheduleTerm, lease: OperationLease) throws -> ScheduleData {
        undo = nil; var next = try current(); try lease.requireValid()
        if let existing = next.identity, existing.studentID != identity.studentID { throw AppError.validation("学校返回学号与课表绑定不一致，请先确认更换课表账号") }
        next.identity = identity
        if next.pending?.identity.studentID != identity.studentID || next.pending?.term.key != term.key || next.pending?.term.weekCount != term.weekCount { next.pending = PendingSchedule(identity: identity, term: term) }
        return try write(next, lease: lease)
    }
    func append(_ week: ScheduleWeek, lease: OperationLease) throws -> ScheduleData {
        var next = try current(); guard var pending = next.pending else { throw AppError.expired }
        try ScheduleParser.validateWeek(week)
        guard (1...pending.term.weekCount).contains(week.number) else { throw AppError.response }
        if let first = pending.weeks.first {
            guard ShanghaiClock.addDays((week.number - first.number) * 7, to: first.dates[0]) == week.dates[0] else { throw AppError.response }
        }
        pending.weeks.removeAll { $0.number == week.number }; pending.weeks.append(week); pending.weeks.sort { $0.number < $1.number }
        next.pending = pending; return try write(next, lease: lease)
    }
    func finish(lease: OperationLease) throws -> ScheduleData {
        var next = try current(); guard let pending = next.pending,
              pending.weeks.map(\.number) == Array(1...pending.term.weekCount) else { throw AppError.response }
        next.complete = SemesterSchedule(identity: pending.identity, term: pending.term, weeks: pending.weeks, updatedAt: Date())
        next.pending = nil
        for index in next.localTerms.indices where next.localTerms[index].adoptOnImport && next.localTerms[index].studentID == nil {
            next.localTerms[index].studentID = pending.identity.studentID; next.localTerms[index].schoolKey = pending.term.key
            next.localTerms[index].firstMonday = pending.weeks[0].dates[0]; next.localTerms[index].adoptOnImport = false
        }
        next.selectedLocalTermID = nil; return try write(next, lease: lease)
    }
    func cancelPending() throws -> ScheduleData { undo = nil; var next = try current(); next.pending = nil; return try write(next) }
    func unbind() throws -> ScheduleData { undo = nil; var next = try current(); next.identity = nil; next.complete = nil; next.pending = nil; return try write(next) }
    func selectLocal(_ id: String?) throws -> ScheduleData { undo = nil; var next = try current(); next.selectedLocalTermID = id; return try write(next) }
    func deleteLocal(_ id: String) throws -> ScheduleData {
        undo = nil; var next = try current(); next.localTerms.removeAll { $0.id == id }
        if next.selectedLocalTermID == id { next.selectedLocalTermID = nil }; return try write(next)
    }
    func editor(course: Course? = nil, week: Int = 1, weekday: Int = 1, localID: String? = nil, newTerm: Bool = false, deleteOnly: Bool = false) throws -> CourseEditContext {
        undo = nil; let data = try current(); let display = newTerm ? nil : data.display(localID: localID)
        let day = course.flatMap { ShanghaiClock.weekday($0.date) } ?? weekday
        let selectedWeek = course.flatMap { c in display?.weeks.first { $0.dates.contains(c.date) }?.number } ?? week
        let matching = display?.weeks.filter { w in w.courses.contains { c in course.map { c.seriesKey == $0.seriesKey && c.lessons == $0.lessons && ShanghaiClock.weekday(c.date) == day } ?? false } }.map(\.number) ?? []
        let arrangements = display?.weeks.flatMap { w in w.courses.filter { course != nil && $0.seriesKey == course!.seriesKey }.map {
            LocalOccurrence(week: w.number, weekday: ShanghaiClock.weekday($0.date) ?? 1, lessons: $0.lessons.sorted())
        } } ?? []
        return CourseEditContext(expected: data, localTermID: localID ?? display?.localTermID, course: course, availableWeeks: matching.sorted(),
                                 week: selectedWeek, weekday: day, firstMonday: display?.firstMonday ?? ShanghaiClock.monday(),
                                 weekCount: display?.weeks.count ?? 20, creatingTerm: newTerm || display == nil, deleteOnly: deleteOnly,
                                 arrangements: Array(Set(arrangements)).sorted { $0.week == $1.week ? $0.weekday < $1.weekday : $0.week < $1.week })
    }
    func edit(_ context: CourseEditContext, draft raw: CourseDraft, deletionWeeks: [Int] = []) throws -> (ScheduleData, UndoReceipt?) {
        undo = nil; let previous = try current(context.expected); let draft = raw.normalized; let source = context.course
        if let source, !context.deleteOnly, draft.name == source.name, draft.location == source.location,
           draft.teacher == source.teacher, draft.weekdays == [context.weekday], draft.weeks == context.availableWeeks,
           draft.lessons == source.lessons.sorted() { return (previous, nil) }
        let sourceWeeks = context.deleteOnly ? deletionWeeks : context.availableWeeks
        if source != nil { guard !sourceWeeks.isEmpty, sourceWeeks.allSatisfy({ context.availableWeeks.contains($0) }) else { throw AppError.validation("请选择要处理的原有周次") } }
        if !context.deleteOnly {
            guard !draft.name.isEmpty, draft.name.count <= 300, draft.location.count <= 500, draft.teacher.count <= 500,
                  !draft.weekdays.isEmpty, draft.weekdays.allSatisfy({ (1...7).contains($0) }), (1...60).contains(draft.weekCount),
                  !draft.weeks.isEmpty, draft.weeks.allSatisfy({ (1...draft.weekCount).contains($0) || context.availableWeeks.contains($0) }) else { throw AppError.validation("请填写课程名并选择有效的星期、节次和周次") }
            let late = draft.lessons.contains { $0 > 12 }
            let keeping = source != nil && draft.lessons == source!.lessons.sorted() && draft.weekdays == [context.weekday] && draft.weeks.allSatisfy { context.availableWeeks.contains($0) }
            guard !draft.lessons.isEmpty, draft.lessons.allSatisfy({ (1...12).contains($0) }) || (late && keeping) else { throw AppError.validation("请重新选择第 1–12 节；原有晚节次仅可整组保留") }
        }
        var next = previous
        if let source, source.source == .school {
            guard var school = next.complete else { throw AppError.conflict }
            let monday = school.weeks[0].dates[0]
            for index in school.weeks.indices {
                let number = school.weeks[index].number
                if sourceWeeks.contains(number) { school.weeks[index].courses.removeAll { $0.seriesKey == source.seriesKey && $0.lessons == source.lessons && ShanghaiClock.weekday($0.date) == context.weekday } }
                if !context.deleteOnly && draft.weeks.contains(number) {
                    for weekday in draft.weekdays {
                        var addition = source; addition.name = draft.name; addition.location = draft.location; addition.teacher = draft.teacher
                        addition.lessons = draft.lessons; addition.date = ShanghaiClock.addDays((number - 1) * 7 + weekday - 1, to: monday)!
                        if addition.name != source.name || addition.location != source.location || addition.teacher != source.teacher || addition.lessons != source.lessons || addition.date != source.date { addition.timeText = "" }
                        addition.weeksText = "第 \(number) 周"
                        if !school.weeks[index].courses.contains(where: { $0.occurrenceKey == addition.occurrenceKey }) { school.weeks[index].courses.append(addition) }
                    }
                }
            }
            next.complete = school
        } else {
            var local = next.localTerms.first { $0.id == context.localTermID }
            if local == nil {
                guard source == nil, ShanghaiClock.weekday(draft.firstMonday) == 1 else { throw AppError.validation("请选择第一周的周一日期") }
                let school = !context.creatingTerm && next.selectedLocalTermID == nil ? next.complete : nil
                local = LocalTerm(firstMonday: draft.firstMonday, weekCount: draft.weekCount, schoolKey: school?.term.key,
                                  studentID: school?.identity.studentID, adoptOnImport: next.identity == nil && next.localTerms.isEmpty())
            }
            guard var local else { throw AppError.conflict }
            if !context.creatingTerm { guard draft.firstMonday == local.firstMonday, draft.weekCount == local.weekCount else { throw AppError.conflict } }
            let old = local.courses.first { $0.id == source?.id }
            if source != nil && old == nil { throw AppError.conflict }
            let retained = old?.occurrences.filter { !(sourceWeeks.contains($0.week) && $0.weekday == context.weekday && $0.lessons == source?.lessons) } ?? []
            let additions = context.deleteOnly ? [] : draft.weeks.flatMap { week in draft.weekdays.map { LocalOccurrence(week: week, weekday: $0, lessons: draft.lessons) } }
            local.courses.removeAll { $0.id == old?.id }
            let sameContent = old?.name == draft.name && old?.location == draft.location && old?.teacher == draft.teacher
            let merged = Array(Set(retained + additions)).sorted { $0.week == $1.week ? $0.weekday < $1.weekday : $0.week < $1.week }
            if !context.deleteOnly, sameContent, let old, merged.count <= 420 {
                var replacement = old; replacement.occurrences = merged; local.courses.append(replacement)
            } else {
                if !retained.isEmpty, var old { old.occurrences = retained; local.courses.append(old) }
                let fresh = sameContent ? additions.filter { !retained.contains($0) } : additions
                if !fresh.isEmpty {
                    var addition = LocalCourse(name: draft.name, location: draft.location, teacher: draft.teacher, occurrences: Array(Set(fresh)))
                    if retained.isEmpty, let old { addition.id = old.id }; local.courses.append(addition)
                }
            }
            guard local.courses.count <= 1000 else { throw AppError.validation("本地课程数量已达上限（1000 条），请先删除不需要的课程") }
            next.localTerms.removeAll { $0.id == local.id }; next.localTerms.append(local)
            if next.complete == nil || next.selectedLocalTermID != nil || context.creatingTerm { next.selectedLocalTermID = local.id }
        }
        // Canonical comparison ignores array insertion order and interaction-only range anchors.
        func canonical(_ data: ScheduleData) -> ScheduleData {
            var data = data
            if var school = data.complete { for i in school.weeks.indices { school.weeks[i].courses.sort { $0.occurrenceKey < $1.occurrenceKey } }; data.complete = school }
            data.localTerms.sort { $0.id < $1.id }
            for i in data.localTerms.indices { data.localTerms[i].courses.sort { $0.id < $1.id }; for j in data.localTerms[i].courses.indices { data.localTerms[i].courses[j].occurrences.sort { String(describing: $0) < String(describing: $1) } } }
            return data
        }
        if canonical(next) == canonical(previous) { return (previous, nil) }
        let result = try write(next)
        if source != nil {
            let id = UUID().uuidString; undo = (id, previous, encoded!, nil)
            return (result, UndoReceipt(id: id, message: context.deleteOnly ? "已删除 \(sourceWeeks.count) 次安排" : "课程已保存", academic: false))
        }
        return (result, nil)
    }
    func activateUndo(_ id: String, seconds: Double) -> Date? {
        guard var receipt = undo, receipt.id == id else { return nil }
        if receipt.deadline == nil { receipt.deadline = Date().addingTimeInterval(seconds); undo = receipt }
        return receipt.deadline
    }
    func undoEdit(_ id: String) throws -> ScheduleData {
        guard let receipt = undo, receipt.id == id, let deadline = receipt.deadline, Date() < deadline,
              try file.read() == receipt.after else { undo = nil; throw AppError.conflict }
        undo = nil; return try write(receipt.before)
    }
    func invalidateUndo(_ id: String? = nil) { if id == nil || undo?.id == id { undo = nil } }
    func reset() throws -> ScheduleData { undo = nil; try file.reset(); value = ScheduleData(); encoded = nil; return value! }
}

actor AcademicRepository {
    private let file = VaultFile(name: "academic.vault", keyName: "academic.v1", limit: 2 * 1024 * 1024)
    private var value: AcademicData?
    private var encoded: Data?
    private var undo: (id: String, before: AcademicData, after: Data, deadline: Date?)?
    func load() throws -> AcademicData {
        undo = nil; value = nil; let bytes = try file.read()
        let data = try bytes.map { try ModelCoding.decode(AcademicData.self, $0) } ?? AcademicData()
        try validate(data); value = data; encoded = bytes; return data
    }
    private func current(_ revision: Int64? = nil) throws -> AcademicData {
        guard let value else { throw AppError.storage }
        guard try file.read() == encoded, revision == nil || revision == value.revision else { throw AppError.conflict }
        return value
    }
    private func validate(_ data: AcademicData) throws {
        guard data.schema == 1, data.revision >= 0, data.exams.count <= 2000, Set(data.exams.map(\.id)).count == data.exams.count else { throw AppError.storage }
        for exam in data.exams { guard !exam.fields.name.trimmed.isEmpty, exam.fields.name.count <= 300, exam.fields.location.count <= 500, exam.fields.note.count <= 2000 else { throw AppError.storage } }
        if let grades = data.grades { guard grades.degree.count <= 5000, grades.elective.count <= 5000, Set(grades.credits.keys).isSubset(of: ["学位学分", "选修学分", "已修学分", "应修学分"]) else { throw AppError.storage } }
    }
    private func write(_ candidate: AcademicData, lease: OperationLease? = nil) throws -> AcademicData {
        let previous = try current(); var next = candidate; next.revision = previous.revision
        if next == previous { return previous }
        guard previous.revision < Int64.max else { throw AppError.storage }
        next.revision += 1; try validate(next); let bytes = try ModelCoding.encode(next)
        try lease?.requireValid(); guard try file.read() == encoded else { throw AppError.conflict }
        try lease?.requireValid(); try file.save(bytes); encoded = bytes; value = next; return next
    }
    private func mergedExams(_ current: [ExamRecord], originals: [SchoolExam]) -> [ExamRecord] {
        let old = Dictionary(grouping: current.filter { $0.original != nil && !$0.retained }, by: { $0.original! })
        let counts = Dictionary(grouping: originals, by: { $0 }).mapValues(\.count)
        var matched = Set<String>()
        let fresh = originals.map { original in
            if let candidates = old[original], candidates.count == 1, counts[original] == 1 {
                var record = candidates[0]; record.original = original; record.retained = false; matched.insert(record.id); return record
            }
            return ExamRecord(original: original, fields: original.fields)
        }
        let retained = current.filter { $0.original != nil && !matched.contains($0.id) && ($0.modified || $0.hidden) }.map { record in var record = record; record.retained = true; return record }
        return fresh + retained + current.filter { $0.original == nil }
    }
    func commit(identity: SchoolIdentity, exams: [SchoolExam]? = nil, grades: GradeSnapshot? = nil, revision: Int64,
                allowChange: Bool, lease: OperationLease) throws -> AcademicData {
        undo = nil; var next = try current(revision); try lease.requireValid()
        guard !identity.studentID.isEmpty, identity.studentID.count <= 128, identity.name.count <= 100 else { throw AppError.identity }
        let changing = next.identity != nil && next.identity?.studentID != identity.studentID
        if changing {
            guard allowChange else { throw AppError.identity }; next.exams.removeAll { $0.original != nil }; next.grades = nil; next.examUpdatedAt = nil
        }
        next.identity = identity
        if let exams { guard exams.count <= 2000 else { throw AppError.response }; next.exams = mergedExams(next.exams, originals: exams); next.examUpdatedAt = Date() }
        if let grades { next.grades = grades }
        return try write(next, lease: lease)
    }
    func editExam(_ id: String?, fields raw: ExamFields, revision: Int64, delete: Bool = false, restore: Bool = false) throws -> (AcademicData, UndoReceipt?) {
        undo = nil; let previous = try current(revision); var next = previous
        var fields = raw; fields.name = fields.name.trimmed; fields.location = fields.location.trimmed; fields.note = fields.note.trimmed
        guard !fields.name.isEmpty, fields.name.count <= 300, fields.location.count <= 500, fields.note.count <= 2000 else { throw AppError.validation("请填写有效考试名称，名称最多 300 字") }
        if let start = ShanghaiClock.examTime(fields.start), let end = ShanghaiClock.examTime(fields.end), end < start { throw AppError.validation("结束时间不能早于开始时间") }
        if let id {
            guard let index = next.exams.firstIndex(where: { $0.id == id }) else { throw AppError.conflict }
            let existing = next.exams[index]
            if delete {
                if existing.original == nil || existing.retained { next.exams.remove(at: index) }
                else { next.exams[index].hidden = true }
            } else if restore {
                guard let original = existing.original else { throw AppError.validation("自定义考试没有学校原始内容") }
                next.exams[index].fields = original.fields; next.exams[index].hidden = false
            } else {
                if existing.original == nil { guard ShanghaiClock.examTime(fields.start) != nil else { throw AppError.validation("请选择有效考试时间") } }
                next.exams[index].fields = fields
            }
        } else {
            guard ShanghaiClock.examTime(fields.start) != nil else { throw AppError.validation("请选择有效考试时间") }
            next.exams.append(ExamRecord(fields: fields))
        }
        guard next.exams.count <= 2000 else { throw AppError.validation("考试记录已达上限，请先删除不需要的记录") }
        let result = try write(next)
        if id != nil, result != previous {
            let token = UUID().uuidString; undo = (token, previous, encoded!, nil)
            return (result, UndoReceipt(id: token, message: delete ? "考试已隐藏或删除" : "考试已保存", academic: true))
        }
        return (result, nil)
    }
    func unbind() throws -> AcademicData { undo = nil; var next = try current(); next.identity = nil; next.exams.removeAll { $0.original != nil }; next.examUpdatedAt = nil; next.grades = nil; return try write(next) }
    func setHidden(_ id: String, hidden: Bool, revision: Int64) throws -> (AcademicData, UndoReceipt?) {
        undo = nil; let previous = try current(revision); var next = previous
        guard let index = next.exams.firstIndex(where: { $0.id == id }), next.exams[index].original != nil else { throw AppError.conflict }
        next.exams[index].hidden = hidden; let result = try write(next)
        guard result != previous else { return (result, nil) }
        let token = UUID().uuidString; undo = (token, previous, encoded!, nil)
        return (result, UndoReceipt(id: token, message: hidden ? "考试已隐藏" : "考试已恢复显示", academic: true))
    }
    func activateUndo(_ id: String, seconds: Double) -> Date? { guard var receipt = undo, receipt.id == id else { return nil }; if receipt.deadline == nil { receipt.deadline = Date().addingTimeInterval(seconds); undo = receipt }; return receipt.deadline }
    func undoEdit(_ id: String) throws -> AcademicData { guard let receipt = undo, receipt.id == id, let deadline = receipt.deadline, Date() < deadline, try file.read() == receipt.after else { undo = nil; throw AppError.conflict }; undo = nil; return try write(receipt.before) }
    func invalidateUndo(_ id: String? = nil) { if id == nil || undo?.id == id { undo = nil } }
    func reset() throws -> AcademicData { undo = nil; try file.reset(); value = AcademicData(); encoded = nil; return value! }
}

actor AcademicCredentialRepository {
    private let file = VaultFile(name: "academic-credentials.vault", keyName: "academic.credentials.v1", limit: 16 * 1024)
    func load() throws -> AcademicCredential? {
        guard let bytes = try file.read() else { return nil }
        let value = try ModelCoding.decode(AcademicCredential.self, bytes)
        guard !value.studentID.isEmpty, value.studentID.count <= 128, !value.password.isEmpty, value.password.count <= 512 else { throw AppError.storage }
        return value
    }
    func save(_ value: AcademicCredential, lease: OperationLease) throws {
        _ = try load(); try lease.requireValid(); let bytes = try ModelCoding.encode(value); try lease.requireValid(); try file.save(bytes)
    }
    func clear(matching studentID: String? = nil) throws {
        if let studentID { guard try load()?.studentID == studentID else { return } }
        try file.remove()
    }
    func reset() throws { try file.reset() }
}

actor BillingCacheRepository {
    private let file = VaultFile(name: "billing-cache.vault", keyName: "billing.cache.v1", limit: 32 * 1024)
    func load(_ accountID: String) -> BillingSnapshot? {
        guard let bytes = try? file.read(), let value = try? ModelCoding.decode(BillingCache.self, bytes), value.accountID == accountID,
              value.snapshot.devices.count <= 64, value.snapshot.metrics.count <= 4 else { return nil }
        return value.snapshot
    }
    func save(accountID: String, snapshot: BillingSnapshot, lease: OperationLease) {
        guard lease.isValid, snapshot.devices.count <= 64, snapshot.metrics.count <= 4,
              snapshot.metrics.allSatisfy({ ["已用流量", "可用流量", "消费保护", "账户余额"].contains($0.key) && $0.value.count <= 128 && $0.unit.count <= 32 }),
              snapshot.devices.allSatisfy({ $0.address.count <= 128 && $0.mac.count <= 64 && $0.loginTime.count <= 128 && $0.duration.count <= 128 && $0.traffic.count <= 128 }) else { return }
        var sanitized = snapshot
        sanitized.devices = snapshot.devices.enumerated().map { index, device in var device = device; device.id = "cached-device-\(index)"; return device }
        sanitized.notice = nil; sanitized.deviceError = snapshot.deviceError == nil ? nil : "在线设备读取失败，请重新查询"
        guard let bytes = try? ModelCoding.encode(BillingCache(accountID: accountID, snapshot: sanitized)), lease.isValid else { return }
        try? file.save(bytes)
    }
    func clear() { try? file.remove() }
}
