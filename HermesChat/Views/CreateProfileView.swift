import SwiftUI

/// 새 프로필 생성 폼 — 프로필 보드의 '+' 카드에서 시트로 띄운다.
/// Hermes Bridge가 백엔드까지 완전 생성한다 (디렉터리 + .env + SOUL.md + 게이트웨이 install/restart).
/// 생성 후 앱 로컬에도 등록하고 해당 프로필을 선택한다. 모델은 생성 직후 상세 화면에서 고른다
/// (카탈로그는 게이트웨이가 1회 기동된 뒤 생성되기 때문).
struct CreateProfileView: View {
    @ObservedObject var appSettings: AppSettings
    @Environment(\.dismiss) private var dismiss

    @State private var name = ""
    @State private var port = ""
    @State private var soul = ""
    @State private var isCreating = false
    @State private var statusMessage: String?
    @State private var statusIsError = false

    private var bridgeConfigured: Bool { appSettings.bridgeClient != nil }
    private var suggestedPort: Int { (appSettings.profiles.map(\.port).max() ?? 8642) + 1 }
    private var trimmedName: String { name.trimmingCharacters(in: .whitespaces) }

    var body: some View {
        NavigationStack {
            Form {
                if !bridgeConfigured {
                    Section {
                        Text("새 프로필 생성은 Hermes Bridge가 필요합니다.\n설정 화면의 \"Hermes Bridge\" 섹션에 URL과 토큰을 입력하세요.")
                            .font(.footnote)
                            .foregroundStyle(.secondary)
                    }
                } else {
                    Section("프로필") {
                        TextField("이름 (예: work)", text: $name)
                            .autocorrectionDisabled()
                            .textInputAutocapitalization(.never)
                        TextField("포트 (\(suggestedPort))", text: $port)
                            .keyboardType(.numberPad)
                    }

                    Section {
                        TextEditor(text: $soul)
                            .font(.system(.footnote, design: .monospaced))
                            .frame(minHeight: 120)
                            .autocorrectionDisabled()
                    } header: {
                        Text("SOUL.md (선택)")
                    } footer: {
                        Text("프로필의 성격/지침. 나중에 상세 화면에서도 편집할 수 있습니다.")
                    }

                    Section {
                        Button {
                            Task { await create() }
                        } label: {
                            if isCreating {
                                HStack(spacing: 8) {
                                    ProgressView()
                                    Text("생성 중... (게이트웨이 기동까지 수십 초)")
                                }
                            } else {
                                Label("프로필 생성", systemImage: "plus.circle")
                            }
                        }
                        .disabled(isCreating || trimmedName.isEmpty)
                    } footer: {
                        Text("디렉터리·.env·SOUL.md를 만들고 게이트웨이를 기동합니다.")
                    }

                    if let statusMessage {
                        Section {
                            Text(statusMessage)
                                .font(.footnote)
                                .foregroundStyle(statusIsError ? Color.red : Color.green)
                        }
                    }
                }
            }
            .navigationTitle("새 프로필")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarLeading) {
                    Button("취소") { dismiss() }
                }
            }
        }
    }

    private func create() async {
        guard let bridge = appSettings.bridgeClient else { return }
        let chosenPort = Int(port.trimmingCharacters(in: .whitespaces))  // nil → Bridge 자동 할당
        isCreating = true
        do {
            let assignedPort = try await bridge.createProfile(
                name: trimmedName,
                port: chosenPort,
                apiKey: appSettings.apiKey,
                soul: soul
            )
            appSettings.addProfile(name: trimmedName, port: assignedPort)
            if let created = appSettings.profiles.first(where: { $0.name == trimmedName }) {
                appSettings.selectProfile(created)
            }
            dismiss()
        } catch {
            statusMessage = "생성 실패: \(error.localizedDescription)"
            statusIsError = true
        }
        isCreating = false
    }
}
