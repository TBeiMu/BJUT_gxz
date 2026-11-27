import SwiftUI
import UIKit

struct EntryRow: View {
    let title: String
    let icon: String
    var busy = false
    let action: () -> Void
    var body: some View {
        Button(action: action) { HStack(spacing: 14) { Image(systemName: icon).font(.system(size: 20)).frame(width: 26); Text(title).font(.body); Spacer(minLength: 8); ZStack { Image(systemName: "chevron.right").opacity(busy ? 0 : 1); if busy { BrandLoader(size: 20) } }.frame(width: 22, height: 22) }.foregroundStyle(.primary).padding(.vertical, 12).contentShape(Rectangle()) }.buttonStyle(.plain)
    }
}
struct MinePage: View {
    @Environment(AppState.self) var app
    @Environment(\.openURL) var openURL
    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 22) {
                VStack(alignment: .leading, spacing: 12) { Text(app.identityTitle).font(.system(.largeTitle, design: .rounded, weight: .bold)).textSelection(.enabled); TimelineView(.periodic(from: .now, by: 60)) { context in Text(ShanghaiClock.greeting(context.date)).font(.subheadline).foregroundStyle(.secondary).fixedSize(horizontal: false, vertical: true) } }.padding(.vertical, 18)
                HStack(alignment: .top, spacing: 12) { quick("考试", icon: "doc.text", page: .exams); quick("成绩", icon: "chart.bar.doc.horizontal", page: .grades) }
                AppCard { VStack(spacing: 0) { EntryRow(title: "校车时刻表", icon: "bus") { app.open(.shuttle) }; Divider(); EntryRow(title: "课表管理", icon: "calendar") { app.open(.schedule) }; Divider(); EntryRow(title: "校园网", icon: "wifi") { app.open(.campus) } } }
                AppCard { VStack(spacing: 0) { EntryRow(title: "个性化", icon: "paintpalette") { app.open(.appearance) }; Divider(); EntryRow(title: "检查更新", icon: "arrow.down.circle", busy: app.updates.checking) { app.updates.check(network: app.network) }; Divider(); EntryRow(title: "关于工小助", icon: "info.circle") { app.open(.about) } } }
                if !app.updates.status.isEmpty { Text(app.updates.status).font(.footnote).foregroundStyle(.secondary) }
                if app.updates.downloading || app.updates.exportURL != nil { Button("查看 iOS 下载") { app.open(.update) } }
            }.padding(.horizontal, 22).padding(.top, 10).frame(maxWidth: 760)
        }.navigationBarHidden(true)
            .safeAreaInset(edge: .bottom) { Button { openURL(AppInfo.repository) { accepted in if !accepted { app.message = "暂时无法打开项目主页" } } } label: { HStack(spacing: 7) { Image("GitHub").renderingMode(.template).resizable().scaledToFit().frame(width: 18, height: 18); Text("github").font(.subheadline) }.foregroundStyle(.secondary).frame(maxWidth: .infinity, minHeight: 48) }.buttonStyle(.plain) }
    }
    private func quick(_ title: String, icon: String, page: AppPage) -> some View { Button { app.open(page) } label: { AppCard { HStack(spacing: 10) { Image(systemName: icon).font(.title2); Text(title).font(.headline); Spacer(minLength: 0) }.frame(minHeight: 48) }.foregroundStyle(.primary) }.buttonStyle(.plain) }
}

