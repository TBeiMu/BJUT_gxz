import Foundation

enum ShanghaiClock {
    static let timezone = TimeZone(identifier: "Asia/Shanghai")!
    static var calendar: Calendar {
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = timezone
        calendar.firstWeekday = 2
        return calendar
    }
    static func format(_ date: Date, _ pattern: String) -> String {
        let formatter = DateFormatter()
        formatter.calendar = calendar
        formatter.locale = Locale(identifier: "zh_CN")
        formatter.timeZone = timezone
        formatter.dateFormat = pattern
        return formatter.string(from: date)
    }
    static func parse(_ text: String, _ pattern: String = "yyyy-MM-dd") -> Date? {
        let formatter = DateFormatter()
        formatter.calendar = calendar
        formatter.locale = Locale(identifier: "en_US_POSIX")
        formatter.timeZone = timezone
        formatter.dateFormat = pattern
        formatter.isLenient = false
        return formatter.date(from: text)
    }
    static func day(_ date: Date = Date()) -> String { format(date, "yyyy-MM-dd") }
    static func addDays(_ days: Int, to text: String) -> String? {
        guard let date = parse(text), let result = calendar.date(byAdding: .day, value: days, to: date) else { return nil }
        return day(result)
    }
    static func days(from first: String, to last: String) -> Int? {
        guard let first = parse(first), let last = parse(last) else { return nil }
        return calendar.dateComponents([.day], from: first, to: last).day
    }
    static func weekday(_ day: String) -> Int? {
        guard let date = parse(day) else { return nil }
        return (calendar.component(.weekday, from: date) + 5) % 7 + 1
    }
    static func monday(_ date: Date = Date()) -> String {
        let weekday = (calendar.component(.weekday, from: date) + 5) % 7
        return day(calendar.date(byAdding: .day, value: -weekday, to: date)!)
    }
    static func examTime(_ text: String) -> Date? {
        for pattern in ["yyyy-MM-dd'T'HH:mm:ss", "yyyy-MM-dd'T'HH:mm", "yyyy-MM-dd H:mm:ss", "yyyy-MM-dd H:mm", "yyyy/M/d H:mm:ss", "yyyy/M/d H:mm"] {
            if let date = parse(text, pattern) { return date }
        }
        return nil
    }
    static func greeting(_ date: Date) -> String {
        let hour = calendar.component(.hour, from: date)
        let starts = [0, 5, 8, 11, 13, 17, 19, 23]
        let phrases = [
            ["世界安静下来，梦还在赶路。", "愿你熬过的夜，都有星光回应。", "夜很深了，先把心事放一放，好好休息。"],
            ["新的一天，从第一缕光开始。", "把昨天归零，把今天点亮。", "早起的人，先拥抱今天的风。"],
            ["把热爱放进日程，把行动交给现在。", "专注当下，答案会在路上。", "上午适合出发，也适合把小事做好。"],
            ["忙里偷闲，也要好好吃饭。", "太阳正好，给自己一点松弛。", "暂停一下，是为了下午走得更稳。"],
            ["保持热爱，奔赴山河。", "一半清醒，一半温柔。", "慢慢来，下午的光也在替你加油。"],
            ["落日归山海，烟火向星辰。", "今天辛苦了，晚风会替你松绑。", "把疲惫留在黄昏，把温柔带回家。"],
            ["把烦恼交给夜色，把期待留给明天。", "灯亮着，生活就有回声。", "夜晚适合收心，也适合悄悄积蓄力量。"],
            ["别急，月亮也在慢慢变圆。", "好好休息，明天继续发光。", "今天到此为止，明天重新开始。"]
        ]
        let period = starts.lastIndex(where: { hour >= $0 }) ?? 0
        let epochDay = days(from: "1970-01-01", to: day(date)) ?? 0
        return phrases[period][((epochDay % 3) + 3) % 3]
    }
}

