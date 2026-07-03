import Foundation

/// Live 탭 음성 백엔드 (T-160).
/// - gemini: Gemini Live 실시간 speech-to-speech (오디오가 Google로 전송)
/// - hermes: hermes-agent 게이트웨이 — 온디바이스 STT → 텍스트 SSE → 문장 단위 TTS
enum LiveVoiceBackend: String, Codable, CaseIterable, Identifiable {
    case gemini
    case hermes

    var id: String { rawValue }
    var displayName: String {
        switch self {
        case .gemini: return "Gemini Live"
        case .hermes: return "Hermes Agent"
        }
    }
    var iconName: String {
        switch self {
        case .gemini: return "waveform"
        case .hermes: return "server.rack"
        }
    }
}

/// Live 대화 1건 — **로컬(온디바이스) 저장 전용** (T-156, T-160에서 다중 백엔드).
/// 게이트웨이 `Session`과 분리한다: 서버 동기 없이 기기에만 보관하며,
/// 메시지는 채팅 화면과 동일한 `ChatMessage`(Codable)를 재사용해 버블 렌더와 저장을 일원화한다.
/// (Hermes 백엔드는 게이트웨이 세션 id만 참조로 보관 — 히스토리는 서버가 가진다.)
struct LiveSession: Identifiable, Codable, Equatable {
    let id: UUID
    var title: String
    var voice: String
    var messages: [ChatMessage]
    var createdAt: Date
    var updatedAt: Date
    /// 이 대화의 음성 백엔드 (T-160). 구버전 저장분은 gemini로 디코드.
    var backend: LiveVoiceBackend
    /// backend == .hermes일 때 이어가기용 게이트웨이 세션 id.
    var hermesSessionId: String?

    init(
        id: UUID = UUID(),
        title: String = "새 대화",
        voice: String = "Aoede",
        messages: [ChatMessage] = [],
        createdAt: Date = Date(),
        updatedAt: Date = Date(),
        backend: LiveVoiceBackend = .gemini,
        hermesSessionId: String? = nil
    ) {
        self.id = id
        self.title = title
        self.voice = voice
        self.messages = messages
        self.createdAt = createdAt
        self.updatedAt = updatedAt
        self.backend = backend
        self.hermesSessionId = hermesSessionId
    }

    private enum CodingKeys: String, CodingKey {
        case id, title, voice, messages, createdAt, updatedAt, backend, hermesSessionId
    }

    /// 하위 호환 디코딩 — 기존 Documents/live_sessions.json에는 backend 필드가 없다.
    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        id = try c.decode(UUID.self, forKey: .id)
        title = try c.decode(String.self, forKey: .title)
        voice = try c.decode(String.self, forKey: .voice)
        messages = try c.decode([ChatMessage].self, forKey: .messages)
        createdAt = try c.decode(Date.self, forKey: .createdAt)
        updatedAt = try c.decode(Date.self, forKey: .updatedAt)
        backend = try c.decodeIfPresent(LiveVoiceBackend.self, forKey: .backend) ?? .gemini
        hermesSessionId = try c.decodeIfPresent(String.self, forKey: .hermesSessionId)
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
