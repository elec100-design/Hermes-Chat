import XCTest
@testable import HermesChat

/// DiscussionOrchestrator 통합 테스트 — MockTransport 주입으로 전체 토론 흐름을 검증한다.
/// 모든 작업이 @MainActor에서 돌므로 폴링 대기로 완료를 확인한다.
@MainActor
final class DiscussionOrchestratorTests: XCTestCase {
    override func setUp() {
        super.setUp()
        UserDefaults.standard.removeObject(forKey: DiscussionStore.storageKey)
    }

    override func tearDown() {
        UserDefaults.standard.removeObject(forKey: DiscussionStore.storageKey)
        super.tearDown()
    }

    // MARK: 헬퍼

    private func makeProfiles() -> [HermesProfile] {
        [
            HermesProfile(name: "Alice", port: 8642, model: "model-a"),
            HermesProfile(name: "Bob", port: 8643, model: "model-b"),
        ]
    }

    private func makeOrchestrator(
        profiles: [HermesProfile],
        transport: DiscussionTransport,
        moderatorID: UUID? = nil,
        rounds: Int = 2
    ) -> DiscussionOrchestrator {
        DiscussionOrchestrator(
            profiles: profiles,
            topic: "주제",
            rounds: rounds,
            moderatorID: moderatorID,
            allowTools: false,
            defaultModel: "hermes-agent",
            makeTransport: { _ in transport }
        )
    }

    private func waitForPhase(
        _ orchestrator: DiscussionOrchestrator,
        _ matches: (DiscussionPhase) -> Bool,
        timeout: TimeInterval = 10
    ) async {
        let deadline = Date.now.addingTimeInterval(timeout)
        while Date.now < deadline {
            if matches(orchestrator.phase) { return }
            try? await Task.sleep(nanoseconds: 50_000_000)
        }
        XCTFail("phase가 기대 값에 도달하지 못했습니다. 현재: \(orchestrator.phase)")
    }

    private func statements(_ orchestrator: DiscussionOrchestrator) -> [DiscussionEntry] {
        orchestrator.entries.filter { $0.kind == .statement }
    }

    // MARK: 테스트

    func testHappyPathCompletesAndSaves() async {
        let mock = MockTransport()
        // think 블록이 제거되어야 한다
        mock.defaultStreamChunks = ["<think>내부 사고</think>", "실제 답변"]
        let profiles = makeProfiles()
        let orch = makeOrchestrator(profiles: profiles, transport: mock, moderatorID: profiles[0].id)

        orch.start()
        await waitForPhase(orch) { phase in
            if case .finished(let saved) = phase { return saved }
            return false
        }

        // 상태
        if case .finished(let saved) = orch.phase {
            XCTAssertTrue(saved)
        } else {
            XCTFail("finished 예상, 현재: \(orch.phase)")
        }

        // 세션 생성: 모델 + 시스템 프롬프트
        XCTAssertEqual(mock.createdSessions.count, 2)
        XCTAssertEqual(mock.createdSessions[0].model, "model-a")
        XCTAssertEqual(mock.createdSessions[1].model, "model-b")
        XCTAssertTrue(mock.createdSessions[0].systemPrompt?.contains("토론") == true)
        XCTAssertTrue(mock.createdSessions[0].systemPrompt?.contains("도구") == true)

        // 타임라인: 라운드 마커 2 + 발언 2명×2라운드 + 결론 1
        XCTAssertEqual(orch.entries.filter { $0.kind == .roundMarker }.count, 2)
        XCTAssertEqual(statements(orch).count, 4)
        XCTAssertEqual(orch.entries.filter { $0.kind == .conclusion }.count, 1)

        // think 블록 제거된 발언 본문
        for statement in statements(orch) {
            XCTAssertEqual(statement.content, "실제 답변")
        }

        // 결론은 지정 사회자(Alice)가 작성
        let conclusion = orch.entries.first { $0.kind == .conclusion }
        XCTAssertEqual(conclusion?.speakerName, "Alice")

        // 스트림 메시지: 1라운드(2) + 2라운드(2) + 결론(1)
        XCTAssertEqual(mock.streamedMessages.count, 5)
        XCTAssertTrue(mock.streamedMessages[0].message.contains("라운드 1"))
        XCTAssertTrue(mock.streamedMessages[1].message.contains("라운드 1"))
        // 2라운드는 병렬 발언이라 순서가 비결정적 — 두 메시지가 상호 검토 형식이고
        // 서로의 참가자 이름을 각각 포함하는지로 검증한다
        let round2 = mock.streamedMessages[2...3]
        XCTAssertTrue(round2.allSatisfy { $0.message.contains("상호 검토") })
        XCTAssertTrue(round2.contains { $0.message.contains("Alice") })
        XCTAssertTrue(round2.contains { $0.message.contains("Bob") })
        XCTAssertTrue(mock.streamedMessages[4].message.contains("사회자"))

        // 로컬 보관 확인
        let saved = DiscussionStore.load()
        XCTAssertEqual(saved.count, 1)
        XCTAssertEqual(saved.first?.topic, "주제")
        XCTAssertEqual(saved.first?.rounds, 2)
        XCTAssertEqual(saved.first?.participantNames, ["Alice", "Bob"])
        XCTAssertEqual(saved.first?.moderatorName, "Alice")
    }

