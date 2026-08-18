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

// MARK: - 프롬프트 템플릿

/// 토론 진행에 쓰는 프롬프트 템플릿과 회수 로직. DiscussionOrchestrator가 사용한다.
enum DiscussionPrompts {
    static func participantSystemPrompt(allowTools: Bool) -> String {
        let toolRule = allowTools
            ? "4. 필요하면 도구(웹 검색 등)로 근거를 확인해도 되지만, 답변은 간결하게 유지하세요."
            : "4. 도구(웹 검색, 파일 접근, 명령 실행 등)는 사용하지 말고 보유 지식과 추론만 사용하세요."
        return """
        당신은 여러 AI 에이전트가 참여하는 토론의 참가자입니다. 각 참가자는 서로 다른 모델과 관점을 가지고 있으며, 토론의 목적은 서로의 오류를 교정하고 더 나은 결론에 도달하는 것입니다.

        규칙:
        1. 답변은 한국어로, 핵심 논거 위주로 5~10문장.
        2. 다른 참가자의 의견이 주어지면 동의/반박을 명확히 구분하고 반드시 근거를 제시하세요.
        3. 확실하지 않은 내용은 "추측"임을 명시하고, 모르면 모른다고 답하세요.
        \(toolRule)
        5. 인사말, 자기소개, 결론 요약 같은 군더더기 없이 본론만 말하고, 당신의 평소 페르소나와 관점은 유지하세요.
        """
    }

    static func firstRoundMessage(topic: String, totalRounds: Int) -> String {
        """
        [토론 시작 — 라운드 1/\(totalRounds)]
        주제: \(topic)

        이 주제에 대한 당신의 입장과 핵심 근거를 제시하세요. 다른 참가자들도 동시에 발언합니다. 당신의 고유한 관점과 근거를 우선하세요.
        """
    }

    static func reviewRoundMessage(
        round: Int,
        totalRounds: Int,
        opinions: [(name: String, statement: String)]
    ) -> String {
        let list = opinions.map { "- \($0.name): \($0.statement)" }.joined(separator: "\n")
        return """
        [라운드 \(round)/\(totalRounds) — 상호 검토]
        다른 참가자들의 최신 의견:
        \(list)

        위 의견들을 검토하고 다음을 간결하게 답하세요:
        ① 동의하는 부분 ② 반박하거나 보완할 부분(근거 필수) ③ 당신의 수정된(또는 유지된) 최종 입장.
        """
    }

    static func moderatorMessage(topic: String, transcript: String) -> String {
        """
        [토론 종료 — 사회자 임무]
        당신은 이제 이 토론의 사회자입니다. 아래 전체 토론 기록을 읽고 최종 결론을 작성하세요. 이번 답변은 길이 제한 없이 충실하게 작성해도 됩니다.

        주제: \(topic)

        토론 기록:
        \(transcript)

        다음 형식의 마크다운으로 작성하세요:
        ## 합의점
        ## 이견 (남은 쟁점과 각 측 근거)
        ## 최종 결론 (실행 가능한 권고 포함)
        """
    }

    /// 마지막 user 메시지 뒤에 오는, think 제거 후 내용이 있는 마지막 assistant 발언.
    /// 토론 세션은 앱 전용이므로 이 술어가 "방금 보낸 메시지에 대한 답"과 일치한다.
    /// expectedUserCount: 지금까지 보낸 user 메시지 수 — 방금 보낸 메시지가 아직
    /// 기록되지 않았을 때 직전 턴의 답변을 오인 반환하는 것을 막는다.
    static func missedReply(in messages: [ChatMessage], expectedUserCount: Int) -> String? {
        let userIndices = messages.indices.filter { messages[$0].role == .user }
        guard userIndices.count >= expectedUserCount, let lastUser = userIndices.last else { return nil }
        return messages[messages.index(after: lastUser)...]
            .filter { $0.role == .assistant }
            .compactMap { message -> String? in
                let visible = MarkdownLite.strippingThink(message.content)
                    .trimmingCharacters(in: .whitespacesAndNewlines)
                return visible.isEmpty ? nil : visible
            }
            .last
    }
}
