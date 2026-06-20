import SwiftUI

struct OnboardingView: View {
    @ObservedObject var appSettings: AppSettings
    @State private var step = 0
    @State private var hostInput = ""
    @State private var apiKeyInput = ""
    @State private var showApiKey = false
    @State private var testResult: TestResult? = nil
    @State private var isTesting = false
    @State private var goToCloudAuth = false

    enum TestResult {
        case success, failure(String)
    }

    var body: some View {
        NavigationStack {
            VStack(spacing: 0) {
                stepsIndicator

                TabView(selection: $step) {
                    welcomeStep.tag(0)
                    serverStep.tag(1)
                    doneStep.tag(2)
                }
                .tabViewStyle(.page(indexDisplayMode: .never))
                .animation(.easeInOut, value: step)
            }
            .navigationBarHidden(true)
        .navigationDestination(isPresented: $goToCloudAuth) {
            AuthView(appSettings: appSettings)
                .toolbar {
                    ToolbarItem(placement: .navigationBarTrailing) {
                        Button("onboarding.done.start") {
                            appSettings.isFirstLaunchComplete = true
                        }
                    }
                }
        }
        }
    }

    // MARK: - Steps indicator

    private var stepsIndicator: some View {
        HStack(spacing: 8) {
            ForEach(0..<3) { i in
                Capsule()
                    .fill(i <= step ? Color.accentColor : Color.secondary.opacity(0.3))
                    .frame(width: i == step ? 24 : 8, height: 8)
                    .animation(.spring(response: 0.3), value: step)
            }
        }
        .padding(.top, 60)
        .padding(.bottom, 20)
    }

    // MARK: - Step 0: Welcome

    private var welcomeStep: some View {
        VStack(spacing: 24) {
            Spacer()

            Image(systemName: "bubble.left.and.bubble.right.fill")
                .font(.system(size: 72))
                .foregroundStyle(.tint)

            VStack(spacing: 8) {
                Text("onboarding.welcome.title")
                    .font(.title.bold())
                    .multilineTextAlignment(.center)
                Text("onboarding.welcome.subtitle")
                    .font(.body)
                    .foregroundStyle(.secondary)
                    .multilineTextAlignment(.center)
            }
            .padding(.horizontal, 32)

            Spacer()

            VStack(spacing: 12) {
                connectionOptionButton(
                    icon: "server.rack",
                    titleKey: "onboarding.welcome.selfhosted",
                    descKey: "onboarding.welcome.selfhosted.desc",
                    isEnabled: true
                ) {
                    withAnimation { step = 1 }
                }

                connectionOptionButton(
                    icon: "icloud.fill",
                    titleKey: "onboarding.welcome.cloud",
                    descKey: "onboarding.welcome.cloud.desc",
                    isEnabled: true
                ) {
                    appSettings.connectionMode = .cloud
                    goToCloudAuth = true
                }
            }
            .padding(.horizontal, 24)
            .padding(.bottom, 48)
        }
    }

    private func connectionOptionButton(
        icon: String,
        titleKey: LocalizedStringKey,
        descKey: LocalizedStringKey,
        isEnabled: Bool,
        action: @escaping () -> Void
    ) -> some View {
        Button(action: action) {
            HStack(spacing: 16) {
                Image(systemName: icon)
                    .font(.title2)
                    .foregroundStyle(isEnabled ? Color.accentColor : Color.secondary)
                    .frame(width: 36)

                VStack(alignment: .leading, spacing: 4) {
                    Text(titleKey)
                        .font(.headline)
                        .foregroundStyle(isEnabled ? .primary : .secondary)
                    Text(descKey)
                        .font(.caption)
                        .foregroundStyle(.secondary)
                        .multilineTextAlignment(.leading)
                }

                Spacer()

                if isEnabled {
                    Image(systemName: "chevron.right")
                        .foregroundStyle(.secondary)
                }
            }
            .padding(16)
            .background(
                RoundedRectangle(cornerRadius: 12)
                    .fill(Color(.secondarySystemGroupedBackground))
            )
        }
        .buttonStyle(.plain)
        .disabled(!isEnabled)
        .opacity(isEnabled ? 1 : 0.5)
    }

    // MARK: - Step 1: Server Setup

