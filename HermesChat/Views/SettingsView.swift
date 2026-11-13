import SwiftUI
import Security

struct SettingsView: View {
    @ObservedObject var appSettings: AppSettings
    @StateObject private var subscriptionService = SubscriptionService.shared
    @Environment(\.openURL) private var openURL
    @State private var testResult: ConnectionTestResult? = nil
    @State private var isTesting = false
    @State private var bridgeTestResult: ConnectionTestResult? = nil
    @State private var isTestingBridge = false
    @State private var showApiKeyInput = false
    @State private var showBridgeTokenInput = false
    @State private var showBotTokenInput = false
    @State private var botTestResult: ConnectionTestResult? = nil
    @State private var isTestingBot = false
    @State private var showDashboard = false
    @State private var showSubscriptionSheet = false
    @State private var newProfileName = ""
    @State private var newProfilePort = ""
    @State private var discoveryResult: String? = nil
    @State private var detailProfile: HermesProfile? = nil
    @State private var editingProfile: HermesProfile? = nil
    @State private var editingProfileName = ""
    @State private var showEditAlert = false

    private enum ConnectionTestResult {
        case success, failure(String)
    }

    var body: some View {
        Form {
            Section("settings.connection") {
                TextField("settings.connection.host", text: $appSettings.serverHost)
                    .textContentType(.URL)
                    .keyboardType(.URL)
                HStack {
                    if showApiKeyInput {
                        TextField("API Key", text: $appSettings.apiKey)
                    } else {
                        SecureField("API Key", text: $appSettings.apiKey)
                    }
                    Button {
                        showApiKeyInput.toggle()
                    } label: {
                        Image(systemName: showApiKeyInput ? "eye" : "eye.slash")
                    }
                    .buttonStyle(.plain)
                }
                Button("settings.connection.test") {
                    testConnection()
                }
                .disabled(isTesting)

                if let result = testResult {
                    HStack(spacing: 6) {
                        switch result {
                        case .success:
                            Image(systemName: "checkmark.circle.fill").foregroundStyle(.green)
                            Text("settings.connection.test.success").foregroundStyle(.green)
                        case .failure(let msg):
                            Image(systemName: "xmark.circle.fill").foregroundStyle(.red)
                            Text(verbatim: msg).foregroundStyle(.red)
                        }
                    }
                    .font(.footnote)
                }
            }

            Section {
                ForEach(appSettings.profiles) { profile in
                    HStack {
                        VStack(alignment: .leading, spacing: 2) {
                            Text(profile.name)
                            Text(verbatim: String(format: NSLocalizedString("settings.profiles.port", comment: ""), profile.port))
                                .font(.caption)
                                .foregroundStyle(.secondary)
                        }
                        Spacer()
                        if profile.id == appSettings.selectedProfileID {
                            Image(systemName: "checkmark")
                                .foregroundStyle(.tint)
                        }
                        Button {
                            detailProfile = profile
                        } label: {
                            Image(systemName: "info.circle")
                                .foregroundStyle(.tint)
                        }
                        .buttonStyle(.borderless)
                    }
                    .contentShape(Rectangle())
                    .onTapGesture { appSettings.selectProfile(profile) }
                    .onLongPressGesture {
                        editingProfile = profile
                        editingProfileName = profile.name
                        showEditAlert = true
                    }
                }
                .onDelete { appSettings.removeProfiles(at: $0) }

                HStack {
                    TextField("settings.profiles.name.placeholder", text: $newProfileName)
                    TextField("settings.profiles.port.placeholder", text: $newProfilePort)
                        .keyboardType(.numberPad)
                        .frame(width: 70)
                    Button("common.add") {
                        if let port = Int(newProfilePort.trimmingCharacters(in: .whitespaces)) {
                            appSettings.addProfile(name: newProfileName, port: port)
                            newProfileName = ""
                            newProfilePort = ""
                        }
                    }
                    .disabled(newProfileName.trimmingCharacters(in: .whitespaces).isEmpty
                              || Int(newProfilePort.trimmingCharacters(in: .whitespaces)) == nil)
                }

                Button {
                    Task {
                        discoveryResult = nil
                        let added = await appSettings.discoverProfiles()
                        discoveryResult = added > 0
                            ? String(format: NSLocalizedString("settings.profiles.discover.added", comment: ""), added)
                            : String(localized: "settings.profiles.discover.none")
                    }
                } label: {
                    if appSettings.isDiscoveringProfiles {
                        HStack(spacing: 8) {
                            ProgressView().scaleEffect(0.8)
                            Text("settings.profiles.discovering")
                        }
                    } else {
                        Label("settings.profiles.discover", systemImage: "antenna.radiowaves.left.and.right")
                    }
                }
                .disabled(appSettings.isDiscoveringProfiles)

                if let discoveryResult {
                    Text(verbatim: discoveryResult)
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                }
            } header: {
                Text("settings.profiles")
            } footer: {
                Text("settings.profiles.footer")
            }

            // MARK: Cloud 계정 / 구독 / 클라우드 설정 — 1.1에서 cloudFeaturesEnabled = true 로 복구
            if AppSettings.cloudFeaturesEnabled {
                Section {
                    NavigationLink {
                        AuthView(appSettings: appSettings)
                    } label: {
                        if appSettings.isCloudAuthenticated {
                            HStack {
                                Label(
                                    appSettings.supabaseEmail.isEmpty
                                        ? String(localized: "auth.label.signedin")
                                        : appSettings.supabaseEmail,
                                    systemImage: "person.crop.circle.badge.checkmark"
                                )
                                Spacer()
                                if !appSettings.cloudPlan.isEmpty {
                                    Text("auth.plan.\(appSettings.cloudPlan)")
                                        .font(.caption)
                                        .foregroundStyle(.secondary)
                                }
                            }
                        } else {
                            Label("auth.label.signedout", systemImage: "person.crop.circle.badge.plus")
                        }
                    }
                    if appSettings.isCloudAuthenticated && appSettings.connectionMode == .cloud {
                        HStack {
                            Label("settings.usage", systemImage: "chart.bar.fill")
                            Spacer()
                            if let limit = appSettings.usageLimit {
                                Text(String(format: NSLocalizedString("settings.usage.count", comment: ""),
                                            appSettings.usageCount, limit))
                                    .font(.caption)
                                    .foregroundStyle(
                                        appSettings.usageCount >= limit ? .red
                                        : appSettings.usageCount >= Int(Double(limit) * 0.8) ? .orange
                                        : .secondary
                                    )
                            } else if appSettings.usageCount > 0 || !appSettings.cloudPlan.isEmpty {
                                Text("settings.usage.unlimited")
                                    .font(.caption)
                                    .foregroundStyle(.secondary)
                            }
                        }
                    }
                } header: {
                    Text("settings.cloud_account")
                } footer: {
                    Text("settings.cloud_account.footer")
                }
                .task {
                    if appSettings.isCloudAuthenticated && appSettings.connectionMode == .cloud {
                        await appSettings.fetchUsage()
                    }
                }

                if appSettings.isCloudAuthenticated {
                    Section {
                        if subscriptionService.isLoading {
                            HStack(spacing: 8) {
                                ProgressView().scaleEffect(0.8)
                                Text("subscription.loading")
                                    .foregroundStyle(.secondary)
                            }
                        } else if subscriptionService.products.isEmpty {
                            Text("subscription.unavailable")
                                .foregroundStyle(.secondary)
                                .font(.footnote)
                        } else if let active = subscriptionService.activeSubscription {
                            HStack {
                                Label(active.displayName, systemImage: "checkmark.seal.fill")
                                    .foregroundStyle(.tint)
                                Spacer()
                                Text(active.displayPrice)
                                    .font(.caption)
                                    .foregroundStyle(.secondary)
                            }
                            Button("subscription.manage") {
                                showSubscriptionSheet = true
                            }
                        } else {
                            Button {
                                showSubscriptionSheet = true
                            } label: {
                                Label("subscription.upgrade", systemImage: "arrow.up.circle")
                            }
                            Button("subscription.restore") {
                                Task { await subscriptionService.restorePurchases() }
                            }
                            .foregroundStyle(.secondary)
                        }
                    } header: {
                        Text("settings.subscription")
                    } footer: {
                        Text("settings.subscription.footer")
                    }
                    .sheet(isPresented: $showSubscriptionSheet) {
                        SubscriptionSheetView(subscriptionService: subscriptionService)
                    }
                }

                Section {
                    TextField("auth.supabase_url", text: $appSettings.supabaseURL)
                        .textContentType(.URL)
                        .keyboardType(.URL)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                    SecureField("auth.supabase_anon_key", text: $appSettings.supabaseAnonKey)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                    TextField("auth.cloud_gateway_url", text: $appSettings.cloudGatewayURL)
                        .textContentType(.URL)
                        .keyboardType(.URL)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                } header: {
                    Text("settings.cloud.config")
                }
            } // cloudFeaturesEnabled

            Section {
                TextField("settings.bridge.url.placeholder", text: $appSettings.bridgeHost)
                    .textContentType(.URL)
                    .keyboardType(.URL)
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled()
                HStack {
                    if showBridgeTokenInput {
                        TextField("Bridge Token", text: $appSettings.bridgeToken)
                    } else {
                        SecureField("Bridge Token", text: $appSettings.bridgeToken)
                    }
                    Button {
                        showBridgeTokenInput.toggle()
                    } label: {
                        Image(systemName: showBridgeTokenInput ? "eye" : "eye.slash")
                    }
                    .buttonStyle(.plain)
                }
                Button("settings.bridge.test") {
                    testBridgeConnection()
                }
                .disabled(isTestingBridge)

                if let result = bridgeTestResult {
                    HStack(spacing: 6) {
                        switch result {
                        case .success:
                            Image(systemName: "checkmark.circle.fill").foregroundStyle(.green)
                            Text("settings.bridge.test.success").foregroundStyle(.green)
                        case .failure(let msg):
                            Image(systemName: "xmark.circle.fill").foregroundStyle(.red)
                            Text(verbatim: msg).foregroundStyle(.red)
                        }
                    }
                    .font(.footnote)
                }
            } header: {
                Text("settings.bridge")
            } footer: {
                Text("settings.bridge.desc")
            }

            Section {
                TextField("settings.bot.url.placeholder", text: $appSettings.botHost)
                    .textContentType(.URL)
                    .keyboardType(.URL)
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled()
                HStack {
                    if showBotTokenInput {
                        TextField("Bot Token", text: $appSettings.botToken)
                    } else {
                        SecureField("Bot Token", text: $appSettings.botToken)
                    }
                    Button {
                        showBotTokenInput.toggle()
                    } label: {
                        Image(systemName: showBotTokenInput ? "eye" : "eye.slash")
                    }
                    .buttonStyle(.plain)
                }
                .textInputAutocapitalization(.never)
                .autocorrectionDisabled()
                Button("settings.bot.test") {
                    testBotConnection()
                }
                .disabled(isTestingBot || appSettings.botClient == nil)

                if let result = botTestResult {
                    HStack(spacing: 6) {
                        switch result {
                        case .success:
                            Image(systemName: "checkmark.circle.fill").foregroundStyle(.green)
                            Text("settings.bot.test.success").foregroundStyle(.green)
                        case .failure(let msg):
                            Image(systemName: "xmark.circle.fill").foregroundStyle(.red)
                            Text(verbatim: msg).foregroundStyle(.red)
                        }
                    }
                    .font(.footnote)
                }
            } header: {
                Text("settings.bot")
            } footer: {
                Text("settings.bot.desc")
            }

            Section("settings.model.default") {
                TextField("Model", text: $appSettings.selectedModel)
            }

            Section {
                NavigationLink {
                    SkillsView(appSettings: appSettings)
                } label: {
                    Label("settings.skills", systemImage: "wand.and.stars")
                }
                NavigationLink {
                    FileBrowserView(appSettings: appSettings)
                } label: {
                    Label("settings.filebrowser", systemImage: "folder")
                }
                Button {
                    showDashboard = true
                } label: {
                    Label("settings.dashboard.open", systemImage: "gauge.with.dots.needle.50percent")
                        .foregroundStyle(.primary)
                }
            } footer: {
                Text(verbatim: String(format: NSLocalizedString("settings.skills.footer", comment: ""), appSettings.selectedProfile.name))
            }

            Section {
                Link("settings.tailscale.download", destination: URL(string: "https://tailscale.com")!)
            }

            Section {
                Button {
                    if let url = URL(string: "app-settings:") {
                        openURL(url)
                    }
                } label: {
                    Label("settings.language", systemImage: "globe")
                        .foregroundStyle(.primary)
                }
            } footer: {
                Text("settings.language.footer")
            }

            Section {
                Link("settings.privacy.policy", destination: URL(string: "https://hermeschat.app/privacy")!)
            }
        }
        .navigationTitle("settings.title")
        .navigationBarTitleDisplayMode(.large)
        .navigationDestination(item: $detailProfile) { profile in
            ProfileDetailView(appSettings: appSettings, profileID: profile.id)
        }
        // 대시보드는 탭에서 빠지고 설정 안에서 전체 화면으로 연다 (T-168)
        .fullScreenCover(isPresented: $showDashboard) {
            DashboardWebView(appSettings: appSettings)
        }
        .alert("settings.profile.rename.title", isPresented: $showEditAlert, presenting: editingProfile) { profile in
            TextField("settings.profile.rename.placeholder", text: $editingProfileName)
            Button("common.cancel", role: .cancel) { }
            Button("common.save") {
                var updated = profile
                updated.name = editingProfileName.trimmingCharacters(in: .whitespaces)
                appSettings.updateProfile(updated)
            }
        } message: { _ in
            Text("settings.profile.rename.message")
        }
    }

