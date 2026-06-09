import Foundation

struct Session: Identifiable, Codable, Equatable, Hashable {
    let id: String
    var title: String?
    var preview: String?
    var updatedAt: Date
    var source: String?

    var displayTitle: String {
        if let t = title, !t.isEmpty { return t }
        if let p = preview, !p.isEmpty { return p }
        return "(제목 없음)"
    }
}
