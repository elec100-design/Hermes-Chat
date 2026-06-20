import AuthenticationServices
import SwiftUI

/// T-C01: Sign in with Apple + Supabase Auth 로그인 화면.
/// Supabase JWT를 KeychainHelper로 저장하고 cloud_gateway POST /auth/login으로 컨테이너 프로비저닝.
struct AuthView: View {
    @ObservedObject var appSettings: AppSettings
    @Environment(\.colorScheme) private var colorScheme

    @State private var isLoading = false
    @State private var errorMessage: String?
    @State private var showSignOutConfirm = false
    @State private var showDeleteConfirm = false

    var body: some View {
        ScrollView {
            VStack(spacing: 32) {
                if appSettings.isCloudAuthenticated {
                    signedInContent
                } else {
                    signInContent
                }
            }
            .padding(24)
        }
        .navigationTitle("auth.title")
        .navigationBarTitleDisplayMode(.large)
    }

    // MARK: - Sign-in screen

    private var signInContent: some View {
        VStack(spacing: 24) {
            Image(systemName: "icloud.and.arrow.up.fill")
                .font(.system(size: 64))
                .foregroundStyle(.tint)
                .padding(.top, 16)

            VStack(spacing: 8) {
                Text("auth.signin.heading")
                    .font(.title2.bold())
                    .multilineTextAlignment(.center)
                Text("auth.signin.subtitle")
                    .font(.body)
                    .foregroundStyle(.secondary)
                    .multilineTextAlignment(.center)
            }

            if appSettings.supabaseURL.isEmpty || appSettings.supabaseAnonKey.isEmpty {
                HStack(spacing: 8) {
                    Image(systemName: "exclamationmark.triangle.fill")
                        .foregroundStyle(.orange)
                    Text("auth.error.supabase_not_configured")
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                }
                .padding(12)
                .background(
                    RoundedRectangle(cornerRadius: 10)
                        .fill(Color.orange.opacity(0.1))
                )
            }

            if isLoading {
                VStack(spacing: 8) {
                    ProgressView()
                    Text("auth.signin.loading")
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                }
                .frame(height: 50)
            } else {
                SignInWithAppleButton(.signIn) { request in
                    request.requestedScopes = [.email, .fullName]
                } onCompletion: { result in
                    Task { await handleAppleSignIn(result) }
                }
                .signInWithAppleButtonStyle(colorScheme == .dark ? .white : .black)
                .frame(height: 50)
                .disabled(appSettings.supabaseURL.isEmpty || appSettings.supabaseAnonKey.isEmpty)
            }

            if let error = errorMessage {
                HStack(alignment: .top, spacing: 8) {
                    Image(systemName: "xmark.circle.fill")
                        .foregroundStyle(.red)
                    Text(error)
                        .font(.footnote)
                        .foregroundStyle(.red)
                        .multilineTextAlignment(.leading)
                }
            }
        }
    }

    // MARK: - Signed-in screen

