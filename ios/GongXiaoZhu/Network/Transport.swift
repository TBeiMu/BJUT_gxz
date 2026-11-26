import Foundation
import CoreFoundation
import Observation
@preconcurrency import Network
@preconcurrency import NIOCore
@preconcurrency import NIOHTTP1
@preconcurrency import NIOFoundationCompat
@preconcurrency import NIOTransportServices
import Security

enum RoutePolicy: Sendable { case wifiOnly, internet }
struct NetworkRoute: @unchecked Sendable {
    var interface: NWInterface
    var epoch: UInt64
    var cellular: Bool
}

@MainActor @Observable final class NetworkMonitor {
    private(set) var wifiAvailable = false
    private(set) var internetAvailable = false
    private(set) var ready = false
    private(set) var epoch: UInt64 = 0
    @ObservationIgnored private var wifiPath: NWPath?
    @ObservationIgnored private var internetPath: NWPath?
    @ObservationIgnored private var monitors: [NWPathMonitor] = []
    @ObservationIgnored var onChange: (() -> Void)?

    func start() {
        guard monitors.isEmpty else { return }
        ready = false
        for wifi in [false, true] {
            let monitor = wifi ? NWPathMonitor(requiredInterfaceType: .wifi) : NWPathMonitor()
            monitor.pathUpdateHandler = { [weak self] path in
                Task { @MainActor in self?.received(path, wifi: wifi) }
            }
            monitor.start(queue: DispatchQueue(label: wifi ? "gxz.route.wifi" : "gxz.route.internet"))
            monitors.append(monitor)
        }
    }
    private func received(_ path: NWPath, wifi: Bool) {
        let previous = wifi ? wifiPath : internetPath
        if wifi { wifiPath = path; wifiAvailable = path.status == .satisfied }
        else { internetPath = path; internetAvailable = path.status == .satisfied }
        ready = wifiPath != nil && internetPath != nil
        // Every delivered route change invalidates leases, including changes on the same interface.
        if previous != nil { epoch &+= 1; onChange?() }
        else if ready { onChange?() }
    }
    func route(_ policy: RoutePolicy) throws -> NetworkRoute {
        guard ready else { throw AppError.connection }
        let path: NWPath?
        if policy == .wifiOnly { path = wifiPath }
        else { path = internetPath }
        guard let path, path.status == .satisfied else { throw policy == .wifiOnly ? AppError.wifiRequired : AppError.connection }
        // A visible tunnel is not an accepted physical route. No VPN capabilities are requested.
        guard !path.usesInterfaceType(.other) else { throw AppError.validation("请关闭 VPN 后重新操作") }
        let type: NWInterface.InterfaceType = policy == .wifiOnly || path.usesInterfaceType(.wifi) ? .wifi : .cellular
        guard let interface = path.availableInterfaces.first(where: { $0.type == type }) else { throw AppError.connection }
        return NetworkRoute(interface: interface, epoch: epoch, cellular: type == .cellular)
    }
    func stop() {
        monitors.forEach { $0.cancel() }; monitors.removeAll()
        wifiPath = nil; internetPath = nil; wifiAvailable = false; internetAvailable = false; ready = false
        epoch &+= 1
    }
}

