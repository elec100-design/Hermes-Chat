import Foundation

// Hermes-bot 서버(:8900) 응답 모델. 계약: 불리언은 정수 0/1, id 는 정수, 시각은 ISO-8601 문자열.
// 서버가 필드를 더하거나 빼도 목록 전체가 깨지지 않도록 대부분 Optional + 관대한 디코딩을 쓴다.

private extension KeyedDecodingContainer {
    /// 타입이 달라도(예: 숫자가 와야 하는데 문자열) 예외 대신 nil — 한 필드 때문에 목록 전체가 죽지 않게.
    func lenient<T: Decodable>(_ type: T.Type, _ key: Key) -> T? {
        try? decodeIfPresent(type, forKey: key)
    }
}

struct BotMember: Identifiable, Decodable, Equatable, Hashable {
    let id: Int
    let slug: String
    let name: String?
    let avatar: String?
    let health: String?
    let tone: String?

    var displayName: String { (name?.isEmpty == false ? name : nil) ?? slug }
    var avatarText: String { (avatar?.isEmpty == false ? avatar : nil) ?? "🤖" }
}

struct Bot: Identifiable, Decodable, Equatable, Hashable {
    let id: Int
    let slug: String
    let name: String?
    let profileName: String?
    let avatar: String?
    let soulSummary: String?
    let health: String?
    let status: String?
    let dmChannelId: Int?
    let preview: String?
    let tone: String?
    let readOnly: Int?

    enum CodingKeys: String, CodingKey {
        case id, slug, name, avatar, health, status, preview, tone
        case profileName = "profile_name"
        case soulSummary = "soul_summary"
        case dmChannelId = "dm_channel_id"
        case readOnly = "read_only"
    }

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        id = try c.decode(Int.self, forKey: .id)
        slug = c.lenient(String.self, .slug) ?? "bot\(id)"
        name = c.lenient(String.self, .name)
        profileName = c.lenient(String.self, .profileName)
        avatar = c.lenient(String.self, .avatar)
        soulSummary = c.lenient(String.self, .soulSummary)
        health = c.lenient(String.self, .health)
        status = c.lenient(String.self, .status)
        dmChannelId = c.lenient(Int.self, .dmChannelId)
        preview = c.lenient(String.self, .preview)
        tone = c.lenient(String.self, .tone)
        readOnly = c.lenient(Int.self, .readOnly)
    }

    var displayName: String { (name?.isEmpty == false ? name : nil) ?? slug }
    /// avatar 가 "" 이면 🤖
    var avatarText: String { (avatar?.isEmpty == false ? avatar : nil) ?? "🤖" }
    var isOnline: Bool { health == "online" }

    /// 목록 한 줄 미리보기: preview → soul_summary → @slug
    var previewLine: String {
        if let p = preview, !p.isEmpty { return p }
        if let s = soulSummary, !s.isEmpty { return s }
        return "@\(slug)"
    }
}

struct BotChannel: Identifiable, Decodable, Equatable, Hashable {
    let id: Int
    let slug: String?
    let title: String
    let topic: String?
    let botChat: Int?
    let status: String?
    let updatedAt: String?
    let members: [BotMember]
    let preview: String?

    enum CodingKeys: String, CodingKey {
        case id, slug, title, topic, status, members, preview
        case botChat = "bot_chat"
        case updatedAt = "updated_at"
    }

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        id = try c.decode(Int.self, forKey: .id)
        slug = c.lenient(String.self, .slug)
        title = c.lenient(String.self, .title) ?? "#\(id)"
        topic = c.lenient(String.self, .topic)
        botChat = c.lenient(Int.self, .botChat)
        status = c.lenient(String.self, .status)
        updatedAt = c.lenient(String.self, .updatedAt)
        members = c.lenient([BotMember].self, .members) ?? []
        preview = c.lenient(String.self, .preview)
    }
}