    private var signedInContent: some View {
        VStack(spacing: 24) {
            Image(systemName: "person.crop.circle.badge.checkmark")
                .font(.system(size: 64))
                .foregroundStyle(.tint)
                .padding(.top, 16)

            VStack(spacing: 6) {
                if !appSettings.supabaseEmail.isEmpty {
                    Text(appSettings.supabaseEmail)
                        .font(.title3.bold())
                }
                if !appSettings.cloudPlan.isEmpty {
                    Text("auth.plan.\(appSettings.cloudPlan)")
                        .font(.subheadline)
                        .foregroundStyle(.secondary)
                }
            }

            if isLoading {
                ProgressView()
                    .frame(height: 50)
            }

            if let error = errorMessage {
                HStack(alignment: .top, spacing: 8) {
                    Image(systemName: "xmark.circle.fill")
                        .foregroundStyle(.red)
                    Text(error)
                        .font(.footnote)
                        .foregroundStyle(.red)
                        .multilineTextAlignment(.leading)
                }
            }

            VStack(spacing: 12) {
                Button("auth.sign_out") {
                    showSignOutConfirm = true
                }
                .frame(maxWidth: .infinity)
                .padding(14)
                .background(RoundedRectangle(cornerRadius: 12).fill(Color(.secondarySystemGroupedBackground)))
                .foregroundStyle(.primary)
                .confirmationDialog("auth.sign_out", isPresented: $showSignOutConfirm) {
                    Button("auth.sign_out", role: .destructive) { appSettings.signOutCloud() }
                    Button("common.cancel", role: .cancel) {}
                }

                Button(role: .destructive) {
                    showDeleteConfirm = true
                } label: {
                    Text("auth.delete_account")
                        .frame(maxWidth: .infinity)
                        .padding(14)
                        .background(RoundedRectangle(cornerRadius: 12).fill(Color(.secondarySystemGroupedBackground)))
                        .foregroundStyle(.red)
                }
                .confirmationDialog(
                    "auth.delete_account.confirm.title",
                    isPresented: $showDeleteConfirm,
                    titleVisibility: .visible
                ) {
                    Button("auth.delete_account.confirm.action", role: .destructive) {
                        Task { await deleteAccount() }
                    }
                    Button("common.cancel", role: .cancel) {}
                } message: {
                    Text("auth.delete_account.confirm.message")
                }
            }
        }
    }

    // MARK: - Sign-in flow

    @MainActor
    private func handleAppleSignIn(_ result: Result<ASAuthorization, Error>) async {
        switch result {
        case .failure(let error):
            if (error as? ASAuthorizationError)?.code != .canceled {
                errorMessage = error.localizedDescription
            }
        case .success(let auth):
            guard let cred = auth.credential as? ASAuthorizationAppleIDCredential,
                  let tokenData = cred.identityToken,
                  let identityToken = String(data: tokenData, encoding: .utf8) else {
                errorMessage = String(localized: "auth.error.no_token")
                return
            }
            isLoading = true
            errorMessage = nil
            defer { isLoading = false }

            do {
                let supabaseResp = try await exchangeWithSupabase(identityToken: identityToken)

                appSettings.supabaseJWT     = supabaseResp.access_token
                appSettings.supabaseRefresh = supabaseResp.refresh_token
                appSettings.supabaseUserID  = supabaseResp.user.id
                appSettings.supabaseEmail   = supabaseResp.user.email ?? ""

                // 컨테이너 프로비저닝 (최대 90s) — 실패해도 로그인 상태 유지
                if !appSettings.cloudGatewayURL.isEmpty {
                    do {
                        let loginResp = try await callCloudLogin(jwt: supabaseResp.access_token)
                        appSettings.cloudPlan = loginResp.plan
                    } catch {
                        // gateway 미설정 또는 일시적 실패는 조용히 무시
                    }
                }
            } catch {
                errorMessage = error.localizedDescription
                // 부분 저장 방지
                appSettings.signOutCloud()
            }
        }
    }

    // MARK: - Supabase token exchange

    @MainActor
    private func exchangeWithSupabase(identityToken: String) async throws -> SupabaseAuthResponse {
        guard
            !appSettings.supabaseURL.isEmpty,
            !appSettings.supabaseAnonKey.isEmpty,
            let url = URL(string: "\(appSettings.supabaseURL)/auth/v1/token?grant_type=id_token")
        else {
            throw AuthError.missingSupabaseConfig
        }
        var request = URLRequest(url: url, timeoutInterval: 15)
        request.httpMethod = "POST"
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.setValue(appSettings.supabaseAnonKey, forHTTPHeaderField: "apikey")
        request.httpBody = try JSONEncoder().encode(["provider": "apple", "id_token": identityToken])

        let (data, response) = try await URLSession.shared.data(for: request)
        guard let http = response as? HTTPURLResponse else {
            throw AuthError.networkFailure
        }
        guard http.statusCode == 200 else {
            let msg = (try? JSONDecoder().decode(SupabaseErrorBody.self, from: data))?.error_description
                      ?? (try? JSONDecoder().decode(SupabaseErrorBody.self, from: data))?.msg
                      ?? "HTTP \(http.statusCode)"
            throw AuthError.supabaseHTTP(http.statusCode, msg)
        }
        return try JSONDecoder().decode(SupabaseAuthResponse.self, from: data)
    }