    /// 봇 서버 연결 테스트 — /health 로 서버 확인 후 /api/bots 로 토큰·응답 확인
    private func testBotConnection() {
        guard let client = appSettings.botClient else { return }
        isTestingBot = true
        botTestResult = nil
        Task {
            do {
                try await client.health()
                _ = try await client.fetchBots()
                botTestResult = .success
            } catch BotError.unauthorized {
                botTestResult = .failure(NSLocalizedString("settings.bot.test.unauthorized", comment: ""))
            } catch {
                botTestResult = .failure(error.localizedDescription)
            }
            isTestingBot = false
        }
    }

    private func testConnection() {
        // /health 는 무인증이라 연결만 확인하고, API Key 검증은 /api/sessions 로 한다.
        let base = appSettings.baseURL(for: appSettings.selectedProfile)
        let healthURL = base.appendingPathComponent("health")
        let sessionsURL = base.appendingPathComponent("api/sessions")

        isTesting = true
        testResult = nil

        Task {
            do {
                var healthReq = URLRequest(url: healthURL)
                healthReq.timeoutInterval = 10
                let (_, healthResp) = try await URLSession.shared.data(for: healthReq)
                guard let healthHTTP = healthResp as? HTTPURLResponse, healthHTTP.statusCode == 200 else {
                    let code = (healthResp as? HTTPURLResponse)?.statusCode ?? 0
                    testResult = .failure(String(format: NSLocalizedString("settings.connection.test.status", comment: ""), code))
                    isTesting = false
                    return
                }

                let key = appSettings.apiKey.trimmingCharacters(in: .whitespacesAndNewlines)
                if key.isEmpty {
                    // 호스트 도달만 확인 (키 미입력)
                    testResult = .success
                    isTesting = false
                    return
                }

                var sessionsReq = URLRequest(url: sessionsURL)
                sessionsReq.timeoutInterval = 10
                sessionsReq.setValue("Bearer \(key)", forHTTPHeaderField: "Authorization")
                let (_, sessionsResp) = try await URLSession.shared.data(for: sessionsReq)
                if let http = sessionsResp as? HTTPURLResponse {
                    if (200..<300).contains(http.statusCode) {
                        testResult = .success
                    } else if http.statusCode == 401 {
                        testResult = .failure(String(localized: "settings.connection.test.unauthorized"))
                    } else {
                        testResult = .failure(String(format: NSLocalizedString("settings.connection.test.status", comment: ""), http.statusCode))
                    }
                } else {
                    testResult = .failure(String(localized: "settings.connection.test.response_error"))
                }
            } catch {
                testResult = .failure(String(format: NSLocalizedString("settings.connection.test.failed", comment: ""), error.localizedDescription))
            }
            isTesting = false
        }
    }

