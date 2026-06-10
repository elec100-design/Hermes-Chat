import SwiftUI

/// 칸반 상태 6단계 (PLAN §3 Phase 6 스키마의 status enum과 일치)
enum KanbanStatus: String, Codable, CaseIterable, Identifiable {
    case triage
    case todo
    case ready
    case inProgress = "in_progress"
    case blocked
    case done

    var id: String { rawValue }

    var displayName: String {
        switch self {
        case .triage: return "Triage"
        case .todo: return "To Do"
        case .ready: return "Ready"
        case .inProgress: return "In Progress"
        case .blocked: return "Blocked"
        case .done: return "Done"
        }
    }

    var color: Color {
        switch self {
        case .triage: return .gray
        case .todo: return .blue
        case .ready: return .teal
        case .inProgress: return .orange
        case .blocked: return .red
        case .done: return .green
        }
    }

    var previous: KanbanStatus? {
        guard let index = Self.allCases.firstIndex(of: self), index > 0 else { return nil }
        return Self.allCases[index - 1]
    }

    var next: KanbanStatus? {
        guard let index = Self.allCases.firstIndex(of: self),
              index < Self.allCases.count - 1 else { return nil }
        return Self.allCases[index + 1]
    }
}

/// 칸반 작업 1건. 보드 JSON은 Hermes 에이전트도 파일 도구로 직접 수정하므로
/// 누락/이상 필드에 관대하게 디코딩한다 (extension의 init(from:)).
struct KanbanTask: Identifiable, Codable, Equatable {
    var id: String
    var title: String
    var detail: String?
    var status: KanbanStatus
    var assignee: String?
    var sessionId: String?
    var createdAt: String?
    var updatedAt: String?

    enum CodingKeys: String, CodingKey {
        case id, title, detail, status, assignee
        case sessionId = "session_id"
        case createdAt = "created_at"
        case updatedAt = "updated_at"
    }
}

extension KanbanTask {
    init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        id = (try? container.decode(String.self, forKey: .id)) ?? UUID().uuidString
        title = (try? container.decode(String.self, forKey: .title)) ?? "(제목 없음)"
        detail = try? container.decode(String.self, forKey: .detail)
        status = (try? container.decode(KanbanStatus.self, forKey: .status)) ?? .triage
        assignee = try? container.decode(String.self, forKey: .assignee)
        sessionId = try? container.decode(String.self, forKey: .sessionId)
        createdAt = try? container.decode(String.self, forKey: .createdAt)
        updatedAt = try? container.decode(String.self, forKey: .updatedAt)
    }

    /// 새 작업 기본값 (현재 보고 있는 컬럼의 상태로 생성)
    static func new(status: KanbanStatus) -> KanbanTask {
        let now = ISO8601DateFormatter().string(from: .now)
        return KanbanTask(
            id: UUID().uuidString,
            title: "",
            detail: nil,
            status: status,
            assignee: nil,
            sessionId: nil,
            createdAt: now,
            updatedAt: now
        )
    }
}

/// 보드 = `~/.hermes/kanban/<board>.json` 전체
struct KanbanBoard: Codable, Equatable {
    var name: String
    var updatedAt: String?
    var tasks: [KanbanTask]

    enum CodingKeys: String, CodingKey {
        case name, tasks
        case updatedAt = "updated_at"
    }
}

extension KanbanBoard {
    init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        name = (try? container.decode(String.self, forKey: .name)) ?? ""
        updatedAt = try? container.decode(String.self, forKey: .updatedAt)
        tasks = (try? container.decode([KanbanTask].self, forKey: .tasks)) ?? []
    }
}
