import SwiftUI

private struct DiscardEdits: View {
    let resume: () -> Void
    let discard: () -> Void
    var body: some View { VStack(spacing: 24) { Image(systemName: "square.and.pencil").font(.largeTitle); Text("放弃未保存的修改？").font(.title2.bold()); Button("继续编辑", action: resume).buttonStyle(.borderedProminent); Button("放弃修改", role: .destructive, action: discard).buttonStyle(.bordered) }.frame(maxWidth: .infinity, maxHeight: .infinity).padding(24) }
}

struct AccountEditor: View {
    @Environment(AppState.self) var app
    let edit: AccountEdit
    @State private var draft: CampusAccount
    @State private var busy = false
    @State private var discard = false
    @State private var reveal = false
    @State private var error: String?
    init(edit: AccountEdit) { self.edit = edit; var draft = edit.original ?? CampusAccount(); if !draft.customLabel && draft.label == draft.username { draft.label = "" }; _draft = State(initialValue: draft) }
    private func normalized(_ account: CampusAccount) -> [String] { [account.username.trimmed, account.label.trimmed.isEmpty ? account.username.trimmed : account.label.trimmed, account.password] }
    private var dirty: Bool { normalized(draft) != normalized(edit.original ?? CampusAccount()) }
    private var valid: Bool { !draft.username.trimmed.isEmpty && !draft.password.isEmpty && (edit.original == nil || dirty) }
    var body: some View {
        NavigationStack {
            Group {
                if discard { DiscardEdits(resume: { discard = false }, discard: { app.accountEdit = nil }) }
                else {
                    Form {
                        Section { TextField("账号／学号", text: $draft.username).textContentType(.username).textInputAutocapitalization(.never).autocorrectionDisabled(); HStack { if reveal { TextField("密码", text: $draft.password) } else { SecureField("密码", text: $draft.password) }; Button { reveal.toggle() } label: { Image(systemName: reveal ? "eye.slash" : "eye") }.accessibilityLabel(reveal ? "隐藏密码" : "显示密码") }; TextField("备注（可留空）", text: $draft.label).textInputAutocapitalization(.never) } footer: { Text("备注留空时可使用已验证课表姓名，或在校园认证成功后尝试补全。") }
                        if let error { Section { Text(error).foregroundStyle(.red) } }
                    }.disabled(busy)
                }
            }.navigationTitle(edit.original == nil ? "添加账号" : "编辑账号").navigationBarTitleDisplayMode(.inline)
                .toolbar { if !discard { ToolbarItem(placement: .cancellationAction) { Button("取消") { if dirty { discard = true } else { app.accountEdit = nil } }.disabled(busy) }; ToolbarItem(placement: .confirmationAction) { Button(busy ? "保存中" : "保存") { busy = true; Task { if !(await app.saveAccount(draft, original: edit.original)) { error = app.message; app.message = nil }; busy = false } }.disabled(!valid || busy) } } }
        }.interactiveDismissDisabled(dirty || busy)
    }
}

/// Tap individual values; long press and drag to supplement a continuous range.
struct SelectionGrid: View {
    let values: [Int]
    @Binding var selected: [Int]
    var columns = 6
    var label: (Int) -> String = { String($0) }
    var single = false
    @State private var anchor: Int?
    @State private var baseline: [Int] = []
    private let height: CGFloat = 44
    var body: some View {
        GeometryReader { geometry in
            let width = geometry.size.width / CGFloat(columns)
            LazyVGrid(columns: Array(repeating: GridItem(.flexible(), spacing: 6), count: columns), spacing: 6) {
                ForEach(values, id: \.self) { value in Button { if single { selected = [value] } else if selected.contains(value) { selected.removeAll { $0 == value } } else { selected.append(value); selected.sort() } } label: { Text(label(value)).font(.subheadline.monospacedDigit()).frame(maxWidth: .infinity).frame(height: height - 6).background(selected.contains(value) ? Color.accentColor.opacity(0.16) : Color.secondary.opacity(0.07), in: RoundedRectangle(cornerRadius: 9)).overlay(RoundedRectangle(cornerRadius: 9).strokeBorder(selected.contains(value) ? Color.accentColor : .clear, lineWidth: 1)) }.buttonStyle(.plain).accessibilityAddTraits(selected.contains(value) ? .isSelected : []) }
            }
            .coordinateSpace(name: "selection-grid")
            .simultaneousGesture(LongPressGesture(minimumDuration: 0.3).sequenced(before: DragGesture(minimumDistance: 0, coordinateSpace: .named("selection-grid"))).onChanged { gesture in
                guard !single, case .second(true, let drag?) = gesture else { return }
                let row = Int(drag.location.y / height), column = Int(drag.location.x / width)
                guard row >= 0, column >= 0, column < columns else { return }; let index = row * columns + column
                guard values.indices.contains(index) else { return }
                if anchor == nil { anchor = index; baseline = selected }
                guard let anchor else { return }; selected = Set(baseline + Array(values[min(anchor, index)...max(anchor, index)])).sorted()
            }.onEnded { _ in anchor = nil; baseline = [] })
        }.frame(height: CGFloat((values.count + columns - 1) / columns) * height)
    }
}

