import Foundation

enum AppInfo {
    static let name = "工小助"
    static let version = Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? "0.5.9"
    static let build = Int(Bundle.main.infoDictionary?["CFBundleVersion"] as? String ?? "40") ?? 40
    static let repository = URL(string: "https://github.com/TBeiMu/BJUT_gxz")!
}

enum AppError: Error, LocalizedError, Sendable, Equatable {
    case connection, networkChanged, wifiRequired, localNetworkDenied, cancelled
    case response, address, login, expired, identity, storage, conflict
    case validation(String)
    var errorDescription: String? {
        switch self {
        case .connection: "服务无法连接或请求超时，请确认网络后手动重试"
        case .networkChanged: "网络已变化，旧请求已停止，请手动继续"
        case .wifiRequired: "请先连接 WiFi"
        case .localNetworkDenied: "请在系统设置中允许工小助访问本地网络"
        case .cancelled: "操作已取消"
        case .response: "学校响应无法识别，已保留原资料，请停止重试并反馈此提示"
        case .address: "学校页面出现未支持的地址，已停止请求"
        case .login: "学校登录未完成，请检查信息后手动重新提交"
        case .expired: "学校登录态已失效，请重新登录"
        case .identity: "未能确认学校返回的学号，已停止读取"
        case .storage: "本机加密资料无法读取或保存，原资料已保留，请重试或确认后清除"
        case .conflict: "资料已变化，请重新读取后再操作"
        case .validation(let message): message
        }
    }
}

func safeMessage(_ error: Error) -> String {
    if let error = error as? AppError { return error.localizedDescription }
    if error is CancellationError { return AppError.cancelled.localizedDescription }
    return AppError.connection.localizedDescription
}

enum NetworkMode: String, Codable, CaseIterable, Identifiable, Sendable {
    case auto, publicWifi, dormWifi
    var id: String { rawValue }
    var title: String {
        switch self { case .auto: "自动识别"; case .publicWifi: "公共 WiFi"; case .dormWifi: "宿舍 WiFi" }
    }
}

struct CampusSettings: Codable, Equatable, Sendable {
    var autoLogin = true
    var networkMode = NetworkMode.auto
}

struct CampusAccount: Codable, Equatable, Identifiable, Sendable, CustomStringConvertible {
    var id = UUID().uuidString
    var label = ""
    var username = ""
    var password = ""
    var customLabel = false
    var nameLookupVersion = 0
    var nameLookupPending = false
    var canFillName: Bool { !customLabel && (label.isEmpty || label == username) }
    var hasNameOpportunity: Bool { canFillName && (nameLookupPending || AppInfo.build > nameLookupVersion) }
    var title: String { label.isEmpty ? username : label }
    var description: String { "CampusAccount(<redacted>)" }
}

struct AccountData: Codable, Equatable, Sendable, CustomStringConvertible {
    var schema = 1
    var accounts: [CampusAccount] = []
    var defaultAccountID: String?
    var settings = CampusSettings()
    var defaultAccount: CampusAccount? { accounts.first { $0.id == defaultAccountID } }
    var description: String { "AccountData(<redacted>)" }
}

struct SchoolIdentity: Codable, Equatable, Sendable, CustomStringConvertible {
    var studentID: String
    var name = ""
    var description: String { "SchoolIdentity(<redacted>)" }
}

struct ScheduleTerm: Codable, Equatable, Sendable {
    var year: String
    var term: String
    var weekCount: Int
    var currentWeek: Int
    var key: String { "\(year)/\(term)" }
    var title: String { "\(year) 学年 · 第 \(term) 学期" }
}

enum CourseSource: String, Codable, Sendable { case school, custom }
struct Course: Codable, Equatable, Hashable, Identifiable, Sendable, CustomStringConvertible {
    var id: String
    var name: String
    var date: String
    var lessons: [Int]
    var location = ""
    var teacher = ""
    var timeText = ""
    var weeksText = ""
    var source = CourseSource.school
    var seriesKey: String { [id, name, location, teacher, source.rawValue].joined(separator: "\u{1f}") }
    var occurrenceKey: String { "\(seriesKey)\u{1f}\(date)\u{1f}\(lessons.sorted())" }
    var description: String { "Course(<redacted>)" }
}

