import Foundation
import CryptoKit
import Security
@preconcurrency import SwiftSoup

struct AcademicPostBack: Sendable { var target: String; var argument: String }
struct AcademicGrid<T: Sendable>: Sendable { var records: [T]; var next: AcademicPostBack? }

enum AcademicParser {
    static let creditLabels: Set<String> = ["学位学分", "选修学分", "已修学分", "应修学分"]
    static func text(_ element: Element) throws -> String { try element.text().replacingOccurrences(of: "\u{a0}", with: " ").trimmed }
    static func login(_ document: Document) throws -> Bool { try !document.select("input#ctl00_txtpassword, input#ctl00_txtusername").array().isEmpty }
    static func labeled(_ document: Document, _ label: String) throws -> [String] {
        var values: [String] = []
        for cell in try document.select("td, th, label, span").array() {
            if try !cell.select("table").array().isEmpty { continue }
            let value = try text(cell)
            if value.trimmingCharacters(in: CharacterSet(charactersIn: "：:")) == label {
                if let sibling = try cell.nextElementSibling() { values.append(try text(sibling)) }
            } else if value.hasPrefix(label + "：") || value.hasPrefix(label + ":") {
                let remainder = String(value.dropFirst(label.count + 1)).trimmed
                if !remainder.isEmpty && remainder.count <= 100 && !remainder.contains("姓名") && !remainder.contains("学号") { values.append(remainder) }
            }
        }
        return values
    }
    static func identity(_ document: Document, expected: String) throws -> SchoolIdentity {
        let ids = Set(try labeled(document, "学号").filter { $0.matches("[A-Za-z0-9][A-Za-z0-9._-]{2,63}") })
        guard ids.count == 1, let id = ids.first, id.caseInsensitiveCompare(expected.trimmed) == .orderedSame else { throw AppError.identity }
        let names = Set(try labeled(document, "姓名").filter { $0.count <= 100 })
        guard names.count <= 1 else { throw AppError.identity }
        return SchoolIdentity(studentID: id, name: names.first ?? "")
    }
    static func credits(_ document: Document) throws -> [String: String] {
        var output: [String: String] = [:]
        for row in try document.select("tr").array() {
            let cells = row.children().array().filter { ["td", "th"].contains($0.tagName()) }
            if try cells.contains(where: { try !$0.select("table").array().isEmpty }) { continue }
            let values = try cells.map(text)
            guard let first = values.first, creditLabels.contains(first), values.count >= 2 else { continue }
            guard values.count % 2 == 0 else { throw AppError.response }
            for index in stride(from: 0, to: values.count, by: 2) {
                let label = values[index]; let value = values[index + 1]
                guard creditLabels.contains(label), !creditLabels.contains(value), value.count <= 100,
                      output[label] == nil || output[label] == value else { throw AppError.response }
                if !value.isEmpty { output[label] = value }
            }
        }
        return output
    }
    static func exams(_ document: Document) throws -> AcademicGrid<SchoolExam> {
        try grid(document, id: "MainWork_dgData", head: ["课程编号", "课程名称", "班级名称", "开始时间", "结束时间", "考试地点"]) { cells in
            let values = try cells.map(text)
            guard !values[0].isEmpty, !values[1].isEmpty else { throw AppError.response }
            return SchoolExam(code: values[0], name: values[1], className: values[2], start: values[3], end: values[4], location: values[5])
        }
    }
    static func grades(_ document: Document, id: String) throws -> AcademicGrid<GradeRecord> {
        try grid(document, id: id, head: ["课程", "课程学分", "选修学期", "成绩"]) { cells in
            let values = try cells.map(text)
            guard !values[0].isEmpty else { throw AppError.response }
            var restricted = values[3].contains("评价") || values[3].contains("评教")
            for cell in cells {
                var elements = try cell.select("*").array()
                var parent = cell.parent()
                while let current = parent, current.tagName() != "table" { elements.append(current); parent = current.parent() }
                for element in elements {
                    let color = try element.attr("color").lowercased()
                    let style = try element.attr("style").lowercased().replacingOccurrences(of: " ", with: "")
                    if ["red", "#f00", "#ff0000"].contains(color) || !style.captures("(?:^|;)color:(?:red|#f00|#ff0000)(?:;|$)").isEmpty { restricted = true }
                }
            }
            return GradeRecord(course: values[0], credits: values[1], term: values[2], grade: values[3], restricted: restricted)
        }
    }
    private static func grid<T: Sendable>(_ document: Document, id: String, head: [String], record: ([Element]) throws -> T) throws -> AcademicGrid<T> {
        if try login(document) { throw AppError.expired }
        guard let table = try document.select("#" + id).first() else { throw AppError.response }
        let rows = try table.select("tr").array().filter { row in
            var ancestor = row.parent()
            while let current = ancestor { if current.tagName() == "table" { return current === table }; ancestor = current.parent() }
            return false
        }
        func cells(_ row: Element) -> [Element] { row.children().array().filter { ["td", "th"].contains($0.tagName()) } }
        guard let first = rows.first, try cells(first).map(text) == head else { throw AppError.response }
        var records: [T] = []; var empty = false; var pager: Element?
        for row in rows.dropFirst() {
            let fields = cells(row)
            if fields.count == head.count, try fields.allSatisfy({ (Int(try $0.attr("colspan")) ?? 1) == 1 }) {
                guard try fields.allSatisfy({ try text($0).count <= 1000 }) else { throw AppError.response }
                records.append(try record(fields))
            } else if fields.count == 1, ["没查询到相关内容", "没有查询到相关内容", "暂无数据", "没有相关记录"].contains(try text(row)) {
                empty = true
            } else if fields.count == 1, Int(try fields[0].attr("colspan")) == head.count {
                guard pager == nil else { throw AppError.response }; pager = fields[0]
            } else { throw AppError.response }
        }
        guard records.isEmpty == empty else { throw AppError.response }
        return AcademicGrid(records: records, next: try pager.flatMap { try next($0, id: id) })
    }
    private static func next(_ pager: Element, id: String) throws -> AcademicPostBack? {
        let links = try pager.select("a[href]").array()
        let numbers = Set(try pager.select("span").array().compactMap { Int(try text($0)) })
        let current = numbers.count == 1 ? numbers.first : Int(try text(pager).captures("第\\s*([0-9]+)\\s*页").first?[1] ?? "")
        func action(_ link: Element) throws -> AcademicPostBack {
            guard let match = try link.attr("href").trimmed.captures("^(?:javascript:)?__doPostBack\\(\\s*['\"]([^'\"]+)['\"]\\s*,\\s*['\"]([^'\"]*)['\"]\\s*\\);?$").first else { throw AppError.response }
            let expected = "ctl00$MainWork$" + id.replacingOccurrences(of: "MainWork_", with: "")
            guard match[1] == expected || match[1].hasPrefix(expected + "$"), match[1].count <= 200, match[2].count <= 100 else { throw AppError.response }
            return AcademicPostBack(target: match[1], argument: match[2])
        }
        func adjacent(_ link: Element, number: Int?) throws -> AcademicPostBack {
            let parsed = try action(link)
            if parsed.argument.hasPrefix("Page$"), parsed.argument != "Page$Next" {
                guard let requested = Int(parsed.argument.dropFirst(5)), requested == number else { throw AppError.response }
            }
            return parsed
        }
        for link in links { if ["下一页", "下页", ">", "›"].contains(try text(link)) { return try adjacent(link, number: current.map { $0 + 1 }) } }
        let numbered = try links.compactMap { link -> (Int, Element)? in guard let number = Int(try text(link)) else { return nil }; return (number, link) }
        if !numbered.isEmpty {
            guard let current else { throw AppError.response }
            if let next = numbered.first(where: { $0.0 == current + 1 }) { return try adjacent(next.1, number: next.0) }
            guard !numbered.contains(where: { $0.0 > current }) else { throw AppError.response }
        }
        if let total = Int(try text(pager).captures("共\\s*([0-9]+)\\s*页").first?[1] ?? ""), current != total { throw AppError.response }
        for link in links {
            let label = try text(link); if Int(label) != nil { continue }
            guard ["首页", "上一页", "上页", "<", "<<", "«"].contains(label) else { throw AppError.response }
            let parsed = try action(link)
            if parsed.argument.hasPrefix("Page$"), !["Page$First", "Page$Prev"].contains(parsed.argument) {
                guard let page = Int(parsed.argument.dropFirst(5)), let current, page >= 1, page < current else { throw AppError.response }
            }
        }
        return nil
    }
}

