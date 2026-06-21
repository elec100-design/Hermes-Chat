import Foundation
import SwiftUI

// MARK: - Connection Mode (T-C02)
enum ConnectionMode: String {
    case selfHosted = "selfHosted"
    case cloud      = "cloud"
}

@MainActor
final class AppSettings: ObservableObject {
    /// 게이트웨이 호스트. 포트가 포함되어 있어도 프로필 포트로 대체된다.
    @AppStorage("serverHost") var serverHost: String = "http://localhost:8642"
    @AppStorage("selectedModel") var selectedModel: String = "hermes-agent"
    /// Hermes Bridge 주소 (예: http://100.x.x.x:8765). 비어 있으면 브리지 기능 비활성.
    @AppStorage("bridgeHost") var bridgeHost: String = ""
    @AppStorage("dashboardPort") var dashboardPort: Int = 8000
    /// 온보딩 완료 여부 — false면 앱 시작 시 OnboardingView를 표시한다.
    @AppStorage("isFirstLaunchComplete") var isFirstLaunchComplete: Bool = false

    // MARK: - Cloud Auth (T-C01)
    /// Supabase 프로젝트 URL (예: https://xxx.supabase.co). T-B02 완료 후 설정.
    @AppStorage("supabaseURL")     var supabaseURL: String = ""
    /// Supabase anon (public) key — 공개 키라 Keychain 불필요, UserDefaults 저장 허용.
    @AppStorage("supabaseAnonKey") var supabaseAnonKey: String = ""
    /// 클라우드 게이트웨이 URL (예: https://gateway.hermeschat.app). T-B04 배포 후 설정.
    @AppStorage("cloudGatewayURL") var cloudGatewayURL: String = ""

    /// Keychain 기반 cloud auth 상태
    @Published var supabaseJWT: String = "" {
        didSet { KeychainHelper.set(supabaseJWT, for: "supabase_jwt") }
    }
    @Published var supabaseRefresh: String = "" {
        didSet { KeychainHelper.set(supabaseRefresh, for: "supabase_refresh") }
    }
    @Published var supabaseUserID: String = "" {
        didSet { KeychainHelper.set(supabaseUserID, for: "supabase_user_id") }
    }
    @Published var supabaseEmail: String = "" {
        didSet { KeychainHelper.set(supabaseEmail, for: "supabase_email") }
    }
    /// 로그인 시 cloud_gateway로부터 받은 플랜 ("free"|"basic"|"pro"). 비영속.
    @Published var cloudPlan: String = ""

    var isCloudAuthenticated: Bool { !supabaseJWT.isEmpty && !supabaseUserID.isEmpty }

    /// .cloud 모드에서는 cloudGatewayURL + supabaseJWT 사용
    @AppStorage("connectionMode") var connectionMode: ConnectionMode = .selfHosted

    // MARK: - Usage (T-C05)
    /// 이번 달 메시지 사용 수. GET /usage 폴링으로 갱신.
    @Published var usageCount: Int = 0
    /// 월 메시지 한도. nil = 무제한 (유료 플랜).
    @Published var usageLimit: Int? = nil

    func fetchUsage() async {
        guard isCloudAuthenticated,
              connectionMode == .cloud,
              !cloudGatewayURL.isEmpty,
              let url = URL(string: "\(cloudGatewayURL.trimmingCharacters(in: .whitespaces))/usage")
        else { return }
        var request = URLRequest(url: url, timeoutInterval: 10)
        request.setValue("Bearer \(supabaseJWT)", forHTTPHeaderField: "Authorization")
        guard let (data, response) = try? await URLSession.shared.data(for: request),
              let http = response as? HTTPURLResponse, http.statusCode == 200 else { return }
        struct UsageResponse: Decodable {
            struct Limits: Decodable { let monthly_messages: Int? }
            struct ThisMonth: Decodable { let messages: Int }
            let limits: Limits
            let this_month: ThisMonth
        }
        if let parsed = try? JSONDecoder().decode(UsageResponse.self, from: data) {
            usageCount = parsed.this_month.messages
            usageLimit = parsed.limits.monthly_messages
        }
    }

    func signOutCloud() {
        supabaseJWT     = ""
        supabaseRefresh = ""
        supabaseUserID  = ""
        supabaseEmail   = ""
        cloudPlan       = ""
        usageCount      = 0
        usageLimit      = nil
    }

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

    /// 로컬에서 고정(Pin)한 세션 ID 집합. 서버 미지원이라 UserDefaults에만 보관.
    @Published var pinnedSessionIDs: Set<String> = []

    private let sessionPageSize = 50

    private static let profilesKey = "hermesProfiles"
    private static let selectedProfileNameKey = "selectedProfileName"
    private static let pinnedSessionsKey = "pinnedSessionIDs"

    /// 프로필 전환 직후 도착하는 이전 프로필의 응답을 버리기 위한 세대 카운터
    private var loadGeneration = 0

