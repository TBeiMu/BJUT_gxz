import Foundation
import Observation
import CryptoKit

private struct UpdateRecord: Codable { var update: IOSUpdate; var started: Bool }
private final class DownloadSink: @unchecked Sendable {
    let lock = NSLock()
    let handle: FileHandle
    let expected: Int64
    private var count: Int64
    private var accepted = false
    private var lastReport = Date.distantPast
    let progress: @Sendable (Int64) -> Void
    init(url: URL, expected: Int64, progress: @escaping @Sendable (Int64) -> Void) throws {
        if !FileManager.default.fileExists(atPath: url.path) { try VaultFile.atomicWrite(Data(), to: url) }
        handle = try FileHandle(forWritingTo: url); count = Int64(try handle.seekToEnd()); self.expected = expected; self.progress = progress
        guard count <= expected else { try handle.truncate(atOffset: 0); count = 0; return }
    }
    var offset: Int64 { lock.withLock { count } }
    func head(_ status: Int, _ headers: [(String, String)]) throws {
        try lock.withLock {
            guard status == 200 || status == 206 else { if (300...399).contains(status) { return }; throw AppError.connection }
            let encoding = headers.first { $0.0.lowercased() == "content-encoding" }?.1.lowercased()
            guard encoding == nil || encoding == "identity" else { throw AppError.response }
            if status == 200 { try handle.truncate(atOffset: 0); try handle.seek(toOffset: 0); count = 0 }
            else {
                let range = headers.first { $0.0.lowercased() == "content-range" }?.1
                let matches = range?.captures("^bytes ([0-9]+)-([0-9]+)/([0-9]+)$").first
                guard let matches, Int64(matches[1]) == count, Int64(matches[2]) == expected - 1, Int64(matches[3]) == expected else { throw AppError.response }
            }
            accepted = true
        }
    }
    func append(_ data: Data) throws {
        try lock.withLock {
            guard accepted, count + Int64(data.count) <= expected else { throw AppError.response }
            try handle.write(contentsOf: data); count += Int64(data.count)
            if Date().timeIntervalSince(lastReport) > 0.15 { lastReport = Date(); progress(count) }
        }
    }
    func close() { lock.withLock { try? handle.synchronize(); try? handle.close() } }
}

