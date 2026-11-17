import Foundation

/// Hermes-bot 서버(기본 :8900)와 통신하는 클라이언트 — 봇 목록, DM/채널 대화, 승인.
///
/// 인증: 서버가 HB_API_TOKEN 을 설정한 경우에만 Bearer 토큰이 필요하다.
/// 그래서 토큰이 비어 있으면 Authorization 헤더를 아예 붙이지 않는다.
/// 오류 본문은 `{"detail": "..."}` 형식이다.

enum BotError: LocalizedError {
    case invalidURL
    /// 401 — 토큰이 없거나 틀림
    case unauthorized
    case server(status: Int, message: String)
    case network(Error)
    case decoding(Error)

    var errorDescription: String? {
        switch self {
        case .invalidURL: return "봇 서버 주소가 올바르지 않습니다."
        case .unauthorized: return "봇 서버 토큰을 확인하세요."
        case .server(let status, let message): return "봇 서버 오류(\(status)): \(message)"
        case .network(let error): return "봇 서버에 연결할 수 없습니다: \(error.localizedDescription)"
        case .decoding(let error): return "봇 서버 응답 처리 오류: \(error.localizedDescription)"
        }
    }
}

@MainActor
final class BotClient {
    let baseURL: URL
    let token: String

    init(baseURL: URL, token: String) {
        self.baseURL = baseURL
        // 붙여넣기에 섞인 공백·줄바꿈 때문에 401 이 나지 않게
        self.token = token.trimmingCharacters(in: .whitespacesAndNewlines)
    }

    // MARK: - Health / Me / Invites (P34)

    /// 연결 확인 — 서버가 떠 있으면 성공, 아니면 throw. (/health 는 무인증)
    func health() async throws {
        _ = try await request("GET", "health", timeout: 10)
    }

    /// 현재 토큰의 사용자·권한("owner" | "guest") 조회.
    func fetchMe() async throws -> BotUserInfo {
        let data = try await request("GET", "api/me")
        return try decode(BotUserInfo.self, from: data)
    }

    /// 초대 코드 수락 (무인증 — 기존 Authorization 헤더를 붙이지 않는다).
    func acceptInvite(code: String, displayName: String) async throws -> InviteAcceptResult {
        let encodedCode = code.trimmingCharacters(in: .whitespacesAndNewlines)
            .addingPercentEncoding(withAllowedCharacters: .urlPathAllowed) ?? code
        let url = baseURL.appendingPathComponent("api/invites/\(encodedCode)/accept")
        var urlRequest = URLRequest(url: url)
        urlRequest.httpMethod = "POST"
        urlRequest.timeoutInterval = 15
        urlRequest.setValue("application/json", forHTTPHeaderField: "Content-Type")
        urlRequest.httpBody = try JSONSerialization.data(withJSONObject: [
            "display_name": displayName.trimmingCharacters(in: .whitespacesAndNewlines)
        ])

        let data: Data
        let response: URLResponse
        do {
            (data, response) = try await URLSession.shared.data(for: urlRequest)
        } catch {
            throw BotError.network(error)
        }
        guard let http = response as? HTTPURLResponse else {
            throw BotError.server(status: 0, message: "응답 없음")
        }
        guard (200..<300).contains(http.statusCode) else {
            throw BotError.server(status: http.statusCode, message: Self.detail(from: data))
        }
        return try decode(InviteAcceptResult.self, from: data)
    }

    // MARK: - Bots / Channels

    func fetchBots() async throws -> [Bot] {
        struct Response: Decodable { let data: [Bot] }
        let data = try await request("GET", "api/bots", query: ["status": "active"])
        return try decode(Response.self, from: data).data
    }

    func fetchChannels() async throws -> [BotChannel] {
        struct Response: Decodable { let data: [BotChannel] }
        let data = try await request("GET", "api/channels")
        return try decode(Response.self, from: data).data
    }