enum LessonTimes {
    static let values = [("08:00", "08:45"), ("08:50", "09:35"), ("09:55", "10:40"), ("10:45", "11:30"),
                         ("13:30", "14:15"), ("14:20", "15:05"), ("15:25", "16:10"), ("16:15", "17:00"),
                         ("18:00", "18:45"), ("18:50", "19:35"), ("19:55", "20:40"), ("20:45", "21:30")]
    static func start(_ lesson: Int) -> String? { (1...12).contains(lesson) ? values[lesson - 1].0 : nil }
    static func end(_ lesson: Int) -> String? { (1...12).contains(lesson) ? values[lesson - 1].1 : nil }
    static func label(_ lessons: [Int]) -> String {
        var groups: [[Int]] = []
        for lesson in Set(lessons).sorted() {
            if let last = groups.last?.last, last + 1 == lesson { groups[groups.count - 1].append(lesson) }
            else { groups.append([lesson]) }
        }
        return "第 " + groups.map { $0.count == 1 ? String($0[0]) : "\($0[0])–\($0.last!)" }.joined(separator: "、") + " 节"
    }
}

enum ShuttleDayType: String, Sendable {
    case workday, weekend
    var suffix: String { self == .workday ? "工作" : "周末" }
    var title: String { "校车时刻表（\(suffix)）" }
    var other: ShuttleDayType { self == .workday ? .weekend : .workday }
    static func at(_ date: Date) -> ShuttleDayType {
        let weekday = ShanghaiClock.calendar.component(.weekday, from: date)
        return weekday == 1 || weekday == 7 ? .weekend : .workday
    }
}

struct ShuttleDeparture: Sendable {
    let route: ShuttleRoute
    let time: String
}

struct ShuttleRoute: Identifiable, Sendable {
    var id: String
    var period: String
    var origin: String
    var departures: [String]
    var stops: [String]
    var operation: String? = nil
    // User-supplied natural-weekday plans, without inferred holiday adjustments.
    private static let workdayRoutes = [
        ShuttleRoute(id: "morning", period: "早间", origin: "中蓝校区",
            departures: ["07:20", "07:30", "07:35", "07:45", "08:00", "08:15", "08:30", "08:40", "08:50", "09:00", "09:10", "09:20", "09:30", "09:40", "09:50", "10:00", "10:15"],
            stops: ["中蓝校区西北门", "北工大东南门", "北工大南门", "松榆东里东门（地铁14号线）", "北工大西门"], operation: "校外运营"),
        ShuttleRoute(id: "evening", period: "晚间", origin: "月亮湖西",
            departures: ["19:45", "20:00", "20:15", "20:30", "20:45", "21:00", "21:10", "21:20", "21:30", "21:40", "21:50", "22:00", "22:10", "22:20", "22:30"],
            stops: ["月亮湖西", "逸夫图书馆北", "第二教学楼西门", "中蓝校区"], operation: "校内运营")
    ]
    private static let weekendRoutes = [
        ShuttleRoute(id: "morning", period: "早间", origin: "中蓝校区",
            departures: ["08:00", "08:30", "09:00", "09:30", "10:00"],
            stops: ["中蓝校区", "月亮湖", "逸夫图书馆", "北工大西门"]),
        ShuttleRoute(id: "evening", period: "晚间", origin: "月亮湖",
            departures: ["20:00", "20:30", "21:00", "21:30", "22:00"],
            stops: ["月亮湖", "逸夫图书馆", "北工大西门", "中蓝校区"])
    ]
    static func routes(for type: ShuttleDayType) -> [ShuttleRoute] {
        type == .workday ? workdayRoutes : weekendRoutes
    }
    static func timeLabel(_ time: String) -> String {
        time.hasPrefix("0") ? String(time.dropFirst()) : time
    }
    static func next(_ date: Date) -> ShuttleDeparture? {
        let minute = ShanghaiClock.format(date, "HH:mm")
        return routes(for: .at(date)).flatMap { route in
            route.departures.filter { $0 >= minute }.map { ShuttleDeparture(route: route, time: $0) }
        }.min { $0.time < $1.time }
    }
    static func status(_ date: Date, viewing type: ShuttleDayType) -> String {
        guard type == .at(date) else { return "非今日时刻表" }
        guard let departure = next(date) else { return "今日班次已结束" }
        return "下一班预计 \(timeLabel(departure.time)) · \(departure.route.origin)发车"
    }
}
