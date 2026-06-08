import Foundation
import SwiftUI

@MainActor
final class AppSettings: ObservableObject {
    @AppStorage("serverHost") var serverHost: String = "http://localhost:8642"
    @AppStorage("selectedModel") var selectedModel: String = "hermes-agent"

    @Published var apiKey: String = ""

    @Published var sessions: [Session] = []

    private var sessionsFileURL: URL {
        let url = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
        return url.appendingPathComponent("sessions.json")
    }

    func loadSessions() {
        guard let data = try? Data(contentsOf: sessionsFileURL) else { return }
        sessions = (try? JSONDecoder().decode([Session].self, from: data)) ?? []
    }

    func saveSessions() {
        let data = try? JSONEncoder().encode(sessions)
        try? data?.write(to: sessionsFileURL, options: .atomic)
    }

    func createSession(title: String = "새 세션") -> Session {
        let session = Session(id: UUID().uuidString, title: title, updatedAt: .init())
        sessions.insert(session, at: 0)
        saveSessions()
        return session
    }

    func deleteSession(id: String) {
        sessions.removeAll { $0.id == id }
        saveSessions()
    }
}

extension AppSettings {
    func headers() -> [String: String] {
        var headers: [String: String] = [
            "Content-Type": "application/json",
            "Accept": "text/event-stream"
        ]
        if !apiKey.isEmpty {
            headers["Authorization"] = "Bearer \(apiKey)"
        }
        return headers
    }
}
