import Foundation

// MARK: - DiscussionTransport

/// 토론 오케스트레이터가 사용하는 게이트웨이 API 인터페이스.
/// HermesAPIClient가 구현하고, 테스트에서는 Mock으로 대체한다.
@MainActor
protocol DiscussionTransport {
    func createSession(model: String?, systemPrompt: String?) async throws -> Session
    func updateSessionTitle(id: String, title: String) async throws
    func streamChat(sessionId: String, message: String) -> AsyncThrowingStream<StreamUpdate, Error>
    func fetchMessages(sessionId: String) async throws -> [ChatMessage]
}

extension HermesAPIClient: DiscussionTransport {}

// MARK: - DiscussionEvent

/// 오케스트레이터 → ViewModel 이벤트. ViewModel이 @Published 상태로 반영한다.
enum DiscussionEvent {
    case phaseChanged(DiscussionPhase)
    case entryAppended(DiscussionEntry)
    case entryUpdated(id: UUID, content: String)
    case entryRemoved(id: UUID)
    case speakingChanged([String])
}

// MARK: - DiscussionOrchestrator

/// Deep think 토론 오케스트레이터.
///
/// 앱이 사회 역할을 맡는 클라이언트 사이드 설계: 참가 프로필마다 해당 게이트웨이에
/// 전용 세션을 만들고, 라운드마다 순차로 발언을 받아 다른 참가자의 발언을 다음 턴
/// 메시지에 실어 중계한다. 마지막에는 사회자 참가자의 세션에 전체 기록을 보내
/// 최종 결론(합의점/이견/결론)을 받는다.
///
/// 네트워크는 `DiscussionTransport` 주입으로 격리되어 있으며, 상태 변화는
/// `onEvent` 콜백으로 방출한다 — 테스트에서 전 과정을 검증할 수 있다.
@MainActor
final class DiscussionOrchestrator {
    /// 참가자 런타임 상태 (메모리 전용)
    private struct Runtime {
        let profile: HermesProfile
        let transport: DiscussionTransport
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

    private let profiles: [HermesProfile]
    private let topic: String
    private let rounds: Int
    private let moderatorID: UUID?
    private let allowTools: Bool
    private let defaultModel: String
    private let makeTransport: (HermesProfile) -> DiscussionTransport

    private(set) var phase: DiscussionPhase = .setup
    private(set) var entries: [DiscussionEntry] = []
    private(set) var speakingNames: [String] = []
    private var runtimes: [Runtime] = []
    private var runTask: Task<Void, Never>?

    var onEvent: ((DiscussionEvent) -> Void)?

    init(
        profiles: [HermesProfile],
        topic: String,
        rounds: Int,
        moderatorID: UUID?,
        allowTools: Bool,
        defaultModel: String,
        makeTransport: @escaping (HermesProfile) -> DiscussionTransport
    ) {
        self.profiles = profiles
        self.topic = topic
        self.rounds = rounds
        self.moderatorID = moderatorID
        self.allowTools = allowTools
        self.defaultModel = defaultModel
        self.makeTransport = makeTransport
    }

    /// 결론 entry (있으면)
    var conclusionEntry: DiscussionEntry? {
        entries.last { $0.kind == .conclusion }
    }

    func start() {
        guard !phase.isActive else { return }
        entries = []
        runtimes = []
        emit(.phaseChanged(.running(round: 1, totalRounds: rounds)))
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
        emit(.phaseChanged(.setup))
    }

    // MARK: - 오케스트레이션

    private func runDiscussion() async {
        defer {
            speakingNames = []
            emit(.speakingChanged([]))
            runTask = nil
        }
        do {
            try await createParticipantSessions()
            guard activeCount >= 2 else {
                emit(.phaseChanged(.failed("참가자 2명 이상이 연결되어야 토론을 시작할 수 있습니다.")))
                return
            }

            for round in 1...rounds {
                emit(.phaseChanged(.running(round: round, totalRounds: rounds)))
                appendEntry(DiscussionEntry(kind: .roundMarker, round: round, content: "라운드 \(round) / \(rounds)"))
                try await runRound(round)
                guard activeCount >= 2 else {
                    emit(.phaseChanged(.failed("참가자가 모두 이탈하여 토론을 종료합니다.")))
                    return
                }
            }

            emit(.phaseChanged(.concluding))
            try await concludeDiscussion()
            // concludeDiscussion이 .failed로 끝냈으면 저장하지 않는다
            if case .failed = phase { return }

            saveCurrentDiscussion()
            emit(.phaseChanged(.finished(saved: true)))
        } catch {
            // speak가 게이트웨이 오류를 탈락으로 흡수하므로 보통 취소(중지 버튼)만 온다
            if error is CancellationError || Task.isCancelled {
                handleCancellation()
            } else {
                emit(.phaseChanged(.failed(error.localizedDescription)))
            }
        }
    }

