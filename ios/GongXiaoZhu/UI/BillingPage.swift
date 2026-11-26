import SwiftUI

struct BillingPage: View {
    @Environment(AppState.self) var app
    @State private var choosingDevice = false
    @State private var device: OnlineDevice?
    private var queryAllowed: Bool { app.network.ready && app.network.wifiAvailable && app.accounts.defaultAccount != nil && app.accountError == nil && !app.billingBusy && (app.billingCaptcha == nil || !app.billingCode.trimmed.isEmpty) }
    var body: some View {
        @Bindable var app = app
        ScrollView {
            VStack(alignment: .leading, spacing: 18) {
                Text("资费").font(.system(.largeTitle, design: .rounded, weight: .bold)).padding(.top, 15)
                HStack(alignment: .top, spacing: 12) {
                    Menu { ForEach(app.accounts.accounts) { account in Button(account.title) { Task { await app.setDefault(account.id) } } } } label: { HStack(spacing: 4) { Text(app.accounts.defaultAccount?.title ?? "未设置默认账号").lineLimit(2); Image(systemName: "chevron.down").font(.caption2) }.font(.subheadline).foregroundStyle(.primary) }.frame(maxWidth: .infinity, alignment: .leading)
                    ZStack(alignment: .topTrailing) {
                        HStack(alignment: .top, spacing: 4) { Color.clear.frame(width: 20, height: 20); ZStack(alignment: .topLeading) { ForEach(["刷新未确认，显示网页数据", "登录未确认，请重查或使用内置网页登录", "注销未确认，请刷新核对", "账号不符，请重新登录", "验证码未通过，请重填"], id: \.self) { Text($0).font(.subheadline).fixedSize(horizontal: false, vertical: true) } } }.frame(maxWidth: 180, alignment: .trailing).hidden().accessibilityHidden(true)
                        HStack(alignment: .top, spacing: 4) { if app.billingBusy { BrandLoader(size: 20) }; Text(app.billingStatus).font(.subheadline).fixedSize(horizontal: false, vertical: true).multilineTextAlignment(.leading) }.frame(maxWidth: 180, alignment: .trailing)
                    }
                }.frame(minHeight: 48, alignment: .top)
                if let error = app.accountError { StorageNotice(module: "accounts", text: error) }
                LazyVGrid(columns: [GridItem(.flexible(), alignment: .top), GridItem(.flexible(), alignment: .top)], spacing: 12) {
                    ForEach(["可用流量", "已用流量", "消费保护", "账户余额"], id: \.self) { key in
                        let metric = app.billing?.metric(key) ?? BillingMetric(key: key, value: "未获取")
                        AppCard { VStack(alignment: .leading, spacing: 12) { Text(key).font(.subheadline).foregroundStyle(.secondary); ZStack(alignment: .leading) { Text("未获取").font(.title2.bold()).hidden().accessibilityHidden(true); Text(metric.value).font(.system(.title2, design: .rounded, weight: .bold)).lineLimit(2).minimumScaleFactor(0.65) }.frame(minHeight: 44, alignment: .leading); Text(metric.unit.isEmpty ? " " : metric.unit).font(.caption).foregroundStyle(.secondary) } }
                    }
                }
                if let data = app.billingCaptcha, let image = UIImage(data: data) { AppCard { HStack { TextField("验证码", text: $app.billingCode).textInputAutocapitalization(.never).autocorrectionDisabled().submitLabel(.done).onSubmit { if queryAllowed { app.refreshBilling() } }; Image(uiImage: image).resizable().scaledToFit().frame(width: 110, height: 46) } } }
                HStack { Button(app.billing == nil ? "登录查询" : "刷新资费") { app.refreshBilling() }.buttonStyle(.borderedProminent).disabled(!queryAllowed); Button("管理账号") { app.open(.campus, accountsFocus: true) }.buttonStyle(.bordered); Spacer(minLength: 0) }
                if !app.billingStatus.isEmpty && app.billingCanOffline == false && app.accounts.defaultAccount != nil {
                    Button("使用内置网页登录") { app.refreshBilling(web: true) }.disabled(!app.network.wifiAvailable || app.billingBusy || app.accountError != nil).font(.subheadline)
                }
                if let snapshot = app.billing {
                    Text((app.billingCached ? "历史缓存 · " : "最近成功查询 · ") + ShanghaiClock.format(snapshot.queriedAt, "M 月 d 日 HH:mm")).font(.caption).foregroundStyle(.secondary)
                    HStack { Text("在线设备").font(.headline); Spacer(); Button("注销设备") { if snapshot.devices.count == 1 { device = snapshot.devices[0] } else { choosingDevice = true } }.buttonStyle(.bordered).disabled(!app.billingCanOffline || snapshot.devices.isEmpty || app.billingBusy || !app.network.wifiAvailable) }
                    if let error = snapshot.deviceError { Text(error).font(.subheadline).foregroundStyle(.secondary) }
                    else if snapshot.devices.isEmpty { Text("无在线设备").font(.subheadline).foregroundStyle(.secondary) }
                    ForEach(snapshot.devices) { device in AppCard { VStack(alignment: .leading, spacing: 8) { Text(device.address.isEmpty ? "在线设备" : device.address).font(.headline); if !device.mac.isEmpty { Text(device.mac).font(.caption).foregroundStyle(.secondary) }; Text("登录时间：" + (device.loginTime.isEmpty ? "未获取" : device.loginTime)).font(.subheadline); Text("在线时长：" + (device.duration.isEmpty ? "未获取" : device.duration)).font(.subheadline); Text("使用流量：" + device.traffic).font(.subheadline) } } }
                }
            }.padding(.horizontal, 22).padding(.bottom, 20).frame(maxWidth: 760)
        }.navigationBarHidden(true)
            .onChange(of: choosingDevice || device != nil) { _, value in app.modal("device-offline", value) }
            .onDisappear { app.modal("device-offline", false) }
            .onAppear { app.runAutomatic() }
            .confirmationDialog("选择要注销的设备", isPresented: $choosingDevice, titleVisibility: .visible) { ForEach(app.billing?.devices ?? []) { item in Button(item.address.isEmpty ? item.mac : item.address) { device = item } } }
            .confirmationDialog("确认注销这台在线设备？", isPresented: Binding(get: { device != nil }, set: { if !$0 { device = nil } }), titleVisibility: .visible, presenting: device) { selected in Button("确认注销设备", role: .destructive) { Task { await app.offlineDevice(selected) }; device = nil } } message: { _ in Text("这会使选中的设备下线。注销后不会自动重新认证。") }
    }
}
