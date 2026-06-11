import Foundation
import SwiftUI

/// Deep think 토론 오케스트레이터.
///
/// 앱이 사회 역할을 맡는 클라이언트 사이드 설계: 참가 프로필마다 해당 게이트웨이에
/// 전용 세션을 만들고, 라운드마다 순차로 발언을 받아 다른 참가자의 발언을 다음 턴
/// 메시지에 실어 중계한다. 마지막에는 사회자 참가자의 세션에 전체 기록을 보내
/// 최종 결론(합의점/이견/결론)을 받는다.
@MainActor
final class DiscussionViewModel: ObservableObject {
    // MARK: 설정 상태
    @Published var selectedProfileIDs: Set<UUID> = []
    @Published var topic: String = ""
    @Published var rounds: Int = 2
    /// nil이면 첫 참가자가 사회자
    @Published var moderatorID: UUID?
    /// 도구(웹 검색 등) 사용 허용 — 켜면 한 발언이 수 분까지 길어질 수 있다
    @Published var allowTools: Bool = false

    // MARK: 진행 상태
    @Published var phase: DiscussionPhase = .setup
    @Published var entries: [DiscussionEntry] = []
    @Published var currentSpeakerName: String?
    @Published var savedDiscussions: [SavedDiscussion] = DiscussionStore.load()

    let appSettings: AppSettings
    private var runTask: Task<Void, Never>?

    /// 참가자 런타임 상태 (메모리 전용)
    private struct Runtime {
        let profile: HermesProfile
        let client: HermesAPIClient
        let sessionID: String
        let colorIndex: Int
        /// strippingThink 적용된 최신 발언
        var lastStatement: String = ""
        /// 게이트웨이 오류로 탈락하면 false
        var isActive: Bool = true
    }
    private var runtimes: [Runtime] = []

    init(appSettings: AppSettings) {
        self.appSettings = appSettings
    }

    var canStart: Bool {
        !topic.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
            && selectedProfileIDs.count >= 2
    }

    /// 보드와 같은 정렬(port 순)로 선택된 참가 프로필
    var selectedProfiles: [HermesProfile] {
        appSettings.profiles.filter { selectedProfileIDs.contains($0.id) }
    }

    func toggleProfile(_ profile: HermesProfile) {
        if selectedProfileIDs.contains(profile.id) {
            selectedProfileIDs.remove(profile.id)
            if moderatorID == profile.id { moderatorID = nil }
        } else {
            selectedProfileIDs.insert(profile.id)
        }
    }

    func start() {
        guard canStart, !phase.isActive else { return }
        entries = []
        runtimes = []
        phase = .running(round: 1, totalRounds: rounds)
        runTask = Task { await runDiscussion() }
    }

    func stop() {
        runTask?.cancel()
    }

    func resetToSetup() {
        guard !phase.isActive else { return }
        entries = []
        runtimes = []
        currentSpeakerName = nil
        phase = .setup
    }

    func deleteSaved(id: UUID) {
        DiscussionStore.delete(id: id)
        savedDiscussions = DiscussionStore.load()
    }

    /// 결론 entry (있으면)
    var conclusionEntry: DiscussionEntry? {
        entries.last { $0.kind == .conclusion }
    }

    /// 공유용 전체 기록 텍스트
    var shareText: String {
        Self.shareText(topic: topic, entries: entries)
    }

    static func shareText(topic: String, entries: [DiscussionEntry]) -> String {
        var lines = ["Deep think 토론", "주제: \(topic)", ""]
        for entry in entries {
            switch entry.kind {
            case .roundMarker:
                lines.append("=== \(entry.content) ===")
            case .system:
                lines.append("· \(entry.content)")
            case .statement:
                lines.append("[\(entry.speakerName)] \(MarkdownLite.plainText(from: entry.content))")
            case .conclusion:
                lines.append("")
                lines.append("=== 최종 결론 (사회자: \(entry.speakerName)) ===")
                lines.append(MarkdownLite.plainText(from: entry.content))
            }
            lines.append("")
        }
        return lines.joined(separator: "\n")
    }

    // MARK: - 오케스트레이션

    private func runDiscussion() async {
        defer {
            currentSpeakerName = nil
            runTask = nil
        }
        do {
            try await createParticipantSessions()
            guard activeCount >= 2 else {
                phase = .failed("참가자 2명 이상이 연결되어야 토론을 시작할 수 있습니다.")
                return
            }

            for round in 1...rounds {
                phase = .running(round: round, totalRounds: rounds)
                appendEntry(DiscussionEntry(kind: .roundMarker, round: round, content: "라운드 \(round) / \(rounds)"))
                try await runRound(round)
                guard activeCount >= 2 else {
                    phase = .failed("참가자가 모두 이탈하여 토론을 종료합니다.")
                    return
                }
            }

            phase = .concluding
            try await concludeDiscussion()
            // concludeDiscussion이 .failed로 끝냈으면 저장하지 않는다
            if case .failed = phase { return }

            saveCurrentDiscussion()
            phase = .finished(saved: true)
        } catch {
            // speak가 게이트웨이 오류를 탈락으로 흡수하므로 보통 취소(중지 버튼)만 온다
            if error is CancellationError || Task.isCancelled {
                handleCancellation()
            } else {
                phase = .failed(error.localizedDescription)
            }
        }
    }