    // MARK: - Cloud gateway provisioning

    @MainActor
    private func callCloudLogin(jwt: String) async throws -> CloudLoginResponse {
        guard let url = URL(string: "\(appSettings.cloudGatewayURL)/auth/login") else {
            throw AuthError.missingGatewayConfig
        }
        var request = URLRequest(url: url, timeoutInterval: 95)
        request.httpMethod = "POST"
        request.setValue("Bearer \(jwt)", forHTTPHeaderField: "Authorization")

        let (data, response) = try await URLSession.shared.data(for: request)
        guard let http = response as? HTTPURLResponse else {
            throw AuthError.networkFailure
        }
        guard http.statusCode == 200 else {
            let msg = (try? JSONDecoder().decode([String: String].self, from: data))?["error"]
                      ?? "HTTP \(http.statusCode)"
            throw AuthError.gatewayHTTP(http.statusCode, msg)
        }
        return try JSONDecoder().decode(CloudLoginResponse.self, from: data)
    }

    // MARK: - Account deletion

    @MainActor
    private func deleteAccount() async {
        let jwt = appSettings.supabaseJWT
        guard !jwt.isEmpty else { return }

        isLoading = true
        errorMessage = nil
        defer { isLoading = false }

        if !appSettings.cloudGatewayURL.isEmpty,
           let url = URL(string: "\(appSettings.cloudGatewayURL)/account") {
            var request = URLRequest(url: url, timeoutInterval: 30)
            request.httpMethod = "DELETE"
            request.setValue("Bearer \(jwt)", forHTTPHeaderField: "Authorization")
            do {
                let (data, response) = try await URLSession.shared.data(for: request)
                if let http = response as? HTTPURLResponse, http.statusCode != 200 {
                    let msg = (try? JSONDecoder().decode([String: String].self, from: data))?["error"]
                              ?? "HTTP \(http.statusCode)"
                    errorMessage = String(format: String(localized: "auth.error.gateway_failed"), msg)
                    return
                }
            } catch {
                errorMessage = error.localizedDescription
                return
            }
        }
        appSettings.signOutCloud()
    }
}

// MARK: - Response models

private struct SupabaseAuthResponse: Decodable {
    let access_token: String
    let refresh_token: String
    let user: SupabaseUser
    struct SupabaseUser: Decodable {
        let id: String
        let email: String?
    }
}

private struct SupabaseErrorBody: Decodable {
    let error_description: String?
    let msg: String?
}

private struct CloudLoginResponse: Decodable {
    let ok: Bool
    let user_id: String
    let container: String
    let plan: String
}

private enum AuthError: LocalizedError {
    case noIdentityToken
    case missingSupabaseConfig
    case missingGatewayConfig
    case networkFailure
    case supabaseHTTP(Int, String)
    case gatewayHTTP(Int, String)

    var errorDescription: String? {
        switch self {
        case .noIdentityToken:
            return String(localized: "auth.error.no_token")
        case .missingSupabaseConfig:
            return String(localized: "auth.error.supabase_not_configured")
        case .missingGatewayConfig:
            return String(localized: "auth.error.gateway_not_configured")
        case .networkFailure:
            return String(localized: "auth.error.network")
        case .supabaseHTTP(let code, let msg):
            return String(format: String(localized: "auth.error.supabase_failed"), "\(code): \(msg)")
        case .gatewayHTTP(let code, let msg):
            return String(format: String(localized: "auth.error.gateway_failed"), "\(code): \(msg)")
        }
    }
}