actor AcademicSession {
    nonisolated let transport: NetworkTransport
    let client: CookieHTTPClient
    private var form: AcademicLoginForm?
    private var verified: SchoolIdentity?
    private var initialGrades: HTTPResponse?
    static let loginURL = URL(string: Addresses.academic + "/pyxx/login.aspx")!
    static let gradesURL = URL(string: Addresses.academic + "/pyxx/grgl/xskccjcx.aspx")!
    static let examsURL = URL(string: Addresses.academic + "/pyxx/pygl/pykscx.aspx")!
    init(transport: NetworkTransport) { self.transport = transport; client = CookieHTTPClient(transport: transport, purpose: .academic) }
    private func document(_ response: HTTPResponse) throws -> Document { try SwiftSoup.parse(response.text(), response.url.absoluteString) }
    private func follow(_ initial: URL, fields: [(String, String)]? = nil) async throws -> HTTPResponse {
        var url = initial; var post = fields
        for _ in 0..<8 {
            let response = try await client.request(url, method: post == nil ? "GET" : "POST", fields: post, headers: [("Referer", url.absoluteString)])
            if [301, 302, 303, 307, 308].contains(response.status) {
                guard let location = response.header("Location"), !(post != nil && [307, 308].contains(response.status)) else { throw AppError.address }
                var target = try Addresses.resolve(location, base: url)
                if target.scheme == "http", target.host == "webrecdoc.bjut.edu.cn", target.port == nil || target.port == 80 {
                    var parts = URLComponents(url: target, resolvingAgainstBaseURL: false)!; parts.scheme = "https"; parts.port = nil; target = parts.url!
                }
                guard Addresses.allowed(target, .academic) else { throw AppError.address }; url = target; post = nil
            } else { guard (200...299).contains(response.status) else { throw AppError.connection }; return response }
        }
        throw AppError.address
    }
    private func hidden(_ document: Document) throws -> [String: String] {
        var output: [String: String] = [:]
        for input in try document.select("form input[type=hidden][name]").array() {
            let name = try input.attr("name")
            if name.matches("__VIEWSTATE[0-9]*|__VIEWSTATEGENERATOR|__VIEWSTATEFIELDCOUNT|__EVENTVALIDATION|__EVENTTARGET|__EVENTARGUMENT") {
                guard output[name] == nil else { throw AppError.response }; output[name] = try input.attr("value")
            }
        }
        guard output["__VIEWSTATE"]?.isEmpty == false, output.count <= 64, output.values.reduce(0, { $0 + $1.count }) <= 512 * 1024 else { throw AppError.response }
        return output
    }
    func open() async throws -> AcademicLoginForm {
        verified = nil; initialGrades = nil; form = nil
        let response = try await follow(Self.loginURL)
        guard response.url.path == Self.loginURL.path else { throw AppError.login }
        let document = try document(response)
        guard let element = try document.select("form#Form1").first(), try element.attr("method").lowercased() == "post" else { throw AppError.response }
        let actionValue = try element.attr("action"); let action = try Addresses.resolve(actionValue.isEmpty ? response.url.absoluteString : actionValue, base: response.url)
        guard Addresses.allowed(action, .academic), action.path == Self.loginURL.path else { throw AppError.address }
        let controls = [("ctl00_txtusername", "ctl00$txtusername"), ("ctl00_txtpassword", "ctl00$txtpassword"), ("ctl00_txtyzm", "ctl00$txtyzm"), ("ctl00_ImageButton1", "ctl00$ImageButton1"), ("ctl00_pubkey", "ctl00$pubkey")]
        for (id, name) in controls {
            guard let control = try element.select("#" + id).first(), try control.attr("name") == name,
                  try element.select("input[name]").array().filter({ try $0.attr("name") == name }).count == 1 else { throw AppError.response }
        }
        guard let password = try element.select("#ctl00_txtpassword").first(), try password.attr("type") == "password",
              let key = try element.select("#ctl00_pubkey").first()?.attr("value"), (64...8192).contains(key.count),
              let image = try element.select("#ctl00_myCode").first() else { throw AppError.response }
        var fields = try hidden(document); fields["ctl00$pubkey"] = key
        let source = try image.attr("src"); let captcha: Data
        if source.hasPrefix("data:image/") {
            guard source.count <= 256 * 1024, source.matches("data:image/(?:png|jpeg|gif|webp);base64,[A-Za-z0-9+/=\\r\\n]+"),
                  let decoded = Data(base64Encoded: String(source.split(separator: ",", maxSplits: 1)[1]), options: .ignoreUnknownCharacters) else { throw AppError.response }
            captcha = decoded
        } else {
            let url = try Addresses.resolve(source, base: response.url)
            guard url.path == "/pyxx/PageTemplate/NsoftPage/yzm/createyzm.aspx", Addresses.allowed(url, .academic) else { throw AppError.address }
            captcha = try await follow(url).body
        }
        guard !captcha.isEmpty, captcha.count <= 192 * 1024 else { throw AppError.response }
        let form = AcademicLoginForm(token: UUID().uuidString, action: action, fields: fields.sorted { $0.key < $1.key }.map { ($0.key, $0.value) },
                                     usernameField: "ctl00$txtusername", passwordField: "ctl00$txtpassword", captchaField: "ctl00$txtyzm", publicKey: key, captcha: captcha)
        try transport.lease.requireValid(); self.form = form; return form
    }
    func refreshCaptcha(_ token: String) async throws -> AcademicLoginForm { guard form?.token == token else { throw AppError.expired }; return try await open() }
    func submit(token: String, username: String, password: String, captcha: String) async throws -> SchoolIdentity {
        guard let form, form.token == token, !username.trimmed.isEmpty, username.count <= 128, !password.isEmpty, password.count <= 512,
              !captcha.trimmed.isEmpty, captcha.count <= 32 else { throw AppError.login }
        self.form = nil
        let encrypted = try AcademicRSA.encrypt(password, publicKey: form.publicKey)
        let fields = form.fields + [(form.usernameField, username.trimmed), (form.passwordField, encrypted), (form.captchaField, captcha.trimmed), ("ctl00$ImageButton1.x", "1"), ("ctl00$ImageButton1.y", "1")]
        let submitted = try await follow(form.action, fields: fields)
        if try AcademicParser.login(document(submitted)) { throw AppError.login }
        let gradePage = try await follow(Self.gradesURL); let document = try document(gradePage)
        if try AcademicParser.login(document) { throw AppError.login }
        let identity = try AcademicParser.identity(document, expected: username.trimmed)
        try transport.lease.requireValid(); verified = identity; initialGrades = gradePage; return identity
    }
    private func pageFields(_ document: Document) throws -> [String: String] {
        var fields = try hidden(document)
        for select in try document.select("form select[name]").array() {
            let name = try select.attr("name"); guard name == "ctl00$MainWork$txtxq" else { throw AppError.response }
            guard let value = try select.select("option[selected]").first() ?? select.select("option").first() else { throw AppError.response }
            fields[name] = try value.attr("value")
        }
        return fields
    }
    private func post(_ response: HTTPResponse, fields: [String: String]) async throws -> HTTPResponse {
        let document = try document(response)
        guard let form = try document.select("form").first(), try form.attr("method").lowercased() == "post" else { throw AppError.response }
        let value = try form.attr("action"); let action = try Addresses.resolve(value.isEmpty ? response.url.absoluteString : value, base: response.url)
        guard Addresses.allowed(action, .academic), action.path == response.url.path else { throw AppError.address }
        return try await follow(action, fields: fields.sorted { $0.key < $1.key }.map { ($0.key, $0.value) })
    }
    private func postBack(_ response: HTTPResponse, _ next: AcademicPostBack) async throws -> HTTPResponse {
        var fields = try pageFields(document(response)); fields["__EVENTTARGET"] = next.target; fields["__EVENTARGUMENT"] = next.argument
        return try await post(response, fields: fields)
    }
    private func marker(_ document: Document, id: String) throws -> String {
        guard let table = try document.select("#" + id).first() else { throw AppError.response }
        return SHA256.hash(data: Data(try table.outerHtml().utf8)).map { String(format: "%02x", $0) }.joined()
    }
    private func allTerms(_ document: Document) throws -> (value: String, selected: Bool) {
        guard let select = try document.select("select#MainWork_txtxq").first(), try select.attr("name") == "ctl00$MainWork$txtxq" else { throw AppError.response }
        let options = try select.select("option").array()
        let all = try options.filter { try AcademicParser.text($0).replacingOccurrences(of: "-", with: "") == "全部" }
        guard all.count == 1 else { throw AppError.response }
        let selected = options.filter { $0.hasAttr("selected") }; guard selected.count <= 1 else { throw AppError.response }
        return (try all[0].attr("value"), (selected.first ?? options.first) === all[0])
    }
    func exams() async throws -> [SchoolExam] {
        guard verified != nil else { throw AppError.expired }
        var response = try await follow(Self.examsURL); var document = try document(response)
        let all = try allTerms(document)
        if !all.selected {
            var fields = try pageFields(document); fields["ctl00$MainWork$txtxq"] = all.value
            fields["ctl00$MainWork$btnSearch.x"] = "1"; fields["ctl00$MainWork$btnSearch.y"] = "1"
            response = try await post(response, fields: fields); document = try self.document(response)
        }
        var records: [SchoolExam] = []; var seen = Set<String>()
        for _ in 0..<100 {
            guard try allTerms(document).selected else { throw AppError.response }
            let parsed = try AcademicParser.exams(document); records += parsed.records
            guard records.count <= 2000 else { throw AppError.response }
            guard let next = parsed.next else { return records }
            guard seen.insert(try marker(document, id: "MainWork_dgData")).inserted else { throw AppError.response }
            response = try await postBack(response, next); document = try self.document(response)
        }
        throw AppError.response
    }
    func grades() async throws -> GradeSnapshot {
        guard let identity = verified else { throw AppError.expired }
        let initial: HTTPResponse
        if let cached = initialGrades { initial = cached } else { initial = try await follow(Self.gradesURL) }
        initialGrades = nil
        let credits = try AcademicParser.credits(document(initial))
        var paginated = false
        func readGrid(_ id: String, initial: HTTPResponse) async throws -> [GradeRecord] {
            var response = initial; var records: [GradeRecord] = []; var seen = Set<String>()
            for _ in 0..<100 {
                let document = try self.document(response); _ = try AcademicParser.identity(document, expected: identity.studentID)
                let parsed = try AcademicParser.grades(document, id: id); records += parsed.records
                guard records.count <= 5000 else { throw AppError.response }
                guard let next = parsed.next else { return records }
                guard seen.insert(try marker(document, id: id)).inserted else { throw AppError.response }
                paginated = true; response = try await postBack(response, next)
            }
            throw AppError.response
        }
        let degree = try await readGrid("MainWork_dgData", initial: initial)
        let electiveStart: HTTPResponse
        if paginated { electiveStart = try await follow(Self.gradesURL) } else { electiveStart = initial }
        let elective = try await readGrid("MainWork_Datagrid1", initial: electiveStart)
        return GradeSnapshot(degree: degree, elective: elective, credits: credits, updatedAt: Date())
    }
    func close() async { transport.cancel(); form = nil; verified = nil; initialGrades = nil; await client.close() }
}