struct CampusPage: View {
    @Environment(AppState.self) var app
    @State private var deleteAccount: CampusAccount?
    var body: some View {
        ScrollViewReader { proxy in
            ScrollView {
                LazyVStack(spacing: 18) {
                    if let error = app.accountError { StorageNotice(module: "accounts", text: error) }
                    AppCard { VStack(alignment: .leading, spacing: 14) { HStack { Image(systemName: app.network.wifiAvailable ? "wifi" : "wifi.slash").font(.title2); Text(app.network.wifiAvailable ? "已连接 WiFi" : "未连接 WiFi").font(.headline); Spacer(); if app.campusRunning { BrandLoader(size: 25, motion: .signal) } }; Text(app.campusMessage).font(.subheadline).fixedSize(horizontal: false, vertical: true); if app.campusRunning { Button("取消认证") { app.cancelCampus() }.buttonStyle(.bordered) } else { Button("登录校园网") { app.loginCampus() }.buttonStyle(.borderedProminent).disabled(!app.network.wifiAvailable || app.accountError != nil) }; if app.campusNeedsChoice { Text("请在连接设置中选择公共 WiFi 或宿舍 WiFi").font(.caption).foregroundStyle(.secondary) } } }.id("connection")
                    AppCard { VStack(alignment: .leading, spacing: 12) { HStack { Text("账号管理").font(.headline); Spacer(); Button { Task { await app.beginAccountEdit() } } label: { Image(systemName: "plus").frame(width: 44, height: 44) }.accessibilityLabel("添加校园网账号").disabled(app.accountError != nil) }; if app.accounts.accounts.isEmpty { Text("添加账号后，首个账号自动设为默认").font(.subheadline).foregroundStyle(.secondary) }; ForEach(app.accounts.accounts) { account in HStack { VStack(alignment: .leading, spacing: 5) { Text(account.title).font(.headline); if account.title != account.username { Text(account.username).font(.caption).foregroundStyle(.secondary) }; if app.accounts.defaultAccountID == account.id { Text("默认账号").font(.caption).foregroundStyle(app.appearance.accent) } }; Spacer(); Menu { if app.accounts.defaultAccountID != account.id { Button("设为默认") { Task { await app.setDefault(account.id) } } }; Button("编辑") { Task { await app.beginAccountEdit(account.id) } }; Button("删除", role: .destructive) { deleteAccount = account } } label: { Image(systemName: "ellipsis").frame(width: 44, height: 44) }.accessibilityLabel("账号操作") }; if account.id != app.accounts.accounts.last?.id { Divider() } } } }.id("accounts")
                    AppCard { DisclosureGroup(isExpanded: Binding(get: { app.settingsExpanded }, set: { app.settingsExpanded = $0 })) { VStack(spacing: 14) { Toggle("打开 App 自动登录", isOn: Binding(get: { app.accounts.settings.autoLogin }, set: { value in var next = app.accounts.settings; next.autoLogin = value; Task { await app.saveSettings(next) } })); Picker("网络模式", selection: Binding(get: { app.accounts.settings.networkMode }, set: { value in var next = app.accounts.settings; next.networkMode = value; Task { await app.saveSettings(next) } })) { ForEach(NetworkMode.allCases) { mode in Text(mode.title).tag(mode) } }.pickerStyle(.menu) }.padding(.top, 14).disabled(app.accountError != nil || app.settingsSaving) } label: { Label("连接设置", systemImage: "slider.horizontal.3").foregroundStyle(.primary) } }
                }.padding(20).frame(maxWidth: 760)
            }.task { if app.campusAccountsFocus { proxy.scrollTo("accounts", anchor: .top); app.campusAccountsFocus = false } }
        }.navigationTitle("校园网")
            .onChange(of: deleteAccount != nil) { _, value in app.modal("account-delete", value) }.onDisappear { app.modal("account-delete", false) }
            .confirmationDialog("删除校园网账号？", isPresented: Binding(get: { deleteAccount != nil }, set: { if !$0 { deleteAccount = nil } }), titleVisibility: .visible, presenting: deleteAccount) { account in Button("删除", role: .destructive) { Task { await app.deleteAccount(account.id) }; deleteAccount = nil } }
    }
}

struct ShuttlePage: View {
    @Environment(AppState.self) private var app
    @Environment(\.scenePhase) private var scenePhase
    @State private var moment = Date()
    @State private var selected: ShuttleDayType?
    @State private var selectionDate = ShanghaiClock.day()
    @State private var clockRevision = 0

    private var viewedType: ShuttleDayType {
        selectionDate == ShanghaiClock.day(moment) ? (selected ?? .at(moment)) : .at(moment)
    }

