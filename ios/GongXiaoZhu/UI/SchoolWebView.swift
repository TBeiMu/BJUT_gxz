import SwiftUI
import WebKit
@preconcurrency import SwiftSoup

private enum WebPageRewrite {
    static let scheme = "gxz-school"
    static func local(_ original: URL) -> URL {
        var parts = URLComponents(url: original, resolvingAgainstBaseURL: false)!; parts.scheme = scheme; return parts.url!
    }
    static func remote(_ local: URL) throws -> URL {
        guard local.scheme == scheme else { throw AppError.address }; var parts = URLComponents(url: local, resolvingAgainstBaseURL: false)!; parts.scheme = "https"; guard let url = parts.url else { throw AppError.address }; return url
    }
    static func convert(_ text: String, base: URL) -> String {
        var result = text
        for host in ["cas.bjut.edu.cn", "itsapp.bjut.edu.cn", "imgitsapp.bjut.edu.cn", "jfself.bjut.edu.cn"] {
            result = result.replacingOccurrences(of: "https://" + host, with: scheme + "://" + host)
            result = result.replacingOccurrences(of: "//" + host, with: "//" + host)
        }
        return result
    }
    static func html(_ text: String, base: URL) throws -> String {
        let document = try SwiftSoup.parse(convert(text, base: base), local(base).absoluteString)
        try document.select("base, meta[http-equiv=refresh], meta[http-equiv=Content-Security-Policy]").remove()
        try document.head()?.prepend("<meta name='viewport' content='width=device-width, initial-scale=1, viewport-fit=cover'><base href='\(local(base).absoluteString)'><meta http-equiv='Content-Security-Policy' content=\"default-src gxz-school: data: blob: 'unsafe-inline' 'unsafe-eval'; connect-src gxz-school:; form-action 'none'; frame-src gxz-school:; object-src 'none';\"><style>html,body{min-height:100%;height:100%;}iframe{min-height:560px;min-height:75vh;}input{font-size:16px!important;}</style>")
        return try document.outerHtml()
    }
}

