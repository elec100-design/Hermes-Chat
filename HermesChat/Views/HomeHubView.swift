import SwiftUI

/// 홈 허브 (T-164) — OpenClaw 'Control' 화면 모티프.
/// 선택된 프로필의 상태 카드 + 에이전트/모니터링/리소스 카드 섹션.
/// 기존 보드·대시보드 탭은 여기서 push로 진입한다 (탭 6→5 개편).
struct HomeHubView: View {
    @ObservedObject var appSettings: AppSettings
    @Binding var selectedTab: AppTab

    @State private var online: Bool?
    @State private var isProbing = false
    @State private var showDiscussion = false
    @State private var showCronManager = false

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(spacing: HermesUI.sectionSpacing) {
                    statusCard

                    VStack(spacing: 8) {
                        SectionHeader(title: String(localized: "home.section.agent"))
                        VStack(spacing: 0) {
                            NavigationLink {
                                ProfileBoardView(appSettings: appSettings, selectedTab: $selectedTab)
                            } label: {
                                IconRow(
                                    systemImage: "square.grid.2x2",
                                    tint: .accentColor,
                                    title: String(localized: "tab.board"),
                                    subtitle: String(localized: "home.board.subtitle")
                                )
                            }
                            .buttonStyle(.plain)
                            Divider().padding(.leading, 46)
                            Button { showDiscussion = true } label: {
                                IconRow(
                                    systemImage: "brain.head.profile",
                                    tint: .purple,
                                    title: "Deep think",
                                    subtitle: String(localized: "home.discussion.subtitle")
                                )
                            }
                            .buttonStyle(.plain)
                            Divider().padding(.leading, 46)
                            Button { showCronManager = true } label: {
                                IconRow(
                                    systemImage: "clock.arrow.circlepath",
                                    tint: .hermesPositive,
                                    title: String(localized: "profile.board.cron.manage"),
                                    subtitle: String(localized: "home.cron.subtitle")
                                )
                            }
                            .buttonStyle(.plain)
                        }
                        .hermesCard()
                    }

                    VStack(spacing: 8) {
                        SectionHeader(title: String(localized: "home.section.monitoring"))
                        VStack(spacing: 0) {
                            NavigationLink {
                                DashboardWebView(appSettings: appSettings)
                                    .hidesFloatingTabBar()
                            } label: {
                                IconRow(
                                    systemImage: "gauge.with.dots.needle.50percent",
                                    tint: .hermesWarning,
                                    title: String(localized: "tab.dashboard"),
                                    subtitle: String(localized: "home.dashboard.subtitle")
                                )
                            }
                            .buttonStyle(.plain)
                            Divider().padding(.leading, 46)
                            Button { selectedTab = .kanban } label: {
                                IconRow(
                                    systemImage: "rectangle.split.3x1",
                                    tint: .accentColor,
                                    title: String(localized: "tab.kanban"),
                                    subtitle: String(localized: "home.kanban.subtitle")
                                )
                            }
                            .buttonStyle(.plain)
                        }
                        .hermesCard()
                    }

                    VStack(spacing: 8) {
                        SectionHeader(title: String(localized: "home.section.resources"))
                        VStack(spacing: 0) {
                            NavigationLink {
                                SkillsView(appSettings: appSettings)
                            } label: {
                                IconRow(
                                    systemImage: "wand.and.stars",
                                    tint: .purple,
                                    title: String(localized: "settings.skills"),
                                    subtitle: String(localized: "home.skills.subtitle")
                                )
                            }
                            .buttonStyle(.plain)
                            Divider().padding(.leading, 46)
                            NavigationLink {
                                FileBrowserView(appSettings: appSettings)
                            } label: {
                                IconRow(
                                    systemImage: "folder",
                                    tint: .hermesPositive,
                                    title: String(localized: "settings.filebrowser"),
                                    subtitle: String(localized: "home.files.subtitle")
                                )
                            }
                            .buttonStyle(.plain)
                        }
                        .hermesCard()
                    }
                }
                .padding()
            }
            .background(Color.hermesGroupedBG)
            .navigationTitle("tab.home")
            .fullScreenCover(isPresented: $showDiscussion) {
                DiscussionView(appSettings: appSettings)
            }
            .sheet(isPresented: $showCronManager) {
                CronManagerView(appSettings: appSettings)
            }
            .refreshable { await probe() }
            .task { await probe() }
        }
    }

    // MARK: - 상태 카드

    private var selectedProfile: HermesProfile? {
        appSettings.profiles.first { $0.id == appSettings.selectedProfileID }
            ?? appSettings.profiles.first
    }

    private var statusCard: some View {
        VStack(spacing: 12) {
            HStack(spacing: 12) {
                Image(systemName: "cpu")
                    .font(.system(size: 22, weight: .semibold))
                    .foregroundStyle(Color.accentColor)
                    .frame(width: 44, height: 44)
                    .background(
                        RoundedRectangle(cornerRadius: 11, style: .continuous)
                            .fill(Color.accentColor.opacity(0.14))
                    )
                VStack(alignment: .leading, spacing: 2) {
                    Text(selectedProfile?.name ?? "Hermes")
                        .font(.title3.bold())
                    Text(appSettings.serverHost)
                        .font(.caption)
                        .foregroundStyle(.secondary)
                        .lineLimit(1)
                }
                Spacer()
                statusPill
            }

            Divider()

            Button { selectedTab = .chat } label: {
                IconRow(
                    systemImage: "bubble.left.and.bubble.right",
                    tint: .accentColor,
                    title: String(localized: "home.open_chat"),
                    subtitle: String(localized: "home.open_chat.subtitle")
                )
            }
            .buttonStyle(.plain)
        }
        .hermesCard()
    }

    @ViewBuilder
    private var statusPill: some View {
        if appSettings.isDemoMode {
            StatusPill(text: "Demo", color: .hermesWarning)
        } else if isProbing {
            ProgressView().scaleEffect(0.8)
        } else {
            switch online {
            case .some(true):
                StatusPill(text: String(localized: "profile.status.online"), color: .hermesPositive)
            case .some(false):
                StatusPill(text: String(localized: "profile.status.offline"), color: .hermesDanger)
            case .none:
                StatusPill(text: String(localized: "profile.status.checking"), color: .gray)
            }
        }
    }

    private func probe() async {
        guard !appSettings.isDemoMode, !isProbing else { return }
        guard let profile = selectedProfile else { return }
        isProbing = true
        defer { isProbing = false }
        online = await ProfileBoardView.probeHealth(baseURL: appSettings.baseURL(for: profile))
    }
}