struct CourseEditor: View {
    @Environment(AppState.self) var app
    let context: CourseEditContext
    @State private var draft: CourseDraft
    private let original: CourseDraft
    @State private var deletingWeeks: [Int]
    @State private var busy = false
    @State private var discard = false
    @State private var error: String?
    init(context: CourseEditContext) {
        self.context = context
        let draft = CourseDraft(name: context.course?.name ?? "", location: context.course?.location ?? "", teacher: context.course?.teacher ?? "", weekdays: [context.weekday], weeks: context.availableWeeks.isEmpty ? [context.week] : context.availableWeeks, lessons: context.course?.lessons ?? [1, 2], firstMonday: context.firstMonday, weekCount: context.weekCount)
        self.original = draft.normalized; _draft = State(initialValue: draft); _deletingWeeks = State(initialValue: [context.week])
    }
    private var dirty: Bool { !context.deleteOnly && draft.normalized != original }
    private var weekValues: [Int] { Array(Set(Array(1...max(1, draft.weekCount)) + context.availableWeeks)).sorted() }
    private var valid: Bool { context.deleteOnly ? !deletingWeeks.isEmpty : !draft.name.trimmed.isEmpty && !draft.weekdays.isEmpty && !draft.weeks.isEmpty && !draft.lessons.isEmpty }
    var body: some View {
        NavigationStack {
            Group {
                if discard { DiscardEdits(resume: { discard = false }, discard: { app.courseEdit = nil }) }
                else {
                    Form {
                        if context.deleteOnly {
                            Section { Text(context.course?.name ?? "课程").font(.headline); Text("星期" + ["一", "二", "三", "四", "五", "六", "日"][context.weekday - 1] + " · " + LessonTimes.label(context.course?.lessons ?? [])).font(.subheadline); Text("只处理以下周次中匹配的同一课程系列与节次，共 \(deletingWeeks.count) 次安排。").font(.subheadline) }
                            Section("删除周次") { SelectionGrid(values: context.availableWeeks, selected: $deletingWeeks); HStack { Button("全部") { deletingWeeks = context.availableWeeks }; Spacer(); Button("仅本次") { deletingWeeks = [context.week] } } }
                        } else {
                            Section { TextField("课程名称", text: $draft.name); TextField("地点（可选）", text: $draft.location); TextField("教师（可选）", text: $draft.teacher) }
                            if context.creatingTerm {
                                Section("本地学期") { DatePicker("第一周周一", selection: Binding(get: { ShanghaiClock.parse(draft.firstMonday) ?? Date() }, set: { draft.firstMonday = ShanghaiClock.day($0) }), displayedComponents: .date).environment(\.timeZone, ShanghaiClock.timezone); Stepper("共 \(draft.weekCount) 周", value: $draft.weekCount, in: 1...60).onChange(of: draft.weekCount) { _, count in draft.weeks.removeAll { $0 > count } } }
                            }
                            Section("星期（可多选）") { SelectionGrid(values: Array(1...7), selected: $draft.weekdays, columns: 7, label: { ["一", "二", "三", "四", "五", "六", "日"][$0 - 1] }) }
                            Section("节次") { SelectionGrid(values: Array(1...12), selected: $draft.lessons); if draft.lessons.contains(where: { $0 > 12 }) { Text("保留原有晚节次：" + LessonTimes.label(draft.lessons)).font(.caption).foregroundStyle(.secondary) } }
                            Section("周次") { SelectionGrid(values: weekValues, selected: $draft.weeks); HStack { Button("全选") { draft.weeks = Array(1...draft.weekCount) }; Spacer(); Button("单周") { draft.weeks = (1...draft.weekCount).filter { $0 % 2 == 1 } }; Spacer(); Button("双周") { draft.weeks = (1...draft.weekCount).filter { $0 % 2 == 0 } }; Spacer(); Button("清空") { draft.weeks = [] } }; Text("长按并拖动可补选连续范围").font(.caption).foregroundStyle(.secondary) }
                            if context.course != nil {
                                Section("原有安排") { Text("本次编辑处理第 " + context.availableWeeks.map(String.init).joined(separator: "、") + " 周的原安排。").font(.subheadline); ForEach(Array(context.arrangements.enumerated()), id: \.offset) { _, occurrence in Text("第 \(occurrence.week) 周 · 星期\(["一", "二", "三", "四", "五", "六", "日"][occurrence.weekday - 1]) · \(LessonTimes.label(occurrence.lessons))").font(.caption).foregroundStyle(.secondary) } }
                            }
                        }
                        if let error { Section { Text(error).foregroundStyle(.red) } }
                    }.disabled(busy)
                }
            }.navigationTitle(context.deleteOnly ? "删除课程安排" : context.course == nil ? "添加课程" : "编辑课程").navigationBarTitleDisplayMode(.inline)
                .toolbar { if !discard { ToolbarItem(placement: .cancellationAction) { Button("取消") { if dirty { discard = true } else { app.courseEdit = nil } }.disabled(busy) }; ToolbarItem(placement: .confirmationAction) { Button(context.deleteOnly ? "删除这 \(deletingWeeks.count) 次安排" : "保存", role: context.deleteOnly ? .destructive : nil) { busy = true; Task { if !(await app.saveCourse(context, draft: draft, weeks: deletingWeeks)) { error = app.message; app.message = nil }; busy = false } }.disabled(!valid || busy || (context.course != nil && !context.deleteOnly && !dirty)) } } }
        }.interactiveDismissDisabled(dirty || busy)
    }
}

