import SwiftUI

/// 모든 프로필의 크론잡을 한 화면에서 관리한다 (hermes-agent 대시보드 :8000 의 Cron 화면을 재현).
/// 상단 드롭다운으로 프로필별 필터링, 각 잡은 재개/일시정지·지금 실행·편집·삭제 버튼으로 조작한다.
/// 데이터는 프로필별 `~/.hermes/profiles/<name>/cron/jobs.json`이며 Hermes Bridge가 필요하다.
struct CronManagerView: View {
    @ObservedObject var appSettings: AppSettings
    /// 시작 시 특정 프로필로 필터를 걸고 싶을 때 (프로필 카드의 시계 버튼). nil이면 전체.
    var initialProfileName: String?

    @Environment(\.dismiss) private var dismiss

    /// 프로필 이름 → 그 프로필의 크론잡 목록. 전 프로필을 동시에 불러 한 곳에 모은다.
    @State private var jobsByProfile: [String: [CronJob]] = [:]
    @State private var isLoading = true
    @State private var loadError: String?
    /// nil = 전체 프로필, 아니면 해당 프로필만 표시.
    @State private var filterProfileName: String?
    /// 현재 액션(실행/토글/삭제)이 진행 중인 잡 (스피너 표시용).
    @State private var busyEntryID: String?
    @State private var editTarget: EditTarget?
    @State private var pendingDelete: EditTarget?
    @State private var showCreate = false
    @State private var banner: Banner?

    init(appSettings: AppSettings, initialProfileName: String? = nil) {
        self.appSettings = appSettings
        self.initialProfileName = initialProfileName
        _filterProfileName = State(initialValue: initialProfileName)
    }

    /// 편집/삭제 대상 — 잡은 프로필 컨텍스트가 있어야 조작할 수 있다.
    struct EditTarget: Identifiable {
        let profile: HermesProfile
        let job: CronJob
        var id: String { profile.name + "::" + job.id }
    }

    struct Banner: Equatable {
        let text: String
        let isError: Bool
    }

    private var bridgeConfigured: Bool { appSettings.bridgeClient != nil }

    /// 필터에 걸리는 프로필들 (앱에 등록된 순서 유지).
    private var visibleProfiles: [HermesProfile] {
        appSettings.profiles.filter { filterProfileName == nil || $0.name == filterProfileName }
    }

    private var totalVisibleJobs: Int {
        visibleProfiles.reduce(0) { $0 + (jobsByProfile[$1.name]?.count ?? 0) }
    }

    /// 새 크론잡의 기본 프로필 — 필터된 프로필 > 현재 선택 프로필 > 첫 프로필.
    private var createTargetProfile: HermesProfile {
        if let name = filterProfileName,
           let match = appSettings.profiles.first(where: { $0.name == name }) {
            return match
        }
        if let selectedID = appSettings.selectedProfileID,
           let match = appSettings.profiles.first(where: { $0.id == selectedID }) {
            return match
        }
        return appSettings.profiles.first ?? .default
    }