    init() {
        let (stored, migrated) = Self.loadStoredProfiles()
        profiles = stored.isEmpty ? [.default] : stored
        let storedName = UserDefaults.standard.string(forKey: Self.selectedProfileNameKey) ?? "default"
        selectedProfileID = (profiles.first { $0.name == storedName } ?? profiles.first)?.id
        apiKey = Self.loadSecret("apiKey")
        bridgeToken = Self.loadSecret("bridgeToken")
        supabaseJWT     = Self.loadSecret("supabase_jwt")
        supabaseRefresh = Self.loadSecret("supabase_refresh")
        supabaseUserID  = Self.loadSecret("supabase_user_id")
        supabaseEmail   = Self.loadSecret("supabase_email")
        if let ids = UserDefaults.standard.array(forKey: Self.pinnedSessionsKey) as? [String] {
            pinnedSessionIDs = Set(ids)
        }
        // 구버전 평문 apiKey가 있었으면 재직렬화로 UserDefaults에서 제거 (T-099)
        if migrated { persistProfiles() }
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
        switch connectionMode {
        case .cloud:
            let raw = cloudGatewayURL.trimmingCharacters(in: .whitespaces)
            let url = URL(string: raw.isEmpty ? "http://localhost:8642" : raw)
                      ?? URL(string: "http://localhost:8642")!
            return HermesAPIClient(baseURL: url, apiKey: supabaseJWT)
        case .selfHosted:
            let profile = selectedProfile
            return HermesAPIClient(
                baseURL: baseURL(for: profile),
                apiKey: profile.apiKey.isEmpty ? apiKey : profile.apiKey
            )
        }
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
        let oldName = profiles[idx].name
        profiles[idx] = profile
        // 프로필 이름이 변경된 경우, 이전 이름의 Keychain 값을 정리
        if oldName != profile.name {
            KeychainHelper.delete(Self.profileKeychainKey(oldName))
        }
        persistProfiles()
    }

    func removeProfiles(at offsets: IndexSet) {
        let removingSelected = offsets.contains { profiles[$0].id == selectedProfileID }
        for offset in offsets {
            KeychainHelper.delete(Self.profileKeychainKey(profiles[offset].name))
        }
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
    /// 이미 등록된 포트의 이름이 서버 보고와 다르면 갱신한다 — 맥에서 프로필
    /// 폴더명을 바꾼 경우(codex→builder 등) 삭제·재검색 없이 따라간다 (T-123).
    /// - Returns: 추가되거나 이름이 갱신된 프로필 수
    @discardableResult
    func discoverProfiles(ports: [Int] = Array(8642...8651)) async -> Int {
        guard !isDiscoveringProfiles else { return 0 }
        isDiscoveringProfiles = true
        defer { isDiscoveringProfiles = false }

        if let bridge = bridgeClient,
           let bridgeProfiles = try? await bridge.fetchProfiles() {
            var changed = 0
            for bp in bridgeProfiles where bp.apiEnabled {
                if profiles.contains(where: { $0.port == bp.port }) {
                    if renameProfileIfNeeded(port: bp.port, to: bp.name) { changed += 1 }
                } else {
                    profiles.append(HermesProfile(name: bp.name, port: bp.port))
                    changed += 1
                }
            }
            if changed > 0 {
                profiles.sort { $0.port < $1.port }
                persistProfiles()
            }
            return changed
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

        var changed = 0
        for item in found {
            if profiles.contains(where: { $0.port == item.port }) {
                if renameProfileIfNeeded(port: item.port, to: item.name) { changed += 1 }
            } else {
                profiles.append(HermesProfile(name: item.name, port: item.port))
                changed += 1
            }
        }
        if changed > 0 {
            profiles.sort { $0.port < $1.port }
            persistProfiles()
        }
        return changed
    }

    /// 같은 포트의 등록 항목 이름을 서버 보고에 맞춰 갱신한다 (T-123).
    /// 프로필별 apiKey Keychain 항목(T-099 체계)과 선택 저장명도 새 이름으로 이전.
    private func renameProfileIfNeeded(port: Int, to name: String) -> Bool {
        guard let idx = profiles.firstIndex(where: { $0.port == port }),
              profiles[idx].name != name, !name.isEmpty else { return false }
        let oldKey = Self.profileKeychainKey(profiles[idx].name)
        if let stored = KeychainHelper.get(oldKey), !stored.isEmpty {
            KeychainHelper.set(stored, for: Self.profileKeychainKey(name))
        }
        KeychainHelper.delete(oldKey)
        profiles[idx].name = name
        if profiles[idx].id == selectedProfileID {
            UserDefaults.standard.set(name, forKey: Self.selectedProfileNameKey)
        }
        return true
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

    private static func profileKeychainKey(_ name: String) -> String { "profileApiKey.\(name)" }

    /// 프로필별 apiKey는 Keychain에 보관하고, UserDefaults JSON에는 빈 문자열로 직렬화한다 (T-099).
    private func persistProfiles() {
        for profile in profiles {
            // 빈 값이면 KeychainHelper.set이 삭제 처리
            KeychainHelper.set(profile.apiKey, for: Self.profileKeychainKey(profile.name))
        }
        var sanitized = profiles
        for idx in sanitized.indices { sanitized[idx].apiKey = "" }
        if let data = try? JSONEncoder().encode(sanitized) {
            UserDefaults.standard.set(data, forKey: Self.profilesKey)
        }
    }

    /// - Returns: 저장된 프로필과, 구버전 평문 apiKey를 Keychain으로 이관했는지 여부
    private static func loadStoredProfiles() -> (profiles: [HermesProfile], migrated: Bool) {
        guard let data = UserDefaults.standard.data(forKey: profilesKey),
              var decoded = try? JSONDecoder().decode([HermesProfile].self, from: data)
        else { return ([], false) }
        var migrated = false
        for idx in decoded.indices {
            let key = profileKeychainKey(decoded[idx].name)
            if !decoded[idx].apiKey.isEmpty {
                // 구버전: UserDefaults에 평문으로 남아 있던 키 → Keychain 1회 이관
                KeychainHelper.set(decoded[idx].apiKey, for: key)
                migrated = true
            } else if let stored = KeychainHelper.get(key) {
                decoded[idx].apiKey = stored
            }
        }
        return (decoded, migrated)
    }

    // MARK: - Sessions

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
