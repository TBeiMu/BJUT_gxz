import Foundation
@preconcurrency import SwiftSoup

enum BillingIssue: Error, LocalizedError, Sendable {
    case captcha, captchaRejected, rejected, unconfirmed, accountMismatch, expired, offline
    var errorDescription: String? {
        switch self {
        case .captcha: "请填写验证码"
        case .captchaRejected: "验证码未通过，请重填"
        case .rejected: "登录未通过，请检查输入"
        case .unconfirmed: "登录未确认，请重查或使用内置网页登录"
        case .accountMismatch: "账号不符，请重新登录"
        case .expired: "会话失效，请重查"
        case .offline: "注销未确认，请刷新核对"
        }
    }
}

enum BillingParser {
    static let labels = ["已用流量", "可用流量", "消费保护", "账户余额"]
    static func login(_ html: String) throws -> Bool { try SwiftSoup.parse(html).select("form input[name=account]").first() != nil }
    static func problem(_ html: String) throws -> BillingIssue {
        let document = try SwiftSoup.parse(html)
        var tip = try document.select("#errorTip, .alert-danger").text()
        for script in try document.select("script:not([src])").array() {
            let text = try script.html()
            if text.contains("function (tip)") || text.contains("function(tip)") { tip += text.captures("\\}\\)\\(\\s*['\"]([^'\"]*)['\"]\\s*\\)").first?[1] ?? "" }
        }
        if tip.contains("验证码") && ["错误", "不正确", "过期"].contains(where: tip.contains) { return .captchaRejected }
        if ["账号", "帐号", "密码"].contains(where: tip.contains) && ["错误", "不正确", "不存在"].contains(where: tip.contains) { return .rejected }
        return .unconfirmed
    }
    static func requireAccount(_ html: String, expected: String) throws {
        let document = try SwiftSoup.parse(html)
        var accounts = Set<String>()
        for script in try document.select("script:not([src])").array() {
            for match in try script.html().captures("[\"']userName[\"']\\s*:\\s*(\"(?:\\\\.|[^\"\\\\])*\")") {
                if let string = try? JSONSerialization.jsonObject(with: Data(match[1].utf8), options: .fragmentsAllowed) as? String { accounts.insert(string) }
            }
        }
        guard accounts.count == 1, accounts.first == expected else { throw BillingIssue.accountMismatch }
    }
    static func form(_ html: String, base: URL) throws -> BillingLoginForm {
        let document = try SwiftSoup.parse(html, base.absoluteString)
        guard let form = try document.select("form").array().first(where: { try $0.select("input[name=account]").first() != nil && $0.select("input[name=password]").first() != nil }),
              try form.attr("method").lowercased() == "post" else { throw AppError.response }
        let scripts = try document.select("script:not([src])").array().map { try $0.html() }.joined(separator: "\n")
        guard !scripts.captures("if\\s*\\(\\s*false\\s*&&\\s*passwordVal").isEmpty,
              let checkcode = try form.select("input[name=checkcode]").first()?.attr("value"), !checkcode.isEmpty else { throw AppError.response }
        let action = try Addresses.resolve(form.attr("action"), base: base)
        guard Addresses.allowed(action, .billing), action.query == nil, action.fragment == nil,
              action.path.matches("/Self/login/verify(?:;jsessionid=[a-zA-Z0-9]+)?") else { throw AppError.address }
        let captcha = try form.select("#randomDiv").first()
        let required: Bool
        if let captcha { required = !captcha.hasClass("hide") && !captcha.hasClass("hidden") && !captcha.hasAttr("hidden") && !(try captcha.attr("style")).replacingOccurrences(of: " ", with: "").lowercased().contains("display:none") }
        else { required = false }
        return BillingLoginForm(token: UUID().uuidString, action: action, html: html, pageURL: base, fields: [("checkcode", checkcode)],
                                usernameField: "account", passwordField: "password", captchaField: "code", captchaURL: URL(string: Addresses.billing + "/Self/login/randomCode"), captchaRequired: required)
    }
    static func metrics(_ html: String) throws -> [BillingMetric] {
        let document = try SwiftSoup.parse(html)
        guard try document.select("#OnlineLisBig").first() != nil else { throw AppError.response }
        var metrics: [String: BillingMetric] = [:]
        for item in try document.select("dl").array() {
            guard let label = try item.select("dd").first()?.text().trimmed, labels.contains(label),
                  let original = try item.select("dt").first() else { continue }
            let value = try SwiftSoup.parse(original.outerHtml())
            let unit = try value.select("small.unit").text().trimmed
            try value.select("small.unit").remove()
            let text = try value.text().trimmed
            if !text.isEmpty, text.count <= 80, unit.count <= 16 { metrics[label] = BillingMetric(key: label, value: text, unit: unit) }
        }
        guard !metrics.isEmpty else { throw AppError.response }
        return labels.compactMap { metrics[$0] }
    }
    static func devices(_ body: Data) throws -> [OnlineDevice] {
        let root = try JSONSerialization.jsonObject(with: body)
        guard let rows = (root as? [[String: Any]]) ?? (root as? [String: Any])?["rows"] as? [[String: Any]], rows.count <= 200 else { throw AppError.response }
        var grouped: [String: [OnlineDevice]] = [:]; var order: [String] = []
        for row in rows {
            func field(_ key: String) throws -> String {
                let value = scalar(row[key]); guard value.count <= 256, !value.unicodeScalars.contains(where: CharacterSet.controlCharacters.contains) else { throw AppError.response }; return value
            }
            let id = try field("sessionId"); guard !id.isEmpty else { throw AppError.response }
            func flow(_ key: String) throws -> Decimal? {
                let value = try field(key).trimmed
                guard value.matches("[0-9]{1,20}(?:\\.[0-9]{1,20})?"), let decimal = Decimal(string: value, locale: Locale(identifier: "en_US_POSIX")) else { return nil }
                var input = decimal; var result = Decimal(); NSDecimalRound(&result, &input, 0, .down); return result
            }
            let traffic: String
            if let down = try flow("downFlow"), let up = try flow("upFlow") {
                var value = (down + up) / Decimal(1024); var rounded = Decimal(); NSDecimalRound(&rounded, &value, 3, .plain)
                let formatter = NumberFormatter(); formatter.locale = Locale(identifier: "en_US_POSIX"); formatter.numberStyle = .decimal
                formatter.usesGroupingSeparator = false; formatter.minimumFractionDigits = 3; formatter.maximumFractionDigits = 3
                traffic = (formatter.string(from: NSDecimalNumber(decimal: rounded)) ?? "未获取") + " MB"
            } else { traffic = "未获取" }
            let seconds = Int64(try field("useTime"))
            let duration = seconds.flatMap { $0 >= 0 ? "\($0 / 60) 分钟" : nil } ?? "未获取"
            let ip = try field("ip"); let ipv6 = try field("ipv6")
            let device = OnlineDevice(id: id, address: ip.isEmpty ? ipv6 : ip, mac: try field("mac"), loginTime: try field("loginTime"), duration: duration, traffic: traffic)
            if grouped[id] == nil { order.append(id) }; grouped[id, default: []].append(device)
        }
        return order.compactMap { id in
            guard let same = grouped[id], var device = same.first else { return nil }
            let values = Set(same.map(\.traffic).filter { $0 != "未获取" }); device.traffic = values.count == 1 ? values.first! : "未获取"
            return device
        }
    }
}

