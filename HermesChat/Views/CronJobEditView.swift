import SwiftUI

/// 크론잡 생성 / 편집 화면 — hermes-agent 대시보드(:8000)의 Cron 폼을 네이티브로 재현.
/// 편집 시: 전달된 필드만 Bridge로 보내고 나머지(id·mode·script·실행상태)는 Bridge가 보존한다.
/// 생성 시: 프로필을 고르고 새 잡을 jobs.json에 추가한다(Bridge가 기존 잡 구조를 템플릿으로 사용).
struct CronJobEditView: View {
    @ObservedObject var appSettings: AppSettings
    /// 저장 성공 후 부모(목록)가 새로고침하도록 알린다.
    var onSaved: () async -> Void

    @Environment(\.dismiss) private var dismiss

    private let isCreating: Bool
    private let editingProfile: HermesProfile?   // 편집 모드의 고정 프로필
    private let editingJob: CronJob?             // 편집 모드의 원본 잡

    @State private var selectedProfile: HermesProfile   // 생성 모드의 프로필 선택
    @State private var name: String
    @State private var prompt: String
    @State private var schedule: String
    @State private var deliverTo: String
    @State private var selectedSkills: Set<String>
    @State private var enabled: Bool
    @State private var availableSkills: [String] = []
    @State private var isSaving = false
    @State private var statusMessage: String?
    @State private var statusIsError = false

    /// 편집 모드.
    init(
        appSettings: AppSettings,
        profile: HermesProfile,
        job: CronJob,
        onSaved: @escaping () async -> Void
    ) {
        self.appSettings = appSettings
        self.onSaved = onSaved
        self.isCreating = false
        self.editingProfile = profile
        self.editingJob = job
        _selectedProfile = State(initialValue: profile)
        _name = State(initialValue: job.name ?? "")
        _prompt = State(initialValue: job.prompt ?? "")
        _schedule = State(initialValue: job.schedule ?? "")
        _deliverTo = State(initialValue: job.deliverTo ?? "origin")
        _selectedSkills = State(initialValue: Set(job.skills))
        _enabled = State(initialValue: job.enabled ?? true)
    }

    /// 생성 모드 — initialProfile을 기본 선택으로.
    init(
        appSettings: AppSettings,
        creatingForProfile initialProfile: HermesProfile,
        onSaved: @escaping () async -> Void
    ) {
        self.appSettings = appSettings
        self.onSaved = onSaved
        self.isCreating = true
        self.editingProfile = nil
        self.editingJob = nil
        _selectedProfile = State(initialValue: initialProfile)
        _name = State(initialValue: "")
        _prompt = State(initialValue: "")
        _schedule = State(initialValue: "0 8 * * *")
        _deliverTo = State(initialValue: "origin")
        _selectedSkills = State(initialValue: [])
        _enabled = State(initialValue: true)
    }

    /// 현재 작업 대상 프로필 (편집=고정, 생성=선택).
    private var activeProfile: HermesProfile { editingProfile ?? selectedProfile }

    private var canSave: Bool {
        !name.trimmingCharacters(in: .whitespaces).isEmpty
            && !schedule.trimmingCharacters(in: .whitespaces).isEmpty
    }

    var body: some View {
        Form {
            if isCreating {
                Section("PROFILE") {
                    Picker("cron.edit.profile", selection: $selectedProfile) {
                        ForEach(appSettings.profiles) { profile in
                            Text(profile.name).tag(profile)
                        }
                    }
                }
            }

            Section {
                TextField("cron.edit.name", text: $name)
                    .autocorrectionDisabled()
                Toggle("cron.edit.enabled", isOn: $enabled)
            } header: {
                Text("NAME")
            } footer: {
                if let mode = editingJob?.mode, !mode.isEmpty {
                    Text(String(format: String(localized: "cron.edit.mode %@"), mode))
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
                Text("cron.edit.prompt.footer")
            }

            Section {
                TextField("0 8 * * *", text: $schedule)
                    .font(.system(.body, design: .monospaced))
                    .autocorrectionDisabled()
                    .textInputAutocapitalization(.never)
            } header: {
                Text("SCHEDULE (CRON EXPRESSION)")
            } footer: {
                if let human = CronJob.humanizeSchedule(schedule) {
                    Text(String(format: String(localized: "cron.edit.schedule.human %@"), human))
                } else {
                    Text("cron.edit.schedule.footer")
                }
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
                            Text(isCreating ? "cron.edit.creating" : "cron.edit.saving")
                        }
                    } else {
                        Label(isCreating ? "cron.edit.create" : "cron.edit.save",
                              systemImage: isCreating ? "plus.circle" : "square.and.arrow.down")
                    }
                }
                .disabled(isSaving || !canSave)
            }

            if let statusMessage {
                Section {
                    Text(statusMessage)
                        .font(.footnote)
                        .foregroundStyle(statusIsError ? Color.red : Color.green)
                }
            }
        }
        .navigationTitle(isCreating ? String(localized: "cron.edit.title.new") : (editingJob?.displayTitle ?? "크론잡"))
        .navigationBarTitleDisplayMode(.inline)
        .task(id: activeProfile.id) { await loadSkills() }
    }

    @ViewBuilder
    private var skillsSection: some View {
        Section {
            let all = Array(Set(availableSkills).union(selectedSkills))
                .sorted { $0.localizedCaseInsensitiveCompare($1) == .orderedAscending }
            if all.isEmpty {
                Text("cron.edit.skills.empty")
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
        let profile = activeProfile
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
        defer { isSaving = false }
        let fields: [String: Any] = [
            "name": name.trimmingCharacters(in: .whitespaces),
            "prompt": prompt,
            "schedule": schedule.trimmingCharacters(in: .whitespaces),
            "deliver_to": deliverTo,
            "skills": Array(selectedSkills).sorted(),
            "enabled": enabled,
        ]
        do {
            if isCreating {
                try await bridge.createCronJob(profile: activeProfile.name, fields: fields)
            } else if let job = editingJob, let profile = editingProfile {
                // 편집 시에도 name 포함해 전달 (Bridge 화이트리스트가 name 허용).
                try await bridge.updateCronJob(profile: profile.name, jobID: job.id, fields: fields)
            }
            await onSaved()
            dismiss()
        } catch {
            statusMessage = (isCreating ? "생성 실패: " : "저장 실패: ") + error.localizedDescription
            statusIsError = true
        }
    }
}
