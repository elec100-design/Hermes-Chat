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
    let tone: Int?  // 서버는 정체색 번호를 정수(1~6)로 준다 — String 으로 받으면 멤버 배열 전체가 디코딩에 실패한다

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
    let tone: Int?  // 서버는 정체색 번호를 정수(1~6)로 준다 — String 으로 받으면 멤버 배열 전체가 디코딩에 실패한다
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
        tone = c.lenient(Int.self, .tone)
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
    /// P34: 게스트 @멘션 정책 ("read_only" | "mention_off" | "full")
    let guestBotMode: String?

    enum CodingKeys: String, CodingKey {
        case id, slug, title, topic, status, members, preview
        case botChat = "bot_chat"
        case updatedAt = "updated_at"
        case guestBotMode = "guest_bot_mode"
    }

    init(
        id: Int,
        slug: String? = nil,
        title: String,
        topic: String? = nil,
        botChat: Int? = nil,
        status: String? = nil,
        updatedAt: String? = nil,
        members: [BotMember] = [],
        preview: String? = nil,
        guestBotMode: String? = nil
    ) {
        self.id = id
        self.slug = slug
        self.title = title
        self.topic = topic
        self.botChat = botChat
        self.status = status
        self.updatedAt = updatedAt
        self.members = members
        self.preview = preview
        self.guestBotMode = guestBotMode
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
        guestBotMode = c.lenient(String.self, .guestBotMode)
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
    /// P34: 인간 발화자 user id (오너 또는 게스트)
    let authorUserId: Int?
    /// P34: 서버가 요청자 기준으로 계산해 내려주는 내 발화 여부
    let isMine: Bool?
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
        case authorUserId = "author_user_id"
        case isMine = "is_mine"
        case createdAt = "created_at"
        case displayName = "display_name"
    }

    init(
        id: Int,
        channelId: Int? = nil,
        seq: Int? = nil,
        role: String,
        author: String? = nil,
        body: String,
        status: String? = nil,
        jobId: Int? = nil,
        authorBotId: Int? = nil,
        authorUserId: Int? = nil,
        isMine: Bool? = nil,
        attachments: String? = nil,
        error: String? = nil,
        createdAt: String? = nil,
        displayName: String? = nil,
        avatar: String? = nil
    ) {
        self.id = id
        self.channelId = channelId
        self.seq = seq
        self.role = role
        self.author = author
        self.body = body
        self.status = status
        self.jobId = jobId
        self.authorBotId = authorBotId
        self.authorUserId = authorUserId
        self.isMine = isMine
        self.attachments = attachments
        self.error = error
        self.createdAt = createdAt
        self.displayName = displayName
        self.avatar = avatar
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
        authorUserId = c.lenient(Int.self, .authorUserId)
        isMine = c.lenient(Bool.self, .isMine) ?? c.lenient(Int.self, .isMine).map { $0 != 0 }
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

    /// 3분할 채팅 렌더링용 — 내가 보낸 user 메시지인지 판정한다.
    /// SSE 브로드캐스트에는 `is_mine` 이 없을 수 있어 `authorUserId == myUserId` 를 함께 본다.
    func isSentByMe(myUserId: Int) -> Bool {
        guard isUser else { return false }
        if let isMine { return isMine }
        if myUserId > 0, let authorUserId { return authorUserId == myUserId }
        return authorUserId == nil
    }
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

// MARK: - Phase 31 / Hermes-bot P34: 채널 초대 · 게스트 · 멀티 유저

/// `GET /api/me` 응답
struct BotUserInfo: Decodable, Equatable {
    let userId: Int
    let displayName: String
    /// "owner" | "guest"
    let role: String
    let publicUrl: String?

    var isGuest: Bool { role == "guest" }

    enum CodingKeys: String, CodingKey {
        case role
        case userId = "user_id"
        case id
        case displayName = "display_name"
        case publicUrl = "public_url"
    }

    init(userId: Int, displayName: String, role: String = "owner", publicUrl: String? = nil) {
        self.userId = userId
        self.displayName = displayName
        self.role = role
        self.publicUrl = publicUrl
    }

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        userId = c.lenient(Int.self, .userId) ?? c.lenient(Int.self, .id) ?? 0
        displayName = c.lenient(String.self, .displayName) ?? "나"
        role = c.lenient(String.self, .role) ?? "owner"
        let rawUrl = c.lenient(String.self, .publicUrl)?.trimmingCharacters(in: .whitespacesAndNewlines)
        publicUrl = (rawUrl?.isEmpty == false) ? rawUrl : nil
    }
}

/// 채널 초대 링크 (`POST/GET /api/channels/{id}/invites`)
struct ChannelInvite: Identifiable, Decodable, Equatable {
    let id: Int
    let channelId: Int
    let label: String
    let inviteCode: String?
    let inviteUrl: String?
    let deepLink: String?
    let maxUses: Int?
    let usesCount: Int
    let expiresAt: String?
    let revoked: Bool
    let createdAt: String?

    enum CodingKeys: String, CodingKey {
        case id, label, revoked
        case channelId = "channel_id"
        case inviteCode = "invite_code"
        case inviteUrl = "invite_url"
        case deepLink = "deep_link"
        case maxUses = "max_uses"
        case usesCount = "uses_count"
        case expiresAt = "expires_at"
        case createdAt = "created_at"
    }

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        id = try c.decode(Int.self, forKey: .id)
        channelId = c.lenient(Int.self, .channelId) ?? 0
        label = c.lenient(String.self, .label) ?? ""
        inviteCode = c.lenient(String.self, .inviteCode)
        inviteUrl = c.lenient(String.self, .inviteUrl)
        deepLink = c.lenient(String.self, .deepLink)
        maxUses = c.lenient(Int.self, .maxUses)
        usesCount = c.lenient(Int.self, .usesCount) ?? 0
        expiresAt = c.lenient(String.self, .expiresAt)
        revoked = c.lenient(Bool.self, .revoked) ?? ((c.lenient(Int.self, .revoked) ?? 0) != 0)
        createdAt = c.lenient(String.self, .createdAt)
    }
}

