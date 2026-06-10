import SwiftUI
import Security

struct SettingsView: View {
    @ObservedObject var appSettings: AppSettings
    @State private var testResult: String? = nil
    @State private var isTesting = false
    @State private var showApiKeyInput = false
    @State private var showBridgeTokenInput = false
    @State private var newProfileName = ""
    @State private var newProfilePort = ""
    @State private var discoveryResult: String? = nil
    @State private var detailProfile: HermesProfile? = nil

    var body: some View {
        Form {
            Section("Hermes 연결") {
                TextField("Server Host", text: $appSettings.serverHost)
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
                Button("연결 테스트") {
                    testConnection()
                }
                .disabled(isTesting)

                if let testResult {
                    Text(testResult)
                        .font(.footnote)
                        .foregroundStyle(testResult.contains("성공") ? .green : .red)
                }
            }

            Section {
                ForEach(appSettings.profiles) { profile in
                    HStack {
                        VStack(alignment: .leading, spacing: 2) {
                            Text(profile.name)
                            Text("포트 " + String(profile.port))
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
                }
                .onDelete { appSettings.removeProfiles(at: $0) }

                HStack {
                    TextField("이름", text: $newProfileName)
                    TextField("포트", text: $newProfilePort)
                        .keyboardType(.numberPad)
                        .frame(width: 70)
                    Button("추가") {
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
                        discoveryResult = added > 0 ? "\(added)개 프로필 발견" : "새 프로필 없음 (8642–8651 스캔)"
                    }
                } label: {
                    if appSettings.isDiscoveringProfiles {
                        HStack(spacing: 8) {
                            ProgressView().scaleEffect(0.8)
                            Text("검색 중...")
                        }
                    } else {
                        Label("프로필 자동 검색", systemImage: "antenna.radiowaves.left.and.right")
                    }
                }
                .disabled(appSettings.isDiscoveringProfiles)

                if let discoveryResult {
                    Text(discoveryResult)
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                }
            } header: {
                Text("프로필 (맥미니 게이트웨이)")
            } footer: {
                Text("행을 탭하면 해당 프로필로 전환하고, ⓘ를 탭하면 모델 선택·SOUL.md 편집·게이트웨이 재시작 화면으로 이동합니다.")
            }

            Section {
                TextField("Bridge URL (예: http://100.x.x.x:8765)", text: $appSettings.bridgeHost)
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
                Text("Hermes Bridge")
            } footer: {
                Text("맥미니의 브리지(:8765)입니다. SOUL.md 편집, 게이트웨이 재시작, 프로필 자동 검색, 파일 업로드에 사용합니다.")
            }

            Section("기본 모델") {
                TextField("Model", text: $appSettings.selectedModel)
            }

            Section {
                NavigationLink {
                    SkillsView(appSettings: appSettings)
                } label: {
                    Label("Skills & Tools", systemImage: "wand.and.stars")
                }
                NavigationLink {
                    FileBrowserView(appSettings: appSettings)
                } label: {
                    Label("파일 브라우저 (~/.hermes)", systemImage: "folder")
                }
            } footer: {
                Text("Skills & Tools는 현재 선택된 프로필(\(appSettings.selectedProfile.name)) 게이트웨이 기준이고, 파일 브라우저는 Bridge가 필요합니다 (읽기전용).")
            }

            Section {
                Link("Tailscale 다운로드", destination: URL(string: "https://tailscale.com")!)
            }
        }
        .navigationTitle("설정")
        .navigationBarTitleDisplayMode(.large)
        .navigationDestination(item: $detailProfile) { profile in
            ProfileDetailView(appSettings: appSettings, profileID: profile.id)
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
                    testResult = http.statusCode == 200 ? "연결 성공" : "상태 코드: \(http.statusCode)"
                } else {
                    testResult = "응답 형식 오류"
                }
            } catch {
                testResult = "연결 실패: \(error.localizedDescription)"
            }
            isTesting = false
        }
    }
}
