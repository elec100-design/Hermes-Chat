import SwiftUI
import Security

struct SettingsView: View {
    @ObservedObject var appSettings: AppSettings
    @StateObject private var subscriptionService = SubscriptionService.shared
    @Environment(\.openURL) private var openURL
    @State private var testResult: ConnectionTestResult? = nil
    @State private var isTesting = false
    @State private var showApiKeyInput = false
    @State private var showGeminiKeyInput = false
    @State private var showBridgeTokenInput = false
    @State private var showSubscriptionSheet = false
    @State private var newProfileName = ""
    @State private var newProfilePort = ""
    @State private var discoveryResult: String? = nil
    @State private var detailProfile: HermesProfile? = nil
    @State private var editingProfile: HermesProfile? = nil
    @State private var editingProfileName = ""
    @State private var showEditAlert = false
    @State private var bridgeTestResult: String? = nil
    @State private var bridgeTestOK = false
    @State private var isTestingBridge = false

    private enum ConnectionTestResult {
        case success, failure(String)
    }

    var body: some View {
        Form {
            Section {
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
            } header: {
                Label("settings.connection", systemImage: "antenna.radiowaves.left.and.right")
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
                Label("settings.profiles", systemImage: "person.2")
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
                    Label("settings.cloud_account", systemImage: "icloud")
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
                        } else if subscriptionService.hasLifetime {
                            // 평생 이용권 보유 — 업그레이드 불필요, 복원만 유지 (T-159)
                            Label("subscription.lifetime.active", systemImage: "infinity.circle.fill")
                                .foregroundStyle(.tint)
                            Button("subscription.restore") {
                                Task { await subscriptionService.restorePurchases() }
                            }
                            .foregroundStyle(.secondary)
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
                        Label("settings.subscription", systemImage: "star.circle")
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
                    Label("settings.cloud.config", systemImage: "gearshape.2")
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
                // 브리지는 /health만 무인증이라, 주소가 맞아도 토큰이 틀리면 모든 기능이
                // 401로 조용히 죽는다. 두 단계를 따로 찍어 어디가 문제인지 알려준다 (T-170).
                Button {
                    testBridgeConnection()
                } label: {
                    HStack {
                        Text("settings.bridge.test")
                        if isTestingBridge {
                            Spacer()
                            ProgressView()
                        }
                    }
                }
                .disabled(isTestingBridge || appSettings.bridgeClient == nil)
                if let bridgeTestResult {
                    Text(bridgeTestResult)
                        .font(.footnote)
                        .foregroundStyle(bridgeTestOK ? .green : .red)
                }
            } header: {
                Label("settings.bridge", systemImage: "link")
            } footer: {
                Text("settings.bridge.desc")
            }

            Section("settings.model.default") {
                TextField("Model", text: $appSettings.selectedModel)
            }

            // MARK: - 음성 읽어주기 (T-153) — 로컬 전용
            Section {
                VStack(alignment: .leading) {
                    Text("말하기 속도")
                    Slider(value: $appSettings.ttsRate, in: 0.4...0.7, step: 0.01) {
                        Text("말하기 속도")
                    } minimumValueLabel: {
                        Image(systemName: "tortoise")
                    } maximumValueLabel: {
                        Image(systemName: "hare")
                    }
                    .onChange(of: appSettings.ttsRate) { _, _ in
                        SpeechService.shared.invalidateVoiceCache()
                    }
                }
            } header: {
                Label("음성 읽어주기", systemImage: "speaker.wave.2")
            } footer: {
                Text("세션 음성 답변은 기기에서만 합성됩니다(외부 전송 없음). 더 자연스러운 목소리는 설정 > 손쉬운 사용 > 음성 콘텐츠에서 고품질 한국어 음성을 내려받으면 자동 적용됩니다.")
            }

            // MARK: - Live 음성 백엔드 (T-160) — Live 탭에서 Gemini/Hermes 선택.
            Section {
                Picker("기본 백엔드", selection: $appSettings.liveVoiceBackend) {
                    ForEach(LiveVoiceBackend.allCases) { b in
                        Text(b.displayName).tag(b)
                    }
                }
                if appSettings.liveVoiceBackend == .hermes {
                    Picker("Hermes TTS", selection: $appSettings.hermesLiveTTSMode) {
                        Text("기기 내장").tag(HermesLiveTTSMode.local)
                        Text("서버 (OpenAI)").tag(HermesLiveTTSMode.server)
                    }
                    if appSettings.hermesLiveTTSMode == .server {
                        TextField("TTS 엔드포인트 URL", text: $appSettings.hermesLiveTTSEndpoint)
                            .textContentType(.URL)
                            .keyboardType(.URL)
                            .textInputAutocapitalization(.never)
                            .autocorrectionDisabled()
                        TextField("TTS 보이스 (예: alloy)", text: $appSettings.hermesLiveTTSVoice)
                            .textInputAutocapitalization(.never)
                            .autocorrectionDisabled()
                    }
                    VStack(alignment: .leading, spacing: 4) {
                        Text("시스템 프롬프트").font(.caption).foregroundStyle(.secondary)
                        TextField("시스템 프롬프트", text: $appSettings.hermesLiveSystemPrompt, axis: .vertical)
                            .lineLimit(2...5)
                    }
                }
            } header: {
                Label("Live 음성", systemImage: "waveform")
            } footer: {
                if appSettings.liveVoiceBackend == .hermes {
                    Text("Hermes 백엔드는 기기에서 음성을 인식해 텍스트로 게이트웨이에 보내고, 답변을 문장 단위로 읽어줍니다. 서버 TTS 엔드포인트는 아직 검증되지 않았습니다 — hermes-agent 게이트웨이의 실제 경로를 맥미니에서 확인해 입력하세요. 호출 실패 시 자동으로 기기 내장 음성으로 전환됩니다.")
                } else {
                    Text("새 Live 대화가 사용할 음성 백엔드입니다. 대화 시작 전 화면에서도 바꿀 수 있습니다.")
                }
            }

            // MARK: - Gemini Live (T-157) — Live 탭 전용. 오디오가 Google로 전송됨.
            Section {
                HStack {
                    if showGeminiKeyInput {
                        TextField("Gemini API Key", text: $appSettings.geminiAPIKey)
                    } else {
                        SecureField("Gemini API Key", text: $appSettings.geminiAPIKey)
                    }
                    Button {
                        showGeminiKeyInput.toggle()
                    } label: {
                        Image(systemName: showGeminiKeyInput ? "eye" : "eye.slash")
                    }
                    .buttonStyle(.plain)
                }
                Picker("음성", selection: $appSettings.geminiLiveVoice) {
                    ForEach(GeminiVoice.allCases) { v in
                        Text("\(v.rawValue) · \(v.subtitle)").tag(v.rawValue)
                    }
                }
                TextField("모델", text: $appSettings.geminiLiveModel)
                    .autocorrectionDisabled()
                    .textInputAutocapitalization(.never)
                VStack(alignment: .leading, spacing: 4) {
                    Text("시스템 프롬프트").font(.caption).foregroundStyle(.secondary)
                    TextField("시스템 프롬프트", text: $appSettings.geminiLiveSystemPrompt, axis: .vertical)
                        .lineLimit(2...5)
                }
            } header: {
                Label("Gemini Live", systemImage: "sparkles")
            } footer: {
                Text("Live 탭의 실시간 음성 대화에 사용됩니다. 마이크 오디오와 자막이 Google로 전송되며, 비용은 본인 Google AI Studio 키로 종량 과금됩니다. 키는 기기 Keychain에만 저장됩니다.")
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

    /// 1단계 `/health`(무인증)로 주소·도달성을, 2단계 `/profiles`(인증)로 토큰을 확인한다.
    private func testBridgeConnection() {
        guard let bridge = appSettings.bridgeClient else { return }
        isTestingBridge = true
        bridgeTestResult = nil
        Task {
            defer { isTestingBridge = false }
            let health: BridgeClient.BridgeHealth
            do {
                health = try await bridge.health()
            } catch {
                bridgeTestOK = false
                bridgeTestResult = String(
                    format: NSLocalizedString("settings.bridge.test.unreachable %@", comment: ""),
                    error.localizedDescription
                )
                return
            }
            do {
                let profiles = try await bridge.fetchProfiles()
                bridgeTestOK = true
                bridgeTestResult = String(
                    format: NSLocalizedString("settings.bridge.test.ok %lld %@", comment: ""),
                    profiles.count,
                    profiles.map(\.name).joined(separator: ", ")
                )
            } catch {
                bridgeTestOK = false
                let tokenEmpty = appSettings.bridgeToken.trimmingCharacters(in: .whitespaces).isEmpty
                if health.authRequired == true, tokenEmpty {
                    bridgeTestResult = String(localized: "settings.bridge.test.token.empty")
                } else {
                    bridgeTestResult = String(
                        format: NSLocalizedString("settings.bridge.test.auth.failed %@", comment: ""),
                        error.localizedDescription
                    )
                }
            }
        }
    }

    private func testConnection() {
        let url = appSettings.baseURL(for: appSettings.selectedProfile).appendingPathComponent("health")

        isTesting = true
        testResult = nil

        var request = URLRequest(url: url)
        if !appSettings.apiKey.isEmpty {
            request.setValue(appSettings.apiKey, forHTTPHeaderField: "Authorization")
        }

        Task {
            do {
                let (_, response) = try await URLSession.shared.data(for: request)
                if let http = response as? HTTPURLResponse {
                    if http.statusCode == 200 {
                        testResult = .success
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
                                        if product.type == .nonConsumable {
                                            Label("subscription.lifetime.badge", systemImage: "infinity")
                                                .font(.caption2.weight(.semibold))
                                                .foregroundStyle(.tint)
                                        }
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