enum RequestPurpose: Sendable { case portal, schedule, billing, academic, update, connectivity }
enum Addresses {
    static let schedule = "https://itsapp.bjut.edu.cn"
    static let cas = "https://cas.bjut.edu.cn"
    static let billing = "https://jfself.bjut.edu.cn"
    static let academic = "https://webrecdoc.bjut.edu.cn"
    static func allowed(_ url: URL, _ purpose: RequestPurpose) -> Bool {
        guard url.user == nil, url.password == nil, let host = url.host?.lowercased() else { return false }
        if purpose == .portal {
            return ["http://10.21.251.3/drcom/chkstatus", "http://10.21.251.3/drcom/login", "http://10.21.221.98/drcom/chkstatus", "http://10.21.221.98:801/eportal/"].contains { base in
                var components = URLComponents(url: url, resolvingAgainstBaseURL: false)
                components?.query = nil; components?.fragment = nil
                return components?.url?.absoluteString == base
            }
        }
        guard url.scheme == "https", (url.port ?? 443) == 443 else { return false }
        switch purpose {
        case .portal: return false
        case .schedule: return ["itsapp.bjut.edu.cn", "cas.bjut.edu.cn", "imgitsapp.bjut.edu.cn"].contains(host)
        case .billing: return host == "jfself.bjut.edu.cn" && url.path.hasPrefix("/Self/")
        case .academic: return host == "webrecdoc.bjut.edu.cn" && ["/pyxx/login.aspx", "/pyxx/pygl/pykscx.aspx", "/pyxx/grgl/xskccjcx.aspx", "/pyxx/PageTemplate/NsoftPage/yzm/createyzm.aspx", "/pyxx/default.aspx", "/pyxx/loging.aspx"].contains(url.path)
        case .connectivity: return ["connectivitycheck.platform.hicloud.com", "connect.rom.miui.com"].contains(host) && url.path == "/generate_204"
        case .update:
            if host == "github.com" { return url.path.matches("/TBeiMu/BJUT_gxz/releases/(?:latest/download|download/v[0-9]+\\.[0-9]+\\.[0-9]+)/(?:update-ios\\.json|gongxiaozhu-ios-unsigned\\.ipa)") }
            return ["release-assets.githubusercontent.com", "objects.githubusercontent.com"].contains(host)
        }
    }
    static func casResource(_ url: URL) -> Bool {
        allowed(url, .schedule) && url.host == "cas.bjut.edu.cn" && ["/cas/", "/js/", "/css/", "/images/", "/img/", "/captcha"].contains { url.path.hasPrefix($0) }
    }
    static func resolve(_ value: String, base: URL) throws -> URL {
        guard let url = URL(string: value, relativeTo: base)?.absoluteURL else { throw AppError.address }
        return url
    }
    static func query(_ url: URL, _ fields: [(String, String)]) throws -> URL {
        guard var components = URLComponents(url: url, resolvingAgainstBaseURL: false) else { throw AppError.address }
        components.queryItems = (components.queryItems ?? []) + fields.map { URLQueryItem(name: $0.0, value: $0.1) }
        // URLComponents leaves '+' literal; portal and form servers interpret it as a space.
        components.percentEncodedQuery = components.percentEncodedQuery?.replacingOccurrences(of: "+", with: "%2B")
        guard let result = components.url else { throw AppError.address }
        return result
    }
    static func form(_ fields: [(String, String)]) -> Data {
        let allowed = CharacterSet(charactersIn: "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~")
        func encode(_ text: String) -> String { text.addingPercentEncoding(withAllowedCharacters: allowed) ?? "" }
        return Data(fields.map { encode($0.0) + "=" + encode($0.1) }.joined(separator: "&").utf8)
    }
}

struct HTTPResponse: Sendable {
    var status: Int
    var headers: [(String, String)]
    var body: Data
    var url: URL
    func header(_ name: String) -> String? { headers.first { $0.0.caseInsensitiveCompare(name) == .orderedSame }?.1 }
    var mime: String { header("Content-Type")?.components(separatedBy: ";").first?.trimmed ?? "application/octet-stream" }
    func text() throws -> String {
        let charset = header("Content-Type")?.captures("charset\\s*=\\s*[\"']?([a-zA-Z0-9_-]+)", options: .caseInsensitive).first?[1]
            ?? String(data: body.prefix(4096), encoding: .isoLatin1)?.captures("charset\\s*=\\s*[\"']?([a-zA-Z0-9_-]+)", options: .caseInsensitive).first?[1]
        let encoding: String.Encoding
        switch charset?.lowercased() {
        case "gbk", "gb2312", "gb18030": encoding = .init(rawValue: CFStringConvertEncodingToNSStringEncoding(CFStringEncoding(CFStringEncodings.GB_18030_2000.rawValue)))
        case "iso-8859-1": encoding = .isoLatin1
        default: encoding = .utf8
        }
        guard let value = String(data: body, encoding: encoding) else { throw AppError.response }
        return value
    }
}

