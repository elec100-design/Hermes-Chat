import SwiftUI

/// 한 프로필의 크론잡 목록 (`~/.hermes/profiles/<name>/cron/jobs.json`).
/// 프로필 보드 카드의 시계 버튼에서 시트로 띄운다. 각 잡을 탭하면 편집 화면으로 들어간다.
/// 읽기/쓰기 모두 Hermes Bridge가 필요하다 (설정 화면에서 URL·토큰 입력).
struct CronJobsView: View {
    @ObservedObject var appSettings: AppSettings
    let profile: HermesProfile

    @Environment(\.dismiss) private var dismiss

    private enum LoadState: Equatable {
        case loading
        case loaded([CronJob])
        case failed(String)
    }

    @State private var state: LoadState = .loading

    private var bridgeConfigured: Bool { appSettings.bridgeClient != nil }

    var body: some View {
        NavigationStack {
            Group {
                if !bridgeConfigured {
                    bridgeHint
                } else {
                    content
                }
            }
            .navigationTitle("크론잡 · \(profile.name)")
            .navigationBarTitleDisplayMode(.inline)
            .navigationDestination(for: CronJob.self) { job in
                CronJobEditView(appSettings: appSettings, profile: profile, job: job) {
                    await load()
                }
            }
            .toolbar {
                ToolbarItem(placement: .topBarLeading) {
                    Button("닫기") { dismiss() }
                }
                ToolbarItem(placement: .topBarTrailing) {
                    if bridgeConfigured {
                        Button {
                            Task { await load() }
                        } label: {
                            Image(systemName: "arrow.clockwise")
                        }
                    }
                }
            }
            .task { await load() }
        }
    }

    @ViewBuilder
    private var content: some View {
        switch state {
        case .loading:
            ProgressView("불러오는 중...")
        case .failed(let message):
            VStack(spacing: 12) {
                Image(systemName: "exclamationmark.triangle")
                    .font(.largeTitle)
                    .foregroundStyle(.orange)
                Text(message)
                    .font(.footnote)
                    .foregroundStyle(.secondary)
                    .multilineTextAlignment(.center)
                Button("다시 시도") { Task { await load() } }
            }
            .padding()
        case .loaded(let jobs):
            if jobs.isEmpty {
                ContentUnavailableView(
                    "크론잡 없음",
                    systemImage: "clock.badge.xmark",
                    description: Text("이 프로필에는 등록된 크론잡이 없습니다.")
                )
            } else {
                List {
                    ForEach(jobs) { job in
                        NavigationLink(value: job) { row(job) }
                    }
                }
            }
        }
    }

    private func row(_ job: CronJob) -> some View {
        VStack(alignment: .leading, spacing: 6) {
            HStack {
                Text(job.displayTitle)
                    .font(.headline)
                    .lineLimit(1)
                Spacer()
                if job.enabled == false {
                    Text("꺼짐")
                        .font(.caption2)
                        .foregroundStyle(.secondary)
                }
            }
            if let schedule = job.schedule, !schedule.isEmpty {
                Label(schedule, systemImage: "clock")
                    .font(.system(.caption, design: .monospaced))
                    .foregroundStyle(.secondary)
            }
            HStack(spacing: 6) {
                if let mode = job.mode, !mode.isEmpty { badge(mode) }
                if let deliver = job.deliverTo, !deliver.isEmpty { badge(deliver) }
                if !job.skills.isEmpty { badge("\(job.skills.count) skills") }
            }
        }
        .padding(.vertical, 2)
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

    private var bridgeHint: some View {
        VStack(spacing: 12) {
            Image(systemName: "link.badge.plus")
                .font(.largeTitle)
                .foregroundStyle(.secondary)
            Text("크론잡을 보려면 Hermes Bridge가 필요합니다.\n설정 화면의 \"Hermes Bridge\" 섹션에 URL과 토큰을 입력하세요.")
                .font(.footnote)
                .foregroundStyle(.secondary)
                .multilineTextAlignment(.center)
        }
        .padding()
    }

    private func load() async {
        guard let bridge = appSettings.bridgeClient else { return }
        state = .loading
        do {
            let jobs = try await bridge.fetchCronJobs(profile: profile.name)
            state = .loaded(jobs)
        } catch {
            state = .failed(error.localizedDescription)
        }
    }
}
