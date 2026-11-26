import Foundation

struct CampusResult: Sendable {
    var message: String
    var mode: NetworkMode?
    var completed = false
    var needsChoice = false
}
private struct CampusProbe: Sendable { var mode: NetworkMode; var status: Bool?; var internet: Bool?; var result: CampusResult? }
private final class CampusConfirmation: @unchecked Sendable {
    private let lock = NSLock()
    private var result: CampusResult?
    var current: CampusResult? { lock.withLock { result } }
    func record(_ result: CampusResult) { lock.withLock { if self.result == nil { self.result = result } } }
}

enum CampusAuthentication {
    static let publicStatus = URL(string: "http://10.21.251.3/drcom/chkstatus")!
    static let dormStatus = URL(string: "http://10.21.221.98/drcom/chkstatus")!
    static func parse(_ text: String) throws -> Bool {
        var payload = text.trimmed.replacingOccurrences(of: "\u{feff}", with: "")
        if !payload.hasPrefix("{") {
            guard let matched = payload.captures("^[a-zA-Z_$][a-zA-Z0-9_$]*\\s*\\(([\\s\\S]*)\\)\\s*;?$").first else { throw AppError.response }
            payload = matched[1]
        }
        let root = try jsonObject(Data(payload.utf8))
        switch scalar(root["result"]) { case "1": return true; case "0": return false; default: throw AppError.response }
    }
    static func status(_ url: URL, transport: NetworkTransport) async throws -> Bool {
        let response = try await transport.request(Addresses.query(url, [("callback", "dr1002")]), purpose: .portal, timeout: 8, limit: 128 * 1024)
        guard (200...299).contains(response.status) else { throw response.status >= 300 && response.status < 400 ? AppError.address : AppError.connection }
        return try parse(response.text())
    }
    static func ipv4Internet(_ transport: NetworkTransport) async -> Bool {
        await withTaskGroup(of: Bool.self) { group in
            for address in ["https://connectivitycheck.platform.hicloud.com/generate_204", "https://connect.rom.miui.com/generate_204"] {
                group.addTask {
                    do { let response = try await withDeadline(4) { try await transport.request(URL(string: address)!, purpose: .connectivity, timeout: 4, limit: 4096, ipv4: true) }; return response.status == 204 }
                    catch { return false }
                }
            }
            defer { group.cancelAll() }
            for await result in group { if result { return true } }
            return false
        }
    }
    private static func authenticated(_ mode: NetworkMode, internet: Bool = false) -> CampusResult {
        CampusResult(message: internet ? "宿舍 WiFi 已联网（IPv4 已验证），无需重复登录" : (mode == .publicWifi ? "公共 WiFi 已认证，无需重复登录" : "宿舍 WiFi 已认证，无需重复登录"), mode: mode, completed: true)
    }
    static func login(transport: NetworkTransport, mode: NetworkMode, credentials: @escaping @Sendable () async throws -> CampusAccount?,
                      progress: @escaping @Sendable (String) async -> Void, confirmed: @escaping @Sendable (CampusResult) async -> Void) async -> CampusResult {
        let confirmation = CampusConfirmation()
        var recognizedDorm = false
        var selected: NetworkMode? = mode == .auto ? nil : mode
        do {
            let result = try await withDeadline(60) {
                await progress("正在检查校园认证状态…")
                var probes: [CampusProbe] = []
                if mode == .auto {
                    let early = await withTaskGroup(of: CampusProbe.self, returning: CampusResult?.self) { group in
                        for probeMode in [NetworkMode.publicWifi, .dormWifi] {
                            group.addTask {
                                let status = try? await Self.status(probeMode == .publicWifi ? publicStatus : dormStatus, transport: transport)
                                let online = probeMode == .dormWifi && status == false ? await ipv4Internet(transport) : nil
                                return CampusProbe(mode: probeMode, status: status, internet: online,
                                                   result: status == true ? authenticated(probeMode) : (online == true ? authenticated(.dormWifi, internet: true) : nil))
                            }
                        }
                        defer { group.cancelAll() }
                        for await probe in group {
                            if let result = probe.result {
                                confirmation.record(result); await confirmed(result)
                                return result
                            }
                            probes.append(probe)
                        }
                        return nil
                    }
                    if let early { return early }
                    let valid = probes.filter { $0.status != nil }
                    if valid.count == 2 { return CampusResult(message: "公共与宿舍认证端点均返回有效状态，请选择当前 WiFi 类型后手动登录", needsChoice: true) }
                    guard let probe = valid.first else { return CampusResult(message: "未识别到校园认证服务，请确认校园 WiFi 或手动选择网络类型") }
                    return try await authenticate(transport, mode: probe.mode, preflight: probe.status, internet: probe.internet,
                                                  credentials: credentials, progress: progress, confirmation: confirmation, confirmed: confirmed)
                }
                return try await authenticate(transport, mode: mode, preflight: nil, internet: nil, credentials: credentials,
                                              progress: progress, confirmation: confirmation, confirmed: confirmed)
            }
            selected = result.mode
            recognizedDorm = result.mode == .dormWifi
            if result.completed { return result }
            if recognizedDorm && transport.lease.isValid {
                if await ipv4Internet(transport) { let online = authenticated(.dormWifi, internet: true); confirmation.record(online); await confirmed(online); return online }
            }
            return result
        } catch {
            if let existing = confirmation.current { return existing }
            if selected == .dormWifi, transport.lease.isValid, await ipv4Internet(transport) {
                let online = authenticated(.dormWifi, internet: true); await confirmed(online); return online
            }
            return CampusResult(message: safeMessage(error), mode: selected)
        }
    }
    private static func authenticate(_ transport: NetworkTransport, mode: NetworkMode, preflight: Bool?, internet: Bool?,
                                     credentials: @escaping @Sendable () async throws -> CampusAccount?,
                                     progress: @escaping @Sendable (String) async -> Void,
                                     confirmation: CampusConfirmation, confirmed: @escaping @Sendable (CampusResult) async -> Void) async throws -> CampusResult {
        let online: Bool
        if let preflight { online = preflight } else { online = try await status(mode == .publicWifi ? publicStatus : dormStatus, transport: transport) }
        if online {
            let result = authenticated(mode); confirmation.record(result); await confirmed(result); return result
        }
        if mode == .dormWifi {
            let connected: Bool
            if let internet { connected = internet } else { connected = await ipv4Internet(transport) }
            if connected { let result = authenticated(.dormWifi, internet: true); confirmation.record(result); await confirmed(result); return result }
        }
        try transport.lease.requireValid(); try Task.checkCancellation()
        guard let account = try await credentials() else { return CampusResult(message: "请先添加并设置默认校园网账号", mode: mode) }
        await progress(mode == .publicWifi ? "正在登录公共 WiFi…" : "正在登录宿舍 WiFi…")
        let fields: [(String, String)]
        let endpoint: URL
        if mode == .publicWifi {
            endpoint = URL(string: "http://10.21.251.3/drcom/login")!
            fields = [("callback", "dr1002"), ("DDDDD", account.username), ("upass", account.password), ("0MKKey", "123456"),
                      ("R1", "0"), ("R2", ""), ("R3", "0"), ("R6", "0"), ("para", "00"), ("v6ip", ""),
                      ("terminal_type", "1"), ("lang", "zh-cn"), ("jsVersion", "4.1"), ("v", "1234")]
        } else {
            endpoint = URL(string: "http://10.21.221.98:801/eportal/")!
            fields = [("c", "Portal"), ("a", "login"), ("user_account", account.username + "@campus"), ("user_password", account.password), ("login_method", "1")]
        }
        let response = try await transport.request(Addresses.query(endpoint, fields), purpose: .portal, timeout: 8, limit: 128 * 1024)
        guard (200...299).contains(response.status) else { throw AppError.connection }
        guard try parse(response.text()) else { throw AppError.validation("认证未通过，请检查默认账号、密码或账号状态后手动重试") }
        guard try await status(mode == .publicWifi ? publicStatus : dormStatus, transport: transport) else { throw AppError.validation("认证已提交，但状态未确认，请稍后手动重试检查状态") }
        let result = CampusResult(message: mode == .publicWifi ? "公共 WiFi 认证成功" : "宿舍 WiFi 认证成功", mode: mode, completed: true)
        confirmation.record(result); await confirmed(result); return result
    }
}
