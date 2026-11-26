import SwiftUI
import Observation
import PhotosUI
import CoreImage
import CoreImage.CIFilterBuiltins

struct AppearancePreferences: Codable, Equatable {
    var theme = "system"
    var accent = 0
    var preset = 0
    var mode = "preset"
    var blur = 12.0
    var transparency = 4.0
    var photoColor: [Double] = [0.6, 0.8, 0.9]
    var valid: Bool { ["system", "light", "dark"].contains(theme) && (0..<4).contains(accent) && (0..<6).contains(preset) && ["preset", "photo", "color"].contains(mode) && (0...24).contains(blur) && (2...100).contains(transparency) && photoColor.count == 3 && photoColor.allSatisfy { (0...1).contains($0) } }
}
@MainActor @Observable final class AppearanceStore {
    var preferences = AppearancePreferences() { didSet { if preferences.valid, let data = try? JSONEncoder().encode(preferences) { UserDefaults.standard.set(data, forKey: "appearance.preferences") } } }
    var photo: UIImage?
    var error: String?
    static var photoURL: URL { VaultFile.directory.appendingPathComponent("wallpaper.jpg") }
    static let accents: [Color] = [Color(hex: 0xE5482E), Color(hex: 0x287CC2), Color(hex: 0x26836C), Color(hex: 0x7B5BB2)]
    static let titles = ["蓝青", "薄荷", "薰衣草", "樱粉", "暖杏", "灰白"]
    static let gradients: [[Color]] = [[Color(hex: 0xDFEBF8), Color(hex: 0xD8F0EC)], [Color(hex: 0xE2F4E9), Color(hex: 0xCDE8DF)], [Color(hex: 0xEBE4F7), Color(hex: 0xDFE6F7)], [Color(hex: 0xF9E4EB), Color(hex: 0xF5E1DD)], [Color(hex: 0xF7E5D5), Color(hex: 0xF4EDDA)], [Color(hex: 0xF0F1F3), Color(hex: 0xE2E5E9)]]
    init() {
        if let bytes = UserDefaults.standard.data(forKey: "appearance.preferences"), let value = try? JSONDecoder().decode(AppearancePreferences.self, from: bytes), value.valid { preferences = value }
        if let data = try? Data(contentsOf: Self.photoURL), data.count <= 20 * 1024 * 1024 { photo = UIImage(data: data) }
        if photo == nil && preferences.mode != "preset" { preferences.mode = "preset" }
    }
    var accent: Color { Self.accents[preferences.accent] }
    var colorScheme: ColorScheme? { preferences.theme == "system" ? nil : preferences.theme == "dark" ? .dark : .light }
    var colors: [Color] {
        if preferences.mode == "color" { let v = preferences.photoColor; return [Color(red: v[0], green: v[1], blue: v[2]).opacity(0.65), Color(red: v[0], green: v[1], blue: v[2]).opacity(0.22)] }
        return Self.gradients[preferences.preset]
    }
    func importPhoto(_ data: Data) async {
        guard data.count <= 30 * 1024 * 1024, let original = UIImage(data: data), original.size.width > 0, original.size.height > 0, original.size.width * original.size.height <= 60_000_000 else { error = "图片无法读取或文件过大"; return }
        let ratio = min(1, 2400 / max(original.size.width, original.size.height))
        let size = CGSize(width: original.size.width * ratio, height: original.size.height * ratio)
        let image = UIGraphicsImageRenderer(size: size).image { _ in original.draw(in: CGRect(origin: .zero, size: size)) }
        guard let bytes = image.jpegData(compressionQuality: 0.88) else { error = "图片保存失败"; return }
        do {
            try VaultFile.atomicWrite(bytes, to: Self.photoURL); photo = image
            if let input = CIImage(image: image) {
                let filter = CIFilter.areaAverage(); filter.inputImage = input; filter.extent = input.extent
                if let output = filter.outputImage {
                    var pixel = [UInt8](repeating: 0, count: 4)
                    CIContext(options: [.workingColorSpace: NSNull()]).render(output, toBitmap: &pixel, rowBytes: 4, bounds: CGRect(x: 0, y: 0, width: 1, height: 1), format: .RGBA8, colorSpace: CGColorSpaceCreateDeviceRGB())
                    preferences.photoColor = pixel.prefix(3).map { Double($0) / 255 }
                }
            }
            preferences.mode = "photo"
        } catch { self.error = "图片未能保存，原背景已保留" }
    }
    func reset() { preferences = AppearancePreferences() }
}
extension Color {
    init(hex: UInt32) { self.init(red: Double((hex >> 16) & 255) / 255, green: Double((hex >> 8) & 255) / 255, blue: Double(hex & 255) / 255) }
}
struct AppBackground: View {
    @Environment(AppState.self) var app
    @Environment(\.colorScheme) var scheme
    var body: some View {
        GeometryReader { geometry in
            ZStack {
                Color(scheme == .dark ? .black : .white)
                if app.appearance.preferences.mode == "photo", let photo = app.appearance.photo {
                    Image(uiImage: photo).resizable().scaledToFill().frame(width: geometry.size.width, height: geometry.size.height).blur(radius: app.appearance.preferences.blur).clipped()
                } else { LinearGradient(colors: app.appearance.colors, startPoint: .topLeading, endPoint: .bottomTrailing) }
                if scheme == .dark { Color.black.opacity(0.68) } else { Color.white.opacity(0.35) }
            }
        }.ignoresSafeArea()
    }
}
struct AppCard<Content: View>: View {
    @Environment(AppState.self) var app
    @Environment(\.colorScheme) var scheme
    @ViewBuilder var content: Content
    var body: some View {
        content.padding(16).frame(maxWidth: .infinity, alignment: .leading)
            .background((scheme == .dark ? Color(hex: 0x20232B) : .white).opacity(1 - app.appearance.preferences.transparency / 100), in: RoundedRectangle(cornerRadius: 14))
            .overlay(RoundedRectangle(cornerRadius: 14).strokeBorder(scheme == .dark ? Color.white.opacity(0.14) : Color.black.opacity(0.08), lineWidth: 0.6))
    }
}
struct AppearancePage: View {
    @Environment(AppState.self) var app
    @State private var photoItem: PhotosPickerItem?
    @State private var photoPicker = false
    var body: some View {
        @Bindable var appearance = app.appearance
        ScrollView {
            VStack(spacing: 16) {
                AppCard {
                    VStack(alignment: .leading, spacing: 14) {
                        Label("效果预览", systemImage: "sparkles").font(.headline)
                        HStack { Image("Bird").resizable().scaledToFit().frame(width: 42, height: 42); VStack(alignment: .leading) { Text("工小助").font(.headline); Text("让校园生活轻松一点").font(.subheadline).foregroundStyle(.secondary) }; Spacer(); Image(systemName: "chevron.right") }
                        Text("文字和图标始终清晰显示").font(.caption).foregroundStyle(.secondary)
                    }
                }
                AppCard {
                    VStack(alignment: .leading, spacing: 16) {
                        Text("主题").font(.headline)
                        Picker("主题", selection: $appearance.preferences.theme) { Text("跟随系统").tag("system"); Text("浅色").tag("light"); Text("深色").tag("dark") }.pickerStyle(.segmented)
                        Text("强调色").font(.headline)
                        HStack { ForEach(0..<4) { index in Button { appearance.preferences.accent = index } label: { Circle().fill(AppearanceStore.accents[index]).frame(width: 38, height: 38).overlay { if appearance.preferences.accent == index { Image(systemName: "checkmark").foregroundStyle(.white) } } }.accessibilityLabel(["朱红", "晴空蓝", "松绿", "暮紫"][index]); Spacer() } }
                    }
                }
                AppCard {
                    VStack(alignment: .leading, spacing: 14) {
                        Text("颜色背景").font(.headline)
                        LazyVGrid(columns: Array(repeating: GridItem(.flexible()), count: 3)) {
                            ForEach(0..<6) { index in Button { appearance.preferences.preset = index; appearance.preferences.mode = "preset" } label: { Text(AppearanceStore.titles[index]).font(.subheadline).frame(maxWidth: .infinity).padding(.vertical, 20).background(LinearGradient(colors: AppearanceStore.gradients[index], startPoint: .topLeading, endPoint: .bottomTrailing), in: RoundedRectangle(cornerRadius: 12)).foregroundStyle(.black).overlay(RoundedRectangle(cornerRadius: 12).strokeBorder(appearance.preferences.mode == "preset" && appearance.preferences.preset == index ? appearance.accent : .clear, lineWidth: 2)) } }
                        }
                        Button { photoPicker = true } label: { Label("选择图片", systemImage: "photo") }.buttonStyle(.bordered)
                        if appearance.photo != nil {
                            Picker("图片显示", selection: $appearance.preferences.mode) { Text("自选图片").tag("photo"); Text("图片主色").tag("color"); Text("颜色背景").tag("preset") }.pickerStyle(.segmented)
                            Text("图片模糊 \(Int(appearance.preferences.blur))").font(.subheadline)
                            Slider(value: $appearance.preferences.blur, in: 0...24, step: 1)
                        }
                    }
                }
                AppCard {
                    VStack(alignment: .leading, spacing: 14) {
                        Text("卡片通透度 \(Int(appearance.preferences.transparency))%").font(.headline)
                        Slider(value: $appearance.preferences.transparency, in: 2...100, step: 2)
                        HStack { ForEach([4, 20, 100], id: \.self) { value in Button([4: "默认 4%", 20: "轻透 20%", 100: "全透 100%"][value]!) { appearance.preferences.transparency = Double(value) }.buttonStyle(.bordered) } }
                    }
                }
                AppCard { VStack(alignment: .leading, spacing: 12) { Text("推荐搭配").font(.headline); Button("薄荷 · 松绿 · 轻透") { appearance.preferences.preset = 1; appearance.preferences.accent = 2; appearance.preferences.mode = "preset"; appearance.preferences.transparency = 20 }; Button("恢复默认外观") { appearance.reset() } } }
            }.padding(20).frame(maxWidth: 760)
        }.navigationTitle("个性化")
            .photosPicker(isPresented: $photoPicker, selection: $photoItem, matching: .images)
            .onChange(of: photoPicker || appearance.error != nil) { _, value in app.modal("photo-picker", value) }.onDisappear { app.modal("photo-picker", false) }
            .onChange(of: photoItem) { _, item in Task { if let data = try? await item?.loadTransferable(type: Data.self) { await appearance.importPhoto(data) } } }
            .alert("图片未保存", isPresented: Binding(get: { appearance.error != nil }, set: { if !$0 { appearance.error = nil } })) { Button("知道了") { appearance.error = nil } } message: { Text(appearance.error ?? "") }
    }
}
