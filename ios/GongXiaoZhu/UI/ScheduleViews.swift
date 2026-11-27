import SwiftUI
import UIKit

private struct CourseSelection: Identifiable { var id = UUID(); var courses: [Course] }
struct ScheduleHome: View {
    @Environment(AppState.self) var app
    @Environment(\.colorScheme) var scheme
    @Environment(\.accessibilityReduceMotion) var reduceMotion
    @State private var now = Date()
    @State private var weekPicker = false
    @State private var detail: CourseSelection?
    var body: some View {
        VStack(spacing: 10) {
            header
            if let error = app.scheduleError {
                ScrollView { VStack(spacing: 18) {
                    StorageNotice(module: "schedule", text: error)
                    if !app.wholeWeek { examSummary(from: ShanghaiClock.day(now)) }
                }.padding(20) }
            }
            else if let display = app.display {
                if app.wholeWeek {
                    WeekPager(week: app.selectedWeek, count: display.weeks.count, animated: !reduceMotion, change: { app.selectedWeek = $0 }) { week in
                        AnyView(WeekGrid(week: display.weeks[week - 1], select: { detail = CourseSelection(courses: $0) }).environment(app))
                    }
                } else {
                    ScrollView {
                        VStack(spacing: 14) {
                            if let week = app.visibleWeek { today(week) }
                        }.padding(.horizontal, 20).padding(.bottom, 12).frame(maxWidth: 760)
                    }
                }
            } else {
                ScrollView { VStack(spacing: 18) {
                    EmptyCard(title: "把课表装进口袋", detail: "导入研究生课表，或添加自己的课程。资料保存在本机，离线也能查看。", symbol: "calendar.badge.plus")
                    Button("导入课表") { app.open(.schedule) }.buttonStyle(.borderedProminent)
                    Button("添加自定义课程") { Task { await app.editCourse() } }.buttonStyle(.bordered)
                    if !app.wholeWeek { examSummary(from: ShanghaiClock.day(now)) }
                }.padding(20) }
            }
            HStack {
                if app.display != nil { Button("返回本周") { withAnimation(.easeOut(duration: 0.22)) { app.selectToday() } }.buttonStyle(.bordered) }
                Spacer()
                Button { app.setWholeWeek(!app.wholeWeek) } label: {
                    Label(app.wholeWeek ? "今日" : "整周", systemImage: app.wholeWeek ? "list.bullet" : "rectangle.split.3x3")
                        .foregroundStyle(.primary).padding(.horizontal, 14).padding(.vertical, 7)
                        .background((scheme == .dark ? Color(hex: 0x20232B) : .white).opacity(1 - app.appearance.preferences.transparency / 100), in: RoundedRectangle(cornerRadius: 8))
                        .overlay(RoundedRectangle(cornerRadius: 8).strokeBorder(scheme == .dark ? Color.white.opacity(0.14) : Color.black.opacity(0.08), lineWidth: 0.6))
                }.buttonStyle(.plain)
            }.padding(.horizontal, 20).padding(.bottom, 8)
        }.navigationBarHidden(true)
            .onChange(of: weekPicker) { _, value in app.modal("week-picker", value) }
            .onDisappear { app.modal("week-picker", false) }
            .task(id: app.active && app.tab == .schedule && (app.paths[.schedule] ?? []).isEmpty) {
                guard app.active, app.tab == .schedule, (app.paths[.schedule] ?? []).isEmpty else { return }
                while !Task.isCancelled { let previousDay = ShanghaiClock.day(now); now = Date(); if previousDay != ShanghaiClock.day(now) { app.selectToday() }; try? await Task.sleep(for: .seconds(60)) }
            }
            .onReceive(NotificationCenter.default.publisher(for: UIApplication.significantTimeChangeNotification)) { _ in let previousDay = ShanghaiClock.day(now); now = Date(); if previousDay != ShanghaiClock.day(now) { app.selectToday() } }
            .sheet(isPresented: $weekPicker) { NavigationStack { ScrollView { LazyVGrid(columns: [GridItem(.adaptive(minimum: 78))], spacing: 12) { ForEach(1...(app.display?.weeks.count ?? 1), id: \.self) { week in Button("第 \(week) 周") { app.selectedWeek = week; weekPicker = false }.buttonStyle(.borderedProminent).tint(week == app.selectedWeek ? app.appearance.accent : .gray) } }.padding() }.navigationTitle("选择周次").toolbar { Button("完成") { weekPicker = false } } }.presentationDetents([.medium, .large]) }
            .sheet(item: $detail) { selection in CourseDetails(courses: selection.courses).environment(app) }
    }
    private var header: some View {
        VStack(alignment: .leading, spacing: 5) {
            HStack(alignment: .center) {
                Button { if app.display != nil { weekPicker = true } } label: { HStack(alignment: .firstTextBaseline, spacing: 8) { ZStack(alignment: .leading) { Text("第 60 周").hidden().accessibilityHidden(true); Text(app.display == nil ? "课表" : "第 \(app.selectedWeek) 周") }.font(.system(.largeTitle, design: .rounded, weight: .bold)).lineLimit(2); if app.display != nil { Image(systemName: "chevron.down").font(.caption) } }.foregroundStyle(.primary).frame(minHeight: 55, alignment: .leading) }
                Spacer(minLength: 8)
                BrandLoader(size: 25).opacity(app.scheduleBusy ? 1 : 0).accessibilityHidden(!app.scheduleBusy)
                Menu {
                    Button("添加自定义课程", systemImage: "plus") { Task { await app.editCourse() } }
                    Button("导入／管理课表", systemImage: "arrow.triangle.2.circlepath") { app.open(.schedule) }
                } label: { Image(systemName: "plus").font(.title3).frame(width: 44, height: 44).background(.white.opacity(0.5), in: Circle()) }.accessibilityLabel("课表操作")
            }
            TimelineView(.periodic(from: .now, by: 60)) { context in Text(ShanghaiClock.format(context.date, "M 月 d 日 EEEE")).font(.subheadline).foregroundStyle(.secondary).frame(maxWidth: .infinity, alignment: .leading) }
            ZStack(alignment: .topLeading) { Text("校园网连接状态\n已为连接提示预留位置").lineLimit(2).hidden().accessibilityHidden(true); HStack(alignment: .top) { Text(app.campusMessage).lineLimit(2); BrandLoader(size: 16, motion: .signal).opacity(app.campusRunning ? 1 : 0).accessibilityHidden(!app.campusRunning) } }.font(.caption).foregroundStyle(.secondary).frame(minHeight: 32, alignment: .topLeading)
        }.padding(.horizontal, 22).padding(.top, 10)
    }
    @ViewBuilder private func today(_ week: ScheduleWeek) -> some View {
        let weekday = ShanghaiClock.weekday(ShanghaiClock.day(now)) ?? 1
        let date = week.dates[weekday - 1]
        let courses = week.courses.filter { $0.date == date }.sorted { ($0.lessons.first ?? 0) < ($1.lessons.first ?? 0) }
        HStack { Text(date == ShanghaiClock.day() ? "今日课程" : date).font(.headline); Spacer(); Button { app.selectedWeek = max(1, app.selectedWeek - 1) } label: { Image(systemName: "chevron.left").frame(width: 35, height: 35) }.disabled(app.selectedWeek <= 1); Button { app.selectedWeek = min(app.display?.weeks.count ?? 1, app.selectedWeek + 1) } label: { Image(systemName: "chevron.right").frame(width: 35, height: 35) }.disabled(app.selectedWeek >= (app.display?.weeks.count ?? 1)) }
        if courses.isEmpty { EmptyCard(title: "今天没有课程", detail: "留一点时间给自己。", symbol: "sun.max") }
        ForEach(courses, id: \.occurrenceKey) { course in
            Button { detail = CourseSelection(courses: [course]) } label: {
                HStack(alignment: .top, spacing: 14) {
                    VStack(alignment: .leading, spacing: 4) { Text(LessonTimes.start(course.lessons.min() ?? 0) ?? "—").font(.headline.monospacedDigit()); Text(LessonTimes.end(course.lessons.max() ?? 0) ?? "—").font(.caption.monospacedDigit()).foregroundStyle(.secondary) }.frame(width: 55, alignment: .leading).padding(.top, 14)
                    AppCard { VStack(alignment: .leading, spacing: 8) { Text(course.name).font(.headline); if !course.location.isEmpty { Label(course.location, systemImage: "mappin").font(.subheadline).foregroundStyle(.secondary) }; Text(LessonTimes.label(course.lessons)).font(.caption).foregroundStyle(.secondary); liveCourse(course) } }
                }.foregroundStyle(.primary)
            }.buttonStyle(.plain)
        }
        examSummary(from: date)
    }
    @ViewBuilder private func examSummary(from day: String) -> some View {
        if app.loaded, app.academicError == nil,
           let overview = UpcomingExamOverview.make(records: app.academic.exams, from: day, now: now) {
            AppCard {
                VStack(spacing: 0) {
                    ForEach(Array(overview.exams.enumerated()), id: \.element.id) { item in
                        if item.offset > 0 { Divider().padding(.leading, 48) }
                        UpcomingExamRow(exam: item.element, viewingDay: day, index: item.offset) {
                            app.open(.exams)
                        }
                    }
                }
                .accessibilityIdentifier("upcoming_exams")
            }
        }
    }
    @ViewBuilder private func liveCourse(_ course: Course) -> some View {
        let intervals = course.lessons.compactMap { lesson -> (Date, Date)? in
            guard let start = LessonTimes.start(lesson), let end = LessonTimes.end(lesson), let first = ShanghaiClock.parse(course.date + " " + start, "yyyy-MM-dd HH:mm"), let last = ShanghaiClock.parse(course.date + " " + end, "yyyy-MM-dd HH:mm") else { return nil }; return (first, last)
        }
        if let current = intervals.first(where: { now >= $0.0 && now <= $0.1 }) {
            ProgressView(value: now.timeIntervalSince(current.0), total: current.1.timeIntervalSince(current.0)).tint(app.appearance.accent)
            Text("正在上课 · 本节还剩 \(max(1, Int(ceil(current.1.timeIntervalSince(now) / 60)))) 分钟").font(.caption).foregroundStyle(app.appearance.accent)
        }
    }
}