    func testSessionCreationFailureExcludesProfile() async {
        let mock = MockTransport()
        // 참가 2(Bob)만 세션 생성 실패 — Alice/Carol은 계속 진행
        mock.createSessionErrorAtIndex = [1: HermesAPIError.serverError("연결 실패")]
        mock.defaultStreamChunks = ["답변"]
        let profiles = [
            HermesProfile(name: "Alice", port: 8642),
            HermesProfile(name: "Bob", port: 8643),
            HermesProfile(name: "Carol", port: 8644),
        ]
        let orch = makeOrchestrator(profiles: profiles, transport: mock)

        orch.start()
        await waitForPhase(orch) { phase in
            if case .finished = phase { return true }
            return false
        }

        XCTAssertEqual(statements(orch).count, 4) // Alice, Carol 2라운드
        XCTAssertTrue(orch.entries.contains { $0.kind == .system && $0.content.contains("연결 실패") })
        // 실패한 참가는 결론 후보에도 없다
        XCTAssertEqual(orch.conclusionEntry?.speakerName, "Alice")
    }

    func testStreamErrorDeactivatesParticipant() async {
        let mock = MockTransport()
        // 참가 2(Bob)만 스트림 에러 — Alice는 계속 진행
        mock.streamErrorsBySessionIndex = [1: HermesAPIError.serverError("응답 없음")]
        mock.defaultStreamChunks = ["답변"]

        // 3명으로 구성해 1명 탈락 후에도 2명 유지되게 한다
        let profiles = [
            HermesProfile(name: "Alice", port: 8642),
            HermesProfile(name: "Bob", port: 8643),
            HermesProfile(name: "Carol", port: 8644),
        ]
        let orch = makeOrchestrator(profiles: profiles, transport: mock, rounds: 1)

        orch.start()
        await waitForPhase(orch) { phase in
            if case .finished = phase { return true }
            return false
        }

        XCTAssertTrue(orch.entries.contains { $0.kind == .system && $0.content.contains("제외되었습니다") })
        // Alice, Carol 발언 + Bob의 결론 시도(실패) → 다른 참가자가 결론 작성
        XCTAssertEqual(orch.entries.filter { $0.kind == .conclusion }.count, 1)
        XCTAssertEqual(orch.conclusionEntry?.speakerName, "Alice") // 후보 순서상 Alice가 이어받음
    }

    func testCancellationPreservesPartialAndFlagsUnsaved() async {
        let mock = MockTransport()
        mock.hang = true
        let profiles = makeProfiles()
        let orch = makeOrchestrator(profiles: profiles, transport: mock, rounds: 2)

        orch.start()
        // 1라운드 발언이 시작될 때까지 대기
        await waitUntilActiveRound(orch)

        orch.stop()
        mock.finishAllStreams()

        await waitForPhase(orch) { phase in
            if case .finished(let saved) = phase { return !saved }
            return false
        }

        XCTAssertTrue(orch.entries.contains { $0.kind == .system && $0.content.contains("중단") })
        // 저장되지 않는다
        XCTAssertEqual(DiscussionStore.load().count, 0)
    }

    private func waitUntilActiveRound(_ orchestrator: DiscussionOrchestrator) async {
        let deadline = Date.now.addingTimeInterval(5)
        while Date.now < deadline {
            if case .running(round: 1, totalRounds: _) = orchestrator.phase, !orchestrator.speakingNames.isEmpty {
                return
            }
            try? await Task.sleep(nanoseconds: 50_000_000)
        }
        XCTFail("1라운드가 시작되지 않았습니다")
    }

    func testPollFallbackRecoversEmptyStreamReply() async {
        let mock = MockTransport()
        // 스트림은 비어 있고 서버 기록에만 답변이 남는다 (SSE 미전송 시나리오)
        mock.persistReplyOnEmptyStream = "폴백으로 회수된 답변"
        let profiles = makeProfiles()
        let orch = makeOrchestrator(profiles: profiles, transport: mock, rounds: 1)

        orch.start()
        await waitForPhase(orch) { phase in
            if case .finished = phase { return true }
            return false
        }

        XCTAssertEqual(statements(orch).count, 2)
        for statement in statements(orch) {
            XCTAssertEqual(statement.content, "폴백으로 회수된 답변")
        }
        // fetchMessages가 실제로 폴링됐는지 확인
        XCTAssertFalse(mock.fetchedSessionIDs.isEmpty)
    }

    func testResetToSetupClearsEntries() async {
        let mock = MockTransport()
        mock.hang = true
        let profiles = makeProfiles()
        let orch = makeOrchestrator(profiles: profiles, transport: mock)
        orch.start()
        orch.stop()
        mock.finishAllStreams()
        // 취소 처리가 끝나 finished(saved:false)가 된 뒤에만 리셋이 허용된다
        await waitForPhase(orch) { phase in
            if case .finished = phase { return true }
            return false
        }
        orch.resetToSetup()

        XCTAssertEqual(orch.phase, .setup)
        XCTAssertTrue(orch.entries.isEmpty)
    }
}
