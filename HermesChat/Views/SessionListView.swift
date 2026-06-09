import SwiftUI

struct SessionListView: View {
    @ObservedObject var appSettings: AppSettings
    @State private var navigationPath = NavigationPath()
    @State private var isCreatingSession = false

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
                ForEach(appSettings.sessions) { session in
                    NavigationLink(value: session) {
                        VStack(alignment: .leading, spacing: 4) {
                            Text(session.displayTitle)
                                .font(.headline)
                                .lineLimit(1)
                            Text(formattedDate(session.updatedAt))
                                .font(.caption)
                                .foregroundStyle(.secondary)
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
            .toolbar {
                ToolbarItem(placement: .primaryAction) {
                    NavigationLink {
                        SettingsView(appSettings: appSettings)
                    } label: {
                        Text("설정")
                    }
                }
                ToolbarItem(placement: .navigationBarLeading) {
                    if appSettings.isLoadingSessions {
                        ProgressView().scaleEffect(0.8)
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

    private func formattedDate(_ date: Date) -> String {
        let formatter = RelativeDateTimeFormatter()
        formatter.unitsStyle = .short
        return formatter.localizedString(for: date, relativeTo: .now)
    }
}