/// 채널에 참여 중인 인간 멤버 (`GET /api/channels/{id}/users`)
struct ChannelHumanMember: Identifiable, Decodable, Equatable {
    let id: Int
    let displayName: String
    let role: String
    let joinedAt: String?

    enum CodingKeys: String, CodingKey {
        case id, role
        case displayName = "display_name"
        case joinedAt = "joined_at"
    }

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        id = try c.decode(Int.self, forKey: .id)
        displayName = c.lenient(String.self, .displayName) ?? "게스트"
        role = c.lenient(String.self, .role) ?? "guest"
        joinedAt = c.lenient(String.self, .joinedAt)
    }
}

/// `POST /api/invites/{code}/accept` 응답
struct InviteAcceptResult: Decodable, Equatable {
    let token: String
    let user: BotUserInfo
    let channel: BotChannel
}

/// 채널 실시간 SSE 이벤트 (`GET /api/channels/{id}/stream`)
enum ChannelStreamEvent: Equatable {
    case ready
    case message(BotMessage)
    case revoked
    case userRemoved(userId: Int)
    case userJoined
}

/// 입력창 끝 `@검색어` 추출 및 선택 결과 치환 도우미
enum MentionAutocomplete {
    struct Query: Equatable {
        let atOffset: Int
        let prefix: String
    }

    static func extractQuery(from text: String) -> Query? {
        guard let regex = try? NSRegularExpression(pattern: "(?:^|\\s)(@[^\\s@]*)$") else { return nil }
        let ns = text as NSString
        let range = NSRange(location: 0, length: ns.length)
        guard let match = regex.firstMatch(in: text, range: range),
              match.numberOfRanges >= 2 else { return nil }
        let tokenRange = match.range(at: 1)
        let token = ns.substring(with: tokenRange)
        return Query(atOffset: tokenRange.location, prefix: String(token.dropFirst()))
    }

    static func replaceQuery(_ query: Query, with handle: String, in text: String) -> String {
        let ns = text as NSString
        let safePrefix = ns.substring(to: min(query.atOffset, ns.length))
        return "\(safePrefix)@\(handle) "
    }
}

/// 초대 링크(`https://host/invite/<code>`, `hermes://invite?url=...&code=...`) 또는
/// 서버 주소 + 초대 코드 입력에서 `(serverURL, code)` 를 추출한다.
enum InviteInputParser {
    static func parse(urlOrLink: String, code rawCode: String = "") -> (serverURL: String, code: String)? {
        let input = urlOrLink.trimmingCharacters(in: .whitespacesAndNewlines)
        let explicitCode = rawCode.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !input.isEmpty else { return nil }

        if let comps = URLComponents(string: input),
           let scheme = comps.scheme?.lowercased(),
           scheme == "hermes" || scheme == "hermeschat" {
            let items = comps.queryItems ?? []
            guard let rawUrl = items.first(where: { $0.name == "url" })?.value?
                .trimmingCharacters(in: .whitespacesAndNewlines)
                .trimmingCharacters(in: CharacterSet(charactersIn: "/")),
                  !rawUrl.isEmpty else {
                return nil
            }
            let linkCode = items.first(where: { $0.name == "code" })?.value?
                .trimmingCharacters(in: .whitespacesAndNewlines)
            let code = (linkCode?.isEmpty == false ? linkCode : nil) ?? explicitCode
            guard !code.isEmpty else { return nil }
            return (normalizeServerURL(rawUrl), code)
        }

        let normalizedInput = input.contains("://") ? input : "http://\(input)"
        if let comps = URLComponents(string: normalizedInput), let host = comps.host, !host.isEmpty {
            let path = comps.path
            if let range = path.range(of: "/invite/") {
                let tail = String(path[range.upperBound...])
                    .trimmingCharacters(in: CharacterSet(charactersIn: "/"))
                let code = tail.isEmpty ? explicitCode : tail
                guard !code.isEmpty else { return nil }
                var origin = "\(comps.scheme ?? "https")://\(host)"
                if let port = comps.port { origin += ":\(port)" }
                return (origin, code)
            }
            guard !explicitCode.isEmpty else { return nil }
            return (normalizeServerURL(input), explicitCode)
        }

        return nil
    }

    static func normalizeServerURL(_ raw: String) -> String {
        var s = raw.trimmingCharacters(in: .whitespacesAndNewlines)
            .trimmingCharacters(in: CharacterSet(charactersIn: "/"))
        if !s.contains("://") {
            s = "http://" + s
        }
        if var comps = URLComponents(string: s) {
            if comps.scheme?.lowercased() == "http", comps.port == nil,
               let host = comps.host, !host.isEmpty,
               (host == "localhost" || host.hasPrefix("100.") || host.hasPrefix("192.168.") || host.hasPrefix("10.") || host.hasSuffix(".local")) {
                comps.port = 8900
            }
            if comps.path == "/" { comps.path = "" }
            if let rebuilt = comps.url?.absoluteString {
                return rebuilt.trimmingCharacters(in: CharacterSet(charactersIn: "/"))
            }
        }
        return s
    }
}
