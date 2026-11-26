import SwiftUI
import UIKit

/// Covers the actual window, including presented password and school-page sheets.
struct PrivacyCurtain: UIViewRepresentable {
    func makeUIView(context: Context) -> AnchorView { AnchorView() }
    func updateUIView(_ uiView: AnchorView, context: Context) { }
    @MainActor final class AnchorView: UIView {
        private var curtain: UIView?
        private var observers: [NSObjectProtocol] = []
        init() {
            super.init(frame: .zero); isUserInteractionEnabled = false
            observers.append(NotificationCenter.default.addObserver(forName: UIApplication.willResignActiveNotification, object: nil, queue: .main) { [weak self] _ in Task { @MainActor in self?.cover() } })
            observers.append(NotificationCenter.default.addObserver(forName: UIApplication.didBecomeActiveNotification, object: nil, queue: .main) { [weak self] _ in Task { @MainActor in self?.uncover() } })
        }
        required init?(coder: NSCoder) { nil }
        private func cover() {
            guard curtain == nil, let window else { return }
            let cover = UIView(frame: window.bounds); cover.backgroundColor = UIColor(red: 0.969, green: 0.973, blue: 0.980, alpha: 1); cover.autoresizingMask = [.flexibleWidth, .flexibleHeight]
            let mark = UIImageView(image: UIImage(named: "LaunchMark")); mark.contentMode = .scaleAspectFit; mark.translatesAutoresizingMaskIntoConstraints = false; cover.addSubview(mark)
            NSLayoutConstraint.activate([mark.centerXAnchor.constraint(equalTo: cover.centerXAnchor), mark.centerYAnchor.constraint(equalTo: cover.centerYAnchor), mark.widthAnchor.constraint(equalToConstant: 90), mark.heightAnchor.constraint(equalToConstant: 90)])
            window.addSubview(cover); window.bringSubviewToFront(cover); curtain = cover
        }
        private func uncover() { curtain?.removeFromSuperview(); curtain = nil }
        deinit { observers.forEach { NotificationCenter.default.removeObserver($0) } }
    }
}
