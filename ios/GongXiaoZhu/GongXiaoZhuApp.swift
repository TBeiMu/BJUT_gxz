import SwiftUI

@main struct GongXiaoZhuApp: App {
    @State private var state = AppState()
    var body: some Scene { WindowGroup { RootView().environment(state).tint(state.appearance.accent).preferredColorScheme(state.appearance.colorScheme) } }
}

struct RootView: View {
    @Environment(AppState.self) var app
    @Environment(\.scenePhase) var scenePhase
    @Environment(\.accessibilityReduceMotion) var reduceMotion
    @State private var splash = true
    var body: some View {
        @Bindable var app = app
        ZStack {
            AppBackground()
            ZStack {
                ForEach(AppTab.allCases, id: \.self) { tab in
                    NavigationStack(path: Binding(get: { app.paths[tab] ?? [] }, set: { app.paths[tab] = $0 })) {
                        Group {
                            switch tab { case .schedule: ScheduleHome(); case .billing: BillingPage(); case .mine: MinePage() }
                        }.frame(maxWidth: .infinity, maxHeight: .infinity).background { AppBackground() }.navigationDestination(for: AppPage.self) { page in
                            Group {
                                switch page {
                                case .campus: CampusPage()
                                case .schedule: ScheduleManagementPage()
                                case .shuttle: ShuttlePage()
                                case .appearance: AppearancePage()
                                case .exams: ExamsPage()
                                case .grades: GradesPage()
                                case .update: UpdatePage()
                                case .about: AboutPage()
                                }
                            }.frame(maxWidth: .infinity, maxHeight: .infinity).background { AppBackground() }.toolbar(.visible, for: .navigationBar).toolbarBackground(.hidden, for: .navigationBar)
                        }.toolbarBackground(.hidden, for: .navigationBar)
                    }.background(.clear).opacity(app.tab == tab ? 1 : 0).allowsHitTesting(app.tab == tab).accessibilityHidden(app.tab != tab)
                }
            }
            if splash {
                Color(hex: 0xF7F8FA).ignoresSafeArea()
                VStack(spacing: 22) { BrandLoader(size: 100, motion: .week); Text("工小助").font(.system(size: 28, weight: .semibold)); Text("让校园生活轻松一点").font(.subheadline).foregroundStyle(.secondary) }.foregroundStyle(Color(hex: 0x1B1D22))
            }
            if scenePhase != .active { Color(hex: 0xF7F8FA).ignoresSafeArea(); Image("LaunchMark").resizable().scaledToFit().frame(width: 90, height: 90) }
        }
        .background { PrivacyCurtain().frame(width: 0, height: 0) }
        .safeAreaInset(edge: .bottom, spacing: 0) {
            VStack(spacing: 8) {
                if let receipt = app.undo, !app.hasModal {
                    HStack { Text(receipt.message).font(.subheadline); Spacer(); Button("撤销") { Task { await app.performUndo() } }.fontWeight(.semibold) }.padding(14).background(.regularMaterial, in: RoundedRectangle(cornerRadius: 14)).padding(.horizontal, 20).task(id: receipt.id) { await app.activateUndo() }
                }
                HStack(spacing: 0) {
                    ForEach(AppTab.allCases, id: \.self) { tab in
                        Button { app.tab = tab; app.runAutomatic() } label: {
                            VStack(spacing: 4) { Image(systemName: tab.symbol).font(.system(size: 20, weight: app.tab == tab ? .semibold : .regular)); Text(tab.title).font(.caption); Circle().fill(app.tab == tab ? Color(hex: 0xE5482E) : .clear).frame(width: 4, height: 4) }
                                .foregroundStyle(app.tab == tab ? Color(hex: 0x1B1D22) : .gray).frame(maxWidth: .infinity).frame(minHeight: 58).contentShape(Rectangle())
                        }.accessibilityAddTraits(app.tab == tab ? .isSelected : [])
                    }
                }.background(.white, in: Capsule()).overlay(Capsule().strokeBorder(.black.opacity(0.06), lineWidth: 0.5)).padding(.horizontal, 32).padding(.bottom, 5)
            }.opacity(splash ? 0 : 1)
        }
        .task { await app.start(); try? await Task.sleep(for: .milliseconds(reduceMotion ? 200 : 1600)); withAnimation(.easeOut(duration: 0.25)) { splash = false } }
        .onChange(of: scenePhase) { _, phase in if phase == .active { app.foreground() } else if phase == .background { app.background() } }
        .onChange(of: app.hasModal) { _, presented in if !presented { Task { await app.activateUndo() } } }
        .sheet(item: $app.accountEdit) { edit in AccountEditor(edit: edit).environment(app) }
        .sheet(item: $app.courseEdit) { context in CourseEditor(context: context).environment(app) }
        .sheet(item: $app.examEdit) { edit in ExamEditor(edit: edit).environment(app) }
        .sheet(item: $app.academicTarget, onDismiss: { if !app.academicCommitting { app.cancelAcademic() } }) { target in AcademicLoginSheet(target: target).environment(app) }
        .sheet(item: $app.schedulePage, onDismiss: { if !app.scheduleSubmitting { app.pauseImport() } }) { page in SchoolPageSheet(page: page).environment(app) }
        .sheet(item: $app.billingPage, onDismiss: { if app.billingPrefill != nil { Task { await app.stopBilling() } } }) { page in SchoolPageSheet(page: page).environment(app) }
        .alert("工小助", isPresented: Binding(get: { app.message != nil }, set: { if !$0 { app.message = nil } })) { Button("知道了") { app.message = nil } } message: { Text(app.message ?? "") }
        .alert("发现 iOS 新版本", isPresented: Binding(get: { app.updates.offered != nil && !app.hasModal }, set: { if !$0 { app.updates.offered = nil } })) {
            Button("取消更新", role: .cancel) { app.updates.offered = nil }
            Button("忽略本次更新") { if let next = app.updates.offered { app.updates.ignore(next) } }
            Button("立即更新") { app.updates.offered = nil; app.open(.update) }
        } message: { Text(app.updates.offered.map { "\($0.versionName)\n\($0.releaseNotes)\n未签名，签名与安装自行处理" } ?? "") }
    }
}

struct EmptyCard: View {
    let title: String
    var detail = ""
    var symbol = "tray"
    var body: some View { AppCard { VStack(alignment: .leading, spacing: 12) { Image(systemName: symbol).font(.title).foregroundStyle(.secondary); Text(title).font(.headline); if !detail.isEmpty { Text(detail).font(.subheadline).foregroundStyle(.secondary) } }.padding(.vertical, 16) } }
}
struct StorageNotice: View {
    @Environment(AppState.self) var app
    let module: String
    let text: String
    @State private var confirm = false
    var body: some View {
        AppCard { VStack(alignment: .leading, spacing: 12) { Text(text).font(.subheadline); Button("重新读取") { Task { do { switch module { case "accounts": app.accounts = try await app.accountStore.load(); app.accountError = nil; case "schedule": app.schedule = try await app.scheduleStore.load(); app.scheduleError = nil; default: app.academic = try await app.academicStore.load(); app.academicError = nil } } catch { app.notify(error) } } }; Button("清除本模块资料并重新开始", role: .destructive) { confirm = true } } }
            .confirmationDialog("清除后无法恢复本模块资料，确定继续？", isPresented: $confirm, titleVisibility: .visible) { Button("清除本模块资料", role: .destructive) { Task { await app.resetStorage(module) } } }
    }
}
