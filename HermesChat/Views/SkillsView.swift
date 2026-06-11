import SwiftUI

/// 선택된 프로필 게이트웨이의 Skills(`/v1/skills`)와 Toolsets(`/v1/toolsets`) 읽기전용 화면.
/// 토글(활성/비활성 변경)은 config.yaml 수정이 필요해서 후순위(T-031).
struct SkillsView: View {
    @ObservedObject var appSettings: AppSettings

    @State private var skills: [GatewayCapability] = []
    @State private var toolsets: [GatewayCapability] = []
    @State private var isLoading = true
    @State private var loadError: String?

    var body: some View {
        List {
            if isLoading {
                HStack(spacing: 8) {
                    ProgressView()
                    Text("불러오는 중...")
                        .foregroundStyle(.secondary)
                }
            } else if let loadError {
                HStack {
                    Image(systemName: "exclamationmark.triangle.fill")
                        .foregroundStyle(.orange)
                    Text(loadError)
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                    Spacer()
                    Button("재시도") {
                        Task { await load() }
                    }
                    .font(.footnote.bold())
                }
            } else {
                Section {
                    if skills.isEmpty {
                        Text("설치된 스킬 없음")
                            .foregroundStyle(.secondary)
                    }
                    ForEach(skills) { capabilityRow($0) }
                } header: {
                    Text("스킬 (\(skills.count))")
                }

                Section {
                    if toolsets.isEmpty {
                        Text("툴셋 없음")
                            .foregroundStyle(.secondary)
                    }
                    ForEach(toolsets) { capabilityRow($0) }
                } header: {
                    Text("툴셋 (\(toolsets.count))")
                } footer: {
                    Text("프로필: \(appSettings.selectedProfile.name). 활성/비활성 변경은 맥미니의 config.yaml에서 합니다.")
                }
            }
        }
        .navigationTitle("Skills & Tools")
        .navigationBarTitleDisplayMode(.inline)
        .refreshable { await load() }
        .task { await load() }
    }

    private func capabilityRow(_ item: GatewayCapability) -> some View {
        HStack(alignment: .firstTextBaseline) {
            VStack(alignment: .leading, spacing: 2) {
                Text(item.name)
                if let detail = item.detail, !detail.isEmpty {
                    Text(detail)
                        .font(.caption)
                        .foregroundStyle(.secondary)
                        .lineLimit(3)
                }
            }
            Spacer()
            if let enabled = item.enabled {
                Image(systemName: enabled ? "checkmark.circle.fill" : "circle")
                    .foregroundStyle(enabled ? Color.green : Color.secondary)
            }
        }
    }

    private func load() async {
        isLoading = true
        loadError = nil
        let client = appSettings.hermesClient
        do {
            async let skillsResult = client.fetchSkills()
            async let toolsetsResult = client.fetchToolsets()
            skills = try await skillsResult
            toolsets = try await toolsetsResult
        } catch {
            loadError = error.localizedDescription
        }
        isLoading = false
    }
}