struct ScheduleWeek: Codable, Equatable, Sendable {
    var number: Int
    var dates: [String]
    var courses: [Course]
}
struct SemesterSchedule: Codable, Equatable, Sendable {
    var identity: SchoolIdentity
    var term: ScheduleTerm
    var weeks: [ScheduleWeek]
    var updatedAt: Date
}
struct PendingSchedule: Codable, Equatable, Sendable {
    var identity: SchoolIdentity
    var term: ScheduleTerm
    var weeks: [ScheduleWeek] = []
}

struct LocalOccurrence: Codable, Equatable, Hashable, Sendable {
    var week: Int
    var weekday: Int
    var lessons: [Int]
}
struct LocalCourse: Codable, Equatable, Identifiable, Sendable {
    var id = UUID().uuidString
    var name: String
    var location = ""
    var teacher = ""
    var occurrences: [LocalOccurrence]
}
struct LocalTerm: Codable, Equatable, Identifiable, Sendable {
    var id = UUID().uuidString
    var firstMonday: String
    var weekCount = 20
    var schoolKey: String?
    var studentID: String?
    var adoptOnImport = true
    var courses: [LocalCourse] = []
    var title: String { "\(firstMonday) 起 · \(weekCount) 周" }
}
struct ScheduleData: Codable, Equatable, Sendable {
    var schema = 1
    var identity: SchoolIdentity?
    var complete: SemesterSchedule?
    var pending: PendingSchedule?
    var localTerms: [LocalTerm] = []
    var selectedLocalTermID: String?
}

struct CourseBlock: Identifiable, Sendable {
    var day: Int
    var first: Int
    var last: Int
    var courses: [Course]
    var id: String { "\(day)-\(first)-\(last)" }
}

func courseBlocks(_ week: ScheduleWeek) -> [CourseBlock] {
    week.dates.enumerated().flatMap { day, date in
        let spans = week.courses.filter { $0.date == date }.flatMap { course in
            var groups: [[Int]] = []
            for lesson in Set(course.lessons).sorted() {
                if let last = groups.last?.last, last + 1 == lesson { groups[groups.count - 1].append(lesson) }
                else { groups.append([lesson]) }
            }
            return groups.map { CourseBlock(day: day, first: $0.first!, last: $0.last!, courses: [course]) }
        }.sorted { $0.first < $1.first }
        var merged: [CourseBlock] = []
        for span in spans {
            if let last = merged.last, span.first <= last.last {
                merged[merged.count - 1].last = max(last.last, span.last)
                for course in span.courses where !merged[merged.count - 1].courses.contains(course) {
                    merged[merged.count - 1].courses.append(course)
                }
            } else { merged.append(span) }
        }
        return merged
    }
}

struct BillingMetric: Codable, Equatable, Identifiable, Sendable {
    var key: String
    var value: String
    var unit = ""
    var id: String { key }
}
struct OnlineDevice: Codable, Equatable, Identifiable, Sendable, CustomStringConvertible {
    var id: String
    var address = ""
    var mac = ""
    var loginTime = ""
    var duration = ""
    var traffic = "未获取"
    var description: String { "OnlineDevice(<redacted>)" }
}
struct BillingSnapshot: Codable, Equatable, Sendable {
    var metrics: [BillingMetric] = []
    var devices: [OnlineDevice] = []
    var deviceError: String?
    var notice: String?
    var queriedAt: Date
    func metric(_ key: String) -> BillingMetric { metrics.first { $0.key == key } ?? BillingMetric(key: key, value: "未获取") }
}
struct BillingCache: Codable, Equatable, Sendable {
    var accountID: String
    var snapshot: BillingSnapshot
}
struct BillingLoginForm: Sendable, CustomStringConvertible {
    var token: String
    var action: URL
    var html: String
    var pageURL: URL
    var fields: [(String, String)]
    var usernameField: String
    var passwordField: String
    var captchaField: String?
    var captchaURL: URL?
    var captchaRequired: Bool
    var description: String { "BillingLoginForm(<memory-only>)" }
}