enum BillingWebResult: Sendable { case page(SchoolLoginPage); case success(BillingSnapshot) }
actor BillingSession {
    nonisolated let transport: NetworkTransport
    let client: CookieHTTPClient
    private var pending: BillingLoginForm?
    private var captcha: Data?
    private var pageURL: URL?
    private var pageHTML: String?
    private var webAccount: CampusAccount?
    private var webToken: String?
    private var webSubmitted = false
    private var resources: [String: WebResource] = [:]
    static let dashboard = URL(string: Addresses.billing + "/Self/dashboard")!
    init(transport: NetworkTransport) { self.transport = transport; client = CookieHTTPClient(transport: transport, purpose: .billing) }
    func captchaImage() -> Data? { captcha }
    private func page(_ initial: URL, fields: [(String, String)]? = nil, referer: URL? = nil) async throws -> String {
        var url = initial; var post = fields
        for _ in 0..<5 {
            let response = try await client.request(url, method: post == nil ? "GET" : "POST", fields: post,
                                                    headers: [("Referer", (referer ?? Self.dashboard).absoluteString), ("Origin", Addresses.billing)], limit: 1024 * 1024)
            if (300...399).contains(response.status) {
                guard let location = response.header("Location"), post == nil || [302, 303].contains(response.status) else { throw AppError.address }
                let target = try Addresses.resolve(location, base: url)
                let path = target.path.components(separatedBy: ";jsessionid=")[0]
                let login = path == "/Self/login" || path == "/Self/login/"
                let parts = URLComponents(url: target, resolvingAgainstBaseURL: false)!
                let knownQuery = target.query == nil || (login && parts.queryItems?.count == 1 && parts.queryItems?.first?.name == "302" && parts.queryItems?.first?.value == "LI")
                guard Addresses.allowed(target, .billing), login || ["/Self/", "/Self/dashboard"].contains(path), knownQuery, target.fragment == nil else { throw AppError.address }
                url = target; post = nil
            } else {
                guard (200...299).contains(response.status) else { throw AppError.connection }
                let html = try response.text()
                if try BillingParser.login(html) { pageURL = response.url; pageHTML = html }
                return html
            }
        }
        throw AppError.address
    }
    private func authenticated(_ url: URL) async throws -> HTTPResponse {
        let response = try await client.request(url, headers: [("Referer", Self.dashboard.absoluteString)], limit: 1024 * 1024)
        guard (200...299).contains(response.status) else { throw BillingIssue.expired }
        if response.mime.contains("html"), try BillingParser.login(response.text()) { throw BillingIssue.expired }
        return response
    }
    private func loadCaptcha() async throws -> Data {
        guard let pageURL else { throw BillingIssue.expired }
        let url = try Addresses.query(URL(string: Addresses.billing + "/Self/login/randomCode")!, [("t", "0." + String(UInt64(Date().timeIntervalSince1970 * 1000)))])
        let response = try await client.request(url, headers: [("Referer", pageURL.absoluteString)], limit: 128 * 1024)
        guard response.status == 200, response.mime.hasPrefix("image/"), !response.body.isEmpty else { throw AppError.response }
        captcha = response.body; return response.body
    }
    private func initialize(_ form: BillingLoginForm) async throws {
        let document = try SwiftSoup.parse(form.html, form.pageURL.absoluteString)
        resources.removeAll(); var total = 0
        for element in try document.select("script[src], link[rel=stylesheet][href], img[src]").array().prefix(32) {
            let source = try element.attr(element.tagName() == "link" ? "href" : "src")
            guard let url = try? Addresses.resolve(source, base: form.pageURL), Addresses.allowed(url, .billing), url.path.hasPrefix("/Self/resources/"), url.query == nil, url.fragment == nil else { continue }
            let response = try await client.request(url, headers: [("Referer", form.pageURL.absoluteString)], limit: 1024 * 1024)
            if response.status == 200 { total += response.body.count; guard total <= 4 * 1024 * 1024 else { throw AppError.response }; resources[url.absoluteString] = WebResource(data: response.body, mime: response.mime, url: url) }
        }
        _ = try await loadCaptcha()
    }
    private func verify(_ account: CampusAccount, form: BillingLoginForm, code: String) async throws -> String {
        guard code.count <= 4 else { throw BillingIssue.captchaRejected }
        let fields = [("account", account.username), ("password", account.password)] + form.fields + [("code", code), ("foo", ""), ("bar", ""), ("submit", "")]
        return try await page(form.action, fields: fields, referer: form.pageURL)
    }
    func refresh(_ account: CampusAccount, code: String? = nil) async throws -> BillingSnapshot {
        var html: String
        if let code, let pending { html = try await verify(account, form: pending, code: code); self.pending = nil }
        else {
            html = try await page(Self.dashboard)
            if try BillingParser.login(html) {
                guard let pageURL else { throw AppError.response }
                let form = try BillingParser.form(html, base: pageURL); pending = form; try await initialize(form)
                if form.captchaRequired { throw BillingIssue.captcha }
                pending = nil; html = try await verify(account, form: form, code: "")
            }
        }
        if try BillingParser.login(html) {
            guard let pageURL else { throw AppError.response }
            let form = try BillingParser.form(html, base: pageURL); let issue = try BillingParser.problem(html)
            if issue == .rejected { throw issue }
            if form.captchaRequired { pending = form; try await initialize(form); throw issue == .captchaRejected ? issue : BillingIssue.captcha }
            throw issue
        }
        pending = nil; captcha = nil
        return try await snapshot(html, account: account.username)
    }
    private func snapshot(_ original: String, account: String) async throws -> BillingSnapshot {
        try BillingParser.requireAccount(original, expected: account)
        var html = original; var notice: String?
        let document = try SwiftSoup.parse(original)
        let script = try document.select("script:not([src])").array().map { try $0.html() }.first { $0.contains("/Self/dashboard/refreshaccount") }
        if let script, let range = script.range(of: "/Self/dashboard/refreshaccount"),
           let csrf = String(script[range.lowerBound...].prefix(1000)).captures("csrftoken\\s*:\\s*['\"]([^'\"]+)['\"]").first?[1], csrf.count <= 256 {
            do {
                _ = try await authenticated(Addresses.query(URL(string: Addresses.billing + "/Self/dashboard/refreshaccount")!, [("csrftoken", csrf), ("t", String(UInt64(Date().timeIntervalSince1970 * 1000)))]))
                html = try await page(Self.dashboard)
                if try BillingParser.login(html) { throw BillingIssue.expired }; try BillingParser.requireAccount(html, expected: account)
            } catch {
                try transport.lease.requireValid(); try Task.checkCancellation()
                if error as? BillingIssue == .accountMismatch { throw error }
                html = original; notice = "刷新未确认，显示网页数据"
            }
        }
        let metrics = try BillingParser.metrics(html)
        var devices: [OnlineDevice] = []; var deviceError: String?
        do { devices = try BillingParser.devices(await authenticated(URL(string: Addresses.billing + "/Self/dashboard/getOnlineList")!).body) }
        catch { try transport.lease.requireValid(); try Task.checkCancellation(); deviceError = "在线设备读取失败，请重新查询" }
        return BillingSnapshot(metrics: metrics, devices: devices, deviceError: deviceError, notice: notice, queriedAt: Date())
    }
    func openWeb(_ account: CampusAccount) async throws -> SchoolLoginPage {
        await client.clear(); _ = try await page(URL(string: Addresses.billing + "/Self/login/")!); webAccount = account
        return try await webPage()
    }
    private func webPage() async throws -> SchoolLoginPage {
        guard let pageURL, let pageHTML else { throw AppError.response }
        let form = try BillingParser.form(pageHTML, base: pageURL); pending = form; try await initialize(form)
        let token = UUID().uuidString; webToken = token; webSubmitted = false
        return SchoolLoginPage(id: token, url: pageURL, html: pageHTML, purpose: .billing)
    }
    func submitWeb(token: String, password: String, code: String) async throws -> BillingWebResult {
        guard var account = webAccount, let form = pending, token == webToken, !webSubmitted, !password.isEmpty, password.count <= 256 else { throw BillingIssue.expired }
        webSubmitted = true; account.password = password
        let html = try await verify(account, form: form, code: code)
        if try BillingParser.login(html) { webAccount = account; return .page(try await webPage()) }
        let result = try await snapshot(html, account: account.username)
        webAccount = nil; webToken = nil; pending = nil; captcha = nil; return .success(result)
    }
    func prefill() -> CampusAccount? { webAccount }
    func resource(token: String, url: URL) async throws -> WebResource {
        guard token == webToken, !webSubmitted, Addresses.allowed(url, .billing) else { throw BillingIssue.expired }
        if url.path == "/Self/login/randomCode" {
            let image = try await loadCaptcha(); return WebResource(data: image, mime: "image/png", url: url)
        }
        guard url.path.hasPrefix("/Self/resources/"), url.query == nil, url.fragment == nil else { throw AppError.address }
        if let resource = resources[url.absoluteString] { return resource }
        guard resources.count < 80 else { throw AppError.response }
        let response = try await client.request(url, limit: 1024 * 1024)
        try transport.lease.requireValid(); guard token == webToken, response.status == 200 else { throw BillingIssue.expired }
        return WebResource(data: response.body, mime: response.mime, url: url)
    }
    func offlineTip() async throws -> String {
        let response = try await authenticated(URL(string: Addresses.billing + "/Self/dashboard/getOfflineTip")!)
        let text = try SwiftSoup.parse(response.text()).text().trimmed
        return !text.isEmpty && text.count <= 250 ? text : "无感知用户可能需要解绑 MAC 才能保持离线。"
    }
    func offline(_ device: OnlineDevice) async throws {
        guard !device.id.hasPrefix("cached-device-") else { throw BillingIssue.expired }
        let url = try Addresses.query(URL(string: Addresses.billing + "/Self/dashboard/tooffline")!, [("sessionid", device.id), ("ip", device.address), ("mac", device.mac.replacingOccurrences(of: "-", with: ""))])
        let response = try await authenticated(url); let root = try jsonObject(response.body)
        guard let success = root["success"] as? NSNumber, CFGetTypeID(success) == CFBooleanGetTypeID(), success.boolValue else { throw BillingIssue.offline }
    }
    func close() async { transport.cancel(); pending = nil; captcha = nil; pageURL = nil; pageHTML = nil; webAccount = nil; webToken = nil; resources.removeAll(); await client.close() }
}
