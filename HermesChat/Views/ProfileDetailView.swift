import SwiftUI

/// 프로필 하나의 상세 화면: 모델 선택, SOUL.md(성격) 편집, 게이트웨이 재시작.
///
/// 모델 목록은 해당 프로필 게이트웨이의 `/v1/models`에서 가져온다.
/// SOUL.md 편집과 재시작은 Hermes Bridge가 필요하다 — 설정 화면의
/// "Hermes Bridge" 섹션에 URL과 토큰을 입력해야 활성화된다.
struct ProfileDetailView: View {
    @ObservedObject var appSettings: AppSettings
    let profileID: UUID

    private enum SoulState: Equatable {
        case loading
        case loaded
        case failed(String)
    }

    @State private var availableModels: [String] = []
    @State private var soulState: SoulState = .loading
    @State private var soulText = ""
    @State private var isSavingSoul = false
    @State private var isRestarting = false
    @State private var showRestartConfirm = false
    @State private var statusMessage: String?
    @State private var statusIsError = false

    private var profile: HermesProfile {
        appSettings.profiles.first { $0.id == profileID } ?? .default
    }

    private var bridgeConfigured: Bool { appSettings.bridgeClient != nil }

    /// 빈 문자열 = "전역 기본 모델 사용" (profile.model == nil)
    private var modelBinding: Binding<String> {
        Binding(
            get: { profile.model ?? "" },
            set: { newValue in
                var updated = profile
                updated.model = newValue.isEmpty ? nil : newValue
                appSettings.updateProfile(updated)
            }
        )
    }

    var body: some View {
        Form {
            Section("연결") {
                LabeledContent("프로필", value: profile.name)
                LabeledContent("포트", value: String(profile.port))
            }

            Section {
                Picker("모델", selection: modelBinding) {
                    Text("기본값 (\(appSettings.selectedModel))").tag("")
                    ForEach(availableModels, id: \.self) { model in
                        Text(model).tag(model)
                    }
                    if let current = profile.model, !current.isEmpty,
                       !availableModels.contains(current) {
                        Text(current).tag(current)
                    }
                }
            } header: {
                Text("모델")
            } footer: {
                Text("이 프로필에서 새 세션을 만들 때 사용할 모델입니다.")
            }

            soulSection

            if bridgeConfigured {
                Section {
                    Button(role: .destructive) {
                        showRestartConfirm = true
                    } label: {
                        if isRestarting {
                            HStack(spacing: 8) {
                                ProgressView()
                                Text("재시작 중...")
                            }
                        } else {
                            Label("Gateway 재시작", systemImage: "arrow.clockwise")
                        }
                    }
                    .disabled(isRestarting)
                } footer: {
                    Text("SOUL.md 변경은 게이트웨이를 재시작해야 반영됩니다.")
                }
            }

            if let statusMessage {
                Section {
                    Text(statusMessage)
                        .font(.footnote)
                        .foregroundStyle(statusIsError ? Color.red : Color.green)
                }
            }
        }
        .navigationTitle(profile.name)
        .navigationBarTitleDisplayMode(.inline)
        .confirmationDialog(
            "\(profile.name) 게이트웨이를 재시작할까요?\n진행 중인 응답이 끊길 수 있습니다.",
            isPresented: $showRestartConfirm,
            titleVisibility: .visible
        ) {
            Button("재시작", role: .destructive) {
                Task { await restartGateway() }
            }
            Button("취소", role: .cancel) {}
        }
        .task {
            await loadModels()
            await loadSoul()
        }
    }

    @ViewBuilder
    private var soulSection: some View {
        Section {
            if !bridgeConfigured {
                Text("설정 화면의 \"Hermes Bridge\" 섹션에 URL과 토큰을 입력하면 SOUL.md 편집과 게이트웨이 재시작을 쓸 수 있습니다.")
                    .font(.footnote)
                    .foregroundStyle(.secondary)
            } else {
                switch soulState {
                case .loading:
                    HStack(spacing: 8) {
                        ProgressView()
                        Text("불러오는 중...")
                    }
                case .failed(let message):
                    Text("불러오기 실패: \(message)")
                        .font(.footnote)
                        .foregroundStyle(.red)
                    Button("다시 시도") {
                        Task { await loadSoul() }
                    }
                case .loaded:
                    TextEditor(text: $soulText)
                        .font(.system(.footnote, design: .monospaced))
                        .frame(minHeight: 220)
                        .autocorrectionDisabled()
                        .textInputAutocapitalization(.never)
                    Button {
                        Task { await saveSoul() }
                    } label: {
                        if isSavingSoul {
                            HStack(spacing: 8) {
                                ProgressView()
                                Text("저장 중...")
                            }
                        } else {
                            Label("SOUL.md 저장", systemImage: "square.and.arrow.down")
                        }
                    }
                    .disabled(isSavingSoul)
                }
            }
        } header: {
            Text("SOUL.md (성격/지침)")
        }
    }

    private func loadModels() async {
        let client = HermesAPIClient(
            baseURL: appSettings.baseURL(for: profile),
            apiKey: profile.apiKey.isEmpty ? appSettings.apiKey : profile.apiKey
        )
        availableModels = (try? await client.fetchModelIDs()) ?? []
    }

    private func loadSoul() async {
        guard let bridge = appSettings.bridgeClient else { return }
        soulState = .loading
        do {
            soulText = try await bridge.fetchSoul(profile: profile.name)
            soulState = .loaded
        } catch {
            soulState = .failed(error.localizedDescription)
        }
    }

    private func saveSoul() async {
        guard let bridge = appSettings.bridgeClient else { return }
        isSavingSoul = true
        do {
            try await bridge.saveSoul(profile: profile.name, content: soulText)
            setStatus("SOUL.md 저장 완료 (재시작해야 반영됩니다)", isError: false)
        } catch {
            setStatus("저장 실패: \(error.localizedDescription)", isError: true)
        }
        isSavingSoul = false
    }

    private func restartGateway() async {
        guard let bridge = appSettings.bridgeClient else { return }
        isRestarting = true
        do {
            _ = try await bridge.restartGateway(profile: profile.name)
            setStatus("게이트웨이 재시작 완료", isError: false)
        } catch {
            setStatus("재시작 실패: \(error.localizedDescription)", isError: true)
        }
        isRestarting = false
    }

    private func setStatus(_ message: String, isError: Bool) {
        statusMessage = message
        statusIsError = isError
    }
}
