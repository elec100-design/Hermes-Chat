import Foundation
import SwiftUI

@MainActor
final class AppSettings: ObservableObject {
    /// 게이트웨이 호스트. 포트가 포함되어 있어도 프로필 포트로 대체된다.
    @AppStorage("serverHost") var serverHost: String = "http://localhost:8642"
    @AppStorage("selectedModel") var selectedModel: String = "hermes-agent"
    /// Hermes Bridge 주소 (예: http://100.x.x.x:8765). 비어 있으면 브리지 기능 비활성.
    @AppStorage("bridgeHost") var bridgeHost: String = ""
    @AppStorage("dashboardPort") var dashboardPort: Int = 8000

    /// 비밀값은 Keychain 보관 (T-070). 구버전 UserDefaults 값은 init에서 1회 이관.
    @Published var apiKey: String = "" {
        didSet { KeychainHelper.set(apiKey, for: "apiKey") }
    }
    @Published var bridgeToken: String = "" {
        didSet { KeychainHelper.set(bridgeToken, for: "bridgeToken") }
    }

    @Published var profiles: [HermesProfile] = []
    @Published var selectedProfileID: UUID?
    @Published var isDiscoveringProfiles: Bool = false

    @Published var sessions: [Session] = []
    @Published var isLoadingSessions: Bool = false
    @Published var sessionLoadError: String? = nil
    @Published var selectedSource: String? = nil
    @Published var hasMoreSessions: Bool = false
    @Published var isLoadingMoreSessions: Bool = false

    private let sessionPageSize = 50

    private static let profilesKey = "hermesProfiles"
    private static let selectedProfileNameKey = "selectedProfileName"

    /// 프로필 전환 직후 도착하는 이전 프로필의 응답을 버리기 위한 세대 카운터
    private var loadGeneration = 0

    init() {
        let stored = Self.loadStoredProfiles()
        profiles = stored.isEmpty ? [.default] : stored
        let storedName = UserDefaults.standard.string(forKey: Self.selectedProfileNameKey) ?? "default"
        selectedProfileID = (profiles.first { $0.name == storedName } ?? profiles.first)?.id
        apiKey = Self.loadSecret("apiKey")
        bridgeToken = Self.loadSecret("bridgeToken")
    }

    /// Keychain 우선, 없으면 구버전 UserDefaults에서 이관 후 삭제
    private static func loadSecret(_ key: String) -> String {
        if let value = KeychainHelper.get(key) { return value }
        if let legacy = UserDefaults.standard.string(forKey: key), !legacy.isEmpty {
            KeychainHelper.set(legacy, for: key)
            UserDefaults.standard.removeObject(forKey: key)
            return legacy
        }
        return ""
    }

    // MARK: - Profiles

    var selectedProfile: HermesProfile {
        profiles.first { $0.id == selectedProfileID } ?? profiles.first ?? .default
    }

    /// serverHost의 scheme/host에 프로필의 포트를 결합한 baseURL
    func baseURL(for profile: HermesProfile) -> URL {
        var comps = URLComponents(string: serverHost.trimmingCharacters(in: .whitespaces)) ?? URLComponents()
        if comps.scheme == nil { comps.scheme = "http" }
        if comps.host == nil || comps.host?.isEmpty == true { comps.host = "localhost" }
        comps.port = profile.port
        comps.path = ""
        comps.query = nil
        return comps.url ?? URL(string: "http://localhost:8642")!
    }

    var hermesClient: HermesAPIClient {
        let profile = selectedProfile
        return HermesAPIClient(
            baseURL: baseURL(for: profile),
            apiKey: profile.apiKey.isEmpty ? apiKey : profile.apiKey
        )
    }

    /// 대시보드(:8000) URL — serverHost의 스킴/호스트에 dashboardPort 결합
    var dashboardURL: URL {
        baseURL(for: HermesProfile(name: "dashboard", port: dashboardPort))
    }

    /// Bridge 주소가 설정되어 있을 때만 만들어진다 (SOUL.md, 재시작, 업로드, 칸반).
    var bridgeClient: BridgeClient? {
        var trimmed = bridgeHost.trimmingCharacters(in: .whitespaces)
        guard !trimmed.isEmpty else { return nil }
        if !trimmed.lowercased().hasPrefix("http") { trimmed = "http://" + trimmed }
        guard let url = URL(string: trimmed) else { return nil }
        return BridgeClient(baseURL: url, token: bridgeToken)
    }

    func selectProfile(_ profile: HermesProfile) {
        guard profile.id != selectedProfileID else { return }
        selectedProfileID = profile.id
        UserDefaults.standard.set(profile.name, forKey: Self.selectedProfileNameKey)
        sessions = []
        selectedSource = nil
        sessionLoadError = nil
        hasMoreSessions = false
        loadSessions()
    }

    func addProfile(name: String, port: Int) {
        let trimmed = name.trimmingCharacters(in: .whitespaces)
        guard !trimmed.isEmpty, !profiles.contains(where: { $0.port == port }) else { return }
        profiles.append(HermesProfile(name: trimmed, port: port))
        profiles.sort { $0.port < $1.port }
        persistProfiles()
    }