struct ExamEditor: View {
    @Environment(AppState.self) var app
    let edit: ExamEdit
    @State private var fields: ExamFields
    private let original: ExamFields
    @State private var busy = false
    @State private var discard = false
    @State private var deleteConfirm = false
    @State private var error: String?
    init(edit: ExamEdit) { self.edit = edit; let fields = edit.record?.fields ?? ExamFields(name: "", start: ShanghaiClock.format(Date(), "yyyy-MM-dd HH:mm")); self.original = fields; _fields = State(initialValue: fields) }
    private func normalized(_ fields: ExamFields) -> ExamFields { var result = fields; result.name = fields.name.trimmed; result.location = fields.location.trimmed; result.note = fields.note.trimmed; return result }
    private var dirty: Bool { normalized(fields) != normalized(original) }
    var body: some View {
        NavigationStack {
            Group {
                if discard { DiscardEdits(resume: { discard = false }, discard: { app.examEdit = nil }) }
                else {
                    Form {
                        Section { TextField("考试名称", text: $fields.name); TextField("地点（可选）", text: $fields.location); TextField("备注（可选）", text: $fields.note, axis: .vertical).lineLimit(3...8) }
                        Section("考试时间（上海时间）") {
                            DatePicker("开始", selection: Binding(get: { ShanghaiClock.examTime(fields.start) ?? Date() }, set: { fields.start = ShanghaiClock.format($0, "yyyy-MM-dd HH:mm") })).environment(\.timeZone, ShanghaiClock.timezone)
                            if ShanghaiClock.examTime(fields.start) == nil { Text("学校原始开始时间：" + fields.start).font(.caption).foregroundStyle(.secondary) }
                            Toggle("填写结束时间", isOn: Binding(get: { !fields.end.isEmpty }, set: { fields.end = $0 ? fields.start : "" }))
                            if !fields.end.isEmpty { DatePicker("结束", selection: Binding(get: { ShanghaiClock.examTime(fields.end) ?? ShanghaiClock.examTime(fields.start) ?? Date() }, set: { fields.end = ShanghaiClock.format($0, "yyyy-MM-dd HH:mm") })).environment(\.timeZone, ShanghaiClock.timezone); if ShanghaiClock.examTime(fields.end) == nil { Text("学校原始结束时间：" + fields.end).font(.caption) } }
                        }
                        if let record = edit.record {
                            Section { if record.original != nil { Button("恢复学校原始内容") { save(restore: true) } }; Button(record.original != nil && !record.retained ? "隐藏这场考试" : "删除这场考试", role: .destructive) { deleteConfirm = true } }
                        }
                        if let error { Section { Text(error).foregroundStyle(.red); Button("重新读取资料") { Task { do { app.academic = try await app.academicStore.load(); error = "已重新读取；请关闭本草稿后重新打开再保存，草稿仍保留在此。" } catch { self.error = safeMessage(error) } } } } }
                    }.disabled(busy)
                }
            }.navigationTitle(edit.record == nil ? "添加考试" : "编辑考试").navigationBarTitleDisplayMode(.inline)
                .toolbar { if !discard { ToolbarItem(placement: .cancellationAction) { Button("取消") { if dirty { discard = true } else { app.examEdit = nil } }.disabled(busy) }; ToolbarItem(placement: .confirmationAction) { Button("保存") { save() }.disabled(busy || fields.name.trimmed.isEmpty || (edit.record != nil && !dirty)) } } }
                .confirmationDialog("确定处理这场考试？", isPresented: $deleteConfirm, titleVisibility: .visible) { Button(edit.record?.original != nil && edit.record?.retained != true ? "隐藏考试" : "删除考试", role: .destructive) { save(delete: true) } }
        }.interactiveDismissDisabled(dirty || busy)
    }
    private func save(delete: Bool = false, restore: Bool = false) { busy = true; Task { if !(await app.saveExam(edit, fields: fields, delete: delete, restore: restore)) { error = app.message; app.message = nil }; busy = false } }
}
