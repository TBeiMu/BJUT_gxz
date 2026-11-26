import Foundation
@preconcurrency import SwiftSoup

enum ScheduleGate: Sendable { case login(SchoolLoginPage); case ready(SchoolIdentity, ScheduleTerm) }
private struct CASForm: Sendable { var action: URL; var html: String; var hidden: [(String, String)]; var token: String }

enum ScheduleParser {
    static func data(_ body: Data) throws -> [String: Any] {
        let root = try jsonObject(body)
        guard scalar(root["e"]) == "0" else { throw scalar(root["e"]) == "10013" ? AppError.expired : AppError.response }
        guard let data = root["d"] as? [String: Any] else { throw AppError.response }; return data
    }
    static func identity(_ body: Data, expected: String? = nil) throws -> SchoolIdentity {
        let data = try data(body); let base = data["base"] as? [String: Any]
        let containers = [base?["role"] as? [String: Any], base, data, data["userInfo"] as? [String: Any], data["userinfo"] as? [String: Any], data["user"] as? [String: Any]].compactMap { $0 }
        let explicit = Set(containers.flatMap { dictionary in ["studentId", "student_id", "number"].map { scalar(dictionary[$0]).trimmed } }.filter { !$0.isEmpty })
        let fallback = Set(containers.map { scalar($0["username"]).trimmed }.filter { !$0.isEmpty })
        let candidates = explicit.isEmpty ? fallback : explicit
        guard candidates.count == 1, let id = candidates.first, id.matches("[A-Za-z0-9._@-]{1,128}"), expected == nil || expected?.trimmed == id else { throw AppError.identity }
        let name = scalar(base?["realname"]).trimmed
        let names = [base, data, data["userInfo"] as? [String: Any], data["userinfo"] as? [String: Any], data["user"] as? [String: Any]].compactMap { $0 }.flatMap { dictionary in ["name", "realname", "realName"].map { scalar(dictionary[$0]) } }
        let resolved = name.isEmpty ? names.first(where: { !$0.isEmpty }) ?? "" : name
        guard resolved.count <= 100 else { throw AppError.identity }
        return SchoolIdentity(studentID: id, name: resolved)
    }
    static func term(_ body: Data) throws -> ScheduleTerm {
        let data = try data(body)
        guard let parameters = data["params"] as? [String: Any], let terms = data["termInfo"] as? [[String: Any]] else { throw AppError.response }
        let year = scalar(parameters["year"]); let term = scalar(parameters["term"])
        let selected = terms.filter { scalar($0["year"]) == year && scalar($0["term"]) == term }
        guard selected.count == 1, let count = Int(scalar(selected[0]["countweek"])), let week = Int(scalar(parameters["week"])) else { throw AppError.response }
        let result = ScheduleTerm(year: year, term: term, weekCount: count, currentWeek: week); try validateTerm(result); return result
    }
    static func validateTerm(_ term: ScheduleTerm) throws {
        guard term.year.matches("[0-9]{4}-[0-9]{4}"), ["1", "2", "3"].contains(term.term), (1...60).contains(term.weekCount),
              (1...term.weekCount).contains(term.currentWeek) else { throw AppError.response }
    }
    static func validateWeek(_ week: ScheduleWeek) throws {
        guard (1...60).contains(week.number), week.dates.count == 7, week.courses.count <= 500,
              ShanghaiClock.weekday(week.dates[0]) == 1, (0..<7).allSatisfy({ ShanghaiClock.addDays($0, to: week.dates[0]) == week.dates[$0] }) else { throw AppError.response }
        for course in week.courses {
            guard !course.name.trimmed.isEmpty, course.name.count <= 300, course.id.count <= 256, week.dates.contains(course.date),
                  !course.lessons.isEmpty, course.lessons.count <= 20, course.lessons.allSatisfy({ (1...20).contains($0) }),
                  [course.location, course.teacher, course.timeText, course.weeksText].allSatisfy({ $0.count <= 500 }) else { throw AppError.response }
        }
    }
    static func week(_ body: Data, number: Int) throws -> ScheduleWeek {
        let data = try data(body)
        guard let rawDates = data["weekdays"] as? [Any], let classes = data["classes"] else { throw AppError.response }
        let dates = try rawDates.map { value -> String in
            let raw = scalar(value).replacingOccurrences(of: "/", with: "-")
            guard let date = ShanghaiClock.parse(raw, "yyyy-M-d") else { throw AppError.response }; return ShanghaiClock.day(date)
        }
        guard dates.count == 7 else { throw AppError.response }
        var courses: [Course] = []
        func flatten(_ raw: Any, day: Int?, depth: Int = 0) throws {
            guard depth <= 4, courses.count <= 500 else { throw AppError.response }
            if let array = raw as? [Any] { for item in array { try flatten(item, day: day, depth: depth + 1) }; return }
            guard let object = raw as? [String: Any] else { throw AppError.response }
            if object["course_name"] == nil { for value in object.values { try flatten(value, day: day, depth: depth + 1) }; return }
            let name = scalar(object["course_name"])
            let explicit = scalar(object["date"])
            let explicitDate: String? = explicit.isEmpty ? nil : ShanghaiClock.parse(explicit.replacingOccurrences(of: "/", with: "-"), "yyyy-M-d").map { ShanghaiClock.day($0) }
            if !explicit.isEmpty && explicitDate == nil { throw AppError.response }
            let weekday = Int(scalar(object["weekday"])).map { $0 == 0 ? 6 : $0 - 1 }
            guard let index = day ?? weekday ?? explicitDate.flatMap({ dates.firstIndex(of: $0) }), (0..<7).contains(index),
                  explicitDate == nil || explicitDate == dates[index], !name.isEmpty else { throw AppError.response }
            let lessons: [Int]
            if let values = object["lessArr"] as? [Any] {
                lessons = try values.map { guard let number = Int(scalar($0)) else { throw AppError.response }; return number }
            } else { lessons = try parseLessons(scalar(object["lessons"])) }
            let rawID = scalar(object["course_id"])
            let course = Course(id: rawID.isEmpty ? name : rawID, name: name, date: dates[index], lessons: Set(lessons).sorted(),
                                location: scalar(object["location"]), teacher: scalar(object["teacher"]), timeText: scalar(object["course_time"]), weeksText: scalar(object["week"]))
            if !courses.contains(course) { courses.append(course) }
        }
        if let array = classes as? [Any] {
            if array.isEmpty { }
            else if array.allSatisfy({ ($0 as? [String: Any])?["course_name"] != nil }) { try flatten(array, day: nil) }
            else if array.count == 7 { for (day, items) in array.enumerated() { try flatten(items, day: day) } }
            else { throw AppError.response }
        } else if let dictionary = classes as? [String: Any] {
            let keys = Set(dictionary.keys)
            if keys.isEmpty { }
            else if keys == Set((0...6).map(String.init)) || keys == Set((1...7).map(String.init)) {
                let start = keys.contains("0") ? 0 : 1
                for day in 0..<7 { try flatten(dictionary[String(day + start)]!, day: day) }
            } else if keys.isSubset(of: Set(dates)) { for (date, items) in dictionary { try flatten(items, day: dates.firstIndex(of: date)) } }
            else { try flatten(dictionary, day: nil) }
        } else { throw AppError.response }
        let result = ScheduleWeek(number: number, dates: dates, courses: courses); try validateWeek(result); return result
    }
    static func parseLessons(_ raw: String) throws -> [Int] {
        let text = raw.replacingOccurrences(of: "第", with: "").replacingOccurrences(of: "节", with: "").trimmed
        guard text.matches("[0-9]+(?:\\s*[-~至、,，]\\s*[0-9]+)*") else { throw AppError.response }
        var output: [Int] = []
        for piece in text.components(separatedBy: CharacterSet(charactersIn: "、,，")) {
            let numbers = piece.components(separatedBy: CharacterSet(charactersIn: "-~至")).compactMap { Int($0.trimmed) }
            guard let first = numbers.first, let last = numbers.last, first <= last, numbers.count <= 2,
                  (1...20).contains(first), (1...20).contains(last) else { throw AppError.response }
            output.append(contentsOf: first...last)
        }
        return Set(output).sorted()
    }
}

