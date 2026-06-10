import SwiftUI

struct SessionListView: View {
    @ObservedObject var appSettings: AppSettings
    @State private var navigationPath = NavigationPath()
    @State private var isCreatingSession = false
    @State private var searchText = ""

    var body: some View {
        NavigationStack(path: $navigationPath) {
            List {
                // Error banner
                if let error = appSettings.sessionLoadError {
                    HStack {
                        Image(systemName: "exclamationmark.triangle.fill")
                            .foregroundStyle(.orange)
                        Text(error)
                            .font(.footnote)
                            .foregroundStyle(.secondary)
                        Spacer()
                        Button("재시도") { appSettings.loadSessions() }
                            .font(.footnote.bold())
                    }
                    .listRowBackground(Color.orange.opacity(0.08))
                }

                // New session button
                Button {
                    guard !isCreatingSession else { return }
                    isCreatingSession = true
                    Task {
                        do {
                            let session = try await appSettings.createSession()
                            navigationPath.append(session)
                        } catch {
                            appSettings.sessionLoadError = error.localizedDescription
                        }
                        isCreatingSession = false
                    }
                } label: {
                    if isCreatingSession {
                        HStack(spacing: 8) {
                            ProgressView().scaleEffect(0.8)
                            Text("생성 중...")
                                .foregroundStyle(.secondary)
                        }
                    } else {
                        Label("새 세션 만들기", systemImage: "speaker.wave.2.bubble.fill")
                            .foregroundStyle(.tint)
                    }
                }
                .disabled(isCreatingSession)

                // Session list
                ForEach(displayedSessions) { session in
                    NavigationLink(value: session) {
                        VStack(alignment: .leading, spacing: 4) {
                            Text(session.displayTitle)
                                .font(.headline)
                                .lineLimit(1)
                            HStack(spacing: 6) {
                                if let source = session.source {
                                    Text(sourceDisplayName(source))
                                        .font(.caption2)
                                        .padding(.horizontal, 5)
                                        .padding(.vertical, 2)
                                        .background(sourceColor(source).opacity(0.15))
                                        .foregroundStyle(sourceColor(source))
                                        .clipShape(Capsule())
                                }
                                Text(formattedDate(session.updatedAt))
                                    .font(.caption)
                                    .foregroundStyle(.secondary)
                            }
                        }
                    }
                    .swipeActions(edge: .trailing) {
                        Button(role: .destructive) {
                            appSettings.deleteSession(id: session.id)
                        } label: {
                            Label("삭제", systemImage: "trash")
                        }
                    }
                }
            }
            .listStyle(.insetGrouped)
            .navigationTitle("세션")
            .searchable(text: $searchText, prompt: "세션 검색")
            .toolbar {
                ToolbarItem(placement: .topBarLeading) {
                    profileMenu
                }
                ToolbarItem(placement: .topBarTrailing) {
                    HStack(spacing: 12) {
                        if appSettings.isLoadingSessions {
                            ProgressView().scaleEffect(0.8)
                        }
                        NavigationLink {
                            SettingsView(appSettings: appSettings)
                        } label: {
                            Text("설정")
                        }
                    }
                }
            }
            .navigationDestination(for: Session.self) { session in
                ChatView(sessionId: session.id, appSettings: appSettings)
            }
            .onAppear {
                appSettings.loadSessions()
            }
        }
    }

    // MARK: - Profile / Source Menu

    private var displayedSessions: [Session] {
        let base = appSettings.filteredSessions
        guard !searchText.isEmpty else { return base }
        return base.filter {
            $0.displayTitle.localizedCaseInsensitiveContains(searchText)
                || ($0.preview ?? "").localizedCaseInsensitiveContains(searchText)
        }
    }

    private var profileMenu: some View {
        Menu {
            Section("프로필") {
                ForEach(appSettings.profiles) { profile in
                    Button {
                        appSettings.selectProfile(profile)
                    } label: {
                        if profile.id == appSettings.selectedProfileID {
                            Label(profile.name, systemImage: "checkmark")
                        } else {
                            Text(profile.name)
                        }
                    }
                }
            }

            if !appSettings.availableSources.isEmpty {
                Section("소스 필터") {
                    Button {
                        appSettings.selectedSource = nil
                    } label: {
                        if appSettings.selectedSource == nil {
                            Label("전체", systemImage: "checkmark")
                        } else {
                            Text("전체")
                        }
                    }
                    ForEach(appSettings.availableSources, id: \.self) { source in
                        Button {
                            appSettings.selectedSource = source
                        } label: {
                            if appSettings.selectedSource == source {
                                Label(sourceDisplayName(source), systemImage: "checkmark")
                            } else {
                                Text(sourceDisplayName(source))
                            }
                        }
                    }
                }
            }
        } label: {
            HStack(spacing: 3) {
                Text(appSettings.selectedProfile.name)
                    .font(.headline)
                if let source = appSettings.selectedSource {
                    Text("· \(sourceDisplayName(source))")
                        .font(.subheadline)
                        .foregroundStyle(.secondary)
                }
                Image(systemName: "chevron.down")
                    .font(.caption.weight(.semibold))
            }
        }
    }

    // MARK: - Helpers

    private func sourceDisplayName(_ source: String) -> String {
        switch source {
        case "telegram": return "Telegram"
        case "cron": return "Cron"
        case "api": return "API"
        case "slack": return "Slack"
        case "discord": return "Discord"
        case "whatsapp": return "WhatsApp"
        default: return source.capitalized
        }
    }

    private func sourceColor(_ source: String) -> Color {
        switch source {
        case "telegram": return .blue
        case "cron": return .orange
        case "api": return .purple
        case "slack": return .green
        case "discord": return .indigo
        default: return .secondary
        }
    }

    private func formattedDate(_ date: Date) -> String {
        let formatter = RelativeDateTimeFormatter()
        formatter.unitsStyle = .short
        return formatter.localizedString(for: date, relativeTo: .now)
    }
}
