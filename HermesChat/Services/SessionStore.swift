import Foundation

/// SessionStore가 사용하는 게이트웨이 API 슬라이스.
/// HermesAPIClient가 구현하고, 테스트에서는 결정적 제어가 가능한 Mock으로 대체한다.
protocol SessionFetching {
    func fetchSessions(limit: Int, offset: Int) async throws -> SessionPage
    func createSession(model: String?, systemPrompt: String?) async throws -> Session
    func forkSession(id: String) async throws -> Session
    func deleteSession(id: String) async throws
}

extension HermesAPIClient: SessionFetching {}

/// 세션 목록·페이지네이션·고정(Pin) 상태를 담당한다 (T-072, T-092).
///
/// hermesClient를 직접 만들지 않고 클로저로 주입받아 테스트에서 교체 가능하게 한다.
/// 프로필 전환 시 선택 변경 콜백을 통해 목록을 리셋한다.
@MainActor
final class SessionStore: ObservableObject {
    @Published var sessions: [Session] = []
    @Published var isLoadingSessions: Bool = false
    @Published var sessionLoadError: String? = nil
    @Published var selectedSource: String? = nil
    @Published var hasMoreSessions: Bool = false
    @Published var isLoadingMoreSessions: Bool = false

    /// 로컬에서 고정(Pin)한 세션 ID 집합. 서버 미지원이라 UserDefaults에만 보관.
    @Published var pinnedSessionIDs: Set<String> = []

    /// 게이트웨이 제공 (AppSettings가 주입). 세대 카운터는 응답 도착 시점의
    /// 최신 요청만 반영하므로, 프로필 전환 후 호출돼도 안전하다.
    var clientProvider: () -> SessionFetching = {
        HermesAPIClient(baseURL: URL(string: "http://localhost:8642")!, apiKey: "")
    }

    private let sessionPageSize = 50
    /// 프로필 전환 직후 도착하는 이전 프로필의 응답을 버리기 위한 세대 카운터
    private var loadGeneration = 0

    private static let pinnedSessionsKey = "pinnedSessionIDs"

    init() {
        if let ids = UserDefaults.standard.array(forKey: Self.pinnedSessionsKey) as? [String] {
            pinnedSessionIDs = Set(ids)
        }
    }

    /// 프로필 선택 변경 후 목록을 비우고 첫 페이지를 다시 불러온다.
    func reloadForProfileChange() {
        sessions = []
        selectedSource = nil
        sessionLoadError = nil
        hasMoreSessions = false
        loadSessions()
    }

    // MARK: - Source / 정렬

    var availableSources: [String] {
        let all = sessions.compactMap { $0.source }.filter { !$0.isEmpty }
        return Array(Set(all)).sorted()
    }

    var filteredSessions: [Session] {
        let base = selectedSource.map { src in sessions.filter { $0.source == src } } ?? sessions
        // 고정한 세션을 앞으로 (기존 상대 순서 유지하는 안정 정렬)
        return base.enumerated().sorted { lhs, rhs in
            let lp = isPinned(id: lhs.element.id), rp = isPinned(id: rhs.element.id)
            if lp != rp { return lp }
            return lhs.offset < rhs.offset
        }.map(\.element)
    }

    // MARK: - Pin

    func isPinned(id: String) -> Bool {
        pinnedSessionIDs.contains(id)
    }

    func togglePin(id: String) {
        if pinnedSessionIDs.contains(id) {
            pinnedSessionIDs.remove(id)
        } else {
            pinnedSessionIDs.insert(id)
        }
        UserDefaults.standard.set(Array(pinnedSessionIDs), forKey: Self.pinnedSessionsKey)
    }

    // MARK: - 로드

    func loadSessions() {
        loadGeneration += 1
        let generation = loadGeneration
        isLoadingSessions = true
        sessionLoadError = nil
        let client = clientProvider()
        let limit = sessionPageSize
        Task {
            do {
                let page = try await client.fetchSessions(limit: limit, offset: 0)
                guard generation == loadGeneration else { return }
                sessions = page.sessions
                hasMoreSessions = page.hasMore
            } catch {
                guard generation == loadGeneration else { return }
                sessionLoadError = error.localizedDescription
            }
            isLoadingSessions = false
        }
    }

    /// 다음 페이지를 이어 붙인다 (T-072). 목록 끝 도달 시 호출.
    func loadMoreSessions() {
        guard hasMoreSessions, !isLoadingMoreSessions, !isLoadingSessions else { return }
        let generation = loadGeneration
        isLoadingMoreSessions = true
        let client = clientProvider()
        let limit = sessionPageSize
        let offset = sessions.count
        Task {
            do {
                let page = try await client.fetchSessions(limit: limit, offset: offset)
                guard generation == loadGeneration else { return }
                let existing = Set(sessions.map(\.id))
                sessions += page.sessions.filter { !existing.contains($0.id) }
                hasMoreSessions = page.hasMore
            } catch {
                guard generation == loadGeneration else { return }
                hasMoreSessions = false
            }
            isLoadingMoreSessions = false
        }
    }

    // MARK: - 변경

    func createSession(model: String) async throws -> Session {
        let session = try await clientProvider().createSession(model: model, systemPrompt: nil)
        sessions.insert(session, at: 0)
        return session
    }

    /// 세션 분기 (T-092) — 분기된 새 세션을 목록 맨 앞에 넣고 돌려준다.
    func forkSession(id: String) async throws -> Session {
        let session = try await clientProvider().forkSession(id: id)
        sessions.insert(session, at: 0)
        return session
    }

    func deleteSession(id: String) {
        sessions.removeAll { $0.id == id }
        let client = clientProvider()
        Task { try? await client.deleteSession(id: id) }
    }

    func updateSession(_ session: Session) {
        if let idx = sessions.firstIndex(where: { $0.id == session.id }) {
            sessions[idx] = session
        }
    }
}
