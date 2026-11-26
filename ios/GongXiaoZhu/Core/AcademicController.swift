import Foundation

extension AppState {
    func loadCredential() async {
        do { let credential = try await credentialStore.load(); credentialPresent = credential != nil; credentialError = false }
        catch { credentialError = true; credentialPresent = false }
    }
    func clearCredential() async { do { try await credentialStore.reset(); credentialPresent = false; credentialError = false; academicPassword = ""; rememberAcademic = false } catch { notify(error) } }
    func removeStoredCredentialKeepingDraft() async { do { try await credentialStore.reset(); credentialPresent = false; credentialError = false } catch { notify(error) } }
    func openAcademic(_ target: AcademicTarget) {
        guard !academicBusy, academicError == nil else { return }; invalidateUndo(); academicTarget = target; academicStatus = "正在连接学校…"; academicBusy = true
        academicTask = Task {
            do {
                academicUsername = academic.identity?.studentID ?? ""; academicPassword = ""; academicCode = ""; rememberAcademic = false
                if let credential = try? await credentialStore.load() { academicUsername = credential.studentID; academicPassword = credential.password; rememberAcademic = true }
                let session = AcademicSession(transport: NetworkTransport(route: try network.route(.internet))); academicSession = session
                let form = try await session.open(); try session.transport.lease.requireValid()
                guard active, academicSession === session, academicTarget == target else { throw AppError.cancelled }
                academicForm = form; academicStatus = "填写学校教务账号与验证码"
            } catch { academicStatus = safeMessage(error) }
            academicBusy = false; academicTask = nil
        }
    }
    func refreshAcademicCaptcha() {
        guard !academicBusy, let session = academicSession, let form = academicForm else { return }; academicBusy = true
        academicTask = Task { do { let next = try await session.refreshCaptcha(form.token); try session.transport.lease.requireValid(); guard active, academicSession === session else { throw AppError.cancelled }; academicForm = next; academicCode = "" } catch { if session.transport.lease.isValid { academicStatus = safeMessage(error); academicForm = nil } }; academicBusy = false; academicTask = nil }
    }
    func submitAcademic() {
        guard !academicBusy, let session = academicSession, let form = academicForm, let target = academicTarget else { return }
        let username = academicUsername, password = academicPassword, code = academicCode, revision = academic.revision
        academicBusy = true; academicForm = nil; academicStatus = "正在登录与读取完整分页…"
        academicTask = Task {
            do {
                let identity = try await session.submit(token: form.token, username: username, password: password, captcha: code)
                let exams: [SchoolExam]? = target == .exams ? try await session.exams() : nil
                let grades: GradeSnapshot? = target == .grades ? try await session.grades() : nil
                try session.transport.lease.requireValid()
                academicResult = (identity, exams, grades, revision, session.transport.lease)
                if let old = academic.identity, old.studentID != identity.studentID { pendingAcademicIdentity = identity; academicStatus = "学校已核验新学号，请确认更换绑定" }
                else { await commitAcademic() }
            } catch { if session.transport.lease.isValid { academicStatus = safeMessage(error) }; await session.close(); if academicSession === session { academicSession = nil } }
            academicBusy = false; academicTask = nil
        }
    }
    func commitAcademic() async {
        guard let result = academicResult else { return }; academicCommitting = true; pendingAcademicIdentity = nil
        do {
            academic = try await academicStore.commit(identity: result.0, exams: result.1, grades: result.2, revision: result.3, allowChange: true, lease: result.4)
            if rememberAcademic {
                do { try await credentialStore.save(AcademicCredential(studentID: result.0.studentID, password: academicPassword), lease: result.4); credentialPresent = true } catch { message = "资料已更新，教务密码未保存" }
            }
            academicStatus = "资料已更新"; academicTarget = nil
        } catch { academicStatus = safeMessage(error) }
        academicResult = nil; academicCommitting = false; academicPassword = ""; academicCode = ""
        if let session = academicSession { await session.close(); academicSession = nil }
    }
    func cancelAcademic() {
        guard !academicCommitting else { return }
        academicSession?.transport.cancel(); academicTask?.cancel(); academicForm = nil; academicResult = nil; pendingAcademicIdentity = nil; academicPassword = ""; academicCode = ""; academicTarget = nil
        let session = academicSession; let task = academicTask
        Task { await task?.value; await session?.close(); if academicSession === session { academicSession = nil }; academicBusy = false }
    }
    func unbindAcademic() async {
        cancelAcademic(); await academicTask?.value; invalidateUndo()
        do { try await credentialStore.reset(); academic = try await academicStore.unbind(); credentialPresent = false; credentialError = false } catch { notify(error) }
    }
    func saveExam(_ edit: ExamEdit, fields: ExamFields, delete: Bool = false, restore: Bool = false) async -> Bool {
        do { let result = try await academicStore.editExam(edit.record?.id, fields: fields, revision: edit.revision, delete: delete, restore: restore); academic = result.0; examEdit = nil; offerUndo(result.1); return true } catch { notify(error); return false }
    }
    func showExam(_ record: ExamRecord? = nil) { invalidateUndo(); examEdit = ExamEdit(record: record, revision: academic.revision) }
    func setExamHidden(_ record: ExamRecord, hidden: Bool) async {
        invalidateUndo()
        do { let result = try await academicStore.setHidden(record.id, hidden: hidden, revision: academic.revision); academic = result.0; offerUndo(result.1) } catch { notify(error) }
    }
}