struct BotMessage: Identifiable, Decodable, Equatable {
    let id: Int
    let channelId: Int?
    let seq: Int?
    /// "user" | "bot" | "system"
    let role: String
    let author: String?
    let body: String
    /// "queued" | "running" | "done" | "failed"
    let status: String?
    let jobId: Int?
    let authorBotId: Int?
    /// 계약상 "JSON 을 담은 문자열 또는 null" — 지금 화면에선 해석하지 않는다.
    let attachments: String?
    let error: String?
    let createdAt: String?
    let displayName: String?
    let avatar: String?

    enum CodingKeys: String, CodingKey {
        case id, seq, role, author, body, status, attachments, error, avatar
        case channelId = "channel_id"
        case jobId = "job_id"
        case authorBotId = "author_bot_id"
        case createdAt = "created_at"
        case displayName = "display_name"
    }

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        id = try c.decode(Int.self, forKey: .id)
        channelId = c.lenient(Int.self, .channelId)
        seq = c.lenient(Int.self, .seq)
        role = c.lenient(String.self, .role) ?? "bot"
        author = c.lenient(String.self, .author)
        body = c.lenient(String.self, .body) ?? ""
        status = c.lenient(String.self, .status)
        jobId = c.lenient(Int.self, .jobId)
        authorBotId = c.lenient(Int.self, .authorBotId)
        attachments = c.lenient(String.self, .attachments)
        error = c.lenient(String.self, .error)
        createdAt = c.lenient(String.self, .createdAt)
        displayName = c.lenient(String.self, .displayName)
        avatar = c.lenient(String.self, .avatar)
    }

    /// SwiftUI/ChatMessage 용 결정적 UUID — 같은 id 면 항상 같은 UUID 라 목록 diff 가 안정적이다.
    var uuid: UUID {
        UUID(uuidString: String(format: "00000000-0000-0000-0000-%012d", id)) ?? UUID()
    }

    var isUser: Bool { role == "user" }
    var isSystem: Bool { role == "system" }
    /// 아직 진행 중인 봇 응답 (스트림으로 따라가야 한다)
    var isInFlight: Bool { status == "queued" || status == "running" }
}

struct BotApproval: Identifiable, Decodable, Equatable {
    let id: Int
    let kind: String?
    let title: String
    let detail: String?
    let action: String?
    let risk: String?
    let status: String?
    let botId: Int?
    let channelId: Int?
    let createdAt: String?

    enum CodingKeys: String, CodingKey {
        case id, kind, title, detail, action, risk, status
        case botId = "bot_id"
        case channelId = "channel_id"
        case createdAt = "created_at"
    }

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        id = try c.decode(Int.self, forKey: .id)
        kind = c.lenient(String.self, .kind)
        title = c.lenient(String.self, .title) ?? "#\(id)"
        detail = c.lenient(String.self, .detail)
        action = c.lenient(String.self, .action)
        risk = c.lenient(String.self, .risk)
        status = c.lenient(String.self, .status)
        botId = c.lenient(Int.self, .botId)
        channelId = c.lenient(Int.self, .channelId)
        createdAt = c.lenient(String.self, .createdAt)
    }
}

/// POST /api/channels/{id}/messages (202) 응답.
/// 채널에서 @멘션이 없으면 jobId == nil (아무도 답하지 않음), 멘션 오류면 error 가 채워진다.
struct BotPostResult: Decodable, Equatable {
    let messageId: Int?
    let replyMessageId: Int?
    let jobId: Int?
    let bot: String?
    let error: String?

    enum CodingKeys: String, CodingKey {
        case bot, error
        case messageId = "message_id"
        case replyMessageId = "reply_message_id"
        case jobId = "job_id"
    }

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        messageId = c.lenient(Int.self, .messageId)
        replyMessageId = c.lenient(Int.self, .replyMessageId)
        jobId = c.lenient(Int.self, .jobId)
        bot = c.lenient(String.self, .bot)
        error = c.lenient(String.self, .error)
    }
}

/// 잡 SSE 스트림에서 올라오는 이벤트
enum BotJobEvent: Equatable {
    /// 새로 도착한 본문 조각(이어 붙인다)
    case delta(String)
    /// 잡 종료. status: "done" | "failed"
    case end(status: String, error: String?)
}
