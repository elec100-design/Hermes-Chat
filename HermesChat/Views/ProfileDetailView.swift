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
    @State private var isDeleting = false
    @State private var showDeleteConfirm = false

    @Environment(\.dismiss) private var dismiss

    private var profile: HermesProfile {
        appSettings.profiles.first { $0.id == profileID } ?? .default
    }

    private var bridgeConfigured: Bool { appSettings.bridgeClient != nil }

    var body: some View {
        Form {
            Section("profile.detail.connection") {
                LabeledContent("profile.detail.profile.label", value: profile.name)
                LabeledContent("profile.detail.port.label", value: String(profile.port))
            }

            modelSection

            soulSection

            if bridgeConfigured {
                Section {
                    Button {
                        showLogs = true
                    } label: {
                        Label("profile.detail.gateway.logs", systemImage: "doc.plaintext")
                    }
                }

                Section {
                    Button(role: .destructive) {
                        showRestartConfirm = true
                    } label: {
                        if isRestarting {
                            HStack(spacing: 8) {
                                ProgressView()
                                Text("profile.detail.restarting")
                            }
                        } else {
                            Label("profile.detail.gateway.restart", systemImage: "arrow.clockwise")
                        }
                    }
                    .disabled(isRestarting)
                } footer: {
                    Text("profile.detail.restart.footer")
                }

                if profile.name != "default" {
                    Section {
                        Button(role: .destructive) {
                            showDeleteConfirm = true
                        } label: {
                            if isDeleting {
                                HStack(spacing: 8) {
                                    ProgressView()
                                    Text("profile.detail.deleting")
                                }
                            } else {
                                Label("profile.detail.delete.action", systemImage: "trash")
                            }
                        }
                        .disabled(isDeleting)
                    } footer: {
                        Text("profile.detail.delete.footer")
                    }
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
            String(format: String(localized: "profile.detail.restart.confirm %@"), profile.name),
            isPresented: $showRestartConfirm,
            titleVisibility: .visible
        ) {
            Button("common.restart", role: .destructive) {
                Task { await restartGateway() }
            }
            Button("common.cancel", role: .cancel) {}
        }
        .confirmationDialog(
            String(format: String(localized: "profile.detail.delete.confirm %@"), profile.name),
            isPresented: $showDeleteConfirm,
            titleVisibility: .visible
        ) {
            Button("common.delete", role: .destructive) {
                Task { await deleteProfile() }
            }
            Button("common.cancel", role: .cancel) {}
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
                    ProgressView("profile.detail.loading")
                }
            }
            .navigationTitle("\(profile.name) 로그")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button("common.close") { showLogs = false }
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
                Text("profile.detail.bridge.soul.required")
                    .font(.footnote)
                    .foregroundStyle(.secondary)
            } else {
                switch soulState {
                case .loading:
                    HStack(spacing: 8) {
                        ProgressView()
                        Text("profile.detail.loading")
                    }
                case .failed(let message):
                    Text(String(format: String(localized: "profile.detail.load.failed %@"), message))
                        .font(.footnote)
                        .foregroundStyle(.red)
                    Button("common.retry") {
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
                                Text("common.saving")
                            }
                        } else {
                            Label("profile.detail.soul.save", systemImage: "square.and.arrow.down")
                        }
                    }
                    .disabled(isSavingSoul)
                }
            }
        } header: {
            Text("profile.detail.soul.header")
        }
    }

    @ViewBuilder
    private var modelSection: some View {
        Section {
            if !bridgeConfigured {
                Text("profile.detail.model.bridge.required")
                    .font(.footnote)
                    .foregroundStyle(.secondary)
            } else {
                switch modelState {
                case .loading:
                    HStack(spacing: 8) {
                        ProgressView()
                        Text("profile.detail.loading")
                    }
                case .failed(let message):
                    Text(String(format: String(localized: "profile.detail.load.failed %@"), message))
                        .font(.footnote)
                        .foregroundStyle(.red)
                    Button("common.retry") { Task { await loadModel() } }
                case .loaded:
                    if modelCatalog.isEmpty {
                        Text("profile.detail.model.catalog.empty")
                            .font(.footnote)
                            .foregroundStyle(.secondary)
                        if let currentModel {
                            LabeledContent("profile.detail.model.current", value: currentModel)
                        }
                    } else {
                        Picker("profile.detail.model.picker", selection: $pickedModel) {
                            ForEach(modelCatalog, id: \.self) { Text($0).tag($0) }
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
                                    Text("profile.detail.model.saving")
                                }
                            } else {
                                Label("profile.detail.model.save", systemImage: "square.and.arrow.down")
                            }
                        }
                        .disabled(isSavingModel || pickedModel.isEmpty || pickedModel == currentModel)
                    }
                }
            }
        } header: {
            Text("profile.detail.model.header")
        } footer: {
            Text("profile.detail.model.footer")
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

    private func deleteProfile() async {
        guard let bridge = appSettings.bridgeClient else { return }
        let target = profile
        isDeleting = true
        do {
            try await bridge.deleteProfile(name: target.name)
            // 백엔드 삭제 성공 → 앱 로컬 목록에서도 제거하고 상세 화면을 닫는다.
            if let idx = appSettings.profiles.firstIndex(where: { $0.id == target.id }) {
                appSettings.removeProfiles(at: IndexSet(integer: idx))
            }
            dismiss()
        } catch {
            setStatus("삭제 실패: \(error.localizedDescription)", isError: true)
            isDeleting = false
        }
    }

    private func setStatus(_ message: String, isError: Bool) {
        statusMessage = message
        statusIsError = isError
    }
}
