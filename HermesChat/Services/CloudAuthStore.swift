import Foundation

/// 클라우드 인증·사용량 상태를 담당한다 (T-C01, T-C05).
///
/// AppSettings의 cloud auth 책임을 분리한 스토어. JWT/refresh/user/email은
/// Keychain에 보관하고, 플랜·사용량은 비영속 상태로 유지한다.
/// gateway URL과 connection mode는 스토어에 두지 않고 호출부(AppSettings)가
/// 전달한다 — Supabase URL/키 설정은 SettingsView의 `$appSettings.` 바인딩을
/// 유지하기 위해 AppSettings에 남는다.
@MainActor
final class CloudAuthStore: ObservableObject {
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

    /// 이번 달 메시지 사용 수. GET /usage 폴링으로 갱신.
    @Published var usageCount: Int = 0
    /// 월 메시지 한도. nil = 무제한 (유료 플랜).
    @Published var usageLimit: Int? = nil

    var isCloudAuthenticated: Bool { !supabaseJWT.isEmpty && !supabaseUserID.isEmpty }

    init() {
        supabaseJWT     = Self.loadSecret("supabase_jwt")
        supabaseRefresh = Self.loadSecret("supabase_refresh")
        supabaseUserID  = Self.loadSecret("supabase_user_id")
        supabaseEmail   = Self.loadSecret("supabase_email")
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

    /// GET /usage 폴링 (T-C05). 게이트웨이 URL/모드는 호출부가 전달한다.
    func fetchUsage(gatewayURL: String, connectionMode: ConnectionMode) async {
        guard isCloudAuthenticated,
              connectionMode == .cloud,
              !gatewayURL.isEmpty,
              let url = URL(string: "\(gatewayURL.trimmingCharacters(in: .whitespaces))/usage")
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
}