    var body: some View {
        let type = viewedType
        let routes = ShuttleRoute.routes(for: type)
        let isToday = type == ShuttleDayType.at(moment)
        let next = isToday ? ShuttleRoute.next(moment) : nil
        ScrollViewReader { proxy in
            ScrollView {
                VStack(alignment: .leading, spacing: 14) {
                    Text(ShuttleRoute.status(moment, viewing: type))
                        .font(.subheadline).foregroundStyle(next == nil ? Color.secondary : app.appearance.accent)
                        .fixedSize(horizontal: false, vertical: true).padding(.horizontal, 4).id("shuttle-top")
                        .accessibilityIdentifier("shuttle_status")
                    HStack(alignment: .top, spacing: 12) {
                        ForEach(routes) { route in
                            VStack(spacing: 2) {
                                Text(route.period).font(.caption).foregroundStyle(.secondary)
                                Text(route.origin).font(.subheadline.weight(.semibold))
                            }.frame(maxWidth: .infinity).multilineTextAlignment(.center)
                        }
                    }
                    VStack(spacing: 6) {
                        ForEach(0..<(routes.map { $0.departures.count }.max() ?? 0), id: \.self) { index in
                            HStack(alignment: .top, spacing: 12) {
                                ForEach(routes) { route in
                                    if index < route.departures.count {
                                        let time = route.departures[index]
                                        ShuttleTimeCell(time: time, last: index == route.departures.count - 1, striped: index % 2 != 0,
                                            highlighted: next?.route.id == route.id && next?.time == time,
                                            past: isToday && time < ShanghaiClock.format(moment, "HH:mm"))
                                            .frame(maxWidth: .infinity, maxHeight: .infinity)
                                            .accessibilityIdentifier("shuttle_departure_\(route.id)_\(index)")
                                    } else { Color.clear.frame(maxWidth: .infinity).accessibilityHidden(true) }
                                }
                            }.fixedSize(horizontal: false, vertical: true)
                        }
                    }
                    ForEach(routes) { route in
                        AppCard {
                            VStack(alignment: .leading, spacing: 6) {
                                Text(route.period + "路线").font(.subheadline.weight(.semibold))
                                Text(route.stops.joined(separator: " → "))
                                    .font(.subheadline).foregroundStyle(.secondary)
                                    .fixedSize(horizontal: false, vertical: true)
                            }
                        }
                    }
                }.padding(.horizontal, 16).padding(.top, 8).padding(.bottom, 24).frame(maxWidth: 760)
            }
            .onChange(of: type) { _, _ in proxy.scrollTo("shuttle-top", anchor: .top) }
        }
        .navigationTitle(type.title).navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .principal) {
                Text(type.title).font(.headline).multilineTextAlignment(.center)
                    .fixedSize(horizontal: false, vertical: true).accessibilityIdentifier("shuttle_title")
            }
            ToolbarItem(placement: .topBarTrailing) {
                Button { selectionDate = ShanghaiClock.day(moment); selected = type.other } label: {
                    Image(systemName: "arrow.left.arrow.right").font(.system(size: 20, weight: .regular))
                        .frame(minWidth: 44, minHeight: 44)
                }
                .tint(.primary).accessibilityLabel("切换为\(type.other.suffix)时刻表")
                .accessibilityValue("当前\(type.suffix)时刻表").accessibilityIdentifier("shuttle_switch")
            }
        }
        .task(id: scenePhase == .active ? clockRevision : -1) {
            guard scenePhase == .active else { return }
            while !Task.isCancelled {
                refreshMoment()
                let interval = 60 - moment.timeIntervalSince1970.truncatingRemainder(dividingBy: 60)
                do { try await Task.sleep(for: .seconds(max(0.05, interval))) } catch { break }
            }
        }
        .onReceive(NotificationCenter.default.publisher(for: UIApplication.significantTimeChangeNotification)) { _ in
            guard scenePhase == .active else { return }; refreshMoment(); clockRevision += 1
        }
        .onReceive(NotificationCenter.default.publisher(for: NSNotification.Name.NSSystemTimeZoneDidChange)) { _ in
            guard scenePhase == .active else { return }; refreshMoment(); clockRevision += 1
        }
    }

    private func refreshMoment() {
        let now = Date()
        if selectionDate != ShanghaiClock.day(now) { selected = nil; selectionDate = ShanghaiClock.day(now) }
        moment = now
    }
}

private struct ShuttleTimeCell: View {
    @Environment(AppState.self) private var app
    @Environment(\.colorScheme) private var scheme
    @ScaledMetric(relativeTo: .body) private var timeSize = 18.0
    let time: String
    let last: Bool
    let striped: Bool
    let highlighted: Bool
    let past: Bool

    private var foreground: Color { highlighted ? .white : (past ? .secondary : .primary) }
    private var timeText: some View {
        ZStack {
            Text("00:00").hidden()
            Text(ShuttleRoute.timeLabel(time))
        }.font(.system(size: timeSize, weight: .medium, design: .rounded)).monospacedDigit()
            .fixedSize(horizontal: true, vertical: true)
    }
    private var badge: some View { Text("末车").font(.caption2).fixedSize() }

    private var fill: Color {
        if highlighted { return app.appearance.accent }
        if striped { return scheme == .dark ? Color.white.opacity(0.07) : Color.black.opacity(0.045) }
        let base: Color = scheme == .dark ? Color(hex: 0x20232B) : .white
        return base.opacity(1 - app.appearance.preferences.transparency / 100)
    }
    private var border: Color {
        if highlighted { return app.appearance.accent }
        return scheme == .dark ? Color.white.opacity(0.14) : Color.black.opacity(0.08)
    }
    private var fittedTime: some View {
        ViewThatFits(in: .horizontal) {
            ZStack {
                HStack(spacing: 6) { badge.hidden(); timeText; badge.hidden() }
                if last { HStack { Spacer(minLength: 0); badge } }
            }
            VStack(spacing: 4) { timeText; badge.opacity(last ? 1 : 0).accessibilityHidden(!last) }
        }
    }

