import XCTest
@testable import HermesChat

/// T-149 회귀 커버리지: 스트림이 도중에 끊겨도 서버가 저장한 답변을 폴링 회수해야 하고,
/// 사용자 중지·명시적 서버 오류는 회수 없이 즉시 표면화되어야 한다.
///
/// ChatViewModel.send() 전체는 URLSession·MainActor·타이머 의존성이 커서
/// 하드-리팩터 없이 통째로 격리 테스트하기가 어렵다. 실기기 검증(TASKS T-149 시나리오)에
/// 의존하되, 여기서는 회수 판정의 핵심 규칙(missedReply 앵커링,
/// isRecoverableTransportError 화이트리스트, recoveryDeadline 정책)을 순수 함수 수준에서
/// 고정한다 — 이 세 가지가 무너지면 UI 회귀가 자동으로 재발한다.
final class ChatRecoveryTests: XCTestCase {

    // MARK: missedReply — 앵커링 (직전 턴 답 오인 방지)

    func testMissedReplyReturnsAssistantAfterLastUser() {
        let history: [ChatMessage] = [
            ChatMessage(role: .user, content: "질문 1"),
            ChatMessage(role: .assistant, content: "답 1"),
            ChatMessage(role: .user, content: "질문 2"),
            ChatMessage(role: .assistant, content: "답 2 — 방금 저장됨"),
        ]
        let recovered = DiscussionViewModel.missedReply(in: history, expectedUserCount: 2)
        XCTAssertEqual(recovered, "답 2 — 방금 저장됨")
    }

    func testMissedReplyReturnsNilWhenServerHasNotSavedYet() {
        let history: [ChatMessage] = [
            ChatMessage(role: .user, content: "질문 1"),
            ChatMessage(role: .assistant, content: "답 1"),
        ]
        let recovered = DiscussionViewModel.missedReply(in: history, expectedUserCount: 2)
        XCTAssertNil(recovered)
    }

    func testMissedReplyIgnoresThinkOnlyReplies() {
        let history: [ChatMessage] = [
            ChatMessage(role: .user, content: "질문"),
            ChatMessage(role: .assistant, content: "<think>고민중</think>"),
        ]
        let recovered = DiscussionViewModel.missedReply(in: history, expectedUserCount: 1)
        XCTAssertNil(recovered)
    }

    func testMissedReplyPrefersLastVisibleAssistantAfterLastUser() {
        let history: [ChatMessage] = [
            ChatMessage(role: .user, content: "질문"),
            ChatMessage(role: .assistant, content: "<think>...</think>"),
            ChatMessage(role: .assistant, content: "최종 답"),
        ]
        let recovered = DiscussionViewModel.missedReply(in: history, expectedUserCount: 1)
        XCTAssertEqual(recovered, "최종 답")
    }

    // MARK: isRecoverableTransportError — 좁은 화이트리스트 (T-149 수용 기준)

    /// 조기 EOF는 HermesAPIClient가 `URLError(.networkConnectionLost)`로 승격한 뒤
    /// `.network(...)`으로 감싸 던진다. 폴링 대상이어야 한다.
    func testPrematureEOFIsRecoverable() {
        let err = HermesAPIError.network(URLError(.networkConnectionLost))
        XCTAssertTrue(ChatViewModel.isRecoverableTransportError(err))
    }

    func testTimedOutIsRecoverable() {
        let err = HermesAPIError.network(URLError(.timedOut))
        XCTAssertTrue(ChatViewModel.isRecoverableTransportError(err))
    }

    /// `event: error`로 서버가 명시적으로 실패를 보낸 경우 — 폴링해도 답이 없다.
    /// 즉시 표면화되어야지 300초 동안 화면이 "생성 중"으로 머물면 안 된다.
    func testServerErrorIsNotRecoverable() {
        let err = HermesAPIError.serverError("import failure")
        XCTAssertFalse(ChatViewModel.isRecoverableTransportError(err))
    }

    /// 401은 다시 시도해도 401이다. 폴링 금지.
    func testUnauthorizedIsNotRecoverable() {
        XCTAssertFalse(ChatViewModel.isRecoverableTransportError(HermesAPIError.unauthorized))
    }

    /// DNS 실패 같은 전송 오류라도 화이트리스트 밖이면 폴링하지 않는다.
    func testDNSFailureIsNotRecoverable() {
        let err = HermesAPIError.network(URLError(.cannotFindHost))
        XCTAssertFalse(ChatViewModel.isRecoverableTransportError(err))
    }

    // MARK: recoveryDeadline — 폴링 예산 규칙

    func testRecoveryDeadlineFullWindowOnDroppedEmptyStream() {
        // 스트림이 끊긴 데다 본문이 하나도 없다 → 서버가 이제부터 답을 쓸 수 있으므로 넉넉히.
        // 2026-08-05 사례에서 서버는 4초 뒤에 저장했지만, 300초 예산이 안전 마진을 준다.
        let error = HermesAPIError.network(URLError(.networkConnectionLost))
        XCTAssertEqual(
            ChatViewModel.recoveryDeadline(caughtError: error, streamedContent: ""),
            300
        )
    }

    func testRecoveryDeadlineShorterOnDroppedPartialStream() {
        let error = HermesAPIError.network(URLError(.networkConnectionLost))
        XCTAssertEqual(
            ChatViewModel.recoveryDeadline(caughtError: error, streamedContent: "부분 답변"),
            30
        )
    }

    func testRecoveryDeadlinePreservesT116PolicyOnCleanEmptyStream() {
        XCTAssertEqual(
            ChatViewModel.recoveryDeadline(caughtError: nil, streamedContent: ""),
            300
        )
    }

    func testRecoveryDeadlineShortWindowOnThinkOnlyCleanStream() {
        XCTAssertEqual(
            ChatViewModel.recoveryDeadline(caughtError: nil, streamedContent: "<think>고민중</think>"),
            6
        )
    }

    // MARK: 파서 상태와 취소의 접점

    func testParserStreamsPartialContentThatShouldSurviveDrop() {
        // 스트림이 아래처럼 흘러오다가 끊긴 상황을 재현 —
        // 파서는 지금까지의 부분 본문을 정확히 방출해야 send()가 이걸 보존하고
        // 회수 폴링을 30초 예산으로 시도할 수 있다.
        var parser = SSEParser()
        let lines = [
            "event: assistant.delta", #"data: {"delta": "안녕 "}"#,
            "event: assistant.delta", #"data: {"delta": "잠시만"}"#,
            // 여기서 URLSession이 커넥션 리셋 — 이후 라인은 도착하지 않는다.
        ]
        var contents: [String] = []
        for line in lines {
            if case .emit(let updates) = parser.feed(line) {
                for update in updates {
                    if case .content(let text) = update { contents.append(text) }
                }
            }
        }
        XCTAssertEqual(contents.joined(), "안녕 잠시만")
        // 조기 절단 조건: run.completed가 아직 안 왔다 → 클라이언트가 조기 EOF로 승격해야 한다.
        XCTAssertFalse(parser.sawRunCompleted)
    }
}
