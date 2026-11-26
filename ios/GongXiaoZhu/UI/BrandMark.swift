import SwiftUI

enum MarkMotion { case week, signal, bounce }
private func span(_ time: Double, _ start: Double, _ length: Double) -> Double { min(1, max(0, (time - start) / length)) }
private func mix(_ a: Double, _ b: Double, _ fraction: Double) -> Double { a + (b - a) * fraction }
private func knock(_ time: Double, _ start: Double) -> Double { let t = time - start; if t < 0 { return 0 }; if t < 120 { return sin(.pi * t / 120) }; if t < 230 { return -0.3 * sin(.pi * (t - 120) / 110) }; return 0 }
private func curve(_ input: Double, _ x1: Double, _ y1: Double, _ x2: Double, _ y2: Double) -> Double {
    if input <= 0 { return 0 }; if input >= 1 { return 1 }
    func coordinate(_ t: Double, _ a: Double, _ b: Double) -> Double { 3 * (1 - t) * (1 - t) * t * a + 3 * (1 - t) * t * t * b + t * t * t }
    var lower = 0.0, upper = 1.0
    for _ in 0..<18 { let t = (lower + upper) / 2; if coordinate(t, x1, x2) < input { lower = t } else { upper = t } }
    return coordinate((lower + upper) / 2, y1, y2)
}
private struct MarkPen {
    var context: GraphicsContext
    var size: CGSize
    var ink: Color
    var red: Color
    var scale = 1.0
    var unit: Double { min(size.width, size.height) / 68 }
    func point(_ x: Double, _ y: Double) -> CGPoint { CGPoint(x: (size.width - 68 * unit) / 2 + (54 + (x - 54) * scale - 20) * unit, y: (size.height - 68 * unit) / 2 + (54 + (y - 54) * scale - 20) * unit) }
    func tile(_ x: Double, _ y: Double, _ width: Double, _ height: Double, _ color: Color, alpha: Double = 1, radius: Double = 4) {
        guard width > 0.05, height > 0.05, alpha > 0 else { return }
        let rect = CGRect(origin: point(x, y), size: CGSize(width: width * unit * scale, height: height * unit * scale))
        context.fill(Path(roundedRect: rect, cornerRadius: min(radius, min(width, height) / 2) * unit * scale), with: .color(color.opacity(min(1, alpha))))
    }
    func rest() { tile(33, 27, 42, 11.5, ink); tile(48, 41.5, 12, 25, red); tile(27, 69.5, 54, 11.5, ink) }
    func week(_ time: Double) {
        if time >= 1500 { rest(); return }
        let cards = [(0,0,2,0,0), (1,1,1,0,1), (3,0,1,0,2), (4,1,2,0,3), (2,1,2,1,0), (0,3,1,2,0), (1,3,2,2,1), (2,4,1,2,2), (3,2,2,2,3), (4,4,1,2,4)]
        let merge = curve(span(time, 930, 220), 0.2, 0, 0, 1)
        var pen = self; pen.scale = 1 - 0.035 * knock(time, 1150)
        for (index, card) in cards.enumerated() {
            let (column, row, rows, target, segment) = card
            let grow = curve(span(time, Double(column * 55 + row * 18), 280), 0.3, 1.6, 0.5, 1)
            let fly = curve(span(time, 470 + Double(index * 28), 470), 0.6, 0, 0.1, 1)
            let fromX = 27 + Double(column) * 10.6 + (10.6 - 9.2) / 2, fromY = 26 + Double(row * 11), fromH = Double(rows * 11) - 1.6
            let x = target == 0 ? 33 + Double(segment) * 42 / 4 : target == 1 ? 48 : 27 + Double(segment) * 54 / 5
            let y = target == 0 ? 27.0 : target == 1 ? 41.5 : 69.5
            let w = target == 0 ? 42.0 / 4 : target == 1 ? 12 : 54.0 / 5, h = target == 1 ? 25.0 : 11.5
            let gap = target == 1 ? 0 : 0.9 * (1 - merge)
            let width = mix(9.2, w - gap, fly) * grow, height = mix(fromH, h, fly) * grow
            let cx = mix(fromX + 9.2 / 2, x + w / 2, fly), cy = mix(fromY + fromH / 2, y + h / 2, fly)
            let alpha = (target == 1 ? 1 : mix(0.28, 1, fly)) * span(time, Double(column * 55 + row * 18), 120)
            pen.tile(cx - width / 2, cy - height / 2, width, height, target == 1 ? red : ink, alpha: alpha, radius: target == 1 ? 4 : mix(2.6, 2, fly))
        }
        if merge > 0 { pen.tile(33, 27, 42, 11.5, ink, alpha: merge); pen.tile(27, 69.5, 54, 11.5, ink, alpha: merge); pen.tile(48, 41.5, 12, 25, red) }
    }
    func signal(_ time: Double) {
        let t = time.truncatingRemainder(dividingBy: 2400)
        let morph: Double
        if t < 700 { morph = 0 } else if t < 1150 { morph = curve(span(t, 700, 450), 0.65, 0, 0.25, 1) } else if t < 1650 { morph = 1 } else if t < 2100 { morph = 1 - curve(span(t, 1650, 450), 0.65, 0, 0.25, 1) } else { morph = 0 }
        func search(_ index: Int) -> Double { if morph > 0 { return 1 }; let start = 80 + Double(index * 170); return 0.3 + 0.7 * min(1, max(0, knock(t, start)) + (t > start + 120 ? 1 : 0)) }
        func arc(_ radius: Double, _ toY: Double, _ length: Double, _ alpha: Double) {
            var path = Path()
            for index in 0...24 { let f = Double(index) / 24 * 2 - 1; let x = mix(54 + radius * sin(.pi / 4.1 * f), 54 + f * (length - 11.5) / 2, morph); let y = mix(80 - radius * cos(.pi / 4.1 * f), toY, morph); if index == 0 { path.move(to: point(x, y)) } else { path.addLine(to: point(x, y)) } }
            context.stroke(path, with: .color(ink.opacity(alpha * (1 - span(morph, 0.9, 0.1)))), style: StrokeStyle(lineWidth: mix(8.5, 11.5, morph) * unit, lineCap: .round, lineJoin: .round))
            if morph > 0.9 { tile(54 - length / 2, toY - 11.5 / 2, length, 11.5, ink, alpha: span(morph, 0.9, 0.1) * alpha) }
        }
        arc(40, 27 + 11.5 / 2, 42, search(1)); arc(23, 69.5 + 11.5 / 2, 54, search(0))
        let y = mix(78, 41.5 + 25 / 2, morph), sway = 5 * sin(.pi * morph), height = mix(12, 25, morph), beat = morph >= 1 ? knock(t, 1250) : 0
        tile(48 + sway, y - height / 2 - beat, 12, height + 2 * beat, red, radius: mix(6, 4, morph))
    }
    func bounce(_ time: Double) {
        let t = time.truncatingRemainder(dividingBy: 1300), top = 38.5, bottom = 69.5, high = 44.5, low = 63.5
        let impact = knock(t, 520), attach = knock(t, 1180)
        var y: Double, rx = 6.0, ry = 6.0
        if t < 520 { let f = span(t, 0, 520); y = mix(high, low, f * f); rx *= 1 - 0.16 * f; ry *= 1 + 0.32 * f; y = min(y, bottom - ry) }
        else if t < 700 { let squash = max(0, impact); rx *= 1 + 0.38 * squash; ry *= 1 - 0.36 * squash; y = bottom - ry + 1.4 * impact }
        else if t < 1180 { let f = span(t, 700, 480); y = mix(low, high, 1 - (1 - f) * (1 - f)); rx *= 1 - 0.1 * (1 - f); ry *= 1 + 0.2 * (1 - f) }
        else { let squash = max(0, attach); rx *= 1 + 0.25 * squash; ry *= 1 - 0.25 * squash; y = top + ry - 1.1 * attach }
        tile(48, 41.5, 12, 25, red, alpha: 0.12); tile(33, 27 - 1.1 * attach, 42, 11.5, ink)
        tile(27 - 0.8 * max(0, impact), 69.5 + 1.4 * impact, 54 + 1.6 * max(0, impact), 11.5 - 0.8 * max(0, impact), ink)
        let gap = y - ry - top
        if gap < 6 {
            let pull = min(1, max(0, 1 - gap / 6)), base = 5 + 6 * pull, waist = 1.4 + 3.4 * pull, upper = top - 0.5, joint = y - ry * 0.4
            var path = Path(); path.move(to: point(54 - base / 2, upper)); path.addQuadCurve(to: point(54 - rx * 0.8, joint), control: point(54 - waist / 2, (upper + joint) / 2)); path.addLine(to: point(54 + rx * 0.8, joint)); path.addQuadCurve(to: point(54 + base / 2, upper), control: point(54 + waist / 2, (upper + joint) / 2)); path.closeSubpath(); context.fill(path, with: .color(red))
        }
        context.fill(Path(ellipseIn: CGRect(origin: point(54 - rx, y - ry), size: CGSize(width: 2 * rx * unit, height: 2 * ry * unit))), with: .color(red))
    }
}

struct BrandLoader: View {
    var size: CGFloat = 22
    var motion: MarkMotion = .bounce
    @Environment(\.accessibilityReduceMotion) var reduceMotion
    @Environment(\.colorScheme) var scheme
    @Environment(\.scenePhase) var scenePhase
    @State private var start = Date()
    var body: some View {
        TimelineView(.animation(minimumInterval: 1.0 / 30, paused: reduceMotion || scenePhase != .active)) { moment in
            Canvas { context, size in
                let pen = MarkPen(context: context, size: size, ink: scheme == .dark ? Color(hex: 0xECEDEF) : Color(hex: 0x1B1D22), red: scheme == .dark ? Color(hex: 0xFF6E52) : Color(hex: 0xE5482E))
                let elapsed = max(0, moment.date.timeIntervalSince(start) * 1000)
                if reduceMotion { pen.rest() } else { switch motion { case .week: pen.week(elapsed); case .signal: pen.signal(elapsed); case .bounce: pen.bounce(elapsed) } }
            }.frame(width: size, height: size)
        }.accessibilityLabel("正在处理")
    }
}
