import Foundation
import SwiftUI

@MainActor
final class AppSettings: ObservableObject {
    @AppStorage("serverHost") var serverHost: String = "http://localhost:8642"
    @AppStorage("selectedModel") var selectedModel: String = "hermes-agent"
    @AppStorage("apiKey") var apiKey: String = ""

    @Published var sessions: [Session] = []
    @Published var isLoadingSessions: Bool = false
    @Published var sessionLoadError: String? = nil

    var hermesClient: HermesAPIClient {
        HermesAPIClient(
            baseURL: URL(string: serverHost) ?? URL(string: "http://localhost:8642")!,
            apiKey: apiKey
        )
    }

    func loadSessions() {
        guard !isLoadingSessions else { return }
        isLoadingSessions = true
        sessionLoadError = nil
        Task {
            do {
                sessions = try await hermesClient.fetchSessions()
            } catch {
                sessionLoadError = error.localizedDescription
            }
            isLoadingSessions = false
        }
    }

    func createSession() async throws -> Session {
        let session = try await hermesClient.createSession(model: selectedModel)
        sessions.insert(session, at: 0)
        return session
    }

    func deleteSession(id: String) {
        sessions.removeAll { $0.id == id }
        Task { try? await hermesClient.deleteSession(id: id) }
    }

    func updateSession(_ session: Session) {
        if let idx = sessions.firstIndex(where: { $0.id == session.id }) {
            sessions[idx] = session
        }
    }
}
