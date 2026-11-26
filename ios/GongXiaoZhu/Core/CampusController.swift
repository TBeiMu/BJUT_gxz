import Foundation

extension AppState {
    func loginCampus() {
        guard !campusRunning, accountError == nil else { return }
        do {
            let route = try network.route(.wifiOnly); let transport = NetworkTransport(route: route)
            let accountID = accounts.defaultAccountID; let mode = accounts.settings.networkMode; let repository = accountStore
            campusTransport = transport; campusRunning = true; campusConfirmed = false; campusNeedsChoice = false
            WorkLifetime.shared.begin("campus", title: "校园网认证") { [weak self] in self?.cancelCampus() }
            campusTask = Task {
                let result = await CampusAuthentication.login(transport: transport, mode: mode, credentials: {
                    try transport.lease.requireValid(); guard let accountID else { return nil }; return try await repository.credentials(accountID)
                }, progress: { [weak self] text in await self?.campusProgress(text, transport: transport) }, confirmed: { [weak self] result in await self?.campusSuccess(result, transport: transport) })
                await transport.closeAndDrain()
                guard self.campusTransport === transport else { return }
                let stillValid = route.epoch == self.network.epoch && !Task.isCancelled
                if stillValid && !self.campusConfirmed { self.campusMessage = result.message; self.campusNeedsChoice = result.needsChoice }
                self.campusRunning = false; self.campusTask = nil; self.campusTransport = nil
                WorkLifetime.shared.end("campus", success: self.campusConfirmed)
                if self.campusConfirmed && !self.active { self.campusFinishedInBackground = true }
                if self.campusConfirmed && stillValid && self.active, let accountID { self.lookupName(accountID) }
                self.stopMonitoringIfIdle()
            }
        } catch { campusMessage = safeMessage(error) }
    }
    func campusProgress(_ text: String, transport: NetworkTransport) { guard campusTransport === transport, transport.lease.isValid, !campusConfirmed else { return }; campusMessage = text }
    func campusSuccess(_ result: CampusResult, transport: NetworkTransport) { guard campusTransport === transport, transport.lease.isValid, transport.route.epoch == network.epoch else { return }; campusConfirmed = true; campusMessage = result.message }
    func cancelCampus() { campusTransport?.cancel(); campusTask?.cancel(); if campusRunning { campusConfirmed = false; campusMessage = "认证已停止，正在清理请求" } }
    func waitCampus() async { cancelCampus(); let old = campusTask; await old?.value }
    func lookupName(_ id: String) {
        guard nameTask == nil, active, accounts.accounts.first(where: { $0.id == id })?.hasNameOpportunity == true else { return }
        do {
            let transport = NetworkTransport(route: try network.route(.wifiOnly)); nameTransport = transport
            nameTask = Task {
                do {
                    if let account = try await accountStore.beginNameLookup(id, lease: transport.lease) {
                        let session = ScheduleSession(transport: transport)
                        let identity = try await withDeadline(30) { try await session.accountIdentity(account) }
                        let next = try await accountStore.fillName(identity, account: account, lease: transport.lease)
                        if transport.lease.isValid { accounts = next }
                        await session.close()
                    }
                } catch {
                    if case AppError.validation(let text) = error, transport.lease.isValid { message = text }
                }
                await transport.closeAndDrain()
                if nameTransport === transport { nameTask = nil; nameTransport = nil }
                if active, let data = try? await accountStore.load() { accounts = data }
            }
        } catch { }
    }
    func cancelName() { nameTransport?.cancel(); nameTask?.cancel() }
    func waitAccountTasks() async { await waitCampus(); cancelName(); await nameTask?.value; await stopBilling() }
    func beginAccountEdit(_ id: String? = nil) async {
        await waitAccountTasks()
        do { let original: CampusAccount?; if let id { original = try await accountStore.credentials(id) } else { original = nil }; accountEdit = AccountEdit(original: original) } catch { notify(error) }
    }
    func saveAccount(_ draft: CampusAccount, original: CampusAccount?) async -> Bool {
        await waitAccountTasks()
        do {
            accounts = try await accountStore.save(draft, original: original); accountError = nil
            billing = nil; billingCanOffline = false; await billingCache.clear()
            if let identity = schedule.identity { let lease = OperationLease(); accounts = (try? await accountStore.fillName(identity, lease: lease)) ?? accounts }
            accountEdit = nil; return true
        } catch { notify(error); return false }
    }
    func deleteAccount(_ id: String) async { await waitAccountTasks(); do { accounts = try await accountStore.delete(id); billing = nil; await billingCache.clear(); await restoreBilling() } catch { notify(error) } }
    func setDefault(_ id: String) async { await stopBilling(); do { accounts = try await accountStore.setDefault(id); billing = nil; billingStatus = ""; await billingCache.clear() } catch { notify(error) } }
    func saveSettings(_ settings: CampusSettings) async { guard !settingsSaving else { return }; settingsSaving = true; defer { settingsSaving = false }; await waitCampus(); cancelName(); await nameTask?.value; do { accounts = try await accountStore.settings(settings) } catch { notify(error) } }
}
