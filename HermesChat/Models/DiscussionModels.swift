import Foundation
import SwiftUI

/// Deep think 토론의 진행 단계
enum DiscussionPhase: Equatable {
    case setup
    case running(round: Int, totalRounds: Int)
    /// 사회자가 최종 결론을 작성하는 중
    case concluding
    /// saved=false 는 사용자가 중단한 토론 — 로컬 보관하지 않는다
    case finished(saved: Bool)
    case failed(String)

    var isActive: Bool {
        switch self {
        case .running, .concluding: return true
        default: return false
        }
    }
}

/// 토론룸 타임라인의 항목 하나 (발언 / 라운드 구분선 / 시스템 알림 / 최종 결론)
struct DiscussionEntry: Identifiable, Codable, Equatable {
    enum Kind: String, Codable {
        case statement, roundMarker, system, conclusion
    }

    let id: UUID
    var kind: Kind
    var round: Int?
    /// statement/conclusion 일 때 발언자 프로필명
    var speakerName: String
    /// 참가 순서 기반 팔레트 인덱스 (DiscussionPalette)
    var colorIndex: Int
    /// 스트리밍 중 누적되고, 완료 시 strippingThink 정리본으로 교체된다
    var content: String
    var createdAt: Date

    init(
        id: UUID = UUID(),
        kind: Kind,
        round: Int? = nil,
        speakerName: String = "",
        colorIndex: Int = 0,
        content: String = "",
        createdAt: Date = .now
    ) {
        self.id = id
        self.kind = kind
        self.round = round
        self.speakerName = speakerName
        self.colorIndex = colorIndex
        self.content = content
        self.createdAt = createdAt
    }
}

/// 발언자 구분 색상 — 참가 순서 % count 로 배정
enum DiscussionPalette {
    static let colors: [Color] = [.blue, .green, .orange, .purple, .pink, .teal, .red]

    static func color(at index: Int) -> Color {
        colors[((index % colors.count) + colors.count) % colors.count]
    }
}

/// 완료된 토론의 로컬 보관본
struct SavedDiscussion: Identifiable, Codable, Equatable {
    let id: UUID
    var topic: String
    var date: Date
    var participantNames: [String]
    var rounds: Int
    var moderatorName: String
    /// 결론 entry 포함 전체 타임라인
    var entries: [DiscussionEntry]
}

/// 완료 토론 보관소 — UserDefaults JSON, 최신순 최대 20건
enum DiscussionStore {
    static let storageKey = "deepThinkDiscussions"
    static let maxCount = 20

    static func load() -> [SavedDiscussion] {
        guard let data = UserDefaults.standard.data(forKey: storageKey),
              let decoded = try? JSONDecoder().decode([SavedDiscussion].self, from: data)
        else { return [] }
        return decoded
    }

    static func save(_ discussion: SavedDiscussion) {
        var all = load()
        all.removeAll { $0.id == discussion.id }
        all.insert(discussion, at: 0)
        if all.count > maxCount { all = Array(all.prefix(maxCount)) }
        persist(all)
    }

    static func delete(id: UUID) {
        var all = load()
        all.removeAll { $0.id == id }
        persist(all)
    }

    private static func persist(_ discussions: [SavedDiscussion]) {
        if let data = try? JSONEncoder().encode(discussions) {
            UserDefaults.standard.set(data, forKey: storageKey)
        }
    }
}