private final class HTTPCollector: ChannelInboundHandler, @unchecked Sendable {
    typealias InboundIn = HTTPClientResponsePart
    let promise: EventLoopPromise<HTTPResponse>
    let url: URL
    let limit: Int
    var status = 0
    var headers: [(String, String)] = []
    var data = Data()
    var complete = false
    var stream: (@Sendable (Data) throws -> Void)?
    var onHead: (@Sendable (Int, [(String, String)]) throws -> Void)?
    init(promise: EventLoopPromise<HTTPResponse>, url: URL, limit: Int, stream: (@Sendable (Data) throws -> Void)?, onHead: (@Sendable (Int, [(String, String)]) throws -> Void)?) {
        self.promise = promise; self.url = url; self.limit = limit; self.stream = stream; self.onHead = onHead
    }
    func channelRead(context: ChannelHandlerContext, data: NIOAny) {
        guard !complete else { return }
        switch unwrapInboundIn(data) {
        case .head(let head):
            status = Int(head.status.code)
            headers = head.headers.map { ($0.name, $0.value) }
            if status < 200 { return }
            do { try onHead?(status, headers) } catch { fail(error, context: context); return }
            if let length = head.headers.first(name: "Content-Length"), let size = Int64(length), size > Int64(limit) { fail(AppError.response, context: context) }
        case .body(let bytes):
            guard status >= 200, let chunk = bytes.getData(at: bytes.readerIndex, length: bytes.readableBytes) else { return }
            do {
                if let stream, status == 200 || status == 206 { try stream(chunk) }
                else { guard self.data.count + chunk.count <= limit else { throw AppError.response }; self.data.append(chunk) }
            } catch { fail(error, context: context) }
        case .end:
            guard status >= 200 else { return }
            do {
                if stream == nil {
                    let coding = headers.first { $0.0.lowercased() == "content-encoding" }?.1.lowercased() ?? "identity"
                    if coding == "gzip" || coding == "deflate" { self.data = try inflate(self.data, limit: limit) }
                    else if coding != "identity" { throw AppError.response }
                }
                complete = true; promise.succeed(HTTPResponse(status: status, headers: headers, body: self.data, url: url))
            } catch { fail(error, context: context) }
        }
    }
    private func fail(_ error: Error, context: ChannelHandlerContext) {
        guard !complete else { return }; complete = true
        promise.fail(error is AppError ? error : AppError.connection)
        context.close(promise: nil)
    }
    func errorCaught(context: ChannelHandlerContext, error: Error) { fail(AppError.connection, context: context) }
    func channelInactive(context: ChannelHandlerContext) {
        if !complete { fail(AppError.connection, context: context) }
        context.fireChannelInactive()
    }
}

private func inflate(_ data: Data, limit: Int) throws -> Data {
    var output: UnsafeMutablePointer<UInt8>?
    var length: Int = 0
    let status = data.withUnsafeBytes { bytes in GXZInflate(bytes.bindMemory(to: UInt8.self).baseAddress, data.count, &output, &length, limit) }
    guard status == 0, let output, length <= limit else { throw AppError.response }
    defer { free(output) }
    return Data(bytes: output, count: length)
}

private final class HTTPCall: @unchecked Sendable {
    let lock = NSLock()
    var channel: Channel?
    var cancelled = false
    func attach(_ channel: Channel) { lock.withLock { self.channel = channel; if cancelled { channel.close(promise: nil) } } }
    func cancel() { lock.withLock { cancelled = true; channel?.close(promise: nil) } }
    var valid: Bool { lock.withLock { !cancelled } }
}

/// One explicit HTTP request per connection; there is no automatic credential replay or redirect.
final class NetworkTransport: @unchecked Sendable {
    let route: NetworkRoute
    let lease: OperationLease
    private let lock = NSLock()
    private var calls: [String: HTTPCall] = [:]
    private var waiters: [CheckedContinuation<Void, Never>] = []
    private static let loops = NIOTSEventLoopGroup(loopCount: 2)
    init(route: NetworkRoute, lease: OperationLease = OperationLease()) { self.route = route; self.lease = lease }

