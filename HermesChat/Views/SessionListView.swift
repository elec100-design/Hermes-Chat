import SwiftUI

struct SessionListView: View {
    @ObservedObject var appSettings: AppSettings
    @Binding var activeSessionId: String?

    var body: some View {
        NavigationStack {
            List {
                Button {
                    let session = appSettings.createSession()
                    activeSessionId = session.id
                } label: {
                    Label("새 세션 만들기", systemImage: "speaker.wave.2.bubble.fill")
                        .foregroundStyle(.tint)
                }

                ForEach(appSettings.sessions) { session in
                    Button {
                        activeSessionId = session.id
                        appSettings.loadSessions()
                    } label: {
                        VStack(alignment: .leading, spacing: 4) {
                            Text(session.title.isEmpty ? "(제목 없음)" : session.title)
                                .font(.headline)
                            Text(formattedDate(session.updatedAt))
                                .font(.caption)
                                .foregroundStyle(.secondary)
                        }
                    }
                    .swipeActions(edge: .trailing) {
                        Button(role: .destructive) {
                            appSettings.deleteSession(id: session.id)
                        } label: {
                            Text("삭제")
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
