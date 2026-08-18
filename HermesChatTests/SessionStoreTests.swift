import XCTest
@testable import HermesChat

/// SessionStore 단위 테스트. 실제 HermesAPIClient를 `http://mock.test`에 연결하고
/// MockURLProtocol로 응답을 가로채 검증한다.
@MainActor
final class SessionStoreTests: XCTestCase {
    private var store: SessionStore!

    override func setUp() {
        super.setUp()
        UserDefaults.standard.removeObject(forKey: "pinnedSessionIDs")
        URLProtocol.registerClass(MockURLProtocol.self)
        store = SessionStore()
        store.clientProvider = {
            HermesAPIClient(baseURL: URL(string: "http://mock.test")!, apiKey: "test-key")
        }
    }

    override func tearDown() {
        URLProtocol.unregisterClass(MockURLProtocol.self)
        store = nil
        super.tearDown()
    }

    // MARK: 헬퍼

    private func makeSessionsJSON(items: [(id: String, title: String, source: String)], hasMore: Bool) -> Data {
        let array = items.map { item -> [String: Any] in
            [
                "id": item.id,
                "title": item.title,
                "source": item.source,
                "started_at": 1_700_000_000.0,
                "last_active": 1_700_001_000.0,
            ]
        }
        let body: [String: Any] = ["data": array, "has_more": hasMore]
        return (try? JSONSerialization.data(withJSONObject: body)) ?? Data()
    }

    private func setHandler(_ handler: @escaping (URLRequest) throws -> (HTTPURLResponse, Data)) {
        MockURLProtocol.requestHandler = handler
    }

    private func waitUntil(timeout: TimeInterval = 5, _ condition: @MainActor () -> Bool) async {
        let deadline = Date.now.addingTimeInterval(timeout)
        while Date.now < deadline {
            if condition() { return }
            try? await Task.sleep(nanoseconds: 50_000_000)
        }
        XCTFail("조건이 만족되지 않았습니다")
    }

    // MARK: 테스트

    func testLoadSessions() async {
        setHandler { request in
            let response = HTTPURLResponse(url: request.url!, statusCode: 200, httpVersion: nil, headerFields: nil)!
            let data = self.makeSessionsJSON(
                items: [("1", "세션 A", "chat"), ("2", "세션 B", "review")],
                hasMore: true
            )
            return (response, data)
        }

        store.loadSessions()
        await waitUntil { self.store.sessions.count == 2 && !self.store.isLoadingSessions }

        XCTAssertEqual(store.sessions.map(\.id).sorted(), ["1", "2"])
        XCTAssertEqual(store.sessions.first?.displayTitle, "세션 A")
        XCTAssertTrue(store.hasMoreSessions)
        XCTAssertNil(store.sessionLoadError)
    }

    func testLoadSessionsError() async {
        setHandler { request in
            throw HermesAPIError.serverError("HTTP 500: boom")
        }

        store.loadSessions()
        await waitUntil { !self.store.isLoadingSessions }

        XCTAssertTrue(store.sessions.isEmpty)
        XCTAssertNotNil(store.sessionLoadError)
    }

    func testReloadDropsStaleGeneration() async {
        // URLProtocol 타이밍 대신 결정적 게이트를 쓰는 Mock으로 세대 가드를 검증한다.
        // gen1의 fetchSessions는 게이트에 걸려 있고, reload(gen2)가 먼저 완료된 뒤
        // 게이트를 열면 늦게 도착한 gen1 응답이 버려져야 한다.
        let gate = Gate()
        let mock = MockSessionClient()
        var firstFetch = true
        mock.fetchHandler = { _, _ in
            if firstFetch {
                firstFetch = false
                await gate.wait()
                return SessionPage(
                    sessions: [Session(id: "slow-1", title: "느린 응답", preview: nil, updatedAt: .now, source: "chat")],
                    hasMore: false
                )
            }
            return SessionPage(
                sessions: [Session(id: "fast-1", title: "빠른 응답", preview: nil, updatedAt: .now, source: "chat")],
                hasMore: false
            )
        }
        store.clientProvider = { mock }

        store.loadSessions()
        store.reloadForProfileChange()
        await waitUntil { !self.store.isLoadingSessions && self.store.sessions.first?.id == "fast-1" }

        // 늦게 도착한 이전 세대 응답은 버려진다
        gate.open()
        try? await Task.sleep(nanoseconds: 100_000_000)
        XCTAssertEqual(store.sessions.first?.id, "fast-1")
        XCTAssertEqual(store.sessions.count, 1)
    }

