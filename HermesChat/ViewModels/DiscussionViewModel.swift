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
    /// 현재 발언(스트리밍/폴백 대기) 중인 참가자 이름들 — 라운드는 동시 진행된다
    @Published var speakingNames: [String] = []
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
        /// 이 세션으로 보낸 user 메시지 수 — 폴백 폴링이 직전 턴 답변을
        /// 새 답변으로 오인하지 않도록 앵커 검증에 쓴다 (speak당 정확히 1 증가)
        var userTurns: Int = 0
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
        speakingNames = []
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
            speakingNames = []
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

    /// 한 라운드: 활성 참가자 전원이 **동시에** 발언한다.
    /// 직전 라운드 발언을 스냅샷해 메시지를 사전 조립하고, 참가자 순서대로 빈 entry를
    /// 먼저 추가해 카드 표시 순서를 고정한 뒤 TaskGroup으로 병렬 스트리밍한다.
    private func runRound(_ round: Int) async throws {
        struct Job {
            let runtimeIndex: Int
            let message: String
            let entryID: UUID
        }

        // 라운드 시작 시점의 직전 발언 스냅샷 — 진행 중 갱신되는 lastStatement와 분리
        let snapshot = runtimes.enumerated()
            .filter { $0.element.isActive && !$0.element.lastStatement.isEmpty }
            .map { (index: $0.offset, name: $0.element.profile.name, statement: $0.element.lastStatement) }

        var jobs: [Job] = []
        for index in runtimes.indices where runtimes[index].isActive {
            let message: String
            if round == 1 {
                message = Self.firstRoundMessage(topic: topic, totalRounds: rounds)
            } else {
                let others = snapshot
                    .filter { $0.index != index }
                    .map { (name: $0.name, statement: $0.statement) }
                message = Self.reviewRoundMessage(round: round, totalRounds: rounds, opinions: others)
            }
            let entry = DiscussionEntry(
                kind: .statement,
                round: round,
                speakerName: runtimes[index].profile.name,
                colorIndex: runtimes[index].colorIndex
            )
            appendEntry(entry)
            jobs.append(Job(runtimeIndex: index, message: message, entryID: entry.id))
        }

        // 비던지는 그룹: 한 참가자의 실패가 형제 발언을 취소하지 않는다
        await withTaskGroup(of: Void.self) { group in
            for job in jobs {
                group.addTask {
                    await self.speak(
                        runtimeIndex: job.runtimeIndex,
                        message: job.message,
                        entryID: job.entryID
                    )
                }
            }
        }
        try Task.checkCancellation()
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
            let entry = DiscussionEntry(
                kind: .conclusion,
                speakerName: runtimes[index].profile.name,
                colorIndex: runtimes[index].colorIndex
            )
            appendEntry(entry)
            let succeeded = await speak(runtimeIndex: index, message: message, entryID: entry.id)
            try Task.checkCancellation()
            if succeeded { return }
        }
        phase = .failed("결론을 작성할 참가자가 없습니다.")
    }

    /// 한 발언: 호출자가 만든 entry를 스트림으로 채우고, think 블록을 제거한 정리본으로
    /// 교체한다. 스트림이 내용 없이 끝나면 세션 기록을 폴링하는 폴백으로 회수한다
    /// (게이트웨이가 응답을 세션에는 쓰지만 SSE로는 안 보내는 경우 — 실기기 확인 버그).
    /// 게이트웨이 오류·폴백 타임아웃은 참가자 탈락으로 흡수하고 false를 돌려준다.
    /// 던지지 않는다 — TaskGroup에서 형제 발언과 독립적으로 실행되기 위함.
    /// 취소 시에는 부분 발언을 보존한 채 false (호출자가 Task.isCancelled로 구분).
    @discardableResult
    private func speak(runtimeIndex: Int, message: String, entryID: UUID) async -> Bool {
        let runtime = runtimes[runtimeIndex]
        speakingNames.append(runtime.profile.name)
        defer {
            if let idx = speakingNames.firstIndex(of: runtime.profile.name) {
                speakingNames.remove(at: idx)
            }
        }

        var accumulated = ""
        runtimes[runtimeIndex].userTurns += 1
        do {
            let stream = runtime.client.streamChat(sessionId: runtime.sessionID, message: message)
            for try await update in stream {
                try Task.checkCancellation()
                if case .content(let chunk) = update {
                    accumulated += chunk
                    updateEntry(id: entryID) { $0.content = accumulated }
                }
                // .toolCallUpdate는 무시 — 도구 실행 결과는 발언 본문으로 돌아온다
            }
            try Task.checkCancellation()
        } catch {
            // 취소 시 스트림은 HermesAPIError.network(URLError(.cancelled))로 끝날 수 있다
            if error is CancellationError || Task.isCancelled {
                return false // 부분 발언 보존 — handleCancellation이 정리
            }
            return deactivate(runtimeIndex: runtimeIndex, entryID: entryID)
        }

        var visible = MarkdownLite.strippingThink(accumulated)
            .trimmingCharacters(in: .whitespacesAndNewlines)

        if visible.isEmpty {
            // 스트림이 비어 있으면 세션에 기록된 답변을 폴링으로 회수.
            // 내용은 왔는데 think 제거 후 비는 경우는 세션 기록도 think-only일
            // 가능성이 높아 짧게만 시도한다.
            let deadline: TimeInterval = accumulated.isEmpty ? 300 : 6
            if let recovered = await pollForMissedReply(runtimeIndex: runtimeIndex, deadline: deadline) {
                visible = recovered
            } else if Task.isCancelled {
                return false // 취소로 인한 nil — 탈락·알림 금지
            } else {
                return deactivate(runtimeIndex: runtimeIndex, entryID: entryID)
            }
        }

        updateEntry(id: entryID) { $0.content = visible }
        runtimes[runtimeIndex].lastStatement = visible
        return true
    }

    /// 참가자 탈락 처리 — 미완성 entry 제거 + 시스템 알림. 항상 false를 돌려준다.
    private func deactivate(runtimeIndex: Int, entryID: UUID) -> Bool {
        entries.removeAll { $0.id == entryID }
        runtimes[runtimeIndex].isActive = false
        appendEntry(DiscussionEntry(
            kind: .system,
            content: "\(runtimes[runtimeIndex].profile.name) 프로필이 응답하지 않아 토론에서 제외되었습니다."
        ))
        return false
    }

    /// 마지막 user 메시지 뒤에 오는, think 제거 후 내용이 있는 마지막 assistant 발언.
    /// 토론 세션은 앱 전용이므로 이 술어가 "방금 보낸 메시지에 대한 답"과 일치한다.
    /// expectedUserCount: 지금까지 보낸 user 메시지 수 — 방금 보낸 메시지가 아직
    /// 기록되지 않았을 때 직전 턴의 답변을 오인 반환하는 것을 막는다.
    nonisolated static func missedReply(in messages: [ChatMessage], expectedUserCount: Int) -> String? {
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

    /// 세션 기록을 2초 간격으로 폴링해 누락된 답변을 회수한다.
    /// 타임아웃 또는 취소 시 nil (호출자가 Task.isCancelled로 구분).
    private func pollForMissedReply(runtimeIndex: Int, deadline: TimeInterval) async -> String? {
        let client = runtimes[runtimeIndex].client
        let sessionID = runtimes[runtimeIndex].sessionID
        let expectedUserCount = runtimes[runtimeIndex].userTurns
        let limit = Date.now.addingTimeInterval(deadline)
        while Date.now < limit {
            if Task.isCancelled { return nil }
            if let messages = try? await client.fetchMessages(sessionId: sessionID),
               let reply = Self.missedReply(in: messages, expectedUserCount: expectedUserCount) {
                return reply
            }
            do {
                try await Task.sleep(nanoseconds: 2_000_000_000)
            } catch {
                return nil // 취소
            }
        }
        return nil
    }

    private func handleCancellation() {
        // 부분 발언은 think 블록만 정리해 보존하고, 아직 비어 있는 카드는 제거한다
        // (동시 라운드에서는 사전 추가된 빈 entry가 여러 개일 수 있다)
        for index in entries.indices
        where entries[index].kind == .statement || entries[index].kind == .conclusion {
            entries[index].content = MarkdownLite.strippingThink(entries[index].content)
                .trimmingCharacters(in: .whitespacesAndNewlines)
        }
        entries.removeAll {
            ($0.kind == .statement || $0.kind == .conclusion) && $0.content.isEmpty
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
}
