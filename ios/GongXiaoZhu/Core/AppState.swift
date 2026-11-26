import Foundation
import Observation
import UIKit

enum AppTab: String, CaseIterable, Hashable {
    case schedule, billing, mine
    var title: String { switch self { case .schedule: "课表"; case .billing: "资费"; case .mine: "我的" } }
    var symbol: String { switch self { case .schedule: "calendar"; case .billing: "chart.bar.xaxis"; case .mine: "person" } }
}
enum AppPage: String, Hashable { case campus, schedule, shuttle, appearance, exams, grades, update, about }
struct AccountEdit: Identifiable { var id = UUID(); var original: CampusAccount? }
struct ExamEdit: Identifiable { var id = UUID(); var record: ExamRecord?; var revision: Int64 }

@MainActor @Observable final class AppState {
    let network = NetworkMonitor()
    let accountStore = AccountRepository()
    let scheduleStore = ScheduleRepository()
    let academicStore = AcademicRepository()
    let credentialStore = AcademicCredentialRepository()
    let billingCache = BillingCacheRepository()
    let appearance = AppearanceStore()
    let updates = UpdateController()
    var tab: AppTab = .schedule
    var paths: [AppTab: [AppPage]] = [:]
    var campusAccountsFocus = false
    var accounts = AccountData()
    var schedule = ScheduleData()
    var academic = AcademicData()
    var accountError: String?
    var scheduleError: String?
    var academicError: String?
    var message: String?
    var loaded = false
    var active = true
    var wholeWeek = UserDefaults.standard.bool(forKey: "schedule.wholeWeek")
    var selectedWeek = 1
    var accountEdit: AccountEdit?
    var courseEdit: CourseEditContext?
    var examEdit: ExamEdit?
    var undo: UndoReceipt?
    var undoDeadline: Date?
    var campusRunning = false
    var campusConfirmed = false
    var campusMessage = "连接校园 WiFi 后可登录"
    var campusNeedsChoice = false
    var settingsExpanded = false
    var scheduleBusy = false
    var scheduleStatus = ""
    var schedulePage: SchoolLoginPage?
    var scheduleSubmitting = false
    var billing: BillingSnapshot?
    var billingBusy = false
    var billingStatus = ""
    var billingCached = false
    var billingCanOffline = false
    var billingCaptcha: Data?
    var billingCode = ""
    var billingPage: SchoolLoginPage?
    var billingPrefill: CampusAccount?
    var academicTarget: AcademicTarget?
    var academicForm: AcademicLoginForm?
    var academicBusy = false
    var academicCommitting = false
    var academicStatus = ""
    var academicUsername = ""
    var academicPassword = ""
    var academicCode = ""
    var rememberAcademic = false
    var credentialPresent = false
    var credentialError = false
    var pendingAcademicIdentity: SchoolIdentity?
    @ObservationIgnored var academicResult: (SchoolIdentity, [SchoolExam]?, GradeSnapshot?, Int64, OperationLease)?
    @ObservationIgnored var campusTask: Task<Void, Never>?
    @ObservationIgnored var campusTransport: NetworkTransport?
    @ObservationIgnored var nameTask: Task<Void, Never>?
    @ObservationIgnored var nameTransport: NetworkTransport?
    @ObservationIgnored var importTask: Task<Void, Never>?
    @ObservationIgnored var importSession: ScheduleSession?
    @ObservationIgnored var billingTask: Task<Void, Never>?
    @ObservationIgnored var billingSession: BillingSession?
    @ObservationIgnored var academicTask: Task<Void, Never>?
    @ObservationIgnored var academicSession: AcademicSession?
    @ObservationIgnored var automaticCampusUsed = false
    @ObservationIgnored var automaticBillingUsed = false
    @ObservationIgnored var autoSuppressed = false
    @ObservationIgnored var undoTimer: Task<Void, Never>?