    var body: some View {
        fittedTime
        .foregroundStyle(foreground).padding(.horizontal, 10).padding(.vertical, 8)
        .frame(maxWidth: .infinity, minHeight: 40, maxHeight: .infinity)
        .background(fill, in: RoundedRectangle(cornerRadius: 10))
        .overlay(RoundedRectangle(cornerRadius: 10).strokeBorder(border, lineWidth: 0.6))
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(ShuttleRoute.timeLabel(time) + (last ? "，末车" : ""))
        .accessibilityValue(highlighted ? "下一班" : (past ? "已过计划发车时间" : "计划发车时间"))
    }
}

struct AboutPage: View {
    @Environment(\.openURL) var openURL
    var body: some View {
        ScrollView { VStack(spacing: 22) { Image("Bird").resizable().scaledToFit().frame(width: 110, height: 110); Text("工小助").font(.largeTitle.bold()); Text("iOS \(AppInfo.version)（\(AppInfo.build)）").foregroundStyle(.secondary); AppCard { VStack(alignment: .leading, spacing: 12) { Text("未签名、未执行功能测试，签名与安装自行处理。").font(.headline); Text("面向北京工业大学校园生活。本机资料使用独立 Keychain 密钥与 AES-GCM 加密，不直接读取 Android 加密资料。iOS 后台执行由系统决定，任务到期暂停，手动继续。iOS 无法阻止所有系统截图，也不会自动读取桌面壁纸。").font(.subheadline).foregroundStyle(.secondary); Button("GitHub 项目主页") { openURL(AppInfo.repository) } } }; AppCard { VStack(alignment: .leading, spacing: 12) { Text("开源许可").font(.headline); Text((try? String(contentsOf: Bundle.main.url(forResource: "Licenses", withExtension: "txt")!, encoding: .utf8)) ?? "SwiftSoup · SwiftNIO · SwiftNIO Transport Services · Octicons").font(.caption).textSelection(.enabled) } } }.padding(22).frame(maxWidth: 760) }.navigationTitle("关于工小助")
    }
}

struct UpdatePage: View {
    @Environment(AppState.self) var app
    var body: some View {
        @Bindable var updates = app.updates
        ScrollView { VStack(spacing: 18) {
            AppCard { VStack(alignment: .leading, spacing: 14) { Text("当前版本 \(AppInfo.version)（\(AppInfo.build)）").font(.headline); Text(updates.status.isEmpty ? "仅检查 iOS 未签名版本" : updates.status).font(.subheadline); Button("检查 iOS 更新") { updates.check(network: app.network) }.buttonStyle(.borderedProminent).disabled(updates.checking || updates.downloading); if updates.checking { ProgressView() } } }
            if let update = updates.update {
                AppCard { VStack(alignment: .leading, spacing: 14) { Text("iOS \(update.versionName)").font(.title2.bold()); Text("需要 iOS \(update.minSystemVersion) · " + ByteCountFormatter.string(fromByteCount: update.ipaSize, countStyle: .file)).font(.subheadline).foregroundStyle(.secondary); Text(update.releaseNotes).font(.subheadline); Text("未签名，签名与安装自行处理。").font(.footnote).foregroundStyle(.secondary); if updates.progress > 0 || updates.downloading { ProgressView(value: Double(updates.progress), total: Double(update.ipaSize)); Text(ByteCountFormatter.string(fromByteCount: updates.progress, countStyle: .file)).font(.caption) }; if updates.downloading { Button("暂停下载") { updates.pause() }.buttonStyle(.bordered) } else if let url = updates.exportURL { ShareLink(item: url) { Label("分享／导出 IPA", systemImage: "square.and.arrow.up") }.buttonStyle(.borderedProminent) } else { Button(updates.progress > 0 ? "继续下载" : "下载未签名 IPA") { updates.requestDownload(network: app.network) }.buttonStyle(.borderedProminent).disabled(!app.network.internetAvailable || updates.checking) } } }
            }
        }.padding(20).frame(maxWidth: 760) }.navigationTitle("iOS 应用更新")
            .alert("使用移动数据下载？", isPresented: $updates.cellularConfirmation) { Button("取消", role: .cancel) { updates.pendingRoute = nil; updates.pendingUpdate = nil }; Button("同意本次下载") { updates.consent(network: app.network) } } message: { Text(updates.pendingUpdate.map { "版本 \($0.versionName)，安装包 " + ByteCountFormatter.string(fromByteCount: $0.ipaSize, countStyle: .file) + "。本次同意只对当前网络和版本有效。" } ?? "") }
    }
}
