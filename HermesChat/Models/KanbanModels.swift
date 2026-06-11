import SwiftUI

/// hermes-agent 내장 칸반(kanban.db)의 상태값.
/// ready 태스크는 게이트웨이 디스패처가 워커 프로필을 띄워 자동 실행하고,
/// running은 디스패처 소유라서 앱에서 수동 이동을 허용하지 않는다.
enum KanbanStatus: String, Codable, CaseIterable, Identifiable {
    case triage
    case todo
    case scheduled
    case ready
    case running
    case blocked
    case done

    var id: String { rawValue }

    var displayName: String {
        switch self {
        case .triage: return "Triage"
        case .todo: return "To Do"
        case .scheduled: return "Scheduled"
        case .ready: return "Ready"
        case .running: return "Running"
        case .blocked: return "Blocked"
        case .done: return "Done"
        }
    }

    var color: Color {
        switch self {
        case .triage: return .gray
        case .todo: return .blue
        case .scheduled: return .purple
        case .ready: return .teal
        case .running: return .orange
        case .blocked: return .red
        case .done: return .green
        }
    }

    /// 이 상태에서 사용자가 수동으로 실행할 수 있는 전이 (hermes kanban CLI 제약과 일치)
    var actions: [KanbanAction] {
        switch self {
        case .triage: return [.block]
        case .todo: return [.promote, .block, .complete]
        case .scheduled: return [.unblock]
        case .ready: return [.block, .complete]
        case .running: return []
        case .blocked: return [.unblock, .complete, .archive]
        case .done: return [.archive]
        }
    }
}

/// Bridge `POST /kanban/<board>/tasks/<id>/action`의 action 값
enum KanbanAction: String, Identifiable {
    case promote   // todo|blocked → ready (디스패처가 곧 실행)
    case block     // → blocked (보류)
    case unblock   // blocked|scheduled → ready
    case complete  // → done
    case archive   // done|blocked → 보드에서 숨김

    var id: String { rawValue }

    var label: String {
        switch self {
        case .promote: return "실행 (Ready로)"
        case .block: return "보류"
        case .unblock: return "재개 (Ready로)"
        case .complete: return "완료 처리"
        case .archive: return "아카이브"
        }
    }

    var systemImage: String {
        switch self {
        case .promote: return "play.circle"
        case .block: return "pause.circle"
        case .unblock: return "play.circle"
        case .complete: return "checkmark.circle"
        case .archive: return "archivebox"
        }
    }

    var isDestructive: Bool { self == .archive }
}

/// 칸반 작업 1건 (Bridge가 kanban.db 행을 이 스키마로 매핑해서 내려준다)
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
}

/// `GET /kanban` 목록의 한 항목 — 보드 slug + 표시명 + 상태별 카운트
struct KanbanBoardSummary: Identifiable, Codable, Equatable {
    let board: String
    let name: String
    let counts: [String: Int]

    var id: String { board }

    /// 아카이브 제외 활성 태스크 수
    var activeCount: Int {
        counts.filter { $0.key != "archived" }.values.reduce(0, +)
    }
}

/// `GET /kanban/<board>` 응답 — 보드 1개의 비아카이브 태스크 전체
struct KanbanBoard: Codable, Equatable {
    var name: String
    var board: String
    var updatedAt: String?
    var tasks: [KanbanTask]

    enum CodingKeys: String, CodingKey {
        case name, board, tasks
        case updatedAt = "updated_at"
    }
}

extension KanbanBoard {
    init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        name = (try? container.decode(String.self, forKey: .name)) ?? ""
        board = (try? container.decode(String.self, forKey: .board)) ?? ""
        updatedAt = try? container.decode(String.self, forKey: .updatedAt)
        tasks = (try? container.decode([KanbanTask].self, forKey: .tasks)) ?? []
    }
}

/// 새 태스크 생성 시 초기 상태 선택지 (Bridge POST status 필드와 일치)
enum KanbanCreateMode: String, CaseIterable, Identifiable {
    case ready    // 곧바로 디스패처가 실행
    case triage   // 스페시파이어가 스펙을 구체화한 뒤 진행
    case blocked  // 보류 — 사람이 재개할 때까지 대기

    var id: String { rawValue }

    var label: String {
        switch self {
        case .ready: return "바로 실행"
        case .triage: return "구체화 후 실행"
        case .blocked: return "보류"
        }
    }

    var footnote: String {
        switch self {
        case .ready: return "1분 내에 담당 프로필 워커가 작업을 시작합니다."
        case .triage: return "스펙 구체화 에이전트가 내용을 다듬은 뒤 실행됩니다."
        case .blocked: return "보드에 보관만 하고 실행하지 않습니다. Blocked 컬럼에서 재개할 수 있습니다."
        }
    }
}
