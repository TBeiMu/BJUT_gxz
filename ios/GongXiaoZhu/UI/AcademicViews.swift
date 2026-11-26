import SwiftUI

struct AcademicHeader: View {
    @Environment(AppState.self) var app
    let target: AcademicTarget
    @State private var unbind = false
    var body: some View {
        AppCard { VStack(alignment: .leading, spacing: 12) {
            HStack { VStack(alignment: .leading, spacing: 5) { Text(app.academic.identity.map { $0.name.isEmpty ? $0.studentID : $0.name + " · " + $0.studentID } ?? "独立教务绑定").font(.headline); if let date = target == .exams ? app.academic.examUpdatedAt : app.academic.grades?.updatedAt { Text("更新于 " + ShanghaiClock.format(date, "M 月 d 日 HH:mm")).font(.caption).foregroundStyle(.secondary) } }; Spacer(); Button(app.academic.identity == nil ? "登录" : "刷新") { app.openAcademic(target) }.buttonStyle(.borderedProminent).disabled(app.academicBusy || !app.network.internetAvailable || app.academicError != nil) }
            if app.credentialPresent || app.credentialError { Button(app.credentialError ? "清除无法读取的教务密码" : "清除已记住的教务密码", role: .destructive) { Task { await app.clearCredential() } }.font(.caption) }
            if app.academic.identity != nil { Button("解绑教务资料", role: .destructive) { unbind = true }.font(.caption) }
        } }.confirmationDialog("清除学校考试与成绩及已记住的教务密码？自定义考试保留。", isPresented: $unbind, titleVisibility: .visible) { Button("解绑", role: .destructive) { Task { await app.unbindAcademic() } } }
    }
}

struct ExamsPage: View {
    @Environment(AppState.self) var app
    @State private var search = ""
    @State private var showHidden = false
    @State private var scope = "全部"
    private var records: [ExamRecord] {
        let text = search.trimmed
        return app.academic.exams.filter { record in
            (showHidden || !record.hidden) && (scope == "全部" || (scope == "学校" ? record.original != nil : record.original == nil)) &&
            (text.isEmpty || [record.fields.name, record.fields.location, record.fields.note].contains { $0.localizedCaseInsensitiveContains(text) })
        }.sorted { a, b in let first = ShanghaiClock.examTime(a.fields.start), second = ShanghaiClock.examTime(b.fields.start); if first == second { return a.fields.name < b.fields.name }; return (first ?? .distantFuture) < (second ?? .distantFuture) }
    }
    var body: some View {
        ScrollView { LazyVStack(alignment: .leading, spacing: 14) {
            if let error = app.academicError { StorageNotice(module: "academic", text: error) }
            AcademicHeader(target: .exams)
            Picker("考试来源", selection: $scope) { ForEach(["全部", "学校", "自定义"], id: \.self) { Text($0) } }.pickerStyle(.segmented)
            if records.isEmpty { EmptyCard(title: "暂无符合条件的考试", detail: app.academic.examUpdatedAt == nil ? "可从学校读取考试，或添加自己的安排。" : "学校快照和自定义考试均保存在本机。", symbol: "doc.text") }
            TimelineView(.periodic(from: .now, by: 60)) { context in
                ForEach(["即将进行", "已结束", "时间待确认"], id: \.self) { section in
                    let items = records.filter { record in guard let start = ShanghaiClock.examTime(record.fields.start) else { return section == "时间待确认" }; let end = ShanghaiClock.examTime(record.fields.end) ?? start; return section == (end < context.date ? "已结束" : "即将进行") }
                    if !items.isEmpty {
                        Text(section).font(.headline).padding(.top, 8)
                        ForEach(items) { record in
                            Button { app.showExam(record) } label: {
                                AppCard { VStack(alignment: .leading, spacing: 9) { HStack(alignment: .top) { Text(record.fields.name).font(.headline); Spacer(); if record.hidden { Text("已隐藏").font(.caption) } }; if !record.fields.start.isEmpty { Label(record.fields.start + (record.fields.end.isEmpty ? "" : " — " + record.fields.end), systemImage: "clock").font(.subheadline) }; if !record.fields.location.isEmpty { Label(record.fields.location, systemImage: "mappin").font(.subheadline) }; if !record.fields.note.isEmpty { Text(record.fields.note).font(.subheadline).foregroundStyle(.secondary) }; Text(record.original == nil ? "自定义考试" : record.retained ? "历史本地保留副本" : record.modified ? "学校考试 · 已在本机修改" : "学校考试").font(.caption).foregroundStyle(.secondary) } }.foregroundStyle(.primary)
                            }.buttonStyle(.plain).contextMenu { if record.hidden { Button("恢复显示") { Task { await app.setExamHidden(record, hidden: false) } } } }
                        }
                    }
                }
            }
        }.padding(20).frame(maxWidth: 760) }.navigationTitle("考试")
            .searchable(text: $search, prompt: "搜索名称、地点或备注")
            .toolbar { ToolbarItemGroup(placement: .topBarTrailing) { Menu { Toggle("显示隐藏考试", isOn: $showHidden) } label: { Image(systemName: "line.3.horizontal.decrease.circle") }; Button { app.showExam() } label: { Image(systemName: "plus") }.disabled(app.academicError != nil) } }
    }
}