    private var activeCount: Int {
        runtimes.filter(\.isActive).count
    }

    /// 참가자마다 해당 게이트웨이에 토론 전용 세션을 만든다. 실패한 프로필은 제외.
    private func createParticipantSessions() async throws {
        let title = "[Deep think] \(topic.trimmingCharacters(in: .whitespacesAndNewlines).prefix(40))"
        for (index, profile) in profiles.enumerated() {
            try Task.checkCancellation()
            let transport = makeTransport(profile)
            do {
                let session = try await transport.createSession(
                    model: profile.model ?? defaultModel,
                    systemPrompt: DiscussionPrompts.participantSystemPrompt(allowTools: allowTools)
                )
                try? await transport.updateSessionTitle(id: session.id, title: title)
                runtimes.append(Runtime(
                    profile: profile,
                    transport: transport,
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
                message = DiscussionPrompts.firstRoundMessage(topic: topic, totalRounds: rounds)
            } else {
                let others = snapshot
                    .filter { $0.index != index }
                    .map { (name: $0.name, statement: $0.statement) }
                message = DiscussionPrompts.reviewRoundMessage(round: round, totalRounds: rounds, opinions: others)
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
        let message = DiscussionPrompts.moderatorMessage(topic: topic, transcript: buildTranscript())
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
        emit(.phaseChanged(.failed("결론을 작성할 참가자가 없습니다.")))
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
        emit(.speakingChanged(speakingNames))
        defer {
            if let idx = speakingNames.firstIndex(of: runtime.profile.name) {
                speakingNames.remove(at: idx)
            }
            emit(.speakingChanged(speakingNames))
        }

        var accumulated = ""
        runtimes[runtimeIndex].userTurns += 1
        do {
            let stream = runtime.transport.streamChat(sessionId: runtime.sessionID, message: message)
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
        removeEntry(id: entryID)
        runtimes[runtimeIndex].isActive = false
        appendEntry(DiscussionEntry(
            kind: .system,
            content: "\(runtimes[runtimeIndex].profile.name) 프로필이 응답하지 않아 토론에서 제외되었습니다."
        ))
        return false
    }

    /// 세션 기록을 2초 간격으로 폴링해 누락된 답변을 회수한다.
    /// 타임아웃 또는 취소 시 nil (호출자가 Task.isCancelled로 구분).
    private func pollForMissedReply(runtimeIndex: Int, deadline: TimeInterval) async -> String? {
        let runtime = runtimes[runtimeIndex]
        let expectedUserCount = runtime.userTurns
        let limit = Date.now.addingTimeInterval(deadline)
        while Date.now < limit {
            if Task.isCancelled { return nil }
            if let messages = try? await runtime.transport.fetchMessages(sessionId: runtime.sessionID),
               let reply = DiscussionPrompts.missedReply(in: messages, expectedUserCount: expectedUserCount) {
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
        emit(.phaseChanged(.finished(saved: false)))
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
    }

    // MARK: - Entry 헬퍼

    private func appendEntry(_ entry: DiscussionEntry) {
        entries.append(entry)
        emit(.entryAppended(entry))
    }

    private func updateEntry(id: UUID, _ mutate: (inout DiscussionEntry) -> Void) {
        guard let idx = entries.firstIndex(where: { $0.id == id }) else { return }
        mutate(&entries[idx])
        emit(.entryUpdated(id: id, content: entries[idx].content))
    }

    private func removeEntry(id: UUID) {
        entries.removeAll { $0.id == id }
        emit(.entryRemoved(id: id))
    }

    private func emit(_ event: DiscussionEvent) {
        if case .phaseChanged(let newPhase) = event {
            phase = newPhase
        }
        onEvent?(event)
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
}
