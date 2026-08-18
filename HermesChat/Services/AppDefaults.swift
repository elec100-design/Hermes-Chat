import Combine
import Foundation
import SwiftUI

// MARK: - Connection Mode (T-C02)
enum ConnectionMode: String {
    case selfHosted = "selfHosted"
    case cloud      = "cloud"
}

/// 전역 설정 퍼사드.
///
/// View/SettingsView가 직접 바인딩(`$appSettings.`)으로 참조하는 설정 필드만
/// 남기고, 상태·로직은 세 전용 스토어로 위임한다:
/// - `ProfileStore`  — 프로필 목록/선택/검색 (T-C02, T-099, T-123)
/// - `SessionStore`  — 세션 목록/페이지네이션/핀 (T-072, T-092)
/// - `CloudAuthStore` — 클라우드 인증/사용량 (T-C01, T-C05)
///
/// 스토어 변경은 Combine으로 `objectWillChange`를 중계하므로, View 코드는
/// 기존 `@ObservedObject var appSettings` 접근 그대로 동작한다.
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
    /// App Review 데모 모드 — 서버/계정 없이 앱의 모든 기능을 체험할 수 있다.
    @AppStorage("isDemoMode") var isDemoMode: Bool = false

    /// 클라우드 SaaS(로그인·구독) 기능 노출 여부.
    /// 1.0은 데모+셀프호스트만 심사 제출하므로 false — 클라우드 게이트웨이/Supabase가
    /// 실제 배포·검증된 1.1에서 true로 전환해 로그인·구독 UI를 다시 노출한다.
    /// (코드는 그대로 두고 진입점만 가린다 → 되돌리기 1줄.)
    static let cloudFeaturesEnabled = false

    /// Supabase 프로젝트 URL (예: https://xxx.supabase.co). T-B02 완료 후 설정.
    @AppStorage("supabaseURL")     var supabaseURL: String = ""
    /// Supabase anon (public) key — 공개 키라 Keychain 불필요, UserDefaults 저장 허용.
    @AppStorage("supabaseAnonKey") var supabaseAnonKey: String = ""
    /// 클라우드 게이트웨이 URL (예: https://gateway.hermeschat.app). T-B04 배포 후 설정.
    @AppStorage("cloudGatewayURL") var cloudGatewayURL: String = ""

    /// .cloud 모드에서는 cloudGatewayURL + supabaseJWT 사용
    @AppStorage("connectionMode") var connectionMode: ConnectionMode = .selfHosted

    /// 비밀값은 Keychain 보관 (T-070). 구버전 UserDefaults 값은 init에서 1회 이관.
    @Published var apiKey: String = "" {
        didSet { KeychainHelper.set(apiKey, for: "apiKey") }
    }
    @Published var bridgeToken: String = "" {
        didSet { KeychainHelper.set(bridgeToken, for: "bridgeToken") }
    }

    let profileStore = ProfileStore()
    let sessionStore = SessionStore()
    let cloudAuthStore = CloudAuthStore()

    private var cancellables: Set<AnyCancellable> = []

    init() {
        apiKey = Self.loadSecret("apiKey")
        bridgeToken = Self.loadSecret("bridgeToken")
        profileStore.hostProvider = { [weak self] in self?.serverHost ?? "http://localhost:8642" }
        profileStore.apiKeyProvider = { [weak self] in self?.apiKey ?? "" }
        profileStore.bridgeProvider = { [weak self] in self?.bridgeClient }
        sessionStore.clientProvider = { [weak self] in self?.hermesClient ?? HermesAPIClient(baseURL: URL(string: "http://localhost:8642")!, apiKey: "") }
        // 프로필 전환/삭제 시 세션 목록 리셋 (기존 selectProfile/removeProfiles 동작 유지)
        profileStore.onProfileSelectionChanged = { [weak self] in
            self?.sessionStore.reloadForProfileChange()
        }
        // 스토어 변경 → AppSettings.objectWillChange 중계 (View 무변경 관찰)
        profileStore.objectWillChange.sink { [weak self] _ in self?.objectWillChange.send() }.store(in: &cancellables)
        sessionStore.objectWillChange.sink { [weak self] _ in self?.objectWillChange.send() }.store(in: &cancellables)
        cloudAuthStore.objectWillChange.sink { [weak self] _ in self?.objectWillChange.send() }.store(in: &cancellables)
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

    // MARK: - Cloud Auth (T-C01)

    var supabaseJWT: String {
        get { cloudAuthStore.supabaseJWT }
        set { cloudAuthStore.supabaseJWT = newValue }
    }
    var supabaseRefresh: String {
        get { cloudAuthStore.supabaseRefresh }
        set { cloudAuthStore.supabaseRefresh = newValue }
    }
    var supabaseUserID: String {
        get { cloudAuthStore.supabaseUserID }
        set { cloudAuthStore.supabaseUserID = newValue }
    }
    var supabaseEmail: String {
        get { cloudAuthStore.supabaseEmail }
        set { cloudAuthStore.supabaseEmail = newValue }
    }
    var cloudPlan: String {
        get { cloudAuthStore.cloudPlan }
        set { cloudAuthStore.cloudPlan = newValue }
    }
    var isCloudAuthenticated: Bool { cloudAuthStore.isCloudAuthenticated }

    // MARK: - Usage (T-C05)

    var usageCount: Int {
        get { cloudAuthStore.usageCount }
        set { cloudAuthStore.usageCount = newValue }
    }
    var usageLimit: Int? {
        get { cloudAuthStore.usageLimit }
        set { cloudAuthStore.usageLimit = newValue }
    }

    func fetchUsage() async {
        await cloudAuthStore.fetchUsage(gatewayURL: cloudGatewayURL, connectionMode: connectionMode)
    }

    func signOutCloud() {
        cloudAuthStore.signOutCloud()
    }

    // MARK: - Profiles (T-C02)

    var profiles: [HermesProfile] {
        get { profileStore.profiles }
        set { profileStore.profiles = newValue }
    }
    var selectedProfileID: UUID? {
        get { profileStore.selectedProfileID }
        set { profileStore.selectedProfileID = newValue }
    }
    var isDiscoveringProfiles: Bool {
        get { profileStore.isDiscoveringProfiles }
        set { profileStore.isDiscoveringProfiles = newValue }
    }
    var selectedProfile: HermesProfile { profileStore.selectedProfile }

    /// serverHost의 scheme/host에 프로필의 포트를 결합한 baseURL
    func baseURL(for profile: HermesProfile) -> URL {
        profileStore.baseURL(for: profile)
    }

    func selectProfile(_ profile: HermesProfile) {
        profileStore.selectProfile(profile)
    }

    func addProfile(name: String, port: Int) {
        profileStore.addProfile(name: name, port: port)
    }

    func updateProfile(_ profile: HermesProfile) {
        profileStore.updateProfile(profile)
    }

    func removeProfiles(at offsets: IndexSet) {
        profileStore.removeProfiles(at: offsets)
    }

    @discardableResult
    func discoverProfiles(ports: [Int] = Array(8642...8651)) async -> Int {
        await profileStore.discoverProfiles(ports: ports)
    }

    // MARK: - Client factories

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

    // MARK: - Sessions (T-072)

    var sessions: [Session] {
        get { sessionStore.sessions }
        set { sessionStore.sessions = newValue }
    }
    var isLoadingSessions: Bool {
        get { sessionStore.isLoadingSessions }
        set { sessionStore.isLoadingSessions = newValue }
    }
    var sessionLoadError: String? {
        get { sessionStore.sessionLoadError }
        set { sessionStore.sessionLoadError = newValue }
    }
    var selectedSource: String? {
        get { sessionStore.selectedSource }
        set { sessionStore.selectedSource = newValue }
    }
    var hasMoreSessions: Bool {
        get { sessionStore.hasMoreSessions }
        set { sessionStore.hasMoreSessions = newValue }
    }
    var isLoadingMoreSessions: Bool {
        get { sessionStore.isLoadingMoreSessions }
        set { sessionStore.isLoadingMoreSessions = newValue }
    }
    var pinnedSessionIDs: Set<String> {
        get { sessionStore.pinnedSessionIDs }
        set { sessionStore.pinnedSessionIDs = newValue }
    }

    var availableSources: [String] { sessionStore.availableSources }
    var filteredSessions: [Session] { sessionStore.filteredSessions }

    func isPinned(id: String) -> Bool { sessionStore.isPinned(id: id) }
    func togglePin(id: String) { sessionStore.togglePin(id: id) }

    func loadSessions() {
        sessionStore.loadSessions()
    }

    func loadMoreSessions() {
        sessionStore.loadMoreSessions()
    }

    func createSession() async throws -> Session {
        try await sessionStore.createSession(model: selectedProfile.model ?? selectedModel)
    }

    func forkSession(id: String) async throws -> Session {
        try await sessionStore.forkSession(id: id)
    }

    func deleteSession(id: String) {
        sessionStore.deleteSession(id: id)
    }

    func updateSession(_ session: Session) {
        sessionStore.updateSession(session)
    }
}