actor ScheduleSession {
    nonisolated let transport: NetworkTransport
    let client: CookieHTTPClient
    private var form: CASForm?
    private var submittedUsername: String?
    private var identity: SchoolIdentity?
    private var resources: [String: WebResource] = [:]
    init(transport: NetworkTransport) { self.transport = transport; client = CookieHTTPClient(transport: transport, purpose: .schedule) }
    func open() async throws -> ScheduleGate { try await gate(client.follow(URL(string: Addresses.schedule + "/yjskb/wap/default/get-index")!, referer: URL(string: Addresses.schedule + "/site/graduateSchedule/index"))) }
    private func loginIfNeeded(_ response: HTTPResponse) async throws -> SchoolLoginPage? {
        if response.url.host == "cas.bjut.edu.cn" { return try await loginPage(response) }
        if let root = try? jsonObject(response.body), scalar(root["e"]) == "10013" {
            guard let data = root["d"] as? [String: Any], let address = data["login_url"] as? String else { throw AppError.expired }
            return try await loginPage(client.follow(Addresses.resolve(address, base: response.url)))
        }
        return nil
    }
    private func gate(_ response: HTTPResponse) async throws -> ScheduleGate {
        if let page = try await loginIfNeeded(response) { return .login(page) }
        let term = try ScheduleParser.term(response.body)
        let info = try await client.follow(URL(string: Addresses.schedule + "/uc/wap/user/get-info")!, referer: URL(string: Addresses.schedule + "/site/graduateSchedule/index"))
        if let page = try await loginIfNeeded(info) { return .login(page) }
        let identity = try ScheduleParser.identity(info.body, expected: submittedUsername)
        try transport.lease.requireValid(); self.identity = identity; form = nil; resources.removeAll()
        return .ready(identity, term)
    }
    private func loginPage(_ original: HTTPResponse) async throws -> SchoolLoginPage {
        guard original.url.host == "cas.bjut.edu.cn", original.url.path == "/login" else { throw AppError.address }
        var response = original
        var components = URLComponents(url: original.url, resolvingAgainstBaseURL: false)!
        if components.queryItems?.first(where: { $0.name == "locale" })?.value != "zh_cn" {
            components.queryItems = (components.queryItems ?? []).filter { $0.name != "locale" } + [URLQueryItem(name: "locale", value: "zh_cn")]
            response = try await client.follow(components.url!)
        }
        let html = try response.text(); let document = try SwiftSoup.parse(html, response.url.absoluteString)
        guard let login = try document.select("form#loginForm").first(), try login.attr("method").lowercased() == "post",
              try login.select("input[name=username]").first() != nil, try login.select("input[name=password]").first() != nil else { throw AppError.login }
        let rawAction = try login.attr("action")
        let action = try Addresses.resolve(rawAction.isEmpty ? response.url.absoluteString : rawAction, base: response.url)
        guard action.host == response.url.host, action.path == "/login", Addresses.allowed(action, .schedule) else { throw AppError.address }
        var hidden: [(String, String)] = []
        for input in try login.select("input[name]").array() {
            let name = try input.attr("name")
            if !["username", "password", "captcha"].contains(name) { hidden.append((name, try input.attr("value"))) }
        }
        guard hidden.contains(where: { $0.0 == "execution" && !$0.1.isEmpty }), hidden.count <= 64 else { throw AppError.login }
        let token = UUID().uuidString; form = CASForm(action: action, html: html, hidden: hidden, token: token)
        return SchoolLoginPage(id: token, url: response.url, html: html, purpose: .schedule)
    }
    func submit(token: String, values: [String: String]) async throws -> ScheduleGate {
        let result = try await submitForm(token: token, values: values)
        if result.url.host == "cas.bjut.edu.cn" { return try await gate(result) }
        return try await open()
    }
    private func submitForm(token: String, values: [String: String]) async throws -> HTTPResponse {
        try transport.lease.requireValid()
        guard let form, form.token == token else { throw AppError.expired }
        guard values.count <= 12, values.keys.allSatisfy({ ["username", "password", "captcha", "type", "submit"].contains($0) }),
              values.values.allSatisfy({ $0.count <= 1024 }), let username = values["username"], !username.trimmed.isEmpty,
              username.count <= 128, let password = values["password"], !password.isEmpty, password.count <= 512,
              values["type"] == nil || values["type"] == "username_password" else { throw AppError.login }
        self.form = nil; resources.removeAll(); submittedUsername = username.trimmed
        let fields = form.hidden.filter { values[$0.0] == nil } + values.sorted { $0.key < $1.key }.map { ($0.key, $0.value) }
        return try await client.follow(form.action, fields: fields, referer: form.action)
    }
    func week(_ term: ScheduleTerm, number: Int) async throws -> ScheduleWeek {
        guard identity != nil, (1...term.weekCount).contains(number) else { throw AppError.expired }
        let response = try await client.follow(URL(string: Addresses.schedule + "/yjskb/wap/default/get-datatmp")!,
                                               fields: [("year", term.year), ("term", term.term), ("week", String(number)), ("type", "2")],
                                               referer: URL(string: Addresses.schedule + "/site/graduateSchedule/index"))
        guard response.url.host == "itsapp.bjut.edu.cn" else { throw AppError.expired }
        return try ScheduleParser.week(response.body, number: number)
    }
    func resource(token: String, url: URL) async throws -> WebResource {
        guard form?.token == token, Addresses.casResource(url) else { throw AppError.address }
        if let resource = resources[url.absoluteString], !url.path.hasPrefix("/captcha") { return resource }
        guard resources.count < 80 else { throw AppError.response }
        let response = try await client.follow(url)
        try transport.lease.requireValid(); guard form?.token == token, Addresses.casResource(response.url) else { throw AppError.expired }
        let resource = WebResource(data: response.body, mime: response.mime, url: response.url)
        if !url.path.hasPrefix("/captcha") { resources[url.absoluteString] = resource }; return resource
    }
    func accountIdentity(_ account: CampusAccount) async throws -> SchoolIdentity {
        var response = try await client.follow(URL(string: Addresses.schedule + "/uc/wap/user/get-info")!)
        if let page = try await loginIfNeeded(response) {
            guard let form else { throw AppError.login }
            let document = try SwiftSoup.parse(form.html)
            let captcha = try document.select("#captchaParent").first()
            let scripts = try document.select("script:not([src])").array().map { try $0.html() }.joined(separator: "\n")
            let configured = scripts.captures("(?:config\\.captcha\\s*=|[\"']captcha[\"']\\s*:)\\s*([^;,\\n}]+)").contains { !["false", "null", "undefined", "0", "\"\"", "''"].contains($0[1].trimmed) }
            if try document.select("input[name=captcha]").first() != nil || (captcha != nil && !(try captcha!.attr("style")).replacingOccurrences(of: " ", with: "").lowercased().contains("display:none")) || configured { throw AppError.login }
            let submitted = try await submitForm(token: page.id, values: ["username": account.username, "password": account.password, "type": "username_password"])
            guard submitted.url.host != "cas.bjut.edu.cn" else { throw AppError.login }
            response = try await client.follow(URL(string: Addresses.schedule + "/uc/wap/user/get-info")!)
        }
        guard response.url.host == "itsapp.bjut.edu.cn" else { throw AppError.identity }
        return try ScheduleParser.identity(response.body, expected: account.username)
    }
    func close() async { transport.cancel(); form = nil; submittedUsername = nil; identity = nil; resources.removeAll(); await client.close() }
}