    func updateProfile(_ profile: HermesProfile) {
        guard let idx = profiles.firstIndex(where: { $0.id == profile.id }) else { return }
        profiles[idx] = profile
        persistProfiles()
    }

    func removeProfiles(at offsets: IndexSet) {
        let removingSelected = offsets.contains { profiles[$0].id == selectedProfileID }
        profiles.remove(atOffsets: offsets)
        if profiles.isEmpty { profiles = [.default] }
        if removingSelected, let first = profiles.first {
            selectedProfileID = first.id
            UserDefaults.standard.set(first.name, forKey: Self.selectedProfileNameKey)
            sessions = []
            loadSessions()
        }
        persistProfiles()
    }

    /// 프로필 자동 검색. Bridge가 설정돼 있으면 정확한 목록을 받아오고,
    /// 아니면 호스트의 포트 범위를 스캔해서 응답하는 hermes API 서버를 등록한다.
    /// 스캔 시 프로필 이름은 각 API 서버가 /v1/models 로 알려주는 모델 식별자
    /// (API_SERVER_MODEL_NAME, 기본값 = 프로필 이름)를 사용한다.
    /// - Returns: 새로 추가된 프로필 수
    @discardableResult
    func discoverProfiles(ports: [Int] = Array(8642...8651)) async -> Int {
        guard !isDiscoveringProfiles else { return 0 }
        isDiscoveringProfiles = true
        defer { isDiscoveringProfiles = false }

        if let bridge = bridgeClient,
           let bridgeProfiles = try? await bridge.fetchProfiles() {
            var added = 0
            for bp in bridgeProfiles
            where bp.apiEnabled && !profiles.contains(where: { $0.port == bp.port }) {
                profiles.append(HermesProfile(name: bp.name, port: bp.port))
                added += 1
            }
            if added > 0 {
                profiles.sort { $0.port < $1.port }
                persistProfiles()
            }
            return added
        }

        var found: [(port: Int, name: String)] = []
        await withTaskGroup(of: (Int, String)?.self) { group in
            for port in ports {
                let url = baseURL(for: HermesProfile(name: "probe", port: port))
                let key = apiKey
                group.addTask {
                    guard let name = await Self.probeModelName(baseURL: url, apiKey: key) else { return nil }
                    return (port, name)
                }
            }
            for await result in group {
                if let result { found.append(result) }
            }
        }

        var added = 0
        for item in found where !profiles.contains(where: { $0.port == item.port }) {
            profiles.append(HermesProfile(name: item.name, port: item.port))
            added += 1
        }
        if added > 0 {
            profiles.sort { $0.port < $1.port }
            persistProfiles()
        }
        return added
    }

    nonisolated private static func probeModelName(baseURL: URL, apiKey: String) async -> String? {
        var request = URLRequest(url: baseURL.appendingPathComponent("v1/models"))
        request.timeoutInterval = 3
        if !apiKey.isEmpty {
            request.setValue("Bearer \(apiKey)", forHTTPHeaderField: "Authorization")
        }
        guard let (data, response) = try? await URLSession.shared.data(for: request),
              let http = response as? HTTPURLResponse, http.statusCode == 200 else { return nil }
        struct ModelsResponse: Decodable {
            struct Model: Decodable { let id: String }
            let data: [Model]
        }
        return (try? JSONDecoder().decode(ModelsResponse.self, from: data))?.data.first?.id
    }

    private func persistProfiles() {
        if let data = try? JSONEncoder().encode(profiles) {
            UserDefaults.standard.set(data, forKey: Self.profilesKey)
        }
    }

    private static func loadStoredProfiles() -> [HermesProfile] {
        guard let data = UserDefaults.standard.data(forKey: profilesKey),
              let decoded = try? JSONDecoder().decode([HermesProfile].self, from: data) else { return [] }
        return decoded
    }

    // MARK: - Sessions

    var availableSources: [String] {
        let all = sessions.compactMap { $0.source }.filter { !$0.isEmpty }
        return Array(Set(all)).sorted()
    }

    var filteredSessions: [Session] {
        guard let source = selectedSource else { return sessions }
        return sessions.filter { $0.source == source }
    }

    func loadSessions() {
        loadGeneration += 1
        let generation = loadGeneration
        isLoadingSessions = true
        sessionLoadError = nil
        let client = hermesClient
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
        let client = hermesClient
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

    func createSession() async throws -> Session {
        let session = try await hermesClient.createSession(
            model: selectedProfile.model ?? selectedModel,
            systemPrompt: nil
        )
        sessions.insert(session, at: 0)
        return session
    }

    /// 세션 분기 (T-092) — 분기된 새 세션을 목록 맨 앞에 넣고 돌려준다.
    func forkSession(id: String) async throws -> Session {
        let session = try await hermesClient.forkSession(id: id)
        sessions.insert(session, at: 0)
        return session
    }

    func deleteSession(id: String) {
        sessions.removeAll { $0.id == id }
        let client = hermesClient
        Task { try? await client.deleteSession(id: id) }
    }

    func updateSession(_ session: Session) {
        if let idx = sessions.firstIndex(where: { $0.id == session.id }) {
            sessions[idx] = session
        }
    }
}