struct GradesPage: View {
    @Environment(AppState.self) var app
    @State private var search = ""
    @State private var term = "全部学期"
    @State private var category = "全部"
    private var all: [GradeRecord] { (app.academic.grades?.degree ?? []) + (app.academic.grades?.elective ?? []) }
    private var terms: [String] { ["全部学期"] + Set(all.map(\.term).filter { !$0.isEmpty }).sorted().reversed() }
    var body: some View {
        ScrollView { LazyVStack(alignment: .leading, spacing: 16) {
            if let error = app.academicError { StorageNotice(module: "academic", text: error) }
            AcademicHeader(target: .grades)
            if let snapshot = app.academic.grades {
                if !snapshot.credits.isEmpty { AppCard { VStack(alignment: .leading, spacing: 12) { Text("学校学分").font(.headline); ForEach(["学位学分", "选修学分", "已修学分", "应修学分"], id: \.self) { key in if let value = snapshot.credits[key] { HStack { Text(key).foregroundStyle(.secondary); Spacer(); Text(value).fontWeight(.medium) } } } } } }
                HStack { Picker("分类", selection: $category) { ForEach(["全部", "学位课程", "选修课程"], id: \.self) { Text($0) } }; Spacer(); Picker("学期", selection: $term) { ForEach(terms, id: \.self) { Text($0) } } }.font(.subheadline)
                ForEach(["学位课程", "选修课程"], id: \.self) { group in
                    let records = (group == "学位课程" ? snapshot.degree : snapshot.elective).filter { (term == "全部学期" || $0.term == term) && (search.trimmed.isEmpty || $0.course.localizedCaseInsensitiveContains(search.trimmed)) }
                    if (category == "全部" || category == group) && !records.isEmpty {
                        Text("\(group) · \(records.count)").font(.headline)
                        ForEach(Array(records.enumerated()), id: \.offset) { _, record in AppCard { HStack(alignment: .top, spacing: 16) { VStack(alignment: .leading, spacing: 7) { Text(record.course).font(.headline); Text(record.term + " · 学分 " + (record.credits.isEmpty ? "未获取" : record.credits)).font(.caption).foregroundStyle(.secondary); if record.restricted { Text("学校限制展示").font(.caption).foregroundStyle(.secondary) } }; Spacer(minLength: 4); Text(record.grade.isEmpty ? "未获取" : record.grade).font(.title3.bold()).multilineTextAlignment(.trailing) } } }
                    }
                }
                if all.isEmpty { EmptyCard(title: "学校暂无成绩记录", symbol: "chart.bar.doc.horizontal") }
                Text("保留学校返回的原始成绩、状态与学分，不计算绩点。").font(.footnote).foregroundStyle(.secondary)
            } else { EmptyCard(title: "还没有成绩资料", detail: "登录研究生教务系统读取，成功后可离线查看。", symbol: "chart.bar.doc.horizontal") }
        }.padding(20).frame(maxWidth: 760) }.navigationTitle("成绩").searchable(text: $search, prompt: "搜索课程名称")
    }
}

struct AcademicLoginSheet: View {
    @Environment(AppState.self) var app
    let target: AcademicTarget
    private var valid: Bool { !app.academicBusy && !app.academicCommitting && app.academicForm != nil && !app.academicUsername.trimmed.isEmpty && !app.academicPassword.isEmpty && !app.academicCode.trimmed.isEmpty }
    var body: some View {
        @Bindable var app = app
        NavigationStack {
            Form {
                Section { Text(app.academicStatus).font(.subheadline); if app.academicBusy { ProgressView() } }
                Section {
                    TextField("教务账号／学号", text: $app.academicUsername).textContentType(.username).textInputAutocapitalization(.never).autocorrectionDisabled()
                    SecureField("教务密码", text: $app.academicPassword).textContentType(.password)
                    HStack { TextField("验证码", text: $app.academicCode).textInputAutocapitalization(.never).autocorrectionDisabled().submitLabel(.done).onSubmit { if valid { app.submitAcademic() } }; if let bytes = app.academicForm?.captcha, let image = UIImage(data: bytes) { Button { app.refreshAcademicCaptcha() } label: { Image(uiImage: image).resizable().scaledToFit().frame(width: 110, height: 45) }.accessibilityLabel("刷新验证码") } }
                    Toggle("记住教务密码", isOn: Binding(get: { app.rememberAcademic }, set: { value in app.rememberAcademic = value; if !value { Task { await app.removeStoredCredentialKeepingDraft() } } }))
                }.disabled(app.academicBusy || app.academicCommitting)
                Section { Button("登录并查询\(target.title)") { app.submitAcademic() }.disabled(!valid); if app.academicForm == nil && !app.academicBusy { Button("重新连接学校") { app.openAcademic(target) } } } footer: { Text("凭据只提交一次，失败后由你重新操作。仅在完整查询成功后保存所选资料；记住的教务密码独立加密，不更改校园网账号。") }
            }.navigationTitle("查询\(target.title)").navigationBarTitleDisplayMode(.inline)
                .toolbar { ToolbarItem(placement: .cancellationAction) { Button("取消") { app.cancelAcademic() }.disabled(app.academicCommitting) } }
                .alert("更换教务绑定？", isPresented: Binding(get: { app.pendingAcademicIdentity != nil }, set: { _ in })) { Button("确认更换并保存") { Task { await app.commitAcademic() } }; Button("取消", role: .cancel) { app.cancelAcademic() } } message: { Text(app.pendingAcademicIdentity.map { "学校核验的新学号为 \($0.studentID)。更换后清理旧学校考试和成绩，保留自定义考试。" } ?? "") }
        }.interactiveDismissDisabled(app.academicCommitting)
    }
}