    var identityTitle: String {
        accounts.defaultAccount?.title ?? schedule.identity.map { !$0.name.isEmpty ? $0.name : $0.studentID } ?? "工小助"
    }
    var display: DisplaySchedule? { schedule.display() }
    var visibleWeek: ScheduleWeek? { display?.weeks.first { $0.number == selectedWeek } }
    var hasModal: Bool { accountEdit != nil || courseEdit != nil || examEdit != nil || schedulePage != nil || billingPage != nil || academicTarget != nil }
    func open(_ page: AppPage, accountsFocus: Bool = false) { campusAccountsFocus = accountsFocus; paths[tab, default: []].append(page) }
    func back() { if !(paths[tab] ?? []).isEmpty { paths[tab]?.removeLast() } }
    func notify(_ error: Error) { message = safeMessage(error) }
    func start() async {
        guard !loaded else { return }; loaded = true
        WorkLifetime.shared.register()
        do { accounts = try await accountStore.load(); accountError = nil } catch { accountError = safeMessage(error) }
        do { schedule = try await scheduleStore.load(); scheduleError = nil; selectToday() } catch { scheduleError = safeMessage(error) }
        do { academic = try await academicStore.load(); academicError = nil } catch { academicError = safeMessage(error) }
        await loadCredential(); await restoreBilling()
        network.onChange = { [weak self] in self?.networkChanged() }; network.start()
    }
    func selectToday() { selectedWeek = display?.currentWeek() ?? min(max(selectedWeek, 1), display?.weeks.count ?? 1) }
    func setWholeWeek(_ value: Bool) { wholeWeek = value; UserDefaults.standard.set(value, forKey: "schedule.wholeWeek") }
    func foreground() {
        guard loaded else { return }; active = true
        automaticCampusUsed = campusRunning; automaticBillingUsed = false; autoSuppressed = false
        selectToday(); network.start(); runAutomatic()
    }
    func background() {
        active = false; invalidateUndo(); cancelName(); cancelBilling(); cancelAcademic(); updates.background()
        if !scheduleSubmitting && schedulePage != nil { pauseImport() }
        stopMonitoringIfIdle()
    }
    func stopMonitoringIfIdle() { if !active && !campusRunning && !scheduleBusy && !updates.downloading { network.stop() } }
    func networkChanged() {
        cancelCampus(); cancelName(); pauseImport(); cancelBilling(); cancelAcademic(); updates.networkChanged()
        if active { runAutomatic() }
    }
    func runAutomatic() {
        guard active, network.ready else { return }
        if !automaticCampusUsed && !autoSuppressed && accounts.settings.autoLogin && accountError == nil && network.wifiAvailable { automaticCampusUsed = true; loginCampus() }
        if !automaticBillingUsed && tab == .billing && network.wifiAvailable && accounts.defaultAccount != nil && accountError == nil { automaticBillingUsed = true; refreshBilling() }
        updates.automaticCheck(network: network, active: active)
    }
    func offerUndo(_ receipt: UndoReceipt?) { undoTimer?.cancel(); undo = receipt; undoDeadline = nil }
    func activateUndo() async {
        guard let receipt = undo, !hasModal, active else { return }
        let seconds = UIAccessibility.isVoiceOverRunning ? 30.0 : 10.0
        let deadline: Date?
        if receipt.academic { deadline = await academicStore.activateUndo(receipt.id, seconds: seconds) }
        else { deadline = await scheduleStore.activateUndo(receipt.id, seconds: seconds) }
        guard undo?.id == receipt.id, let deadline else { return }; undoDeadline = deadline
        undoTimer?.cancel(); undoTimer = Task {
            try? await Task.sleep(for: .seconds(max(0, deadline.timeIntervalSinceNow)))
            guard !Task.isCancelled, undo?.id == receipt.id else { return }; invalidateUndo()
        }
    }
    func performUndo() async {
        guard let receipt = undo else { return }
        do { if receipt.academic { academic = try await academicStore.undoEdit(receipt.id) } else { schedule = try await scheduleStore.undoEdit(receipt.id) }; undo = nil; message = "已撤销" } catch { undo = nil; notify(error) }
    }
    func invalidateUndo() { let old = undo; undoTimer?.cancel(); undo = nil; undoDeadline = nil; if let old { Task { if old.academic { await academicStore.invalidateUndo(old.id) } else { await scheduleStore.invalidateUndo(old.id) } } } }
    func resetStorage(_ module: String) async {
        do {
            switch module {
            case "accounts": await waitAccountTasks(); accounts = try await accountStore.reset(); accountError = nil; billing = nil; await billingCache.clear()
            case "schedule": pauseImport(); await importTask?.value; schedule = try await scheduleStore.reset(); scheduleError = nil
            case "academic": cancelAcademic(); await academicTask?.value; try await credentialStore.reset(); academic = try await academicStore.reset(); academicError = nil; credentialError = false; credentialPresent = false
            default: return
            }
        } catch { notify(error) }
    }
}