    /// 새 채널 생성 (201). 응답 본문은 쓰지 않고 목록을 다시 읽는다.
    func createChannel(title: String, topic: String? = nil, botIds: [Int] = []) async throws {
        var payload: [String: Any] = ["title": title, "bot_ids": botIds]
        if let topic, !topic.isEmpty { payload["topic"] = topic }
        let body = try JSONSerialization.data(withJSONObject: payload)
        _ = try await request("POST", "api/channels", body: body, timeout: 30)
    }

    // MARK: - Messages

    /// seq 오름차순 최근 200개(서버 기준), 평평한 타임라인.
    func fetchMessages(channelId: Int) async throws -> [BotMessage] {
        struct Response: Decodable { let data: [BotMessage] }
        let data = try await request("GET", "api/channels/\(channelId)/messages")
        return try decode(Response.self, from: data).data
    }

    func postMessage(channelId: Int, body text: String) async throws -> BotPostResult {
        let body = try JSONSerialization.data(withJSONObject: ["body": text])
        let data = try await request("POST", "api/channels/\(channelId)/messages", body: body, timeout: 30)
        return try decode(BotPostResult.self, from: data)
    }

    /// 새 대화 경계 설정 — 화면 기록은 유지하고 이후 발화부터 새 맥락/세션으로 시작한다.
    func resetChannelContext(channelId: Int) async throws {
        _ = try await request("POST", "api/channels/\(channelId)/reset-context", body: Data("{}".utf8))
    }

    // MARK: - Channel Invites & Human Members (P34)

    func updateChannelGuestBotMode(channelId: Int, mode: String) async throws -> BotChannel {
        let body = try JSONSerialization.data(withJSONObject: ["guest_bot_mode": mode])
        let data = try await request("PATCH", "api/channels/\(channelId)", body: body)
        return try decode(BotChannel.self, from: data)
    }

    func createChannelInvite(
        channelId: Int,
        label: String = "",
        maxUses: Int? = nil,
        expiresHours: Int? = nil
    ) async throws -> ChannelInvite {
        var payload: [String: Any] = [:]
        let trimmedLabel = label.trimmingCharacters(in: .whitespacesAndNewlines)
        if !trimmedLabel.isEmpty { payload["label"] = trimmedLabel }
        if let maxUses { payload["max_uses"] = maxUses }
        if let expiresHours { payload["expires_hours"] = expiresHours }
        let body = try JSONSerialization.data(withJSONObject: payload)
        let data = try await request("POST", "api/channels/\(channelId)/invites", body: body)
        return try decode(ChannelInvite.self, from: data)
    }

    func fetchChannelInvites(channelId: Int) async throws -> [ChannelInvite] {
        struct Response: Decodable { let data: [ChannelInvite] }
        let data = try await request("GET", "api/channels/\(channelId)/invites")
        return try decode(Response.self, from: data).data
    }

    func revokeChannelInvite(channelId: Int, inviteId: Int) async throws {
        _ = try await request("DELETE", "api/channels/\(channelId)/invites/\(inviteId)")
    }

    func fetchChannelUsers(channelId: Int) async throws -> [ChannelHumanMember] {
        struct Response: Decodable { let data: [ChannelHumanMember] }
        let data = try await request("GET", "api/channels/\(channelId)/users")
        return try decode(Response.self, from: data).data
    }

    func removeChannelUser(channelId: Int, userId: Int) async throws {
        _ = try await request("DELETE", "api/channels/\(channelId)/users/\(userId)")
    }

    func fetchChannelAttachment(channelId: Int, path: String) async throws -> Data {
        try await request("GET", "api/channels/\(channelId)/attachments/file", query: ["path": path], timeout: 60)
    }

