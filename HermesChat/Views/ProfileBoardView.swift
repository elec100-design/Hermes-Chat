import SwiftUI

/// 홈 화면: 프로필을 2열 카드 그리드로 보여준다.
/// 카드마다 온라인 상태(/health 프로브)와 세션 수를 표시하고,
/// 탭하면 해당 프로필로 전환한 뒤 세션 탭으로 이동한다.
struct ProfileBoardView: View {
    @ObservedObject var appSettings: AppSettings
    @Binding var selectedTab: AppTab

    struct ProfileStatus: Equatable {
        var online: Bool?
        var sessionCount: Int?
    }

    @State private var status: [UUID: ProfileStatus] = [:]
    @State private var isProbing = false
    @State private var showDiscussion = false
    @State private var showCronManager = false
    @State private var showCreateProfile = false

    /// iPhone에선 2열, iPad에선 화면 폭에 맞춰 자동 증가
    private let columns = [
        GridItem(.adaptive(minimum: 160), spacing: 12),
    ]

    var body: some View {
        NavigationStack {
            ScrollView {
                LazyVGrid(columns: columns, spacing: 12) {
                    ForEach(appSettings.profiles) { profile in
                        card(profile)
                    }
                    addCard
                }
                .padding()
            }
            .navigationTitle("profile.board.title")
            .toolbar {
                ToolbarItemGroup(placement: .topBarTrailing) {
                    // 멀티 에이전트 토론룸 진입 (Phase 14)
                    Button {
                        showDiscussion = true
                    } label: {
                        Label("Deep think", systemImage: "brain.head.profile")
                            .labelStyle(.titleAndIcon)
                    }
                    // 전 프로필 크론 관리 (한 곳에서 드롭다운 필터)
                    Button {
                        showCronManager = true
                    } label: {
                        Label("profile.board.cron.manage", systemImage: "clock.arrow.circlepath")
                    }
                    if isProbing {
                        ProgressView().scaleEffect(0.8)
                    } else {
                        Button {
                            Task { await probeAll() }
                        } label: {
                            Image(systemName: "arrow.clockwise")
                        }
                    }
                }
            }
            .fullScreenCover(isPresented: $showDiscussion) {
                DiscussionView(appSettings: appSettings)
            }
            .sheet(isPresented: $showCronManager) {
                CronManagerView(appSettings: appSettings)
            }
            .sheet(isPresented: $showCreateProfile) {
                CreateProfileView(appSettings: appSettings)
            }
            .refreshable { await probeAll() }
            .task { await probeAll() }
        }
    }

    private func card(_ profile: HermesProfile) -> some View {
        let profileStatus = status[profile.id]
        return Button {
            appSettings.selectProfile(profile)
            selectedTab = .sessions
        } label: {
            VStack(alignment: .leading, spacing: 8) {
                HStack(spacing: 6) {
                    Circle()
                        .fill(statusColor(profileStatus?.online))
                        .frame(width: 10, height: 10)
                    Text(profile.name)
                        .font(.headline)
                        .lineLimit(1)
                    Spacer()
                    if profile.id == appSettings.selectedProfileID {
                        Image(systemName: "checkmark.circle.fill")
                            .foregroundStyle(.tint)
                    }
                }
                Text(String(format: String(localized: "settings.profiles.port"), profile.port))
                    .font(.caption)
                    .foregroundStyle(.secondary)
                Text(sessionLabel(profileStatus))
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
            .padding()
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(Color(.secondarySystemBackground))
            .clipShape(RoundedRectangle(cornerRadius: 14))
        }
        .buttonStyle(.plain)
    }

    /// 그리드 끝의 '+' 카드 — 새 프로필 생성 진입.
    private var addCard: some View {
        Button {
            showCreateProfile = true
        } label: {
            VStack(spacing: 8) {
                Image(systemName: "plus")
                    .font(.system(size: 32, weight: .semibold))
                Text("profile.add")
                    .font(.caption)
            }
            .foregroundStyle(.tint)
            .frame(maxWidth: .infinity, minHeight: 96)
            .padding()
            .background(Color(.secondarySystemBackground))
            .clipShape(RoundedRectangle(cornerRadius: 14))
            .overlay(
                RoundedRectangle(cornerRadius: 14)
                    .strokeBorder(style: StrokeStyle(lineWidth: 1, dash: [6]))
                    .foregroundStyle(.tint.opacity(0.5))
            )
        }
        .buttonStyle(.plain)
        .accessibilityLabel("profile.add.accessibility")
    }

    private func statusColor(_ online: Bool?) -> Color {
        switch online {
        case .some(true): return .green
        case .some(false): return .red
        case .none: return .gray
        }
    }

    private func sessionLabel(_ profileStatus: ProfileStatus?) -> String {
        guard let profileStatus, let online = profileStatus.online else {
            return String(localized: "profile.status.checking")
        }
        guard online else { return String(localized: "profile.status.offline") }
        if let count = profileStatus.sessionCount {
            return String(format: String(localized: "profile.sessions.count %lld"), Int64(count))
        }
        return String(localized: "profile.status.online")
    }

    private func probeAll() async {
        guard !isProbing else { return }
        isProbing = true
        defer { isProbing = false }

        await withTaskGroup(of: (UUID, ProfileStatus).self) { group in
            for profile in appSettings.profiles {
                let url = appSettings.baseURL(for: profile)
                let key = profile.apiKey.isEmpty ? appSettings.apiKey : profile.apiKey
                let id = profile.id
                group.addTask {
                    let online = await Self.probeHealth(baseURL: url)
                    var count: Int?
                    if online {
                        count = await Self.sessionCount(baseURL: url, apiKey: key)
                    }
                    return (id, ProfileStatus(online: online, sessionCount: count))
                }
            }
            for await (id, profileStatus) in group {
                status[id] = profileStatus
            }
        }
    }

    nonisolated private static func probeHealth(baseURL: URL) async -> Bool {
        var request = URLRequest(url: baseURL.appendingPathComponent("health"))
        request.timeoutInterval = 3
        guard let (_, response) = try? await URLSession.shared.data(for: request),
              let http = response as? HTTPURLResponse else { return false }
        return http.statusCode == 200
    }

    nonisolated private static func sessionCount(baseURL: URL, apiKey: String) async -> Int? {
        var request = URLRequest(url: baseURL.appendingPathComponent("api/sessions"))
        request.timeoutInterval = 5
        if !apiKey.isEmpty {
            request.setValue("Bearer \(apiKey)", forHTTPHeaderField: "Authorization")
        }
        guard let (data, response) = try? await URLSession.shared.data(for: request),
              let http = response as? HTTPURLResponse, http.statusCode == 200 else { return nil }
        struct Response: Decodable {
            struct Item: Decodable {}
            let data: [Item]
        }
        return (try? JSONDecoder().decode(Response.self, from: data))?.data.count
    }
}
