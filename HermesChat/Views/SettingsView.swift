import SwiftUI
import Security

struct SettingsView: View {
    @ObservedObject var appSettings: AppSettings
    @State private var testResult: ConnectionTestResult? = nil
    @State private var isTesting = false
    @State private var showApiKeyInput = false
    @State private var showBridgeTokenInput = false
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
            } header: {
                Text("settings.bridge")
            } footer: {
                Text("settings.bridge.desc")
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
            } footer: {
                Text(verbatim: String(format: NSLocalizedString("settings.skills.footer", comment: ""), appSettings.selectedProfile.name))
            }

            Section {
                Link("settings.tailscale.download", destination: URL(string: "https://tailscale.com")!)
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
