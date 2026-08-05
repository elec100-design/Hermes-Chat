import XCTest
@testable import HermesChat

/// T-149 회귀 커버리지: 네이티브 Hermes SSE 이벤트 파싱과 레거시 OpenAI 청크 병존,
/// 그리고 프로토콜 종료(`done`/`[DONE]`)와 실행 종료(`run.completed`)의 분리.
final class SSEParserTests: XCTestCase {

    // MARK: assistant.delta 스트리밍

    func testNativeAssistantDeltaEmitsContent() {
        var parser = SSEParser()
        _ = parser.feed("event: assistant.delta")
        let action = parser.feed(#"data: {"delta": "Hello"}"#)
        XCTAssertEqual(collectedContent(action), ["Hello"])
    }

    func testMultipleAssistantDeltasEmitEachChunk() {
        var parser = SSEParser()
        let lines = [
            "event: assistant.delta", #"data: {"delta": "안녕"}"#,
            "event: assistant.delta", #"data: {"delta": "하세요"}"#,
        ]
        let all = feedAll(&parser, lines)
        XCTAssertEqual(all, ["안녕", "하세요"])
    }

    // MARK: assistant.completed — 중복 방지

    func testAssistantCompletedIsSuppressedAfterDeltas() {
        var parser = SSEParser()
        let lines = [
            "event: assistant.delta", #"data: {"delta": "part1 "}"#,
            "event: assistant.delta", #"data: {"delta": "part2"}"#,
            "event: assistant.completed", #"data: {"content": "part1 part2"}"#,
        ]
        let all = feedAll(&parser, lines)
        XCTAssertEqual(all, ["part1 ", "part2"])
    }

    func testAssistantCompletedFallsBackWhenNoDeltasSeen() {
        var parser = SSEParser()
        _ = parser.feed("event: assistant.completed")
        let action = parser.feed(#"data: {"content": "전체 본문"}"#)
        XCTAssertEqual(collectedContent(action), ["전체 본문"])
    }

    // MARK: 도구 이벤트 — 안전한 맵핑 (T-149 수용 기준)

    /// deployed 스키마: `{message_id, run_id, tool_name, tool_args, sequence}`.
    /// `message_id`는 **턴 전체**의 식별자라서 그걸 도구 ID로 쓰면 서로 다른 도구들이
    /// 하나로 병합돼버린다. 파서는 started 발생마다 별도 합성 ID를 부여하고
    /// 같은 이름의 completed에 FIFO로 짝지어야 한다.
    func testToolStartedAndCompletedShareSyntheticId() {
        var parser = SSEParser()
        _ = parser.feed("event: tool.started")
        let started = parser.feed(#"data: {"message_id":"m-42","run_id":"r-7","tool_name":"skills_list","tool_args":{},"sequence":2}"#)
        guard case .emit(let startedUpdates) = started,
              case .toolCallUpdate(let startedId, let startedName, let startedArgs) = startedUpdates.first else {
            XCTFail("expected tool update on started, got \(started)")
            return
        }
        XCTAssertEqual(startedName, "skills_list")
        XCTAssertEqual(startedArgs, "{}")

        _ = parser.feed("event: tool.completed")
        let completed = parser.feed(#"data: {"message_id":"m-42","run_id":"r-7","tool_name":"skills_list","tool_args":{"q":"weather"},"sequence":3}"#)
        guard case .emit(let completedUpdates) = completed,
              case .toolCallUpdate(let completedId, let completedName, let completedArgs) = completedUpdates.first else {
            XCTFail("expected tool update on completed, got \(completed)")
            return
        }
        XCTAssertEqual(completedId, startedId, "same-name started/completed pair must share synthetic id so UI merges them")
        XCTAssertEqual(completedName, "skills_list")
        XCTAssertEqual(completedArgs, #"{"q":"weather"}"#)
    }

    /// source 스키마: `{message_id, run_id, tool_name, args, seq}`. `tool_args` 대신
    /// `args` 키를 쓴다는 것만 다르다.
    func testSourcePayloadArgsKeyIsAccepted() {
        var parser = SSEParser()
        _ = parser.feed("event: tool.started")
        let started = parser.feed(#"data: {"message_id":"m-1","run_id":"r-1","tool_name":"echo","args":{"say":"hi"},"seq":1}"#)
        guard case .emit(let startedUpdates) = started,
              case .toolCallUpdate(let startedId, let startedName, let startedArgs) = startedUpdates.first else {
            XCTFail("expected tool update on started, got \(started)")
            return
        }
        XCTAssertEqual(startedName, "echo")
        XCTAssertEqual(startedArgs, #"{"say":"hi"}"#)

        _ = parser.feed("event: tool.completed")
        let completed = parser.feed(#"data: {"message_id":"m-1","run_id":"r-1","tool_name":"echo","args":"raw string","seq":2}"#)
        guard case .emit(let completedUpdates) = completed,
              case .toolCallUpdate(let completedId, _, let completedArgs) = completedUpdates.first else {
            XCTFail("expected tool update on completed, got \(completed)")
            return
        }
        XCTAssertEqual(completedId, startedId, "same-name pair must share synthetic id")
        XCTAssertEqual(completedArgs, "raw string", "args as string must pass through")
    }

    /// 같은 턴에서 서로 다른 도구가 실행되면 별도 ID를 가져야 한다 — 안 그러면 UI 칩이 하나로 합쳐진다.
    func testDifferentToolsInSameTurnGetDistinctIds() {
        var parser = SSEParser()
        _ = parser.feed("event: tool.started")
        let a = parser.feed(#"data: {"message_id":"m-1","tool_name":"toolA","tool_args":{}}"#)
        _ = parser.feed("event: tool.started")
        let b = parser.feed(#"data: {"message_id":"m-1","tool_name":"toolB","tool_args":{}}"#)
        guard case .emit(let aUpdates) = a, case .toolCallUpdate(let aId, _, _) = aUpdates.first,
              case .emit(let bUpdates) = b, case .toolCallUpdate(let bId, _, _) = bUpdates.first else {
            XCTFail("expected two tool updates")
            return
        }
        XCTAssertNotEqual(aId, bId, "different tool names in same turn must not collide")
    }

    /// 같은 이름의 도구가 한 턴에 두 번 실행되면 각각 별도 ID를 가지고
    /// completed는 도착 순서대로 FIFO 매칭돼야 한다.
    func testRepeatedSameNameStartsGetDistinctIdsAndCompleteFIFO() {
        var parser = SSEParser()
        _ = parser.feed("event: tool.started")
        let start1 = parser.feed(#"data: {"message_id":"m-1","tool_name":"web_search","tool_args":{"q":"a"}}"#)
        _ = parser.feed("event: tool.started")
        let start2 = parser.feed(#"data: {"message_id":"m-1","tool_name":"web_search","tool_args":{"q":"b"}}"#)
        _ = parser.feed("event: tool.completed")
        let done1 = parser.feed(#"data: {"message_id":"m-1","tool_name":"web_search","tool_args":{"q":"a"}}"#)
        _ = parser.feed("event: tool.completed")
        let done2 = parser.feed(#"data: {"message_id":"m-1","tool_name":"web_search","tool_args":{"q":"b"}}"#)

        guard case .emit(let s1) = start1, case .toolCallUpdate(let s1Id, _, _) = s1.first,
              case .emit(let s2) = start2, case .toolCallUpdate(let s2Id, _, _) = s2.first,
              case .emit(let d1) = done1, case .toolCallUpdate(let d1Id, _, _) = d1.first,
              case .emit(let d2) = done2, case .toolCallUpdate(let d2Id, _, _) = d2.first else {
            XCTFail("expected four tool updates")
            return
        }
        XCTAssertNotEqual(s1Id, s2Id, "two started of same name must have distinct ids")
        XCTAssertEqual(d1Id, s1Id, "first completed pairs with first started (FIFO)")
        XCTAssertEqual(d2Id, s2Id, "second completed pairs with second started (FIFO)")
    }

    func testToolFailedPairsWithMatchingStarted() {
        var parser = SSEParser()
        _ = parser.feed("event: tool.started")
        let started = parser.feed(#"data: {"message_id":"m-1","tool_name":"http_get","tool_args":{}}"#)
        _ = parser.feed("event: tool.failed")
        let failed = parser.feed(#"data: {"message_id":"m-1","tool_name":"http_get","tool_args":{"error":"timeout"}}"#)

        guard case .emit(let s) = started, case .toolCallUpdate(let sId, _, _) = s.first,
              case .emit(let f) = failed, case .toolCallUpdate(let fId, _, _) = f.first else {
            XCTFail("expected two tool updates")
            return
        }
        XCTAssertEqual(fId, sId, "tool.failed must pair with matching tool.started by FIFO per name")
    }

    func testLegacyToolPayloadKeysStillAccepted() {
        // 예전 배포 스키마(`{id, name, arguments}`)도 파싱은 되어야 한다.
        // ID는 이제 합성이므로 값은 검증하지 않고, 스트림 방출 자체와 필드 매핑만 확인한다.
        var parser = SSEParser()
        _ = parser.feed("event: tool.started")
        let action = parser.feed(#"data: {"id":"t1","name":"web_search","arguments":"{\"q\":\"weather\"}"}"#)
        guard case .emit(let updates) = action,
              case .toolCallUpdate(_, let name, let args) = updates.first else {
            XCTFail("expected tool update, got \(action)")
            return
        }
        XCTAssertEqual(name, "web_search")
        XCTAssertEqual(args, #"{"q":"weather"}"#)
    }

    func testUnknownToolPayloadDoesNotCrashStream() {
        var parser = SSEParser()
        _ = parser.feed("event: tool.completed")
        let action = parser.feed(#"data: {}"#)
        guard case .emit(let updates) = action else {
            XCTFail("expected .emit, got \(action)")
            return
        }
        XCTAssertEqual(updates.count, 1)
    }

    // MARK: 종료 신호 — `run.completed`는 종료가 아님 (T-149 수용 기준)

    func testRunCompletedDoesNotFinishAndFlagsTerminalState() {
        var parser = SSEParser()
        _ = parser.feed("event: run.completed")
        let action = parser.feed(#"data: {}"#)
        // 실행이 성공적으로 끝났다는 마커일 뿐, 서버는 `done`을 이어 보낼 수 있다.
        // 여기서 `.finish`가 나오면 뒤이어 오는 `done`을 놓치고 조기 종료 취급된다.
        guard case .none = action else {
            XCTFail("run.completed must not finish the stream; got \(action)")
            return
        }
        XCTAssertTrue(parser.sawRunCompleted, "sawRunCompleted must be set so the client can distinguish clean EOF")
    }

    func testDoneEventFinishesStream() {
        var parser = SSEParser()
        _ = parser.feed("event: done")
        let action = parser.feed(#"data: {}"#)
        guard case .finish = action else {
            XCTFail("expected .finish, got \(action)")
            return
        }
    }

    func testBracketDoneSentinelFinishesStream() {
        var parser = SSEParser()
        let action = parser.feed("data: [DONE]")
        guard case .finish = action else {
            XCTFail("expected .finish, got \(action)")
            return
        }
    }

    func testRunCompletedThenDoneFinishesStream() {
        // 정상적인 프로토콜: run.completed 뒤에 done이 온다.
        var parser = SSEParser()
        _ = parser.feed("event: run.completed")
        _ = parser.feed(#"data: {}"#)
        XCTAssertTrue(parser.sawRunCompleted)

        _ = parser.feed("event: done")
        let action = parser.feed(#"data: {}"#)
        guard case .finish = action else {
            XCTFail("expected .finish after done, got \(action)")
            return
        }
    }

    // MARK: 에러 이벤트

    func testErrorEventSurfacesServerError() {
        var parser = SSEParser()
        _ = parser.feed("event: error")
        let action = parser.feed(#"data: {"message":"import failure"}"#)
        guard case .fail(let err) = action,
              case .serverError(let msg) = err else {
            XCTFail("expected .fail(.serverError), got \(action)")
            return
        }
        XCTAssertEqual(msg, "import failure")
    }

    // MARK: 레거시 OpenAI 청크

    func testLegacyChoicesChunkIsAccepted() {
        var parser = SSEParser()
        let json = #"data: {"choices":[{"delta":{"content":"legacy"}}]}"#
        let action = parser.feed(json)
        XCTAssertEqual(collectedContent(action), ["legacy"])
    }

    func testLegacyAndNativeInterleaveWithoutDuplication() {
        var parser = SSEParser()
        let lines = [
            "event: assistant.delta", #"data: {"delta": "hi"}"#,
            "event: assistant.completed", #"data: {"content": "hi there"}"#,
        ]
        let all = feedAll(&parser, lines)
        XCTAssertEqual(all, ["hi"])
    }

    // MARK: 알 수 없는 named event

    func testUnknownNamedEventIsIgnoredButStreamSurvives() {
        var parser = SSEParser()
        _ = parser.feed("event: some.future.thing")
        let action = parser.feed(#"data: {"foo":"bar"}"#)
        guard case .none = action else {
            XCTFail("expected .none, got \(action)")
            return
        }
        _ = parser.feed("event: assistant.delta")
        let next = parser.feed(#"data: {"delta":"resumed"}"#)
        XCTAssertEqual(collectedContent(next), ["resumed"])
    }

    func testEventNameOnlyAppliesToNextDataLine() {
        var parser = SSEParser()
        _ = parser.feed("event: assistant.delta")
        _ = parser.feed(#"data: {"delta":"a"}"#)
        // 이벤트가 없는 두 번째 data는 레거시 청크로 취급된다 — 스키마 불일치로 방출 없음.
        let next = parser.feed(#"data: {"delta":"b"}"#)
        guard case .none = next else {
            XCTFail("expected .none for orphan delta payload, got \(next)")
            return
        }
    }

    // MARK: helpers

    private func feedAll(_ parser: inout SSEParser, _ lines: [String]) -> [String] {
        var contents: [String] = []
        for line in lines {
            let action = parser.feed(line)
            contents.append(contentsOf: collectedContent(action))
        }
        return contents
    }

    private func collectedContent(_ action: SSEParseAction) -> [String] {
        guard case .emit(let updates) = action else { return [] }
        return updates.compactMap {
            if case .content(let text) = $0 { return text } else { return nil }
        }
    }
}