    func request(_ url: URL, purpose: RequestPurpose, method: String = "GET", headers: [(String, String)] = [], body: Data? = nil,
                 timeout: Double = 18, limit: Int = 2 * 1024 * 1024, ipv4: Bool = false,
                 stream: (@Sendable (Data) throws -> Void)? = nil,
                 onHead: (@Sendable (Int, [(String, String)]) throws -> Void)? = nil) async throws -> HTTPResponse {
        guard Addresses.allowed(url, purpose), method == "GET" || method == "POST" else { throw AppError.address }
        try lease.requireValid(); try Task.checkCancellation()
        let call = HTTPCall(); let id = UUID().uuidString
        lock.withLock { calls[id] = call }
        defer {
            let pending = lock.withLock { () -> [CheckedContinuation<Void, Never>] in
                calls.removeValue(forKey: id)
                if calls.isEmpty { let pending = waiters; waiters = []; return pending }
                return []
            }
            pending.forEach { $0.resume() }
        }
        return try await withTaskCancellationHandler {
            let bootstrap = NIOTSConnectionBootstrap(group: Self.loops)
                .connectTimeout(.milliseconds(Int64(min(timeout, 8) * 1000)))
                .channelOption(NIOTSChannelOptions.waitForActivity, value: false)
                .configureNWParameters { [route] parameters in
                    parameters.requiredInterface = route.interface
                    parameters.requiredInterfaceType = route.cellular ? .cellular : .wifi
                    parameters.prohibitedInterfaceTypes = [.other, .loopback]
                    parameters.multipathServiceType = .disabled
                    parameters.includePeerToPeer = false
                    if ipv4, let options = parameters.defaultProtocolStack.internetProtocol as? NWProtocolIP.Options { options.version = .v4 }
                }
            if url.scheme == "https" {
                let options = NWProtocolTLS.Options()
                sec_protocol_options_set_tls_server_name(options.securityProtocolOptions, url.host!)
                sec_protocol_options_add_tls_application_protocol(options.securityProtocolOptions, "http/1.1")
                _ = bootstrap.tlsOptions(options)
            }
            let channel = try await bootstrap.connect(host: url.host!, port: url.port ?? (url.scheme == "https" ? 443 : 80)).get()
            call.attach(channel)
            let promise = channel.eventLoop.makePromise(of: HTTPResponse.self)
            let collector = HTTPCollector(promise: promise, url: url, limit: limit, stream: stream, onHead: onHead)
            let timer = channel.eventLoop.scheduleTask(in: .milliseconds(Int64(timeout * 1000))) {
                if !collector.complete { collector.complete = true; promise.fail(AppError.connection); channel.close(promise: nil) }
            }
            do {
                try lease.requireValid(); try Task.checkCancellation()
                guard call.valid else { throw AppError.cancelled }
                try await channel.pipeline.configureHTTPClientPipeline().flatMap { channel.pipeline.addHandler(collector) }.get()
                var fields = HTTPHeaders()
                fields.add(name: "Host", value: url.host! + (url.port.map { ":\($0)" } ?? ""))
                fields.add(name: "User-Agent", value: "Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) AppleWebKit/605.1.15 Mobile/15E148 GongXiaoZhu/" + AppInfo.version)
                fields.add(name: "Accept-Language", value: "zh-CN,zh;q=0.9")
                fields.add(name: "Accept-Encoding", value: "identity")
                fields.add(name: "Cache-Control", value: "no-store")
                fields.add(name: "Connection", value: "close")
                for (key, value) in headers {
                    guard !key.contains("\r"), !key.contains("\n"), !value.contains("\r"), !value.contains("\n") else { throw AppError.address }
                    fields.replaceOrAdd(name: key, value: value)
                }
                if let body { fields.replaceOrAdd(name: "Content-Length", value: String(body.count)) }
                let components = URLComponents(url: url, resolvingAgainstBaseURL: false)!
                let uri = (components.percentEncodedPath.isEmpty ? "/" : components.percentEncodedPath) + (components.percentEncodedQuery.map { "?" + $0 } ?? "")
                let head = HTTPRequestHead(version: .http1_1, method: method == "POST" ? .POST : .GET, uri: uri, headers: fields)
                try await channel.writeAndFlush(HTTPClientRequestPart.head(head)).get()
                if let body { var buffer = channel.allocator.buffer(capacity: body.count); buffer.writeBytes(body); try await channel.writeAndFlush(HTTPClientRequestPart.body(.byteBuffer(buffer))).get() }
                try await channel.writeAndFlush(HTTPClientRequestPart.end(nil)).get()
                let response = try await promise.futureResult.get()
                timer.cancel(); try? await channel.close().get(); try? await channel.closeFuture.get()
                try lease.requireValid(); try Task.checkCancellation()
                return response
            } catch {
                timer.cancel(); try? await channel.close().get(); try? await channel.closeFuture.get()
                if !lease.isValid || !call.valid || Task.isCancelled { throw AppError.cancelled }
                throw error is AppError ? error : AppError.connection
            }
        } onCancel: { call.cancel() }
    }
    func cancel() { lease.invalidate(); let active = lock.withLock { Array(calls.values) }; active.forEach { $0.cancel() } }
    func drain() async {
        await withCheckedContinuation { continuation in
            let empty = lock.withLock { if calls.isEmpty { return true }; waiters.append(continuation); return false }
            if empty { continuation.resume() }
        }
    }
    func closeAndDrain() async { cancel(); await drain() }
}