    /// Bridge URL + Token 을 `/profiles` 로 검증 (model/SOUL/칸반과 동일 인증 경로).
    private func testBridgeConnection() {
        isTestingBridge = true
        bridgeTestResult = nil

        Task {
            guard let bridge = appSettings.bridgeClient else {
                let hostEmpty = appSettings.bridgeHost.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
                let tokenEmpty = appSettings.bridgeToken.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
                if hostEmpty {
                    bridgeTestResult = .failure(String(localized: "settings.bridge.test.missing_url"))
                } else if tokenEmpty {
                    bridgeTestResult = .failure(String(localized: "settings.bridge.test.missing_token"))
                } else {
                    bridgeTestResult = .failure(String(localized: "settings.bridge.test.invalid_url"))
                }
                isTestingBridge = false
                return
            }
            do {
                _ = try await bridge.fetchProfiles()
                bridgeTestResult = .success
            } catch {
                bridgeTestResult = .failure(error.localizedDescription)
            }
            isTestingBridge = false
        }
    }
}

// MARK: - SubscriptionSheetView

private struct SubscriptionSheetView: View {
    @ObservedObject var subscriptionService: SubscriptionService
    @Environment(\.dismiss) private var dismiss
    @State private var errorMessage: String?
    @State private var isPurchasing = false

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(spacing: 24) {
                    VStack(spacing: 8) {
                        Image(systemName: "star.circle.fill")
                            .font(.system(size: 56))
                            .foregroundStyle(.tint)
                            .padding(.top, 16)
                        Text("subscription.sheet.title")
                            .font(.title2.bold())
                        Text("subscription.sheet.subtitle")
                            .font(.body)
                            .foregroundStyle(.secondary)
                            .multilineTextAlignment(.center)
                    }
                    .padding(.horizontal, 24)

                    VStack(spacing: 12) {
                        ForEach(subscriptionService.products, id: \.id) { product in
                            let isPurchased = subscriptionService.purchasedProductIDs.contains(product.id)
                            Button {
                                guard !isPurchased else { return }
                                Task {
                                    isPurchasing = true
                                    errorMessage = nil
                                    defer { isPurchasing = false }
                                    do {
                                        _ = try await subscriptionService.purchase(product)
                                    } catch {
                                        errorMessage = error.localizedDescription
                                    }
                                }
                            } label: {
                                HStack {
                                    VStack(alignment: .leading, spacing: 4) {
                                        Text(product.displayName)
                                            .font(.headline)
                                            .foregroundStyle(.primary)
                                        Text(product.description)
                                            .font(.caption)
                                            .foregroundStyle(.secondary)
                                            .multilineTextAlignment(.leading)
                                    }
                                    Spacer()
                                    if isPurchased {
                                        Image(systemName: "checkmark.circle.fill")
                                            .foregroundStyle(.tint)
                                    } else {
                                        Text(product.displayPrice)
                                            .font(.subheadline.weight(.semibold))
                                            .foregroundStyle(.tint)
                                    }
                                }
                                .padding(16)
                                .background(
                                    RoundedRectangle(cornerRadius: 12)
                                        .fill(Color(.secondarySystemGroupedBackground))
                                        .overlay(
                                            RoundedRectangle(cornerRadius: 12)
                                                .stroke(isPurchased ? Color.accentColor : .clear, lineWidth: 2)
                                        )
                                )
                            }
                            .buttonStyle(.plain)
                            .disabled(isPurchased || isPurchasing)
                        }
                    }
                    .padding(.horizontal, 24)

                    if isPurchasing {
                        ProgressView()
                    }

                    if let error = errorMessage {
                        Text(error)
                            .font(.footnote)
                            .foregroundStyle(.red)
                            .multilineTextAlignment(.center)
                            .padding(.horizontal, 24)
                    }

                    Button("subscription.restore") {
                        Task { await subscriptionService.restorePurchases() }
                    }
                    .font(.footnote)
                    .foregroundStyle(.secondary)
                }
                .padding(.bottom, 24)
            }
            .navigationTitle("subscription.sheet.title")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .navigationBarTrailing) {
                    Button("common.done") { dismiss() }
                }
            }
        }
    }
}