struct SchoolWebView: UIViewRepresentable {
    let page: SchoolLoginPage
    var prefill: CampusAccount?
    let resource: @Sendable (URL) async throws -> WebResource
    let submit: (String, [String: String]) -> Void
    let fail: (String) -> Void
    func makeCoordinator() -> Coordinator { Coordinator(self) }
    func makeUIView(context: Context) -> WKWebView {
        let configuration = WKWebViewConfiguration()
        configuration.websiteDataStore = .nonPersistent()
        configuration.setURLSchemeHandler(context.coordinator, forURLScheme: WebPageRewrite.scheme)
        configuration.preferences.javaScriptCanOpenWindowsAutomatically = false
        configuration.defaultWebpagePreferences.allowsContentJavaScript = true
        configuration.userContentController.add(context.coordinator, name: "school")
        configuration.userContentController.addUserScript(WKUserScript(source: context.coordinator.bridge(), injectionTime: .atDocumentStart, forMainFrameOnly: false))
        let view = WKWebView(frame: .zero, configuration: configuration)
        view.navigationDelegate = context.coordinator; view.isInspectable = false
        view.scrollView.keyboardDismissMode = .interactive
        context.coordinator.webView = view
        let rules = "[{\"trigger\":{\"url-filter\":\"^https?://\"},\"action\":{\"type\":\"block\"}}]"
        WKContentRuleListStore.default().compileContentRuleList(forIdentifier: "gxz.offline.school", encodedContentRuleList: rules) { list, error in
            Task { @MainActor in
                guard let list else { self.fail("学校页面隔离初始化失败，请关闭后重新连接"); return }
                view.configuration.userContentController.add(list)
                view.load(URLRequest(url: WebPageRewrite.local(page.url)))
            }
        }
        return view
    }
    func updateUIView(_ view: WKWebView, context: Context) { }
    static func dismantleUIView(_ view: WKWebView, coordinator: Coordinator) { view.stopLoading(); view.navigationDelegate = nil; view.configuration.userContentController.removeScriptMessageHandler(forName: "school"); coordinator.tasks.values.forEach { $0.cancel() }; coordinator.tasks.removeAll() }
    @MainActor final class Coordinator: NSObject, WKURLSchemeHandler, WKScriptMessageHandler, WKNavigationDelegate {
        let owner: SchoolWebView
        weak var webView: WKWebView?
        var tasks: [ObjectIdentifier: Task<Void, Never>] = [:]
        var submitted = false
        init(_ owner: SchoolWebView) { self.owner = owner }
        func bridge() -> String {
            let prefill: [String: String] = owner.prefill.map { ["account": $0.username, "password": $0.password] } ?? [:]
            let bytes = (try? JSONSerialization.data(withJSONObject: ["token": owner.page.id, "fill": prefill])) ?? Data("{}".utf8)
            let json = String(data: bytes, encoding: .utf8)!
            return #"""
            (()=>{'use strict'; const config=__CONFIG__;let actionAt=0,sent=false;
            ['pointerdown','touchstart','keydown'].forEach(n=>document.addEventListener(n,e=>{if(e.isTrusted)actionAt=Date.now()},true));
            const rewrite=u=>{try{let x=new URL(u,location.href);if(x.protocol==='https:'&&['cas.bjut.edu.cn','itsapp.bjut.edu.cn','imgitsapp.bjut.edu.cn','jfself.bjut.edu.cn'].includes(x.hostname))x.protocol='gxz-school:';return x.href}catch{return 'about:blank'}};
            const oldOpen=XMLHttpRequest.prototype.open;XMLHttpRequest.prototype.open=function(m,u,...rest){if(String(m).toUpperCase()!=='GET')throw Error('Native submission required');return oldOpen.call(this,m,rewrite(u),...rest)};
            window.fetch=(u,o)=>Promise.reject(Error('Native submission required'));
            window.open=()=>null;
            function send(form){if(sent||Date.now()-actionAt>2500)return;const values={};for(const input of form.querySelectorAll('input[name],select[name]')){if(!['username','password','captcha','type','submit','account','code'].includes(input.name))continue;values[input.name]=String(input.value).slice(0,1024)};
            if(!values.password)return;sent=true;window.webkit.messageHandlers.school.postMessage({token:config.token,action:form.action,values})}
            document.addEventListener('submit',e=>{e.preventDefault();e.stopImmediatePropagation();send(e.target)},true);
            HTMLFormElement.prototype.submit=function(){send(this)};
            document.addEventListener('DOMContentLoaded',()=>{for(const [key,value] of Object.entries(config.fill)){const input=document.querySelector('form input[name="'+key+'"]');if(input)input.value=value;}const fill=setInterval(()=>{document.querySelectorAll('iframe').forEach(f=>{f.style.minHeight='75vh';f.style.height='100%'});},500);setTimeout(()=>clearInterval(fill),5000);});
            })();
            """#.replacingOccurrences(of: "__CONFIG__", with: json)
        }
        func webView(_ webView: WKWebView, start urlSchemeTask: WKURLSchemeTask) {
            let key = ObjectIdentifier(urlSchemeTask)
            tasks[key] = Task { @MainActor in
                do {
                    guard let local = urlSchemeTask.request.url else { throw AppError.address }
                    let remote = try WebPageRewrite.remote(local)
                    let result: WebResource
                    if remote == owner.page.url { result = WebResource(data: Data(try WebPageRewrite.html(owner.page.html, base: remote).utf8), mime: "text/html", url: remote) }
                    else {
                        let fetched = try await owner.resource(remote)
                        if fetched.mime.contains("html"), let text = String(data: fetched.data, encoding: .utf8) { result = WebResource(data: Data(try WebPageRewrite.html(text, base: remote).utf8), mime: "text/html", url: remote) }
                        else if fetched.mime.contains("css") || fetched.mime.contains("javascript"), let text = String(data: fetched.data, encoding: .utf8) { result = WebResource(data: Data(WebPageRewrite.convert(text, base: remote).utf8), mime: fetched.mime, url: remote) }
                        else { result = fetched }
                    }
                    guard !Task.isCancelled, tasks[key] != nil else { return }
                    let response = URLResponse(url: local, mimeType: result.mime, expectedContentLength: result.data.count, textEncodingName: result.mime.contains("text") || result.mime.contains("javascript") ? "utf-8" : nil)
                    urlSchemeTask.didReceive(response); urlSchemeTask.didReceive(result.data); urlSchemeTask.didFinish()
                } catch { if !Task.isCancelled, tasks[key] != nil { urlSchemeTask.didFailWithError(AppError.connection) } }
                tasks.removeValue(forKey: key)
            }
        }
        func webView(_ webView: WKWebView, stop urlSchemeTask: WKURLSchemeTask) { tasks.removeValue(forKey: ObjectIdentifier(urlSchemeTask))?.cancel() }
        func userContentController(_ userContentController: WKUserContentController, didReceive message: WKScriptMessage) {
            guard !submitted, message.name == "school", let frameURL = message.frameInfo.request.url,
                  frameURL.scheme == WebPageRewrite.scheme, frameURL.host == owner.page.url.host,
                  let body = message.body as? [String: Any], body["token"] as? String == owner.page.id,
                  let actionText = body["action"] as? String, let action = URL(string: actionText),
                  let remote = try? WebPageRewrite.remote(action), remote.host == owner.page.url.host,
                  let values = body["values"] as? [String: String], values.count <= 12, values.values.allSatisfy({ $0.count <= 1024 }) else { return }
            if owner.page.purpose == .schedule { guard remote.path == "/login" else { return } }
            else { guard remote.path == "/Self/login" || remote.path == "/Self/login/" else { return } }
            submitted = true; owner.submit(owner.page.id, values)
        }
        func webView(_ webView: WKWebView, decidePolicyFor navigationAction: WKNavigationAction, decisionHandler: @escaping (WKNavigationActionPolicy) -> Void) {
            guard navigationAction.request.httpMethod == "GET", let url = navigationAction.request.url,
                  url.scheme == WebPageRewrite.scheme, url.host == owner.page.url.host,
                  navigationAction.navigationType != .linkActivated else { decisionHandler(.cancel); return }
            decisionHandler(.allow)
        }
        func webViewWebContentProcessDidTerminate(_ webView: WKWebView) { owner.fail("学校页面已停止，请关闭后重新连接") }
    }
}

struct SchoolPageSheet: View {
    @Environment(AppState.self) var app
    let page: SchoolLoginPage
    var body: some View {
        NavigationStack {
            SchoolWebView(page: page, prefill: page.purpose == .billing ? app.billingPrefill : nil, resource: { url in
                if page.purpose == .schedule { guard let session = await app.importSession else { throw AppError.expired }; return try await session.resource(token: page.id, url: url) }
                guard let session = await app.billingSession else { throw AppError.expired }; return try await session.resource(token: page.id, url: url)
            }, submit: { token, values in if page.purpose == .schedule { app.submitSchedulePage(token: token, values: values) } else { app.submitBillingPage(token: token, values: values) } }, fail: { app.message = $0 })
                .id(page.id).navigationTitle(page.purpose == .schedule ? "学校统一认证" : "学校自助服务").navigationBarTitleDisplayMode(.inline)
                .toolbar { ToolbarItem(placement: .cancellationAction) { Button("关闭") { if page.purpose == .schedule { app.pauseImport() } else { app.cancelBilling() } } } }
                .overlay { if page.purpose == .schedule ? app.scheduleSubmitting : app.billingBusy { ZStack { Color.black.opacity(0.08); ProgressView("正在提交，请稍候").padding(24).background(.regularMaterial, in: RoundedRectangle(cornerRadius: 16)) } } }
        }.interactiveDismissDisabled(page.purpose == .schedule ? app.scheduleSubmitting : app.billingBusy)
    }
}