    /// 채널 실시간 이벤트 스트림 (타인 발화·봇 응답·강퇴 알림)
    func channelStream(channelId: Int) -> AsyncThrowingStream<ChannelStreamEvent, Error> {
        var urlRequest = URLRequest(url: baseURL.appendingPathComponent("api/channels/\(channelId)/stream"))
        urlRequest.httpMethod = "GET"
        urlRequest.timeoutInterval = 600
        urlRequest.setValue("text/event-stream", forHTTPHeaderField: "Accept")
        if !token.isEmpty {
            urlRequest.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
        }
        let request = urlRequest

        return AsyncThrowingStream { continuation in
            let task = Task {
                do {
                    let (bytes, response) = try await URLSession.shared.bytes(for: request)
                    guard let http = response as? HTTPURLResponse else {
                        continuation.finish(throwing: BotError.server(status: 0, message: "응답 없음"))
                        return
                    }
                    guard http.statusCode == 200 else {
                        if http.statusCode == 401 {
                            continuation.finish(throwing: BotError.unauthorized)
                        } else {
                            continuation.finish(throwing: BotError.server(
                                status: http.statusCode, message: "채널 스트림을 열 수 없습니다"))
                        }
                        return
                    }

                    var currentEvent = ""
                    let decoder = JSONDecoder()
                    for try await line in bytes.lines {
                        if line.hasPrefix("event:") {
                            currentEvent = line.dropFirst(6).trimmingCharacters(in: .whitespaces)
                            continue
                        }
                        guard line.hasPrefix("data:") else { continue }
                        let payload = line.dropFirst(5).trimmingCharacters(in: .whitespaces)
                        let eventName = currentEvent
                        currentEvent = ""
                        let data = Data(payload.utf8)

                        switch eventName {
                        case "ready":
                            continuation.yield(.ready)
                        case "message":
                            if let msg = try? decoder.decode(BotMessage.self, from: data) {
                                continuation.yield(.message(msg))
                            }
                        case "revoked":
                            continuation.yield(.revoked)
                        case "user_removed":
                            if let obj = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any],
                               let uid = obj["user_id"] as? Int {
                                continuation.yield(.userRemoved(userId: uid))
                            }
                        default:
                            continue
                        }
                    }
                    continuation.finish()
                } catch is CancellationError {
                    continuation.finish()
                } catch {
                    continuation.finish(throwing: error)
                }
            }
            continuation.onTermination = { _ in task.cancel() }
        }
    }

    // MARK: - Job stream (SSE)

    /// 잡 출력 스트림. 서버는 구독할 때마다 **처음부터** 재생하므로 호출 측은 구독 시작 시 버퍼를 비워야 한다.
    /// delta{"text"} → .delta, steps → 무시, end{"status","error"} → .end 후 종료, error{"message"} → throw.
    func jobStream(jobId: Int) -> AsyncThrowingStream<BotJobEvent, Error> {
        var urlRequest = URLRequest(url: baseURL.appendingPathComponent("jobs/\(jobId)/stream"))
        urlRequest.httpMethod = "GET"
        // 긴 도구 실행 동안에도 서버가 steps 를 주기적으로 보내지만, 넉넉히 둔다
        urlRequest.timeoutInterval = 600
        urlRequest.setValue("text/event-stream", forHTTPHeaderField: "Accept")
        if !token.isEmpty {
            urlRequest.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
        }
        let request = urlRequest

        return AsyncThrowingStream { continuation in
            let task = Task {
                do {
                    let (bytes, response) = try await URLSession.shared.bytes(for: request)
                    guard let http = response as? HTTPURLResponse else {
                        continuation.finish(throwing: BotError.server(status: 0, message: "응답 없음"))
                        return
                    }
                    guard http.statusCode == 200 else {
                        if http.statusCode == 401 {
                            continuation.finish(throwing: BotError.unauthorized)
                        } else {
                            continuation.finish(throwing: BotError.server(
                                status: http.statusCode, message: "잡 스트림을 열 수 없습니다"))
                        }
                        return
                    }

                    var currentEvent = ""
                    for try await line in bytes.lines {
                        if line.hasPrefix("event:") {
                            currentEvent = line.dropFirst(6).trimmingCharacters(in: .whitespaces)
                            continue
                        }
                        guard line.hasPrefix("data:") else { continue }
                        let payload = line.dropFirst(5).trimmingCharacters(in: .whitespaces)
                        let eventName = currentEvent
                        currentEvent = ""  // SSE 규격: event 는 바로 다음 data 한 줄에만 적용
                        guard let object = (try? JSONSerialization.jsonObject(
                            with: Data(payload.utf8))) as? [String: Any] else { continue }

                        switch eventName {
                        case "delta":
                            if let text = object["text"] as? String, !text.isEmpty {
                                continuation.yield(.delta(text))
                            }
                        case "end":
                            let status = (object["status"] as? String) ?? "done"
                            let error = object["error"] as? String
                            continuation.yield(.end(status: status, error: error))
                            continuation.finish()
                            return
                        case "error":
                            let message = (object["message"] as? String) ?? payload
                            continuation.finish(throwing: BotError.server(status: 200, message: message))
                            return
                        default:
                            continue  // steps 등은 무시
                        }
                    }
                    // end 없이 연결이 닫힌 경우 — 호출 측이 목록을 다시 읽어 확정본을 얻는다
                    continuation.finish()
                } catch is CancellationError {
                    continuation.finish()
                } catch {
                    continuation.finish(throwing: error)
                }
            }
            continuation.onTermination = { _ in task.cancel() }
        }
    }

    // MARK: - Approvals

    func fetchApprovals(status: String = "pending") async throws -> [BotApproval] {
        struct Response: Decodable { let data: [BotApproval] }
        let data = try await request("GET", "api/approvals", query: ["status": status])
        return try decode(Response.self, from: data).data
    }

    /// 승인은 서버가 동작을 실행하므로 오래 걸릴 수 있다 — 타임아웃 200초.
    func approve(id: Int, note: String? = nil) async throws {
        _ = try await request("POST", "api/approvals/\(id)/approve",
                              body: Self.noteBody(note), timeout: 200)
    }

    func reject(id: Int, note: String? = nil) async throws {
        _ = try await request("POST", "api/approvals/\(id)/reject",
                              body: Self.noteBody(note), timeout: 30)
    }

    // MARK: - Private

    private static func noteBody(_ note: String?) -> Data? {
        var payload: [String: Any] = [:]
        if let note, !note.isEmpty { payload["note"] = note }
        return try? JSONSerialization.data(withJSONObject: payload)
    }

    private func request(
        _ method: String,
        _ path: String,
        query: [String: String]? = nil,
        body: Data? = nil,
        timeout: TimeInterval = 15
    ) async throws -> Data {
        var url = baseURL.appendingPathComponent(path)
        if let query,
           var comps = URLComponents(url: url, resolvingAgainstBaseURL: false) {
            comps.queryItems = query.map { URLQueryItem(name: $0.key, value: $0.value) }
            url = comps.url ?? url
        }
        var urlRequest = URLRequest(url: url)
        urlRequest.httpMethod = method
        urlRequest.timeoutInterval = timeout
        // 토큰이 설정된 서버만 인증을 요구한다 — 비어 있으면 헤더를 붙이지 않는다
        if !token.isEmpty {
            urlRequest.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
        }
        if body != nil {
            urlRequest.setValue("application/json", forHTTPHeaderField: "Content-Type")
        }
        urlRequest.httpBody = body

        let data: Data
        let response: URLResponse
        do {
            (data, response) = try await URLSession.shared.data(for: urlRequest)
        } catch {
            throw BotError.network(error)
        }
        guard let http = response as? HTTPURLResponse else {
            throw BotError.server(status: 0, message: "응답 없음")
        }
        switch http.statusCode {
        case 200..<300:
            return data
        case 401:
            throw BotError.unauthorized
        default:
            throw BotError.server(status: http.statusCode, message: Self.detail(from: data))
        }
    }

    /// `{"detail": "..."}` 에서 사람이 읽을 문구를 꺼낸다. detail 이 문자열이 아니면(검증 오류 배열 등) 원문.
    private static func detail(from data: Data) -> String {
        if let object = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any],
           let detail = object["detail"] {
            if let text = detail as? String { return text }
            if let nested = try? JSONSerialization.data(withJSONObject: detail),
               let text = String(data: nested, encoding: .utf8) { return text }
        }
        return String(data: data, encoding: .utf8) ?? "알 수 없는 오류"
    }

    private func decode<T: Decodable>(_ type: T.Type, from data: Data) throws -> T {
        do {
            return try JSONDecoder().decode(type, from: data)
        } catch {
            throw BotError.decoding(error)
        }
    }
}