    func testLoadMoreSessionsAppends() async {
        var call = 0
        setHandler { request in
            call += 1
            let response = HTTPURLResponse(url: request.url!, statusCode: 200, httpVersion: nil, headerFields: nil)!
            if call == 1 {
                return (response, self.makeSessionsJSON(
                    items: [("1", "A", "chat"), ("2", "B", "chat")],
                    hasMore: true
                ))
            }
            return (response, self.makeSessionsJSON(
                items: [("3", "C", "chat")],
                hasMore: false
            ))
        }

        store.loadSessions()
        await waitUntil { self.store.sessions.count == 2 && !self.store.isLoadingSessions }

        store.loadMoreSessions()
        await waitUntil { self.store.sessions.count == 3 }

        XCTAssertEqual(store.sessions.map(\.id), ["1", "2", "3"])
        XCTAssertFalse(store.hasMoreSessions)
    }

    func testCreateSessionInsertsAtFront() async {
        setHandler { request in
            let response = HTTPURLResponse(url: request.url!, statusCode: 200, httpVersion: nil, headerFields: nil)!
            let body: [String: Any] = ["id": "new-1", "title": NSNull()]
            return (response, (try? JSONSerialization.data(withJSONObject: body)) ?? Data())
        }

        store.sessions = [
            Session(id: "old-1", title: "기존", preview: nil, updatedAt: .now, source: nil)
        ]
        let created = try? await store.createSession(model: "hermes-agent")
        XCTAssertEqual(created?.id, "new-1")
        XCTAssertEqual(store.sessions.first?.id, "new-1")
        XCTAssertEqual(store.sessions.count, 2)
    }

    func testDeleteSessionRemovesAndCallsServer() async {
        var deleted = false
        setHandler { request in
            let response = HTTPURLResponse(url: request.url!, statusCode: 200, httpVersion: nil, headerFields: nil)!
            if request.httpMethod == "DELETE" {
                deleted = true
                return (response, Data())
            }
            return (response, self.makeSessionsJSON(
                items: [("1", "A", "chat")], hasMore: false
            ))
        }

        store.sessions = [Session(id: "1", title: "A", preview: nil, updatedAt: .now, source: "chat")]
        store.deleteSession(id: "1")

        XCTAssertTrue(store.sessions.isEmpty)
        await waitUntil { deleted }
    }

    func testTogglePinPersistsAndSortsFirst() async {
        store.sessions = [
            Session(id: "1", title: "A", preview: nil, updatedAt: .now, source: "chat"),
            Session(id: "2", title: "B", preview: nil, updatedAt: .now, source: "chat"),
            Session(id: "3", title: "C", preview: nil, updatedAt: .now, source: "chat"),
        ]

        store.togglePin(id: "3")
        XCTAssertEqual(store.filteredSessions.map(\.id), ["3", "1", "2"])

        let reloaded = SessionStore()
        XCTAssertTrue(reloaded.pinnedSessionIDs.contains("3"))

        store.togglePin(id: "3")
        XCTAssertEqual(store.filteredSessions.map(\.id), ["1", "2", "3"])
    }

    func testAvailableSourcesAreUniqueAndSorted() {
        store.sessions = [
            Session(id: "1", title: "A", preview: nil, updatedAt: .now, source: "review"),
            Session(id: "2", title: "B", preview: nil, updatedAt: .now, source: "chat"),
            Session(id: "3", title: "C", preview: nil, updatedAt: .now, source: "chat"),
        ]
        XCTAssertEqual(store.availableSources, ["chat", "review"])
    }

    func testFilteredSessionsBySource() {
        store.sessions = [
            Session(id: "1", title: "A", preview: nil, updatedAt: .now, source: "review"),
            Session(id: "2", title: "B", preview: nil, updatedAt: .now, source: "chat"),
        ]
        store.selectedSource = "chat"
        XCTAssertEqual(store.filteredSessions.map(\.id), ["2"])
    }
}

/// 결정적 제어가 가능한 SessionFetching 모의 구현.
@MainActor
final class MockSessionClient: SessionFetching {
    var fetchHandler: ((Int, Int) async throws -> SessionPage)?
    var createHandler: ((String?, String?) async throws -> Session)?

    func fetchSessions(limit: Int, offset: Int) async throws -> SessionPage {
        guard let fetchHandler else { throw HermesAPIError.serverError("핸들러 없음") }
        return try await fetchHandler(limit, offset)
    }

    func createSession(model: String?, systemPrompt: String?) async throws -> Session {
        guard let createHandler else { throw HermesAPIError.serverError("핸들러 없음") }
        return try await createHandler(model, systemPrompt)
    }

    func forkSession(id: String) async throws -> Session {
        throw HermesAPIError.serverError("미지원")
    }

    func deleteSession(id: String) async throws {}
}

/// 한 번 open()될 때까지 wait()를 대기시키는 1회용 게이트 (테스트 시점 제어).
@MainActor
final class Gate {
    private var continuation: CheckedContinuation<Void, Never>?
    private var isOpen = false

    func wait() async {
        if isOpen { return }
        await withCheckedContinuation { continuation = $0 }
    }

    func open() {
        isOpen = true
        continuation?.resume()
        continuation = nil
    }
}