struct SchoolExam: Codable, Equatable, Hashable, Sendable {
    var code: String
    var name: String
    var className: String
    var start: String
    var end: String
    var location: String
}
struct ExamFields: Codable, Equatable, Sendable {
    var name: String
    var start = ""
    var end = ""
    var location = ""
    var note = ""
}
struct ExamRecord: Codable, Equatable, Identifiable, Sendable {
    var id = UUID().uuidString
    var original: SchoolExam?
    var fields: ExamFields
    var retained = false
    var hidden = false
    var modified: Bool { original.map { fields != $0.fields } ?? false }
}
extension SchoolExam {
    var fields: ExamFields { ExamFields(name: name, start: start, end: end, location: location) }
}
struct GradeRecord: Codable, Equatable, Identifiable, Sendable {
    var course: String
    var credits: String
    var term: String
    var grade: String
    var restricted = false
    var id: String { [course, credits, term, grade, String(restricted)].joined(separator: "\u{1f}") }
}
struct GradeSnapshot: Codable, Equatable, Sendable {
    var degree: [GradeRecord]
    var elective: [GradeRecord]
    var credits: [String: String]
    var updatedAt: Date
}
struct AcademicData: Codable, Equatable, Sendable {
    var schema = 1
    var identity: SchoolIdentity?
    var exams: [ExamRecord] = []
    var examUpdatedAt: Date?
    var grades: GradeSnapshot?
    var revision: Int64 = 0
}
struct AcademicCredential: Codable, Equatable, Sendable, CustomStringConvertible {
    var studentID: String
    var password: String
    var description: String { "AcademicCredential(<redacted>)" }
}
enum AcademicTarget: String, Identifiable, Sendable { case exams, grades; var id: String { rawValue }; var title: String { self == .exams ? "考试" : "成绩" } }
struct AcademicLoginForm: Sendable, CustomStringConvertible {
    var token: String
    var action: URL
    var fields: [(String, String)]
    var usernameField: String
    var passwordField: String
    var captchaField: String
    var publicKey: String
    var captcha: Data
    var description: String { "AcademicLoginForm(<memory-only>)" }
}

struct SchoolLoginPage: Sendable, Identifiable, CustomStringConvertible {
    var id = UUID().uuidString
    var url: URL
    var html: String
    var purpose: Purpose
    enum Purpose: Sendable { case schedule, billing }
    var description: String { "SchoolLoginPage(<memory-only>)" }
}
struct WebResource: Sendable { var data: Data; var mime: String; var url: URL }

struct IOSUpdate: Codable, Equatable, Sendable, Identifiable {
    var platform: String
    var versionName: String
    var versionCode: Int
    var minSystemVersion: String
    var ipaURL: String
    var ipaSize: Int64
    var ipaSHA256: String
    var releaseTag: String
    var releaseNotes: String
    var sourceCommit: String
    var unsigned: Bool
    var id: Int { versionCode }
}

extension String {
    var trimmed: String { trimmingCharacters(in: .whitespacesAndNewlines) }
    func matches(_ pattern: String) -> Bool {
        guard let regex = try? NSRegularExpression(pattern: "^(?:\(pattern))$", options: [.dotMatchesLineSeparators]) else { return false }
        return regex.firstMatch(in: self, range: NSRange(startIndex..., in: self)) != nil
    }
    func captures(_ pattern: String, options: NSRegularExpression.Options = []) -> [[String]] {
        guard let regex = try? NSRegularExpression(pattern: pattern, options: options) else { return [] }
        return regex.matches(in: self, range: NSRange(startIndex..., in: self)).map { match in
            (0..<match.numberOfRanges).map { index in Range(match.range(at: index), in: self).map { String(self[$0]) } ?? "" }
        }
    }
}

func scalar(_ value: Any?) -> String {
    if let value = value as? String { return value }
    if let value = value as? NSNumber { return value.stringValue }
    return ""
}
func jsonObject(_ data: Data) throws -> [String: Any] {
    guard let result = try JSONSerialization.jsonObject(with: data) as? [String: Any] else { throw AppError.response }
    return result
}