    private var activeCount: Int {
        runtimes.filter(\.isActive).count
    }

    /// 참가자마다 해당 게이트웨이에 토론 전용 세션을 만든다. 실패한 프로필은 제외.
    private func createParticipantSessions() async throws {
        let title = "[Deep think] \(topic.trimmingCharacters(in: .whitespacesAndNewlines).prefix(40))"
        for (index, profile) in selectedProfiles.enumerated() {
            try Task.checkCancellation()
            let client = makeClient(for: profile)
            do {
                let session = try await client.createSession(
                    model: profile.model ?? appSettings.selectedModel,
                    systemPrompt: Self.participantSystemPrompt(allowTools: allowTools)
                )
                try? await client.updateSessionTitle(id: session.id, title: title)
                runtimes.append(Runtime(
                    profile: profile,
                    client: client,
                    sessionID: session.id,
                    colorIndex: index
                ))
            } catch {
                if error is CancellationError { throw error }
                appendEntry(DiscussionEntry(
                    kind: .system,
                    content: "\(profile.name) 프로필 연결 실패 — 토론에서 제외되었습니다."
                ))
            }
        }
    }

    private func runRound(_ round: Int) async throws {
        for index in runtimes.indices where runtimes[index].isActive {
            let message: String
            if round == 1 {
                // 같은 라운드 선발언자의 발언을 함께 전달해 겹치지 않는 관점을 유도
                let earlier = runtimes.filter { $0.isActive && !$0.lastStatement.isEmpty }
                    .map { (name: $0.profile.name, statement: $0.lastStatement) }
                message = Self.firstRoundMessage(topic: topic, totalRounds: rounds, earlierOpinions: earlier)
            } else {
                let others = runtimes.enumerated()
                    .filter { $0.offset != index && $0.element.isActive && !$0.element.lastStatement.isEmpty }
                    .map { (name: $0.element.profile.name, statement: $0.element.lastStatement) }
                message = Self.reviewRoundMessage(round: round, totalRounds: rounds, opinions: others)
            }
            try await speak(runtimeIndex: index, message: message, round: round, kind: .statement)
        }
    }

    /// 사회자에게 전체 기록을 보내 결론을 받는다. 지정 사회자가 탈락했거나
    /// 결론 작성 중 실패하면 다른 활성 참가자가 이어받는다.
    private func concludeDiscussion() async throws {
        let message = Self.moderatorMessage(topic: topic, transcript: buildTranscript())
        var candidates = runtimes.indices.filter { runtimes[$0].isActive }
        // 지정 사회자를 맨 앞으로
        if let preferred = candidates.firstIndex(where: { runtimes[$0].profile.id == moderatorID }) {
            candidates.insert(candidates.remove(at: preferred), at: 0)
        }
        for index in candidates {
            try await speak(runtimeIndex: index, message: message, round: nil, kind: .conclusion)
            if runtimes[index].isActive { return }
        }
        phase = .failed("결론을 작성할 참가자가 없습니다.")
    }

    /// 한 발언: 빈 entry를 추가하고 스트림을 소비하며 채운 뒤, think 블록을 제거한
    /// 정리본으로 교체한다. 게이트웨이 오류는 해당 참가자 탈락으로 흡수하고,
    /// 취소는 CancellationError로 다시 던진다 (스트리밍 중이던 부분 발언은 보존).
    private func speak(runtimeIndex: Int, message: String, round: Int?, kind: DiscussionEntry.Kind) async throws {
        try Task.checkCancellation()
        let runtime = runtimes[runtimeIndex]
        currentSpeakerName = runtime.profile.name
        defer { currentSpeakerName = nil }

        let entry = DiscussionEntry(
            kind: kind,
            round: round,
            speakerName: runtime.profile.name,
            colorIndex: runtime.colorIndex
        )
        appendEntry(entry)

        do {
            var accumulated = ""
            let stream = runtime.client.streamChat(sessionId: runtime.sessionID, message: message)
            for try await update in stream {
                try Task.checkCancellation()
                if case .content(let chunk) = update {
                    accumulated += chunk
                    updateEntry(id: entry.id) { $0.content = accumulated }
                }
                // .toolCallUpdate는 무시 — 도구 실행 결과는 발언 본문으로 돌아온다
            }
            try Task.checkCancellation()
            let cleaned = MarkdownLite.strippingThink(accumulated)
                .trimmingCharacters(in: .whitespacesAndNewlines)
            let final = cleaned.isEmpty ? "(응답 없음)" : cleaned
            updateEntry(id: entry.id) { $0.content = final }
            runtimes[runtimeIndex].lastStatement = final
        } catch {
            // 취소 시 스트림은 HermesAPIError.network(CancellationError)로 끝날 수 있다
            if error is CancellationError || Task.isCancelled {
                throw CancellationError()
            }
            entries.removeAll { $0.id == entry.id }
            runtimes[runtimeIndex].isActive = false
            appendEntry(DiscussionEntry(
                kind: .system,
                content: "\(runtime.profile.name) 프로필이 응답하지 않아 토론에서 제외되었습니다."
            ))
        }
    }