actor CookieHTTPClient {
    nonisolated let transport: NetworkTransport
    let purpose: RequestPurpose
    private var cookies: [HTTPCookie] = []
    init(transport: NetworkTransport, purpose: RequestPurpose) { self.transport = transport; self.purpose = purpose }
    func request(_ url: URL, method: String = "GET", fields: [(String, String)]? = nil, headers: [(String, String)] = [], limit: Int = 2 * 1024 * 1024) async throws -> HTTPResponse {
        try transport.lease.requireValid()
        cookies.removeAll { $0.expiresDate.map { $0 <= Date() } ?? false }
        let selected = cookies.filter { cookie in
            let domain = cookie.domain.trimmingCharacters(in: CharacterSet(charactersIn: "."))
            let host = url.host ?? ""
            return (host == domain || host.hasSuffix("." + domain)) && url.path.hasPrefix(cookie.path) && (!cookie.isSecure || url.scheme == "https")
        }.sorted { $0.path.count > $1.path.count }
        var outgoing = headers
        if !selected.isEmpty { outgoing.append(("Cookie", selected.map { $0.name + "=" + $0.value }.joined(separator: "; "))) }
        if fields != nil { outgoing.append(("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")) }
        let response = try await transport.request(url, purpose: purpose, method: method, headers: outgoing,
                                                   body: fields.map(Addresses.form), limit: limit)
        try transport.lease.requireValid()
        for field in response.headers where field.0.lowercased() == "set-cookie" {
            for cookie in HTTPCookie.cookies(withResponseHeaderFields: ["Set-Cookie": field.1], for: url) {
                let domain = cookie.domain.trimmingCharacters(in: CharacterSet(charactersIn: "."))
                guard url.host == domain || url.host?.hasSuffix("." + domain) == true else { continue }
                cookies.removeAll { $0.name == cookie.name && $0.domain == cookie.domain && $0.path == cookie.path }
                if cookie.expiresDate.map({ $0 > Date() }) ?? true { cookies.append(cookie) }
            }
        }
        guard cookies.count <= 64, cookies.reduce(0, { $0 + $1.name.count + $1.value.count }) <= 32 * 1024 else { cookies.removeAll(); throw AppError.response }
        return response
    }
    func follow(_ initial: URL, fields: [(String, String)]? = nil, referer: URL? = nil, limit: Int = 2 * 1024 * 1024) async throws -> HTTPResponse {
        var url = initial; var post = fields
        for _ in 0..<8 {
            let response = try await request(url, method: post == nil ? "GET" : "POST", fields: post,
                                             headers: referer.map { [("Referer", $0.absoluteString)] } ?? [], limit: limit)
            if [301, 302, 303, 307, 308].contains(response.status) {
                guard let location = response.header("Location"), !(post != nil && [307, 308].contains(response.status)) else { throw AppError.address }
                url = try Addresses.resolve(location, base: url)
                guard Addresses.allowed(url, purpose) else { throw AppError.address }
                post = nil
            } else {
                guard (200...299).contains(response.status) else { throw AppError.connection }
                return response
            }
        }
        throw AppError.address
    }
    func clear() { cookies.removeAll() }
    func close() async { transport.cancel(); cookies.removeAll(); await transport.drain() }
}

func withDeadline<T: Sendable>(_ seconds: Double, operation: @escaping @Sendable () async throws -> T) async throws -> T {
    try await withThrowingTaskGroup(of: T.self) { group in
        group.addTask { try await operation() }
        group.addTask { try await Task.sleep(for: .seconds(seconds)); throw AppError.connection }
        defer { group.cancelAll() }
        guard let result = try await group.next() else { throw AppError.cancelled }
        return result
    }
}
