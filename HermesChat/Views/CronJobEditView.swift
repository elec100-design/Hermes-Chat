import SwiftUI

/// 크론잡 한 건의 편집 화면 — hermes-agent 대시보드(:8000)의 Cron 편집 폼을 네이티브로 재현.
/// 프롬프트·스케줄(cron식)·전달대상·스킬·활성화를 수정해 저장한다.
/// 저장은 편집된 필드만 Bridge로 보내고, 나머지 필드(id·mode·script 등)는 Bridge가 보존한다.
struct CronJobEditView: View {
    @ObservedObject var appSettings: AppSettings
    let profile: HermesProfile
    let job: CronJob
    /// 저장 성공 후 부모(목록)가 새로고침하도록 알린다.
    var onSaved: () async -> Void

    @Environment(\.dismiss) private var dismiss

    @State private var prompt: String
    @State private var schedule: String
    @State private var deliverTo: String
    @State private var selectedSkills: Set<String>
    @State private var enabled: Bool
    @State private var availableSkills: [String] = []
    @State private var isSaving = false
    @State private var statusMessage: String?
    @State private var statusIsError = false

    init(
        appSettings: AppSettings,
        profile: HermesProfile,
        job: CronJob,
        onSaved: @escaping () async -> Void
    ) {
        self.appSettings = appSettings
        self.profile = profile
        self.job = job
        self.onSaved = onSaved
        _prompt = State(initialValue: job.prompt ?? "")
        _schedule = State(initialValue: job.schedule ?? "")
        _deliverTo = State(initialValue: job.deliverTo ?? "origin")
        _selectedSkills = State(initialValue: Set(job.skills))
        _enabled = State(initialValue: job.enabled ?? true)
    }

    var body: some View {
        Form {
            Section {
                Toggle("이 크론잡 사용", isOn: $enabled)
            } footer: {
                if let mode = job.mode, !mode.isEmpty {
                    Text("모드: \(mode)")
                }
            }

            Section {
                TextEditor(text: $prompt)
                    .font(.system(.footnote, design: .monospaced))
                    .frame(minHeight: 120)
                    .autocorrectionDisabled()
            } header: {
                Text("PROMPT")
            } footer: {
                Text("에이전트가 실행할 지시. 스크립트형(no_agent) 잡은 비어 있을 수 있습니다.")
            }

            Section {
                TextField("0 8 * * *", text: $schedule)
                    .font(.system(.body, design: .monospaced))
                    .autocorrectionDisabled()
                    .textInputAutocapitalization(.never)
            } header: {
                Text("SCHEDULE (CRON EXPRESSION)")
            } footer: {
                Text("분 시 일 월 요일. 예: `0 8 * * *` = 매일 오전 8시.")
            }

            Section("DELIVER TO") {
                TextField("origin", text: $deliverTo)
                    .autocorrectionDisabled()
                    .textInputAutocapitalization(.never)
            }

            skillsSection

            Section {
                Button {
                    Task { await save() }
                } label: {
                    if isSaving {
                        HStack(spacing: 8) {
                            ProgressView()
                            Text("저장 중...")
                        }
                    } else {
                        Label("변경 저장", systemImage: "square.and.arrow.down")
                    }
                }
                .disabled(isSaving)
            }

            if let statusMessage {
                Section {
                    Text(statusMessage)
                        .font(.footnote)
                        .foregroundStyle(statusIsError ? Color.red : Color.green)
                }
            }
        }
        .navigationTitle(job.displayTitle)
        .navigationBarTitleDisplayMode(.inline)
        .task { await loadSkills() }
    }

    @ViewBuilder
    private var skillsSection: some View {
        Section {
            let all = Array(Set(availableSkills).union(selectedSkills))
                .sorted { $0.localizedCaseInsensitiveCompare($1) == .orderedAscending }
            if all.isEmpty {
                Text("사용 가능한 스킬 없음")
                    .foregroundStyle(.secondary)
            }
            ForEach(all, id: \.self) { skill in
                Button {
                    if selectedSkills.contains(skill) {
                        selectedSkills.remove(skill)
                    } else {
                        selectedSkills.insert(skill)
                    }
                } label: {
                    HStack {
                        Image(systemName: selectedSkills.contains(skill) ? "checkmark.square.fill" : "square")
                            .foregroundStyle(selectedSkills.contains(skill) ? Color.accentColor : Color.secondary)
                        Text(skill)
                            .foregroundStyle(.primary)
                        Spacer()
                    }
                }
                .buttonStyle(.plain)
            }
        } header: {
            Text("SKILLS (\(selectedSkills.count))")
        }
    }

    private func loadSkills() async {
        let client = HermesAPIClient(
            baseURL: appSettings.baseURL(for: profile),
            apiKey: profile.apiKey.isEmpty ? appSettings.apiKey : profile.apiKey
        )
        let caps = (try? await client.fetchSkills()) ?? []
        availableSkills = caps.map(\.name)
    }

    private func save() async {
        guard let bridge = appSettings.bridgeClient else {
            statusMessage = "Hermes Bridge가 설정되지 않았습니다."
            statusIsError = true
            return
        }
        isSaving = true
        let fields: [String: Any] = [
            "prompt": prompt,
            "schedule": schedule,
            "deliver_to": deliverTo,
            "skills": Array(selectedSkills).sorted(),
            "enabled": enabled,
        ]
        do {
            try await bridge.updateCronJob(profile: profile.name, jobID: job.id, fields: fields)
            await onSaved()
            dismiss()
        } catch {
            statusMessage = "저장 실패: \(error.localizedDescription)"
            statusIsError = true
        }
        isSaving = false
    }
}