private struct UpcomingExamRow: View {
    @Environment(AppState.self) private var app
    @Environment(\.dynamicTypeSize) private var typeSize
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var appeared = false
    let exam: UpcomingExam
    let viewingDay: String
    let index: Int
    let open: () -> Void

    private var primary: Bool { index == 0 }
    private var tone: Color { primary ? app.appearance.accent : .secondary }

    var body: some View {
        Button(action: open) {
            HStack(spacing: 10) {
                Image(systemName: "doc.text")
                    .font(.system(size: 16, weight: .medium))
                    .foregroundStyle(tone)
                    .frame(width: 38, height: 38)
                    .background(tone.opacity(primary ? 0.12 : 0.08), in: Circle())
                    .accessibilityHidden(true)
                if typeSize >= .xxLarge {
                    stackedDetails
                } else {
                    ViewThatFits(in: .horizontal) {
                        HStack(spacing: 10) { details; badge.fixedSize() }
                        stackedDetails
                    }
                }
                Image(systemName: "chevron.right").font(.caption.weight(.semibold))
                    .foregroundStyle(.tertiary).accessibilityHidden(true)
            }
            .padding(.vertical, 8)
            .frame(maxWidth: .infinity, minHeight: 52, alignment: .leading)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityIdentifier("upcoming_exam_" + exam.id)
        .accessibilityHint("查看全部考试")
        .opacity(reduceMotion || appeared ? 1 : 0)
        .offset(y: reduceMotion || appeared ? 0 : 6)
        .animation(reduceMotion ? nil : .easeOut(duration: 0.24).delay(Double(index) * 0.045), value: appeared)
        .onAppear { appeared = true }
    }

    private var details: some View {
        let time = exam.dateTimeLabel(viewingDay: viewingDay)
        return VStack(alignment: .leading, spacing: 4) {
            Text(exam.record.fields.name)
                .font(.subheadline.weight(primary ? .semibold : .regular))
                .foregroundStyle(.primary)
            Text(time).font(.caption).foregroundStyle(.secondary)
                .contentTransition(reduceMotion ? .identity : .opacity)
                .animation(reduceMotion ? nil : .easeOut(duration: 0.15), value: time)
        }
        .fixedSize(horizontal: false, vertical: true)
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    private var stackedDetails: some View {
        VStack(alignment: .leading, spacing: 4) { details; badge }
    }

    private var badge: some View {
        let countdown = exam.countdownLabel(viewingDay: viewingDay)
        return Text(countdown)
            .font(.caption.weight(.medium).monospacedDigit())
            .foregroundStyle(tone)
            .fixedSize(horizontal: false, vertical: true)
            .padding(.horizontal, 9).padding(.vertical, 4)
            .background(tone.opacity(primary ? 0.12 : 0.08), in: Capsule())
            .contentTransition(reduceMotion ? .identity : .numericText())
            .animation(reduceMotion ? nil : .easeOut(duration: 0.15), value: countdown)
    }
}

struct WeekGrid: View {
    @Environment(AppState.self) var app
    @Environment(\.dynamicTypeSize) var typeSize
    @ScaledMetric(relativeTo: .body) private var rowHeight = 58.0
    let week: ScheduleWeek
    let select: ([Course]) -> Void
    private let names = ["一", "二", "三", "四", "五", "六", "日"]
    var body: some View {
        GeometryReader { geometry in
            let width = max(geometry.size.width - 16, typeSize.isAccessibilitySize ? 650 : 0)
            let column = (width - 42) / 7
            let rows = max(12, week.courses.flatMap(\.lessons).max() ?? 12)
            ScrollView([.vertical, typeSize.isAccessibilitySize ? .horizontal : .vertical]) {
                VStack(spacing: 0) {
                    HStack(spacing: 0) {
                        Text("\(String(week.dates[0].dropFirst(5).prefix(2)))月").font(.caption2).frame(width: 42)
                        ForEach(0..<7) { index in VStack(spacing: 5) { Text(names[index]).font(.subheadline.weight(.medium)); Text(String(week.dates[index].suffix(2))).font(.caption2.monospacedDigit()) }.foregroundStyle(week.dates[index] == ShanghaiClock.day() ? app.appearance.accent : .primary).frame(width: column).padding(.vertical, 10) }
                    }
                    ZStack(alignment: .topLeading) {
                        HStack(alignment: .top, spacing: 0) {
                            VStack(spacing: 0) { ForEach(1...rows, id: \.self) { lesson in VStack(spacing: 3) { Text(LessonTimes.start(lesson) ?? "").font(.system(size: 8)); Text("\(lesson)").font(.caption.weight(.semibold)); Text(LessonTimes.end(lesson) ?? "").font(.system(size: 8)) }.foregroundStyle(.secondary).frame(width: 42, height: rowHeight) } }
                            ForEach(0..<7) { _ in Rectangle().fill(.primary.opacity(0.025)).frame(width: column - 1).overlay(alignment: .trailing) { Rectangle().fill(.primary.opacity(0.04)).frame(width: 0.5) }.padding(.trailing, 1) }
                        }.frame(height: CGFloat(rows) * rowHeight)
                        ForEach(courseBlocks(week)) { block in
                            Button { select(block.courses) } label: {
                                VStack(spacing: 4) { Text(block.courses[0].name).font(.system(size: 12, weight: .medium)).multilineTextAlignment(.center); if !block.courses[0].location.isEmpty { Text(block.courses[0].location).font(.system(size: 10)).multilineTextAlignment(.center) }; if block.courses.count > 1 { Text("共 \(block.courses.count) 门").font(.system(size: 9)) } }.padding(4).frame(width: max(1, column - 4), height: max(1, CGFloat(block.last - block.first + 1) * rowHeight - 4)).foregroundStyle(Color(hex: 0x26364A)).background(courseColor(block.courses[0]), in: RoundedRectangle(cornerRadius: 8)).clipped()
                            }.buttonStyle(.plain).offset(x: 42 + CGFloat(block.day) * column + 2, y: CGFloat(block.first - 1) * rowHeight + 2)
                        }
                    }
                }.frame(width: width).padding(.horizontal, 8)
            }
        }
    }
    private func courseColor(_ course: Course) -> Color { let palette: [UInt32] = [0xD8E6F5, 0xE4DCF4, 0xD8EDE1, 0xF5DED8, 0xF2E8CC, 0xD7EBED]; let hash = course.name.unicodeScalars.reduce(0) { ($0 + Int($1.value)) % palette.count }; return Color(hex: palette[hash]) }
}

/// Rebase the three native pages without losing the rendered position when a new drag interrupts settling.
struct WeekPager: UIViewControllerRepresentable {
    var week: Int
    var count: Int
    var animated = true
    var change: (Int) -> Void
    var content: (Int) -> AnyView
    func makeUIViewController(context: Context) -> PagerController { let controller = PagerController(); controller.owner = self; return controller }
    func updateUIViewController(_ controller: PagerController, context: Context) { controller.owner = self; controller.refreshIfNeeded() }
    final class PagerController: UIViewController, UIScrollViewDelegate {
        var owner: WeekPager!
        let scroll = UIScrollView()
        var hosts: [UIHostingController<AnyView>] = []
        var base = 0
        var pending: Int?
        var programmaticGoal: Int?
        var oldWidth: CGFloat = 0
        override func viewDidLoad() {
            super.viewDidLoad(); view.backgroundColor = .clear; scroll.backgroundColor = .clear; scroll.delegate = self; scroll.isPagingEnabled = true; scroll.showsHorizontalScrollIndicator = false; scroll.alwaysBounceHorizontal = false; scroll.isDirectionalLockEnabled = true; scroll.contentInsetAdjustmentBehavior = .never; view.addSubview(scroll)
            for _ in 0..<3 { let host = UIHostingController(rootView: AnyView(EmptyView())); host.view.backgroundColor = .clear; addChild(host); scroll.addSubview(host.view); host.didMove(toParent: self); hosts.append(host) }
        }
        override func viewDidLayoutSubviews() {
            super.viewDidLayoutSubviews(); scroll.frame = view.bounds
            let width = view.bounds.width; guard width > 0 else { return }
            scroll.contentSize = CGSize(width: width * 3, height: view.bounds.height)
            for (index, host) in hosts.enumerated() { host.view.frame = CGRect(x: CGFloat(index) * width, y: 0, width: width, height: view.bounds.height) }
            if oldWidth != width { oldWidth = width; pending = nil; base = owner.week; fill(); scroll.setContentOffset(CGPoint(x: width, y: 0), animated: false) }
            else { refreshIfNeeded() }
        }
        func fill() { guard hosts.count == 3 else { return }; for (index, host) in hosts.enumerated() { let number = base + index - 1; host.rootView = (1...owner.count).contains(number) ? owner.content(number) : AnyView(Color.clear) } }
        func refreshIfNeeded() {
            guard isViewLoaded, oldWidth > 0 else { return }
            if programmaticGoal != nil && programmaticGoal != owner.week { programmaticGoal = owner.week }
            if base == 0 { base = owner.week; fill() }
            else if base != owner.week && !scroll.isDragging && programmaticGoal == nil && pending == nil {
                if owner.animated { programmaticGoal = owner.week; stepToGoal() }
                else { base = owner.week; fill(); scroll.setContentOffset(CGPoint(x: oldWidth, y: 0), animated: false) }
            }
            else if pending == nil && !scroll.isDragging && !scroll.isDecelerating { fill() }
        }
        func rebase() {
            guard let target = pending else { return }; let offset = scroll.contentOffset.x - CGFloat(target - base) * oldWidth
            base = target; pending = nil; fill(); scroll.setContentOffset(CGPoint(x: offset, y: 0), animated: false)
        }
        func scrollViewWillBeginDragging(_ scrollView: UIScrollView) { rebase(); programmaticGoal = nil; owner.change(base) }
        func stepToGoal() { guard let goal = programmaticGoal, goal != base else { programmaticGoal = nil; return }; pending = base + (goal > base ? 1 : -1); scroll.setContentOffset(CGPoint(x: CGFloat(pending! - base + 1) * oldWidth, y: 0), animated: true) }
        func scrollViewDidEndScrollingAnimation(_ scrollView: UIScrollView) { rebase(); scroll.setContentOffset(CGPoint(x: oldWidth, y: 0), animated: false); if programmaticGoal == base { programmaticGoal = nil } else { stepToGoal() } }
        func scrollViewWillEndDragging(_ scrollView: UIScrollView, withVelocity velocity: CGPoint, targetContentOffset: UnsafeMutablePointer<CGPoint>) {
            let delta = scrollView.contentOffset.x - oldWidth
            let direction = abs(delta) > oldWidth * 0.18 || abs(velocity.x) > 0.3 ? (delta == 0 ? (velocity.x > 0 ? 1 : -1) : (delta > 0 ? 1 : -1)) : 0
            let target = min(owner.count, max(1, base + direction)); pending = target
            targetContentOffset.pointee.x = CGFloat(target - base + 1) * oldWidth; owner.change(target)
        }
        func scrollViewDidEndDecelerating(_ scrollView: UIScrollView) { rebase(); scrollView.setContentOffset(CGPoint(x: oldWidth, y: 0), animated: false) }
        func scrollViewDidEndDragging(_ scrollView: UIScrollView, willDecelerate decelerate: Bool) { if !decelerate { scrollViewDidEndDecelerating(scrollView) } }
    }
}

struct CourseDetails: View {
    @Environment(AppState.self) var app
    @Environment(\.dismiss) var dismiss
    let courses: [Course]
    var body: some View {
        NavigationStack {
            ScrollView { VStack(spacing: 18) { ForEach(courses, id: \.occurrenceKey) { course in AppCard { VStack(alignment: .leading, spacing: 14) { Text(course.name).font(.title2.bold()); Text(course.date + " · " + LessonTimes.label(course.lessons)).font(.subheadline); if !course.location.isEmpty { Label(course.location, systemImage: "mappin.and.ellipse") }; if !course.teacher.isEmpty { Label(course.teacher, systemImage: "person") }; if !course.weeksText.isEmpty { Text(course.weeksText).foregroundStyle(.secondary) }; HStack { Button("编辑") { open(course, delete: false) }.buttonStyle(.borderedProminent); Button("删除", role: .destructive) { open(course, delete: true) }.buttonStyle(.bordered) } } } } }.padding(20) }.navigationTitle(courses.count > 1 ? "重叠课程" : "课程详情").toolbar { Button("完成") { dismiss() } }
        }.presentationDetents([.medium, .large]).onAppear { app.modal("course-details", true) }.onDisappear { app.modal("course-details", false) }
    }
    private func open(_ course: Course, delete: Bool) { dismiss(); Task { try? await Task.sleep(for: .milliseconds(300)); await app.editCourse(course, deleteOnly: delete) } }
}

struct ScheduleManagementPage: View {
    @Environment(AppState.self) var app
    @State private var confirmUnbind = false
    @State private var confirmDiscard = false
    var body: some View {
        ScrollView { VStack(spacing: 16) {
            if let error = app.scheduleError { StorageNotice(module: "schedule", text: error) }
            else {
                AppCard { VStack(alignment: .leading, spacing: 12) { Text("研究生课表").font(.headline); if let identity = app.schedule.identity { Text(identity.name.isEmpty ? identity.studentID : identity.name + " · " + identity.studentID) }; if let complete = app.schedule.complete { Text(complete.term.title).font(.subheadline); Text("更新于 " + ShanghaiClock.format(complete.updatedAt, "M 月 d 日 HH:mm")).font(.caption).foregroundStyle(.secondary) }; if !app.scheduleStatus.isEmpty { Text(app.scheduleStatus).font(.subheadline) }; if let pending = app.schedule.pending { ProgressView(value: Double(pending.weeks.count), total: Double(pending.term.weekCount)); Text("已保存 \(pending.weeks.count)／\(pending.term.weekCount) 周").font(.caption) }; HStack { if app.scheduleBusy { Button("暂停导入") { app.pauseImport() }.buttonStyle(.bordered).disabled(app.scheduleCommitting) } else { Button(app.schedule.pending == nil ? "登录并导入整学期" : "继续导入") { app.startImport() }.buttonStyle(.borderedProminent).disabled(!app.network.internetAvailable || app.importSession != nil) } }; if app.schedule.pending != nil { Button("放弃未完成导入", role: .destructive) { confirmDiscard = true } }; if app.schedule.identity != nil { Button("解绑学校课表", role: .destructive) { confirmUnbind = true } } } }
                AppCard { VStack(alignment: .leading, spacing: 14) { Text("本地课程与学期").font(.headline); Button("添加自定义课程", systemImage: "plus") { Task { await app.editCourse() } }; Button("建立本地学期", systemImage: "calendar.badge.plus") { Task { await app.editCourse(newTerm: true) } }; if app.schedule.complete != nil { Button("查看学校课表") { Task { await app.selectTerm(nil) } } }; ForEach(app.schedule.localTerms) { term in HStack { Button(term.title) { Task { await app.selectTerm(term.id) } }; Spacer(); if app.schedule.selectedLocalTermID == term.id { Image(systemName: "checkmark") }; Menu { Button("删除本地学期及其课程", role: .destructive) { confirmTermDelete(term) } } label: { Image(systemName: "ellipsis").frame(width: 44, height: 44) } } } } }
                Text("本科课表导入暂不可用").font(.footnote).foregroundStyle(.secondary)
            }
        }.padding(20).frame(maxWidth: 760) }.navigationTitle("课表管理")
            .onChange(of: confirmUnbind || confirmDiscard || deletingTerm != nil) { _, value in app.modal("schedule-confirm", value) }.onDisappear { app.modal("schedule-confirm", false) }
            .confirmationDialog("解绑只清除学校资料，保留自定义课程。继续？", isPresented: $confirmUnbind, titleVisibility: .visible) { Button("解绑", role: .destructive) { Task { await app.unbindSchedule() } } }
            .confirmationDialog("放弃后将清除未完成进度，保留原完整课表。", isPresented: $confirmDiscard, titleVisibility: .visible) { Button("放弃导入", role: .destructive) { Task { await app.clearPending() } } }
            .confirmationDialog("删除该本地学期及全部自定义课程？", isPresented: Binding(get: { deletingTerm != nil }, set: { if !$0 { deletingTerm = nil } }), titleVisibility: .visible, presenting: deletingTerm) { term in Button("删除", role: .destructive) { Task { await app.deleteLocalTerm(term.id) }; deletingTerm = nil } }
    }
    @State private var deletingTerm: LocalTerm?
    private func confirmTermDelete(_ term: LocalTerm) { deletingTerm = term }
}
