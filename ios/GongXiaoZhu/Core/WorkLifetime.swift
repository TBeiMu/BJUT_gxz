import UIKit
import BackgroundTasks

/// System expiration always pauses the owning operation and preserves encrypted progress.
@MainActor final class WorkLifetime {
    static let shared = WorkLifetime()
    private var finite: [String: UIBackgroundTaskIdentifier] = [:]
    private var expires: [String: () -> Void] = [:]
    private var ongoing: [String: BGTask] = [:]
    private var requested = Set<String>()
    private func identifier(_ kind: String) -> String { (Bundle.main.bundleIdentifier ?? "io.github.tbeimu.gongxiaozhu.ios") + "." + kind + ".work" }
    func register() {
        if #available(iOS 26.0, *) {
            for kind in ["schedule", "download"] {
                BGTaskScheduler.shared.register(forTaskWithIdentifier: identifier(kind), using: .main) { [weak self] task in
                    Task { @MainActor in
                        guard let self, let cancel = self.expires[kind], self.requested.contains(kind), let continued = task as? BGContinuedProcessingTask else { task.setTaskCompleted(success: false); return }
                        self.ongoing[kind] = continued
                        continued.progress.totalUnitCount = 1000
                        continued.progress.completedUnitCount = 1
                        continued.expirationHandler = { Task { @MainActor in cancel(); self.end(kind, success: false) } }
                    }
                }
            }
        }
    }
    func begin(_ kind: String, title: String, continued: Bool = false, expiration: @escaping () -> Void) {
        if expires[kind] != nil { expires[kind] = expiration; return }
        end(kind, success: false)
        expires[kind] = expiration
        finite[kind] = UIApplication.shared.beginBackgroundTask(withName: title) { [weak self] in
            Task { @MainActor in
                guard let self else { return }
                if let token = self.finite.removeValue(forKey: kind), token != .invalid { UIApplication.shared.endBackgroundTask(token) }
                if self.ongoing[kind] == nil { self.expires[kind]?(); self.end(kind, success: false) }
            }
        }
        if #available(iOS 26.0, *), continued {
            let request = BGContinuedProcessingTaskRequest(identifier: identifier(kind), title: title, subtitle: "由你启动，可随时暂停")
            request.strategy = .fail
            do { try BGTaskScheduler.shared.submit(request); requested.insert(kind) } catch { }
        }
    }
    func progress(_ kind: String, fraction: Double) {
        if #available(iOS 26.0, *), let task = ongoing[kind] as? BGContinuedProcessingTask {
            task.progress.completedUnitCount = Int64(max(1, min(999, fraction * 1000)))
        }
    }
    func end(_ kind: String, success: Bool) {
        if let token = finite.removeValue(forKey: kind), token != .invalid { UIApplication.shared.endBackgroundTask(token) }
        ongoing.removeValue(forKey: kind)?.setTaskCompleted(success: success)
        if requested.remove(kind) != nil { BGTaskScheduler.shared.cancel(taskRequestWithIdentifier: identifier(kind)) }
        expires.removeValue(forKey: kind)
    }
}
