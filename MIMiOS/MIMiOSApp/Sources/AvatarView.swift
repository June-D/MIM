import SwiftUI

/// 圆形头像：有图用图，没有就用首字母。
///
/// 首字母取第一个字符（中文就是首字），颜色由用户名哈希决定——
/// 同一个人在任何界面都是同一个颜色，但不暴露任何真实信息。
struct AvatarView: View {
    let name: String
    var image: UIImage?
    var size: CGFloat = 40

    var body: some View {
        Group {
            if let image {
                Image(uiImage: image)
                    .resizable()
                    .scaledToFill()
            } else {
                Text(initial)
                    .font(.system(size: size * 0.42, weight: .semibold))
                    .foregroundStyle(.white)
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
                    .background(color)
            }
        }
        .frame(width: size, height: size)
        .clipShape(Circle())
        .accessibilityLabel(name)
    }

    private var initial: String {
        String(name.prefix(1)).uppercased()
    }

    /// 固定调色板里按哈希取一个：不用随机，保证跨启动一致。
    private var color: Color {
        let palette: [Color] = [
            Color(red: 0.15, green: 0.42, blue: 0.36),
            Color(red: 0.20, green: 0.35, blue: 0.55),
            Color(red: 0.45, green: 0.28, blue: 0.50),
            Color(red: 0.55, green: 0.35, blue: 0.20),
            Color(red: 0.25, green: 0.45, blue: 0.55),
        ]
        var hash = 5381
        for byte in name.utf8 {
            hash = (hash &* 33) &+ Int(byte)
        }
        return palette[abs(hash) % palette.count]
    }
}

/// 会话时间：今天显示时刻、昨天显示「昨天」、更早显示日期。
enum ChatTime {
    static func list(_ epochMs: Int64) -> String {
        let date = Date(timeIntervalSince1970: Double(epochMs) / 1000)
        let calendar = Calendar.current
        if calendar.isDateInToday(date) {
            return formatter("HH:mm").string(from: date)
        }
        if calendar.isDateInYesterday(date) {
            return "昨天"
        }
        if calendar.isDate(date, equalTo: Date(), toGranularity: .year) {
            return formatter("M月d日").string(from: date)
        }
        return formatter("yyyy年M月d日").string(from: date)
    }

    static func clock(_ epochMs: Int64) -> String {
        formatter("HH:mm").string(from: Date(timeIntervalSince1970: Double(epochMs) / 1000))
    }

    /// 聊天页的日期分隔：今天/昨天/具体日期。
    static func dayDivider(_ epochMs: Int64) -> String {
        let date = Date(timeIntervalSince1970: Double(epochMs) / 1000)
        let calendar = Calendar.current
        if calendar.isDateInToday(date) { return "今天" }
        if calendar.isDateInYesterday(date) { return "昨天" }
        if calendar.isDate(date, equalTo: Date(), toGranularity: .year) {
            return formatter("M月d日").string(from: date)
        }
        return formatter("yyyy年M月d日").string(from: date)
    }

    /// 两条消息是否跨天（决定要不要插分隔条）。
    static func isNewDay(_ current: Int64, previous: Int64?) -> Bool {
        guard let previous else { return true }
        let calendar = Calendar.current
        let a = Date(timeIntervalSince1970: Double(current) / 1000)
        let b = Date(timeIntervalSince1970: Double(previous) / 1000)
        return !calendar.isDate(a, inSameDayAs: b)
    }

    private static var cache: [String: DateFormatter] = [:]

    private static func formatter(_ format: String) -> DateFormatter {
        if let cached = cache[format] { return cached }
        let formatter = DateFormatter()
        formatter.dateFormat = format
        cache[format] = formatter
        return formatter
    }
}