    var body: some View {
        NavigationStack {
            Group {
                if !bridgeConfigured {
                    bridgeHint
                } else {
                    content
                }
            }
            .navigationTitle("cron.manager.title")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarLeading) {
                    Button("common.close") { dismiss() }
                }
                ToolbarItemGroup(placement: .topBarTrailing) {
                    if bridgeConfigured {
                        Button {
                            Task { await loadAll() }
                        } label: {
                            Image(systemName: "arrow.clockwise")
                        }
                        .disabled(isLoading)
                        Button {
                            showCreate = true
                        } label: {
                            Label("cron.manager.new.job", systemImage: "plus")
                        }
                        .disabled(appSettings.profiles.isEmpty)
                    }
                }
            }
            .sheet(isPresented: $showCreate) {
                NavigationStack {
                    CronJobEditView(
                        appSettings: appSettings,
                        creatingForProfile: createTargetProfile
                    ) {
                        await loadAll()
                    }
                    .toolbar {
                        ToolbarItem(placement: .topBarLeading) {
                            Button("common.cancel") { showCreate = false }
                        }
                    }
                }
            }
            .sheet(item: $editTarget) { target in
                NavigationStack {
                    CronJobEditView(
                        appSettings: appSettings,
                        profile: target.profile,
                        job: target.job
                    ) {
                        await loadAll()
                    }
                    .toolbar {
                        ToolbarItem(placement: .topBarLeading) {
                            Button("common.cancel") { editTarget = nil }
                        }
                    }
                }
            }
            .confirmationDialog(
                "cron.manager.delete.confirm",
                isPresented: Binding(
                    get: { pendingDelete != nil },
                    set: { if !$0 { pendingDelete = nil } }
                ),
                titleVisibility: .visible,
                presenting: pendingDelete
            ) { target in
                Button("common.delete", role: .destructive) {
                    Task { await delete(target) }
                }
                Button("common.cancel", role: .cancel) {}
            } message: { target in
                Text("\(target.profile.name) · \(target.job.displayTitle)\n이 작업은 되돌릴 수 없습니다.")
            }
            .task { await loadAll() }
        }
    }

    // MARK: - Content

    @ViewBuilder
    private var content: some View {
        VStack(spacing: 0) {
            filterBar
            Divider()
            jobList
        }
        .overlay(alignment: .bottom) { bannerView }
    }

    /// 프로필 필터 드롭다운 (스크린샷의 프로필 선택 메뉴).
    private var filterBar: some View {
        HStack {
            Menu {
                Button {
                    filterProfileName = nil
                } label: {
                    Label("cron.manager.filter.all", systemImage: filterProfileName == nil ? "checkmark" : "tray.full")
                }
                Divider()
                ForEach(appSettings.profiles) { profile in
                    Button {
                        filterProfileName = profile.name
                    } label: {
                        let count = jobsByProfile[profile.name]?.count ?? 0
                        Label(
                            "\(profile.name) (\(count))",
                            systemImage: filterProfileName == profile.name ? "checkmark" : "person.crop.circle"
                        )
                    }
                }
            } label: {
                HStack(spacing: 6) {
                    Image(systemName: "line.3.horizontal.decrease.circle")
                    Text(filterProfileName ?? String(localized: "cron.manager.filter.all"))
                        .fontWeight(.medium)
                    Image(systemName: "chevron.down")
                        .font(.caption2)
                }
                .padding(.horizontal, 12)
                .padding(.vertical, 7)
                .background(Color(.secondarySystemBackground))
                .clipShape(Capsule())
            }
            Spacer()
            if isLoading {
                ProgressView().scaleEffect(0.8)
            } else {
                Text(String(format: String(localized: "cron.manager.jobs.count %lld"), Int64(totalVisibleJobs)))
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
        }
        .padding(.horizontal)
        .padding(.vertical, 8)
    }

    @ViewBuilder
    private var jobList: some View {
        if let loadError, jobsByProfile.isEmpty {
            errorState(loadError)
        } else if !isLoading && totalVisibleJobs == 0 {
            ContentUnavailableView(
                "cron.manager.empty",
                systemImage: "clock.badge.xmark",
                description: Text(filterProfileName == nil
                    ? String(localized: "cron.manager.empty.desc")
                    : String(format: String(localized: "cron.manager.empty.filtered.desc %@"), filterProfileName!))
            )
        } else {
            List {
                ForEach(visibleProfiles) { profile in
                    let jobs = jobsByProfile[profile.name] ?? []
                    if !jobs.isEmpty {
                        Section {
                            ForEach(jobs) { job in
                                jobCard(profile: profile, job: job)
                            }
                        } header: {
                            Label("\(profile.name) · 포트 \(String(profile.port))", systemImage: "person.crop.circle")
                        }
                    }
                }
            }
            .listStyle(.insetGrouped)
        }
    }

    // MARK: - Job card (정보 + 액션 버튼)

    private func jobCard(profile: HermesProfile, job: CronJob) -> some View {
        let target = EditTarget(profile: profile, job: job)
        let enabled = job.enabled ?? true
        let isBusy = busyEntryID == target.id
        return VStack(alignment: .leading, spacing: 8) {
            HStack(spacing: 6) {
                Text(job.displayTitle)
                    .font(.headline)
                    .lineLimit(1)
                statusBadge(job)
                Spacer(minLength: 4)
            }
            // 메타 배지 (전달대상 + 스킬 수)
            if hasMeta(job) {
                HStack(spacing: 6) {
                    if let mode = job.mode, !mode.isEmpty { badge(mode) }
                    if let deliver = job.deliverTo, !deliver.isEmpty { badge(deliver) }
                    if !job.skills.isEmpty { badge("\(job.skills.count) skills") }
                }
            }
            // 프롬프트/스크립트 미리보기 한 줄
            if let preview = previewText(job) {
                Text(preview)
                    .font(.caption)
                    .foregroundStyle(.secondary)
                    .lineLimit(1)
            }
            // 사람이 읽는 스케줄
            if let desc = job.scheduleDescription {
                Label(desc, systemImage: "clock")
                    .font(.system(.caption, design: .monospaced))
                    .foregroundStyle(.secondary)
            }
            // 마지막/다음 실행 시각
            if job.lastRunDisplay != nil || job.nextRunDisplay != nil {
                HStack(spacing: 12) {
                    if let last = job.lastRunDisplay {
                        Text(String(format: String(localized: "cron.job.last.run %@"), last))
                    }
                    if let next = job.nextRunDisplay {
                        Text(String(format: String(localized: "cron.job.next.run %@"), next))
                    }
                }
                .font(.caption2)
                .foregroundStyle(.tertiary)
            }
            actionRow(target: target, enabled: enabled, isBusy: isBusy)
        }
        .padding(.vertical, 4)
    }

    private func statusBadge(_ job: CronJob) -> some View {
        let paused = job.isPaused
        let color: Color = paused ? .orange : .green
        return Text(job.statusLabel)
            .font(.caption2.weight(.medium))
            .padding(.horizontal, 7)
            .padding(.vertical, 2)
            .background(color.opacity(0.18))
            .foregroundStyle(color)
            .clipShape(Capsule())
    }

    /// 프롬프트(에이전트형) 또는 mode 문구를 한 줄 미리보기로.
    private func previewText(_ job: CronJob) -> String? {
        if let prompt = job.prompt?.trimmingCharacters(in: .whitespacesAndNewlines), !prompt.isEmpty {
            return prompt
        }
        return nil
    }

    private func actionRow(target: EditTarget, enabled: Bool, isBusy: Bool) -> some View {
        HStack(spacing: 8) {
            actionButton(
                String(localized: enabled ? "cron.job.toggle.pause" : "cron.job.toggle.resume"),
                systemImage: enabled ? "pause.fill" : "play.fill"
            ) {
                Task { await toggleEnabled(target, to: !enabled) }
            }
            actionButton(String(localized: "cron.job.run"), systemImage: "bolt.fill", tint: .orange) {
                Task { await trigger(target) }
            }
            actionButton(String(localized: "cron.job.edit"), systemImage: "pencil") {
                editTarget = target
            }
            actionButton(String(localized: "common.delete"), systemImage: "trash", tint: .red) {
                pendingDelete = target
            }
        }
        .overlay {
            if isBusy {
                // 액션 진행 중에는 버튼을 가리고 스피너를 띄워 중복 탭을 막는다.
                RoundedRectangle(cornerRadius: 8)
                    .fill(Color(.systemBackground).opacity(0.6))
                    .overlay { ProgressView() }
            }
        }
        .disabled(isBusy)
        .padding(.top, 2)
    }

    private func actionButton(
        _ title: String,
        systemImage: String,
        tint: Color = .accentColor,
        action: @escaping () -> Void
    ) -> some View {
        Button(action: action) {
            VStack(spacing: 3) {
                Image(systemName: systemImage)
                    .font(.system(size: 14))
                Text(title)
                    .font(.caption2)
            }
            .frame(maxWidth: .infinity)
            .padding(.vertical, 6)
            .contentShape(Rectangle())
        }
        .buttonStyle(.bordered)
        .tint(tint)
    }

    private func hasMeta(_ job: CronJob) -> Bool {
        (job.mode?.isEmpty == false) || (job.deliverTo?.isEmpty == false) || !job.skills.isEmpty
    }

    private func badge(_ text: String) -> some View {
        Text(text)
            .font(.caption2)
            .padding(.horizontal, 6)
            .padding(.vertical, 2)
            .background(Color(.secondarySystemBackground))
            .clipShape(Capsule())
            .foregroundStyle(.secondary)
    }

    @ViewBuilder
    private var bannerView: some View {
        if let banner {
            Text(banner.text)
                .font(.footnote)
                .foregroundStyle(.white)
                .padding(.horizontal, 14)
                .padding(.vertical, 10)
                .background(banner.isError ? Color.red : Color.green, in: Capsule())
                .padding(.bottom, 16)
                .shadow(radius: 4)
                .transition(.move(edge: .bottom).combined(with: .opacity))
        }
    }

    private func errorState(_ message: String) -> some View {
        VStack(spacing: 12) {
            Image(systemName: "exclamationmark.triangle")
                .font(.largeTitle)
                .foregroundStyle(.orange)
            Text(message)
                .font(.footnote)
                .foregroundStyle(.secondary)
                .multilineTextAlignment(.center)
            Button("common.retry") { Task { await loadAll() } }
        }
        .padding()
        .frame(maxHeight: .infinity)
    }

    private var bridgeHint: some View {
        VStack(spacing: 12) {
            Image(systemName: "link.badge.plus")
                .font(.largeTitle)
                .foregroundStyle(.secondary)
            Text("cron.manager.bridge.required")
                .font(.footnote)
                .foregroundStyle(.secondary)
                .multilineTextAlignment(.center)
        }
        .padding()
    }

    // MARK: - Data / actions

    private func loadAll() async {
        guard let bridge = appSettings.bridgeClient else { return }
        isLoading = true
        loadError = nil
        var collected: [String: [CronJob]] = [:]
        var firstError: String?
        // 프로필별 jobs.json을 순차로 읽는다(프로필 수가 적어 충분히 빠름). 일부가 실패해도 나머지는 표시.
        for profile in appSettings.profiles {
            do {
                collected[profile.name] = try await bridge.fetchCronJobs(profile: profile.name)
            } catch {
                firstError = firstError ?? error.localizedDescription
            }
        }
        jobsByProfile = collected
        // 모든 프로필이 실패했을 때만 에러 화면으로 강등 (일부 성공이면 그대로 표시).
        loadError = collected.isEmpty ? firstError : nil
        isLoading = false
    }

    private func toggleEnabled(_ target: EditTarget, to enabled: Bool) async {
        guard let bridge = appSettings.bridgeClient else { return }
        busyEntryID = target.id
        defer { busyEntryID = nil }
        do {
            try await bridge.setCronJobEnabled(
                profile: target.profile.name, jobID: target.job.id, enabled: enabled
            )
            applyLocalEnabled(target, enabled: enabled)
            showBanner(enabled ? "크론잡을 재개했습니다." : "크론잡을 일시정지했습니다.", isError: false)
        } catch {
            showBanner("변경 실패: \(error.localizedDescription)", isError: true)
        }
    }

    private func trigger(_ target: EditTarget) async {
        guard let bridge = appSettings.bridgeClient else { return }
        busyEntryID = target.id
        defer { busyEntryID = nil }
        do {
            try await bridge.triggerCronJob(profile: target.profile.name, jobID: target.job.id)
            showBanner("‘\(target.job.displayTitle)’ 실행을 시작했습니다.", isError: false)
        } catch {
            showBanner("실행 실패: \(error.localizedDescription)", isError: true)
        }
    }

    private func delete(_ target: EditTarget) async {
        guard let bridge = appSettings.bridgeClient else { return }
        busyEntryID = target.id
        defer { busyEntryID = nil }
        do {
            try await bridge.deleteCronJob(profile: target.profile.name, jobID: target.job.id)
            removeLocal(target)
            showBanner("크론잡을 삭제했습니다.", isError: false)
        } catch {
            showBanner("삭제 실패: \(error.localizedDescription)", isError: true)
        }
    }

    /// 토글 성공 시 서버 재조회 없이 로컬 상태만 반영 (즉각적인 UI 피드백).
    private func applyLocalEnabled(_ target: EditTarget, enabled: Bool) {
        guard var jobs = jobsByProfile[target.profile.name] else { return }
        if let idx = jobs.firstIndex(where: { $0.id == target.job.id }) {
            jobs[idx].enabled = enabled
            jobsByProfile[target.profile.name] = jobs
        }
    }

    private func removeLocal(_ target: EditTarget) {
        guard var jobs = jobsByProfile[target.profile.name] else { return }
        jobs.removeAll { $0.id == target.job.id }
        jobsByProfile[target.profile.name] = jobs
    }

    private func showBanner(_ text: String, isError: Bool) {
        withAnimation { banner = Banner(text: text, isError: isError) }
        Task {
            try? await Task.sleep(nanoseconds: 2_500_000_000)
            withAnimation { if banner?.text == text { banner = nil } }
        }
    }
}
