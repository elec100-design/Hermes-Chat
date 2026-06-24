import Foundation

/// Gemini Live 대화 1건 — **로컬(온디바이스) 저장 전용** (T-156).
/// 게이트웨이 `Session`과 분리한다: Gemini 대화라 서버 동기 없이 기기에만 보관하며,
/// 메시지는 채팅 화면과 동일한 `ChatMessage`(Codable)를 재사용해 버블 렌더와 저장을 일원화한다.
struct LiveSession: Identifiable, Codable, Equatable {
    let id: UUID
    var title: String
    var voice: String
    var messages: [ChatMessage]
    var createdAt: Date
    var updatedAt: Date

    init(
        id: UUID = UUID(),
        title: String = "새 대화",
        voice: String = "Aoede",
        messages: [ChatMessage] = [],
        createdAt: Date = Date(),
        updatedAt: Date = Date()
    ) {
        self.id = id
        self.title = title
        self.voice = voice
        self.messages = messages
        self.createdAt = createdAt
        self.updatedAt = updatedAt
    }

    /// 첫 사용자 발화로 제목을 만든다(기존 세션 자동 제목과 동형). 최대 24자.
    static func makeTitle(from messages: [ChatMessage]) -> String {
        guard let first = messages.first(where: { $0.role == .user && !$0.content.isEmpty }) else {
            return "새 대화"
        }
        let trimmed = first.content.trimmingCharacters(in: .whitespacesAndNewlines)
        return String(trimmed.prefix(24))
    }
}