    private func handleCancellation() {
        // 스트리밍 중이던 부분 발언은 think 블록만 정리해 보존한다
        for index in entries.indices
        where entries[index].kind == .statement || entries[index].kind == .conclusion {
            let cleaned = MarkdownLite.strippingThink(entries[index].content)
                .trimmingCharacters(in: .whitespacesAndNewlines)
            entries[index].content = cleaned.isEmpty ? "(중단됨)" : cleaned
        }
        appendEntry(DiscussionEntry(kind: .system, content: "사용자가 토론을 중단했습니다."))
        phase = .finished(saved: false)
    }

    private func saveCurrentDiscussion() {
        let moderatorName = conclusionEntry?.speakerName
            ?? runtimes.first(where: \.isActive)?.profile.name ?? ""
        let saved = SavedDiscussion(
            id: UUID(),
            topic: topic,
            date: .now,
            participantNames: runtimes.map(\.profile.name),
            rounds: rounds,
            moderatorName: moderatorName,
            entries: entries
        )
        DiscussionStore.save(saved)
        savedDiscussions = DiscussionStore.load()
    }

    // MARK: - Entry 헬퍼

    private func appendEntry(_ entry: DiscussionEntry) {
        entries.append(entry)
    }

    private func updateEntry(id: UUID, _ mutate: (inout DiscussionEntry) -> Void) {
        guard let idx = entries.firstIndex(where: { $0.id == id }) else { return }
        mutate(&entries[idx])
    }

    private func makeClient(for profile: HermesProfile) -> HermesAPIClient {
        HermesAPIClient(
            baseURL: appSettings.baseURL(for: profile),
            apiKey: profile.apiKey.isEmpty ? appSettings.apiKey : profile.apiKey
        )
    }

    /// 사회자용 전체 토론 기록 — 라운드별 [이름] 발언
    private func buildTranscript() -> String {
        var lines: [String] = []
        for entry in entries {
            switch entry.kind {
            case .roundMarker:
                lines.append("=== \(entry.content) ===")
            case .statement:
                lines.append("[\(entry.speakerName)] \(entry.content)")
            case .system, .conclusion:
                continue
            }
        }
        return lines.joined(separator: "\n")
    }

    // MARK: - 프롬프트 템플릿

    static func participantSystemPrompt(allowTools: Bool) -> String {
        let toolRule = allowTools
            ? "4. 필요하면 도구(웹 검색 등)로 근거를 확인해도 되지만, 답변은 간결하게 유지하세요."
            : "4. 도구(웹 검색, 파일 접근, 명령 실행 등)는 사용하지 말고 보유 지식과 추론만 사용하세요."
        return """
        당신은 여러 AI 에이전트가 참여하는 토론의 참가자입니다. 각 참가자는 서로 다른 모델과 관점을 가지고 있으며, 토론의 목적은 서로의 오류를 교정하고 더 나은 결론에 도달하는 것입니다.

        규칙:
        1. 답변은 한국어로, 핵심만 간결하게 — 최대 6문장(약 300자) 이내.
        2. 다른 참가자의 의견이 주어지면 동의/반박을 명확히 구분하고 반드시 근거를 제시하세요.
        3. 확실하지 않은 내용은 "추측"임을 명시하고, 모르면 모른다고 답하세요.
        \(toolRule)
        5. 인사말, 자기소개, 결론 요약 같은 군더더기 없이 본론만 말하고, 당신의 평소 페르소나와 관점은 유지하세요.
        """
    }

    static func firstRoundMessage(
        topic: String,
        totalRounds: Int,
        earlierOpinions: [(name: String, statement: String)]
    ) -> String {
        var text = "[토론 시작 — 라운드 1/\(totalRounds)]\n주제: \(topic)\n"
        if !earlierOpinions.isEmpty {
            text += "\n앞서 발언한 참가자들의 의견:\n"
            text += earlierOpinions.map { "- \($0.name): \($0.statement)" }.joined(separator: "\n")
            text += "\n\n이 주제에 대한 당신의 입장과 핵심 근거를 제시하세요. 앞선 의견과 겹치지 않는 새로운 관점이 있으면 우선하세요."
        } else {
            text += "\n이 주제에 대한 당신의 입장과 핵심 근거를 제시하세요."
        }
        return text
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
}