enum AcademicRSA {
    /// Matches this school's JSEncrypt UTF-16-code-unit encoding, including surrogate pairs.
    static func encrypt(_ password: String, publicKey: String) throws -> String {
        let payload = publicKey.replacingOccurrences(of: "-----[^-]+-----|\\s", with: "", options: .regularExpression)
        guard var der = Data(base64Encoded: payload), der.count <= 8192 else { throw AppError.response }
        func tlv(_ data: Data, offset: Int) throws -> (tag: UInt8, start: Int, end: Int) {
            let bytes = [UInt8](data); guard offset >= 0, offset + 2 <= bytes.count else { throw AppError.response }
            let tag = bytes[offset]; var length = Int(bytes[offset + 1]); var position = offset + 2
            if length & 128 != 0 {
                let count = length & 127; guard count > 0, count <= 3, position + count <= bytes.count else { throw AppError.response }
                length = 0; for byte in bytes[position..<(position + count)] { length = length * 256 + Int(byte) }; position += count
            }
            guard length <= 8192, position + length <= bytes.count else { throw AppError.response }
            return (tag, position, position + length)
        }
        let root = try tlv(der, offset: 0); guard root.tag == 0x30, root.end == der.count else { throw AppError.response }
        let first = try tlv(der, offset: root.start)
        if first.tag == 0x30 {
            let bitString = try tlv(der, offset: first.end)
            guard bitString.tag == 0x03, der[bitString.start] == 0, bitString.end == root.end else { throw AppError.response }
            der = Data(der[(bitString.start + 1)..<bitString.end])
        } else { guard first.tag == 0x02 else { throw AppError.response } }
        let attributes: [String: Any] = [kSecAttrKeyType as String: kSecAttrKeyTypeRSA, kSecAttrKeyClass as String: kSecAttrKeyClassPublic]
        var error: Unmanaged<CFError>?
        guard let key = SecKeyCreateWithData(der as CFData, attributes as CFDictionary, &error) else { throw AppError.response }
        var bytes = Data()
        for unit in password.utf16 {
            if unit < 128 { bytes.append(UInt8(unit)) }
            else if unit < 2048 { bytes.append(UInt8((unit >> 6) | 192)); bytes.append(UInt8((unit & 63) | 128)) }
            else { bytes.append(UInt8((unit >> 12) | 224)); bytes.append(UInt8(((unit >> 6) & 63) | 128)); bytes.append(UInt8((unit & 63) | 128)) }
        }
        let size = SecKeyGetBlockSize(key)
        guard (128...1024).contains(size), bytes.count <= size - 11, SecKeyIsAlgorithmSupported(key, .encrypt, .rsaEncryptionPKCS1),
              let encrypted = SecKeyCreateEncryptedData(key, .rsaEncryptionPKCS1, bytes as CFData, &error) as Data? else { throw AppError.login }
        return encrypted.base64EncodedString()
    }
}