@MainActor @Observable final class UpdateController {
    var checking = false
    var downloading = false
    var update: IOSUpdate?
    var offered: IOSUpdate?
    var status = ""
    var progress: Int64 = 0
    var cellularConfirmation = false
    var exportURL: URL?
    @ObservationIgnored var task: Task<Void, Never>?
    @ObservationIgnored var transport: NetworkTransport?
    @ObservationIgnored var automaticUsed = false
    @ObservationIgnored var pendingRoute: NetworkRoute?
    @ObservationIgnored var pendingUpdate: IOSUpdate?
    @ObservationIgnored var onFinished: (() -> Void)?
    private static var directory: URL { VaultFile.directory.appendingPathComponent("Updates", isDirectory: true) }
    private static var recordURL: URL { directory.appendingPathComponent("record.json") }
    private static var partialURL: URL { directory.appendingPathComponent("download.part") }
    private static var readyURL: URL { directory.appendingPathComponent("gongxiaozhu-ios-unsigned.ipa") }
    init() {
        if let bytes = try? Data(contentsOf: Self.recordURL), bytes.count <= 64 * 1024,
           let record = try? JSONDecoder().decode(UpdateRecord.self, from: bytes), Self.valid(record.update) {
            update = record.update
            progress = ((try? FileManager.default.attributesOfItem(atPath: Self.partialURL.path)[.size]) as? NSNumber)?.int64Value ?? 0
            status = record.started ? "下载已暂停，点击继续" : "已发现版本"
            if Self.verify(Self.readyURL, update: record.update) { exportURL = Self.readyURL; status = "未签名 IPA 已校验，可分享或导出" }
        }
    }
    static func valid(_ value: IOSUpdate) -> Bool {
        value.platform == "ios" && value.unsigned && value.versionCode > 0 && value.versionName.matches("[0-9]+\\.[0-9]+\\.[0-9]+") &&
        value.releaseTag == "v" + value.versionName && value.ipaURL == "https://github.com/TBeiMu/BJUT_gxz/releases/download/\(value.releaseTag)/gongxiaozhu-ios-unsigned.ipa" &&
        value.ipaSHA256.matches("[a-f0-9]{64}") && value.sourceCommit.matches("[a-f0-9]{40}") && value.ipaSize > 0 && value.ipaSize <= 200 * 1024 * 1024 &&
        value.minSystemVersion.matches("[0-9]+(?:\\.[0-9]+){0,2}") && value.releaseNotes.count <= 20_000
    }
    nonisolated static func verify(_ url: URL, update: IOSUpdate) -> Bool {
        guard let handle = try? FileHandle(forReadingFrom: url) else { return false }; defer { try? handle.close() }
        var hasher = SHA256(); var count: Int64 = 0
        do { while let bytes = try handle.read(upToCount: 1024 * 1024), !bytes.isEmpty { count += Int64(bytes.count); guard count <= update.ipaSize else { return false }; hasher.update(data: bytes) } } catch { return false }
        return count == update.ipaSize && hasher.finalize().map { String(format: "%02x", $0) }.joined() == update.ipaSHA256
    }
    func automaticCheck(network: NetworkMonitor, active: Bool) {
        guard active, !automaticUsed, network.internetAvailable, !downloading else { return }; automaticUsed = true; check(network: network, manual: false)
    }
    func check(network: NetworkMonitor, manual: Bool = true) {
        guard !checking, !downloading else { return }
        do {
            let session = NetworkTransport(route: try network.route(.internet)); checking = true
            if !downloading { transport = session }
            task = Task {
                do {
                    let client = CookieHTTPClient(transport: session, purpose: .update)
                    let response = try await client.follow(URL(string: "https://github.com/TBeiMu/BJUT_gxz/releases/latest/download/update-ios.json")!, limit: 64 * 1024)
                    let next = try JSONDecoder().decode(IOSUpdate.self, from: response.body)
                    guard Self.valid(next) else { throw AppError.response }; try session.lease.requireValid()
                    if next.versionCode > AppInfo.build {
                        let old = (try? Data(contentsOf: Self.recordURL)).flatMap { try? JSONDecoder().decode(UpdateRecord.self, from: $0) }
                        if old?.started != true { update = next; try persist(next, started: false) }
                        let ignored = UserDefaults.standard.integer(forKey: "update.ignoredVersion")
                        if manual || ignored != next.versionCode { offered = next }
                        if manual { status = "发现新版本 \(next.versionName)" }
                    } else if manual { status = "已是最新，无需更新" }
                } catch { if manual && session.lease.isValid { status = safeMessage(error) } }
                await session.closeAndDrain(); checking = false
                if transport === session { transport = nil; task = nil }
            }
        } catch { if manual { status = safeMessage(error) } }
    }
    func persist(_ update: IOSUpdate, started: Bool) throws { try VaultFile.atomicWrite(ModelCoding.encode(UpdateRecord(update: update, started: started)), to: Self.recordURL) }
    func ignore(_ next: IOSUpdate) { UserDefaults.standard.set(next.versionCode, forKey: "update.ignoredVersion"); offered = nil }
    func select(_ next: IOSUpdate) { if update != next { progress = 0; exportURL = nil }; update = next; offered = nil }
    func requestDownload(network: NetworkMonitor) {
        guard !downloading, !checking, let update else { return }
        do {
            let route = try network.route(.internet)
            let current = ProcessInfo.processInfo.operatingSystemVersion
            let installed = "\(current.majorVersion).\(current.minorVersion).\(current.patchVersion)"
            guard installed.compare(update.minSystemVersion, options: .numeric) != .orderedAscending else { status = "此版本需要 iOS \(update.minSystemVersion)"; return }
            if route.cellular { pendingRoute = route; pendingUpdate = update; cellularConfirmation = true }
            else { startDownload(update, route: route) }
        } catch { status = safeMessage(error) }
    }
    func consent(network: NetworkMonitor) {
        cellularConfirmation = false
        defer { pendingRoute = nil; pendingUpdate = nil }
        guard let route = pendingRoute, let next = pendingUpdate, route.epoch == network.epoch, update == next else { status = "网络已变化，请重新确认流量下载"; return }
        startDownload(next, route: route)
    }
    func startDownload(_ next: IOSUpdate, route: NetworkRoute) {
        guard !downloading else { return }
        let session = NetworkTransport(route: route); transport = session; downloading = true; exportURL = nil; status = "正在下载未签名 IPA…"
        WorkLifetime.shared.begin("download", title: "下载工小助未签名 IPA", continued: true) { [weak self] in self?.pause() }
        task = Task {
            let timeout = Task { try? await Task.sleep(for: .seconds(1800)); if !Task.isCancelled, self.transport === session { self.pause(); self.status = "下载已超时，分段保留，请手动继续" } }; defer { timeout.cancel() }
            var success = false
            do {
                let old = (try? Data(contentsOf: Self.recordURL)).flatMap { try? JSONDecoder().decode(UpdateRecord.self, from: $0) }
                if old?.update != next { try? FileManager.default.removeItem(at: Self.partialURL); try? FileManager.default.removeItem(at: Self.readyURL) }
                try persist(next, started: true)
                let sink = try DownloadSink(url: Self.partialURL, expected: next.ipaSize) { [weak self] count in
                    Task { @MainActor in guard self?.transport === session, session.lease.isValid else { return }; self?.progress = count; WorkLifetime.shared.progress("download", fraction: Double(count) / Double(next.ipaSize)) }
                }
                do {
                    if sink.offset < next.ipaSize {
                        var url = URL(string: next.ipaURL)!
                        for redirects in 0...5 {
                            let headers = sink.offset > 0 ? [("Range", "bytes=\(sink.offset)-")] : []
                            let result = try await session.request(url, purpose: .update, headers: headers, timeout: 30 * 60, limit: Int(next.ipaSize), stream: { try sink.append($0) }, onHead: { try sink.head($0, $1) })
                            if (300...399).contains(result.status) {
                                guard redirects < 5, let location = result.header("Location") else { throw AppError.address }
                                url = try Addresses.resolve(location, base: url); guard Addresses.allowed(url, .update) else { throw AppError.address }
                            } else { break }
                        }
                    }
                    sink.close()
                } catch { sink.close(); throw error }
                try session.lease.requireValid()
                let partial = Self.partialURL
                let verified = await Task.detached { Self.verify(partial, update: next) }.value
                try session.lease.requireValid(); guard verified else { try? FileManager.default.removeItem(at: Self.partialURL); throw AppError.validation("IPA 摘要不符，请重新下载") }
                try? FileManager.default.removeItem(at: Self.readyURL); try FileManager.default.moveItem(at: Self.partialURL, to: Self.readyURL)
                exportURL = Self.readyURL; progress = next.ipaSize; status = "未签名 IPA 已校验，可分享或导出"; success = true
            } catch { if session.lease.isValid { status = safeMessage(error) } }
            await session.closeAndDrain()
            if transport === session { transport = nil; task = nil; downloading = false }
            WorkLifetime.shared.end("download", success: success)
            onFinished?()
        }
    }
    func pause() { transport?.cancel(); task?.cancel(); pendingRoute = nil; pendingUpdate = nil; cellularConfirmation = false; if downloading { status = "下载已暂停，可手动继续" } }
    func networkChanged() { pause(); offered = nil }
    func background() { if !downloading { pause() }; offered = nil; automaticUsed = false }
}
