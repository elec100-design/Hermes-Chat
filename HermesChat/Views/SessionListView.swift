import SwiftUI

struct SessionListView: View {
    @ObservedObject var appSettings: AppSettings
    /// 음성 진입 시 앱 레벨에서 세션을 push할 수 있도록 경로를 코디네이터로 승격 (T-131)
    @ObservedObject private var coordinator = VoiceEntryCoordinator.shared
    @State private var isCreatingSession = false
    @State private var searchText = ""
    @State private var renamingSession: Session?
    @State private var renameText: String = ""

    var body: some View {
        NavigationStack(path: $coordinator.sessionsPath) {
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
                        Button("common.retry") { appSettings.loadSessions() }
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
                            coordinator.sessionsPath.append(session)
                        } catch {
                            appSettings.sessionLoadError = error.localizedDescription
                        }
                        isCreatingSession = false
                    }
                } label: {
                    if isCreatingSession {
                        HStack(spacing: 8) {
                            ProgressView().scaleEffect(0.8)
                            Text("session.creating")
                                .foregroundStyle(.secondary)
                        }
                    } else {
                        Label("session.new", systemImage: "speaker.wave.2.bubble.fill")
                            .foregroundStyle(.tint)
                    }
                }
                .disabled(isCreatingSession)

                // Session list
                ForEach(displayedSessions) { session in
                    NavigationLink(value: session) {
                        VStack(alignment: .leading, spacing: 4) {
                            HStack(spacing: 4) {
                                if appSettings.isPinned(id: session.id) {
                                    Image(systemName: "pin.fill")
                                        .font(.caption2)
                                        .foregroundStyle(.orange)
                                }
                                Text(session.displayTitle)
                                    .font(.headline)
                                    .lineLimit(1)
                            }
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
                    .swipeActions(edge: .trailing, allowsFullSwipe: false) {
                        // 풀 스와이프 자동 삭제 방지(allowsFullSwipe: false) — 버튼을 눌러야만 동작.
                        // 선언 순서상 첫 버튼이 가장 오른쪽 → 삭제를 맨 끝, 그 왼쪽에 이름변경·Pin
                        Button(role: .destructive) {
                            appSettings.deleteSession(id: session.id)
                        } label: {
                            Label("common.delete", systemImage: "trash")
                        }
                        Button {
                            renamingSession = session
                            renameText = session.title ?? ""
                        } label: {
                            Label("session.rename", systemImage: "pencil")
                        }
                        .tint(.blue)
                        Button {
                            appSettings.togglePin(id: session.id)
                        } label: {
                            if appSettings.isPinned(id: session.id) {
                                Label("session.unpin", systemImage: "pin.slash")
                            } else {
                                Label("session.pin", systemImage: "pin")
                            }
                        }
                        .tint(.orange)
                    }
                    .swipeActions(edge: .leading) {
                        Button {
                            Task {
                                do {
                                    let forked = try await appSettings.forkSession(id: session.id)
                                    coordinator.sessionsPath.append(forked)
                                } catch {
                                    appSettings.sessionLoadError = error.localizedDescription
                                }
                            }
                        } label: {
                            Label("session.fork", systemImage: "arrow.triangle.branch")
                        }
                        .tint(.indigo)
                    }
                }

                // 페이지네이션: 목록 끝에 도달하면 다음 페이지 로드 (T-072)
                if appSettings.hasMoreSessions && searchText.isEmpty {
                    HStack {
                        Spacer()
                        if appSettings.isLoadingMoreSessions {
                            ProgressView().scaleEffect(0.8)
                        } else {
                            Text("common.load_more")
                                .font(.footnote)
                                .foregroundStyle(.secondary)
                        }
                        Spacer()
                    }
                    .onAppear { appSettings.loadMoreSessions() }
                }
            }
            .listStyle(.insetGrouped)
            .navigationTitle(appSettings.selectedProfile.name)
            .searchable(text: $searchText, prompt: "session.search.placeholder")
            .alert("session.rename.title", isPresented: Binding(
                get: { renamingSession != nil },
                set: { if !$0 { renamingSession = nil } }
            ), presenting: renamingSession) { session in
                TextField("session.rename.placeholder", text: $renameText)
                Button("common.cancel", role: .cancel) { }
                Button("common.save") {
                    var updated = session
                    let trimmed = renameText.trimmingCharacters(in: .whitespaces)
                    updated.title = trimmed
                    appSettings.updateSession(updated)
                    let client = appSettings.hermesClient
                    Task { try? await client.updateSessionTitle(id: session.id, title: trimmed) }
                }
            } message: { _ in
                Text("session.rename.message")
            }
            .toolbar {
                ToolbarItem(placement: .topBarLeading) {
                    sourceFilterMenu
                }
                ToolbarItem(placement: .topBarTrailing) {
                    HStack(spacing: 12) {
                        if appSettings.isLoadingSessions {
                            ProgressView().scaleEffect(0.8)
                        }
                        NavigationLink {
                            SettingsView(appSettings: appSettings)
                        } label: {
                            Text("settings.title")
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

    /// 프로필 선택은 보드 탭으로 일원화 — 여기는 소스 필터만 (T-074)
    @ViewBuilder
    private var sourceFilterMenu: some View {
        if !appSettings.availableSources.isEmpty {
            Menu {
                Button {
                    appSettings.selectedSource = nil
                } label: {
                    if appSettings.selectedSource == nil {
                        Label("common.all", systemImage: "checkmark")
                    } else {
                        Text("common.all")
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
            } label: {
                HStack(spacing: 3) {
                    Image(systemName: appSettings.selectedSource == nil
                          ? "line.3.horizontal.decrease.circle"
                          : "line.3.horizontal.decrease.circle.fill")
                    if let source = appSettings.selectedSource {
                        Text(sourceDisplayName(source))
                            .font(.subheadline)
                    }
                }
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