    private var serverStep: some View {
        VStack(spacing: 0) {
            ScrollView {
                VStack(spacing: 24) {
                    VStack(spacing: 8) {
                        Image(systemName: "network")
                            .font(.system(size: 48))
                            .foregroundStyle(.tint)
                            .padding(.top, 8)
                        Text("onboarding.server.title")
                            .font(.title2.bold())
                    }

                    VStack(alignment: .leading, spacing: 16) {
                        VStack(alignment: .leading, spacing: 6) {
                            Text("onboarding.server.host.label")
                                .font(.subheadline.weight(.medium))
                            TextField("onboarding.server.host.placeholder", text: $hostInput)
                                .textContentType(.URL)
                                .keyboardType(.URL)
                                .autocorrectionDisabled()
                                .textInputAutocapitalization(.never)
                                .padding(12)
                                .background(
                                    RoundedRectangle(cornerRadius: 10)
                                        .fill(Color(.secondarySystemGroupedBackground))
                                )
                        }

                        VStack(alignment: .leading, spacing: 6) {
                            Text("onboarding.server.apikey.label")
                                .font(.subheadline.weight(.medium))
                            HStack {
                                Group {
                                    if showApiKey {
                                        TextField("onboarding.server.apikey.placeholder", text: $apiKeyInput)
                                    } else {
                                        SecureField("onboarding.server.apikey.placeholder", text: $apiKeyInput)
                                    }
                                }
                                .autocorrectionDisabled()
                                .textInputAutocapitalization(.never)

                                Button {
                                    showApiKey.toggle()
                                } label: {
                                    Image(systemName: showApiKey ? "eye" : "eye.slash")
                                        .foregroundStyle(.secondary)
                                }
                                .buttonStyle(.plain)
                            }
                            .padding(12)
                            .background(
                                RoundedRectangle(cornerRadius: 10)
                                    .fill(Color(.secondarySystemGroupedBackground))
                            )
                        }

                        Button {
                            runConnectionTest()
                        } label: {
                            HStack {
                                if isTesting {
                                    ProgressView().scaleEffect(0.8)
                                    Text("onboarding.server.testing")
                                } else {
                                    Image(systemName: "antenna.radiowaves.left.and.right")
                                    Text("onboarding.server.test")
                                }
                            }
                            .frame(maxWidth: .infinity)
                            .padding(12)
                            .background(
                                RoundedRectangle(cornerRadius: 10)
                                    .fill(Color.accentColor)
                            )
                            .foregroundStyle(.white)
                        }
                        .buttonStyle(.plain)
                        .disabled(hostInput.trimmingCharacters(in: .whitespaces).isEmpty || isTesting)

                        if let result = testResult {
                            HStack(spacing: 6) {
                                switch result {
                                case .success:
                                    Image(systemName: "checkmark.circle.fill").foregroundStyle(.green)
                                    Text("onboarding.server.success").foregroundStyle(.green)
                                case .failure(let msg):
                                    Image(systemName: "xmark.circle.fill").foregroundStyle(.red)
                                    VStack(alignment: .leading) {
                                        Text("onboarding.server.failed").foregroundStyle(.red)
                                        Text(msg).font(.caption).foregroundStyle(.secondary)
                                    }
                                }
                            }
                            .font(.subheadline)
                        }
                    }
                    .padding(.horizontal, 24)
                }
                .padding(.bottom, 16)
            }

            VStack(spacing: 12) {
                Button {
                    applyServerSettings()
                    withAnimation { step = 2 }
                } label: {
                    Text("onboarding.next")
                        .frame(maxWidth: .infinity)
                        .padding(14)
                        .background(
                            RoundedRectangle(cornerRadius: 12)
                                .fill(hostInput.trimmingCharacters(in: .whitespaces).isEmpty
                                      ? Color.accentColor.opacity(0.4)
                                      : Color.accentColor)
                        )
                        .foregroundStyle(.white)
                        .font(.headline)
                }
                .buttonStyle(.plain)
                .disabled(hostInput.trimmingCharacters(in: .whitespaces).isEmpty)
                .padding(.horizontal, 24)

                Button {
                    withAnimation { step = 0 }
                } label: {
                    Text("onboarding.back")
                        .foregroundStyle(.secondary)
                }
            }
            .padding(.bottom, 40)
        }
    }

    // MARK: - Step 2: Done

    private var doneStep: some View {
        VStack(spacing: 24) {
            Spacer()

            Image(systemName: "checkmark.seal.fill")
                .font(.system(size: 72))
                .foregroundStyle(.tint)

            VStack(spacing: 8) {
                Text("onboarding.done.title")
                    .font(.title.bold())
                Text("onboarding.done.subtitle")
                    .font(.body)
                    .foregroundStyle(.secondary)
                    .multilineTextAlignment(.center)
                    .padding(.horizontal, 32)
            }

            Spacer()

            Button {
                appSettings.isFirstLaunchComplete = true
            } label: {
                Text("onboarding.done.start")
                    .frame(maxWidth: .infinity)
                    .padding(14)
                    .background(RoundedRectangle(cornerRadius: 12).fill(Color.accentColor))
                    .foregroundStyle(.white)
                    .font(.headline)
            }
            .buttonStyle(.plain)
            .padding(.horizontal, 24)
            .padding(.bottom, 48)
        }
    }

    // MARK: - Helpers

    private func runConnectionTest() {
        let host = hostInput.trimmingCharacters(in: .whitespaces)
        guard !host.isEmpty, let base = URL(string: host) else { return }

        let url = base.appendingPathComponent("health")
        var request = URLRequest(url: url, timeoutInterval: 10)
        if !apiKeyInput.isEmpty {
            request.setValue(apiKeyInput, forHTTPHeaderField: "Authorization")
        }

        isTesting = true
        testResult = nil

        Task {
            do {
                let (_, response) = try await URLSession.shared.data(for: request)
                if let http = response as? HTTPURLResponse, http.statusCode == 200 {
                    testResult = .success
                } else {
                    let code = (response as? HTTPURLResponse)?.statusCode ?? 0
                    testResult = .failure("HTTP \(code)")
                }
            } catch {
                testResult = .failure(error.localizedDescription)
            }
            isTesting = false
        }
    }

    private func applyServerSettings() {
        let host = hostInput.trimmingCharacters(in: .whitespaces)
        if !host.isEmpty { appSettings.serverHost = host }
        if !apiKeyInput.isEmpty { appSettings.apiKey = apiKeyInput }
    }
}
