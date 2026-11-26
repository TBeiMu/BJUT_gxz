import Foundation

extension AppState {
    func restoreBilling() async {
        guard let account = accounts.defaultAccount else { billing = nil; return }
        let snapshot = await billingCache.load(account.id)
        guard accounts.defaultAccountID == account.id else { return }
        billing = snapshot; billingCached = snapshot != nil; billingCanOffline = false
    }
    func cancelBilling() {
        let session = billingSession; session?.transport.cancel(); billingTask?.cancel(); billingCanOffline = false; billingCaptcha = nil; billingCode = ""; billingPage = nil; billingPrefill = nil
        if let session { Task { await session.close(); if billingSession === session { billingSession = nil } } }
    }
    func stopBilling() async {
        cancelBilling(); await billingTask?.value
        if let session = billingSession { await session.close(); if billingSession === session { billingSession = nil } }
        billingBusy = false
    }
    func refreshBilling(web: Bool = false) {
        guard !billingBusy, accountError == nil, let id = accounts.defaultAccountID, network.ready, network.wifiAvailable else { return }
        automaticBillingUsed = true; billingBusy = true; billingCanOffline = false; billingStatus = "正在查询…"
        billingTask = Task {
            do {
                let account = try await accountStore.credentials(id)
                let session: BillingSession
                if let previous = billingSession, previous.transport.lease.isValid, !web { session = previous }
                else { if let old = billingSession { await old.close() }; session = BillingSession(transport: NetworkTransport(route: try network.route(.wifiOnly))); billingSession = session }
                if web {
                    let page = try await session.openWeb(account); let prefill = await session.prefill()
                    try session.transport.lease.requireValid(); guard active, accounts.defaultAccountID == id else { throw AppError.cancelled }
                    billingPage = page; billingPrefill = prefill
                } else {
                    let code: String? = billingCaptcha == nil ? nil : billingCode.trimmed
                    let result = try await session.refresh(account, code: code); try await acceptBilling(result, session: session, id: id)
                }
            } catch { await billingFailed(error, id: id) }
            billingBusy = false; billingTask = nil
        }
    }
    func acceptBilling(_ result: BillingSnapshot, session: BillingSession, id: String) async throws {
        try session.transport.lease.requireValid(); guard accounts.defaultAccountID == id else { throw AppError.conflict }
        billing = result; billingCached = false; billingCanOffline = result.deviceError == nil; billingStatus = result.notice ?? "查询成功"
        billingCaptcha = nil; billingCode = ""; billingPage = nil; billingPrefill = nil
        await billingCache.save(accountID: id, snapshot: result, lease: session.transport.lease)
    }
    func billingFailed(_ error: Error, id: String) async {
        guard let session = billingSession, session.transport.lease.isValid, accounts.defaultAccountID == id else { return }
        billingCanOffline = false
        if let issue = error as? BillingIssue {
            billingStatus = issue.localizedDescription
            if issue == .captcha || issue == .captchaRejected { billingCaptcha = await session.captchaImage(); billingCode = "" }
            if issue == .accountMismatch || issue == .expired { billing = nil; await billingCache.clear(); await session.close(); billingSession = nil }
        } else { billingStatus = "查询失败" }
    }
    func submitBillingPage(token: String, values: [String: String]) {
        guard !billingBusy, let session = billingSession, let id = accounts.defaultAccountID else { return }; billingBusy = true
        billingTask = Task {
            do {
                let result = try await session.submitWeb(token: token, password: values["password"] ?? "", code: values["code"] ?? "")
                try session.transport.lease.requireValid(); guard active, accounts.defaultAccountID == id else { throw AppError.cancelled }
                switch result {
                case .page(let page): billingPage = page
                case .success(let result): try await acceptBilling(result, session: session, id: id)
                }
            } catch { await billingFailed(error, id: id) }
            billingBusy = false; billingTask = nil
        }
    }
    func offlineDevice(_ device: OnlineDevice) async {
        guard billingCanOffline, !billingBusy, let session = billingSession, network.wifiAvailable else { return }
        let accountID = accounts.defaultAccountID
        autoSuppressed = true; await waitCampus(); guard session.transport.lease.isValid else { return }
        billingBusy = true; billingCanOffline = false; billingStatus = "正在注销设备…"; await billingCache.clear()
        do { try await session.offline(device); try session.transport.lease.requireValid(); billingStatus = "注销已确认，请刷新核对" }
        catch { if accounts.defaultAccountID == accountID { billingStatus = "注销未确认，请刷新核对" } }
        billingBusy = false
    }
}
