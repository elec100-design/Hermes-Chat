import Foundation

/// Gemini Live 대화의 로컬 영속 저장소 (T-156).
/// 앱 컨테이너(Documents/)에 JSON 1개로 보관 — **온디바이스 전용**, 게이트웨이/클라우드 미전송.
/// (자막 본문은 Gemini가 들은 사용자 발화이므로 외부에 다시 보내지 않는다.)
@MainActor
final class LiveSessionStore: ObservableObject {
    static let shared = LiveSessionStore()

    @Published private(set) var sessions: [LiveSession] = []

    private let fileURL: URL
    private static let maxSessions = 200

    private init() {
        let docs = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
        fileURL = docs.appendingPathComponent("live_sessions.json")
        load()
    }

    private func load() {
        guard let data = try? Data(contentsOf: fileURL),
              let decoded = try? JSONDecoder().decode([LiveSession].self, from: data) else {
            sessions = []
            return
        }
        sessions = decoded.sorted { $0.updatedAt > $1.updatedAt }
    }

    private func persist() {
        let trimmed = Array(sessions.prefix(Self.maxSessions))
        guard let data = try? JSONEncoder().encode(trimmed) else { return }
        try? data.write(to: fileURL, options: .atomic)
    }

    /// 신규/기존 세션 upsert 후 최신순 정렬. 메시지가 비면 저장하지 않는다(빈 대화 노이즈 방지).
    func save(_ session: LiveSession) {
        guard !session.messages.isEmpty else { return }
        var updated = session
        updated.updatedAt = Date()
        if updated.title == "새 대화" {
            updated.title = LiveSession.makeTitle(from: updated.messages)
        }
        if let idx = sessions.firstIndex(where: { $0.id == updated.id }) {
            sessions[idx] = updated
        } else {
            sessions.insert(updated, at: 0)
        }
        sessions.sort { $0.updatedAt > $1.updatedAt }
        persist()
    }

    func delete(_ id: UUID) {
        sessions.removeAll { $0.id == id }
        persist()
    }

    func delete(atOffsets offsets: IndexSet, in filtered: [LiveSession]) {
        let ids = offsets.map { filtered[$0].id }
        sessions.removeAll { ids.contains($0.id) }
        persist()
    }

    func session(for id: UUID) -> LiveSession? {
        sessions.first { $0.id == id }
    }
}
