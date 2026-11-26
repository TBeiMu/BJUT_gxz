import Foundation

extension AppState {
    func startImport() {
        guard !scheduleBusy, importSession == nil, scheduleError == nil else { return }; invalidateUndo()
        do {
            let session = ScheduleSession(transport: NetworkTransport(route: try network.route(.internet))); importSession = session; scheduleBusy = true; scheduleStatus = "正在连接学校…"
            importTask = Task {
                let timeout = Task { try? await Task.sleep(for: .seconds(1800)); if !Task.isCancelled, self.importSession === session { self.pauseImport(); self.scheduleStatus = "导入已超时，周次进度保留，请手动继续" } }; defer { timeout.cancel() }
                do { try await handleGate(session.open(), session: session) } catch { if session.transport.lease.isValid { scheduleStatus = safeMessage(error) }; await finishImport(session, success: false) }
            }
        } catch { scheduleStatus = safeMessage(error) }
    }
    func handleGate(_ gate: ScheduleGate, session: ScheduleSession) async throws {
        try session.transport.lease.requireValid()
        switch gate {
        case .login(let page):
            guard active else { throw AppError.expired }; schedulePage = page; scheduleBusy = false; scheduleSubmitting = false; scheduleStatus = "请在学校页面完成登录"
            WorkLifetime.shared.end("schedule", success: false)
        case .ready(let identity, let term):
            schedulePage = nil; scheduleSubmitting = true; scheduleBusy = true
            WorkLifetime.shared.begin("schedule", title: "导入整学期课表", continued: true) { [weak self] in self?.pauseImport() }
            schedule = try await scheduleStore.begin(identity, term: term, lease: session.transport.lease)
            for number in 1...term.weekCount {
                try session.transport.lease.requireValid(); try Task.checkCancellation()
                if schedule.pending?.weeks.contains(where: { $0.number == number }) == true { continue }
                scheduleStatus = "正在导入第 \(number)／\(term.weekCount) 周"
                let week = try await session.week(term, number: number)
                schedule = try await scheduleStore.append(week, lease: session.transport.lease)
                WorkLifetime.shared.progress("schedule", fraction: Double(number) / Double(term.weekCount))
            }
            scheduleCommitting = true
            do { schedule = try await scheduleStore.finish(lease: session.transport.lease) } catch { scheduleCommitting = false; throw error }
            scheduleCommitting = false; selectToday(); scheduleStatus = "整学期课表已保存"
            if accountError == nil, let next = try? await accountStore.fillName(identity, lease: session.transport.lease) { accounts = next }
            await finishImport(session, success: true)
        }
    }
    func submitSchedulePage(token: String, values: [String: String]) {
        guard let session = importSession, schedulePage?.id == token, !scheduleSubmitting else { return }
        scheduleSubmitting = true; scheduleBusy = true
        WorkLifetime.shared.begin("schedule", title: "连接学校课表", continued: true) { [weak self] in self?.pauseImport() }
        importTask = Task {
            let timeout = Task { try? await Task.sleep(for: .seconds(1800)); if !Task.isCancelled, self.importSession === session { self.pauseImport(); self.scheduleStatus = "导入已超时，周次进度保留，请手动继续" } }; defer { timeout.cancel() }
            do { try await handleGate(session.submit(token: token, values: values), session: session) }
            catch { if session.transport.lease.isValid { scheduleStatus = safeMessage(error) }; await finishImport(session, success: false) }
        }
    }
    func finishImport(_ session: ScheduleSession, success: Bool) async {
        await session.close(); guard importSession === session else { return }
        importSession = nil; importTask = nil; scheduleBusy = false; scheduleSubmitting = false; scheduleCommitting = false; schedulePage = nil
        WorkLifetime.shared.end("schedule", success: success); stopMonitoringIfIdle()
    }
    func pauseImport() {
        guard let session = importSession, !scheduleCommitting else { return }; session.transport.cancel(); importTask?.cancel(); schedulePage = nil; scheduleStatus = "已暂停，已完成的周次保留；继续时需重新登录"
        if !scheduleBusy { Task { await finishImport(session, success: false) } }
    }
    func clearPending() async { pauseImport(); await importTask?.value; do { schedule = try await scheduleStore.cancelPending() } catch { notify(error) } }
    func unbindSchedule() async { pauseImport(); await importTask?.value; invalidateUndo(); do { schedule = try await scheduleStore.unbind(); selectToday() } catch { notify(error) } }
    func selectTerm(_ id: String?) async { invalidateUndo(); do { schedule = try await scheduleStore.selectLocal(id); selectToday() } catch { notify(error) } }
    func deleteLocalTerm(_ id: String) async { invalidateUndo(); do { schedule = try await scheduleStore.deleteLocal(id); selectToday() } catch { notify(error) } }
    func editCourse(_ course: Course? = nil, newTerm: Bool = false, deleteOnly: Bool = false) async {
        pauseImport(); await importTask?.value
        invalidateUndo()
        do { courseEdit = try await scheduleStore.editor(course: course, week: selectedWeek, weekday: ShanghaiClock.weekday(ShanghaiClock.day()) ?? 1, newTerm: newTerm, deleteOnly: deleteOnly) } catch { notify(error) }
    }
    func saveCourse(_ context: CourseEditContext, draft: CourseDraft, weeks: [Int] = []) async -> Bool {
        do { let result = try await scheduleStore.edit(context, draft: draft, deletionWeeks: weeks); schedule = result.0; courseEdit = nil; offerUndo(result.1); return true } catch { notify(error); return false }
    }
}
