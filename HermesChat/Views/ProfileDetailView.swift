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

    @State private var modelState: SoulState = .loading
    @State private var modelCatalog: [String] = []
    @State private var currentModel: String?
    @State private var pickedModel = ""
    @State private var isSavingModel = false
    @State private var soulState: SoulState = .loading
    @State private var soulText = ""
    @State private var isSavingSoul = false
    @State private var isRestarting = false
    @State private var showRestartConfirm = false
    @State private var statusMessage: String?
    @State private var statusIsError = false
    @State private var showLogs = false
    @State private var logsText: String?

    private var profile: HermesProfile {
        appSettings.profiles.first { $0.id == profileID } ?? .default
    }

    private var bridgeConfigured: Bool { appSettings.bridgeClient != nil }

    var body: some View {
        Form {
            Section("연결") {
                LabeledContent("프로필", value: profile.name)
                LabeledContent("포트", value: String(profile.port))
            }

            modelSection

            soulSection

            if bridgeConfigured {
                Section {
                    Button {
                        showLogs = true
                    } label: {
                        Label("게이트웨이 로그 보기 (최근 200줄)", systemImage: "doc.plaintext")
                    }
                }

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
            await loadModel()
            await loadSoul()
        }
        .sheet(isPresented: $showLogs) {
            logsSheet
                .task { await loadLogs() }
        }
    }

    private var logsSheet: some View {
        NavigationStack {
            Group {
                if let logsText {
                    ScrollView {
                        Text(logsText)
                            .font(.system(.caption2, design: .monospaced))
                            .frame(maxWidth: .infinity, alignment: .leading)
                            .padding()
                            .textSelection(.enabled)
                    }
                } else {
                    ProgressView("로그 불러오는 중...")
                }
            }
            .navigationTitle("\(profile.name) 로그")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button("닫기") { showLogs = false }
                }
            }
        }
    }

    private func loadLogs() async {
        guard let bridge = appSettings.bridgeClient else { return }
        logsText = nil
        do {
            logsText = try await bridge.fetchLogs(profile: profile.name, tail: 200)
        } catch {
            logsText = "로그를 불러오지 못했습니다: \(error.localizedDescription)"
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

    @ViewBuilder
    private var modelSection: some View {
        Section {
            if !bridgeConfigured {
                Text("모델 선택은 Hermes Bridge가 필요합니다. 설정 화면에서 URL과 토큰을 입력하세요.")
                    .font(.footnote)
                    .foregroundStyle(.secondary)
            } else {
                switch modelState {
                case .loading:
                    HStack(spacing: 8) {
                        ProgressView()
                        Text("불러오는 중...")
                    }
                case .failed(let message):
                    Text("불러오기 실패: \(message)")
                        .font(.footnote)
                        .foregroundStyle(.red)
                    Button("다시 시도") { Task { await loadModel() } }
                case .loaded:
                    if modelCatalog.isEmpty {
                        Text("모델 카탈로그가 비어 있습니다. 게이트웨이를 1회 기동하면 cache/model_catalog.json이 생성됩니다.")
                            .font(.footnote)
                            .foregroundStyle(.secondary)
                        if let currentModel {
                            LabeledContent("현재 모델", value: currentModel)
                        }
                    } else {
                        Picker("모델", selection: $pickedModel) {
                            ForEach(modelCatalog, id: \.self) { Text($0).tag($0) }
                            // 카탈로그에 없는 현재값도 노출 (외부에서 바뀐 경우)
                            if !pickedModel.isEmpty, !modelCatalog.contains(pickedModel) {
                                Text(pickedModel).tag(pickedModel)
                            }
                        }
                        Button {
                            Task { await saveModel() }
                        } label: {
                            if isSavingModel {
                                HStack(spacing: 8) {
                                    ProgressView()
                                    Text("저장·재시작 중...")
                                }
                            } else {
                                Label("모델 저장 (게이트웨이 재시작)", systemImage: "square.and.arrow.down")
                            }
                        }
                        .disabled(isSavingModel || pickedModel.isEmpty || pickedModel == currentModel)
                    }
                }
            }
        } header: {
            Text("모델")
        } footer: {
            Text("프로필 config.yaml의 모델을 변경하고 게이트웨이를 재시작합니다.")
        }
    }

    private func loadModel() async {
        guard let bridge = appSettings.bridgeClient else { return }
        modelState = .loading
        do {
            let info = try await bridge.fetchModelInfo(profile: profile.name)
            modelCatalog = info.catalog
            currentModel = info.current
            pickedModel = info.current ?? info.catalog.first ?? ""
            modelState = .loaded
        } catch {
            modelState = .failed(error.localizedDescription)
        }
    }

    private func saveModel() async {
        guard let bridge = appSettings.bridgeClient else { return }
        isSavingModel = true
        do {
            try await bridge.setModel(profile: profile.name, model: pickedModel, restart: true)
            currentModel = pickedModel
            setStatus("모델 저장 완료 — 게이트웨이 재시작됨", isError: false)
        } catch {
            setStatus("모델 저장 실패: \(error.localizedDescription)", isError: true)
        }
        isSavingModel = false
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
