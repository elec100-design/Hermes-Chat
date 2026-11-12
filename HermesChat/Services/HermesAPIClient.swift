import Foundation

// MARK: - Server Response Types (private)

private struct SessionListResponse: Decodable {
    let data: [ServerSession]
    let hasMore: Bool?

    enum CodingKeys: String, CodingKey {
        case data
        case hasMore = "has_more"
    }
}

/// 페이지네이션 결과 (T-072). 서버가 has_more를 안 주면 false로 간주.
struct SessionPage {
    let sessions: [Session]
    let hasMore: Bool
}

private struct ServerSession: Decodable {
    let id: String
    let title: String?
    let preview: String?
    let startedAt: Double?
    let lastActive: Double?
    let model: String?
    let source: String?

    enum CodingKeys: String, CodingKey {
        case id, title, preview, model, source
        case startedAt = "started_at"
        case lastActive = "last_active"
    }

    var asSession: Session {
        let ts = lastActive ?? startedAt ?? Date.now.timeIntervalSince1970
        return Session(id: id, title: title, preview: preview, updatedAt: Date(timeIntervalSince1970: ts), source: source)
    }
}

private struct MessageListResponse: Decodable {
    let data: [FailableMessage]
}

/// 메시지 하나가 깨져도 나머지를 살리기 위한 lossy 래퍼
private struct FailableMessage: Decodable {
    let value: ServerMessage?
    init(from decoder: Decoder) throws {
        value = try? ServerMessage(from: decoder)
    }
}

private struct ServerMessage: Decodable {
    let id: String
    let role: String
    let content: String
    let toolCallId: String?
    let toolCalls: [ServerToolCall]?
    let toolName: String?
    let timestamp: Double

    enum CodingKeys: String, CodingKey {
        case id, role, content
        case toolCallId = "tool_call_id"
        case toolCalls = "tool_calls"
        case toolName = "tool_name"
        case timestamp
    }

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        // id: Int 또는 String 모두 수용
        if let intId = try? c.decode(Int.self, forKey: .id) {
            id = String(intId)
        } else {
            id = (try? c.decode(String.self, forKey: .id)) ?? ""
        }
        role = (try? c.decode(String.self, forKey: .role)) ?? "assistant"
        content = (try? c.decode(String.self, forKey: .content)) ?? ""
        toolCallId = try? c.decodeIfPresent(String.self, forKey: .toolCallId)
        toolCalls = try? c.decodeIfPresent([ServerToolCall].self, forKey: .toolCalls)
        toolName = try? c.decodeIfPresent(String.self, forKey: .toolName)
        timestamp = (try? c.decode(Double.self, forKey: .timestamp)) ?? Date.now.timeIntervalSince1970
    }

    func asChatMessage() -> ChatMessage {
        let chatRole = ChatMessage.Role(rawValue: role) ?? .assistant
        let calls: [ToolCall]? = toolCalls.flatMap { list in
            let mapped = list.compactMap { tc -> ToolCall? in
                guard let fn = tc.function else { return nil }
                let args: [String: String]?
                if let data = fn.arguments.data(using: .utf8),
                   let parsed = try? JSONSerialization.jsonObject(with: data) as? [String: Any] {
                    args = parsed.mapValues { "\($0)" }
                } else {
                    args = fn.arguments.isEmpty ? nil : ["input": fn.arguments]
                }
                return ToolCall(id: tc.id, name: fn.name, arguments: args, result: nil)
            }
            return mapped.isEmpty ? nil : mapped
        }
        return ChatMessage(
            id: UUID(),
            role: chatRole,
            content: content,
            toolCalls: calls,
            createdAt: Date(timeIntervalSince1970: timestamp)
        )
    }
}

private struct ServerToolCall: Decodable {
    let id: String
    let function: ServerFunction?

    struct ServerFunction: Decodable {
        let name: String
        let arguments: String
    }
}

// MARK: - HermesAPIClient

@MainActor
final class HermesAPIClient {
    let baseURL: URL
    let apiKey: String

    init(baseURL: URL, apiKey: String) {
        self.baseURL = baseURL
        self.apiKey = apiKey
    }

    // MARK: Sessions

    func fetchSessions(limit: Int = 50, offset: Int = 0) async throws -> SessionPage {
        let data = try await get(
            "/api/sessions",
            query: ["limit": String(limit), "offset": String(offset)]
        )
        let response = try JSONDecoder().decode(SessionListResponse.self, from: data)
        return SessionPage(
            sessions: response.data
                .map { $0.asSession }
                .sorted { $0.updatedAt > $1.updatedAt },
            hasMore: response.hasMore ?? false
        )
    }

    func createSession(model: String? = nil, systemPrompt: String? = nil) async throws -> Session {
        var body: [String: Any] = [:]
        if let model { body["model"] = model }
        if let sp = systemPrompt, !sp.isEmpty { body["system_prompt"] = sp }
        let data = try await post("/api/sessions", body: body)
        return try parseSessionResponse(data)
    }

    /// 세션 분기 (`POST /api/sessions/{id}/fork`) — 기존 히스토리를 가진 새 세션을 돌려준다.
    func forkSession(id: String) async throws -> Session {
        let data = try await post("/api/sessions/\(Self.encodeSegment(id))/fork")
        return try parseSessionResponse(data)
    }

    /// 생성/분기 응답 형식이 서버 버전에 따라 달라서 단계적으로 해석한다:
    /// ① {"object":"hermes.session","session":{...}} (실서버 확인 형식)
    /// ② ServerSession 그대로 ③ {"data": {...}} 래핑 ④ id 계열 키 재귀 탐색
    private func parseSessionResponse(_ data: Data) throws -> Session {
        struct HermesWrapped: Decodable { let session: ServerSession }
        if let wrapped = try? JSONDecoder().decode(HermesWrapped.self, from: data) {
            return wrapped.session.asSession
        }
        if let server = try? JSONDecoder().decode(ServerSession.self, from: data) {
            return server.asSession
        }
        struct Wrapped: Decodable { let data: ServerSession }
        if let wrapped = try? JSONDecoder().decode(Wrapped.self, from: data) {
            return wrapped.data.asSession
        }
        if let object = try? JSONSerialization.jsonObject(with: data) {
            // 최상위가 그냥 문자열이면 그것이 곧 세션 id
            if let id = object as? String, !id.isEmpty {
                return Session(id: id, title: nil, preview: nil, updatedAt: .now, source: nil)
            }
            // 중첩 어디에 있든 id 계열 키를 가진 객체를 찾는다
            if let (id, dict) = Self.extractSessionID(from: object) {
                return Session(
                    id: id,
                    title: dict["title"] as? String,
                    preview: nil,
                    updatedAt: .now,
                    source: dict["source"] as? String
                )
            }
        }
        let raw = String(data: data, encoding: .utf8) ?? ""
        throw HermesAPIError.serverError("세션 응답을 해석할 수 없습니다: \(raw.prefix(300))")
    }

    /// 응답 JSON을 깊이 3까지 훑어 id/session_id 키를 가진 첫 객체를 돌려준다.
    nonisolated private static func extractSessionID(
        from object: Any, depth: Int = 0
    ) -> (id: String, dict: [String: Any])? {
        guard depth <= 3 else { return nil }
        if let dict = object as? [String: Any] {
            let rawId = dict["id"] ?? dict["session_id"] ?? dict["sessionId"]
            if let id = (rawId as? String) ?? (rawId as? Int).map(String.init), !id.isEmpty {
                return (id, dict)
            }
            for value in dict.values {
                if let found = extractSessionID(from: value, depth: depth + 1) {
                    return found
                }
            }
        } else if let array = object as? [Any] {
            for value in array {
                if let found = extractSessionID(from: value, depth: depth + 1) {
                    return found
                }
            }
        }
        return nil
    }

    func deleteSession(id: String) async throws {
        try await delete("/api/sessions/\(Self.encodeSegment(id))")
    }

    func updateSessionTitle(id: String, title: String) async throws {
        _ = try await patch("/api/sessions/\(Self.encodeSegment(id))", body: ["title": title])
    }

    // MARK: Models

    /// 이 게이트웨이가 알려주는 모델 식별자 목록 (`GET /v1/models`)
    func fetchModelIDs() async throws -> [String] {
        struct ModelsResponse: Decodable {
            struct Model: Decodable { let id: String }
            let data: [Model]
        }
        let data = try await get("/v1/models")
        return try JSONDecoder().decode(ModelsResponse.self, from: data).data.map(\.id)
    }

    // MARK: Skills & Toolsets (읽기전용 — 응답 스키마가 버전에 따라 다를 수 있어 방어적 파싱)

    /// `GET /v1/skills` — [{name, description, ...}] 형태로 정규화
    func fetchSkills() async throws -> [GatewayCapability] {
        let data = try await get("/v1/skills")
        return Self.parseCapabilities(data, arrayKeys: ["data", "skills"])
    }

    /// `GET /v1/toolsets` — 활성화 상태 포함
    func fetchToolsets() async throws -> [GatewayCapability] {
        let data = try await get("/v1/toolsets")
        return Self.parseCapabilities(data, arrayKeys: ["data", "toolsets"])
    }

    /// {data:[...]}, {skills:[...]}, 최상위 배열, 문자열 배열 등 다양한 형태를 수용한다.
    nonisolated static func parseCapabilities(_ data: Data, arrayKeys: [String]) -> [GatewayCapability] {
        guard let json = try? JSONSerialization.jsonObject(with: data) else { return [] }
        var items: [Any] = []
        if let array = json as? [Any] {
            items = array
        } else if let dict = json as? [String: Any] {
            for key in arrayKeys {
                if let array = dict[key] as? [Any] {
                    items = array
                    break
                }
            }
            // {"이름": {설명...}} 같은 딕셔너리 맵 형태
            if items.isEmpty, !dict.isEmpty, dict.values.allSatisfy({ $0 is [String: Any] }) {
                items = dict.map { ["name": $0.key].merging($0.value as? [String: Any] ?? [:]) { a, _ in a } }
            }
        }
        return items.compactMap { item in
            if let name = item as? String {
                return GatewayCapability(name: name, detail: nil, enabled: nil)
            }
            guard let dict = item as? [String: Any] else { return nil }
            let name = (dict["name"] ?? dict["id"] ?? dict["title"]) as? String
            guard let name, !name.isEmpty else { return nil }
            let detail = (dict["description"] ?? dict["summary"] ?? dict["detail"]) as? String
            let enabled = (dict["enabled"] ?? dict["active"] ?? dict["is_enabled"]) as? Bool
            return GatewayCapability(name: name, detail: detail, enabled: enabled)
        }
        .sorted { $0.name.localizedCaseInsensitiveCompare($1.name) == .orderedAscending }
    }

    // MARK: Messages

    func fetchMessages(sessionId: String) async throws -> [ChatMessage] {
        let encoded = Self.encodeSegment(sessionId)
        let data = try await get("/api/sessions/\(encoded)/messages")
        let response = try JSONDecoder().decode(MessageListResponse.self, from: data)
        return response.data.compactMap { $0.value?.asChatMessage() }
    }

    // MARK: Streaming Chat

    /// 실시간 SSE 스트리밍: `URLSession.bytes`로 라인 단위 수신 — 토큰이 도착하는 대로 UI에 반영된다.
    ///
    /// 현재 Hermes 게이트웨이는 네이티브 이벤트를 흘린다 —
    /// `assistant.delta`(`{delta}`), `tool.started/completed`, `assistant.completed`(`{content}`),
    /// `run.completed`, `done`. 예전 OpenAI 호환 배포는 무명 `data:` 라인에 `choices` 청크를 흘려
    /// 왔기 때문에 두 형식을 모두 파싱한다. 파싱 자체는 `SSEParser`로 분리해 테스트 가능하다 (T-149).
    ///
    /// **에러 분류**(T-149):
    /// - `HermesAPIError`(401/serverError/파서 에러)는 원본 그대로 상위로 표면화 — 회수 폴링 대상이 아님
    /// - `URLError.cancelled`(사용자 중지, 상위 Task 취소)는 조용히 finish
    /// - 그 외 URLSession 에러는 `HermesAPIError.network`로 감싸 상위(`ChatViewModel`)가
    ///   좁은 화이트리스트(`networkConnectionLost`/`timedOut`)에 한해 폴링 회수를 시도한다
    /// - 바이트 EOF가 `run.completed` 없이 오면 조기 절단으로 판정해 `networkConnectionLost`로 승격
    func streamChat(sessionId: String, message: String) -> AsyncThrowingStream<StreamUpdate, Error> {
        let encoded = Self.encodeSegment(sessionId)
        let streamURL = URL(string: baseURL.absoluteString.trimmingCharacters(in: .init(charactersIn: "/")) + "/api/sessions/\(encoded)/chat/stream") ?? baseURL
        var urlRequest = URLRequest(url: streamURL)
        urlRequest.httpMethod = "POST"
        urlRequest.timeoutInterval = 300  // Hermes의 긴 도구 실행 동안 유휴 타임아웃 방지
        urlRequest.setValue("Bearer \(apiKey)", forHTTPHeaderField: "Authorization")
        urlRequest.setValue("application/json", forHTTPHeaderField: "Content-Type")
        urlRequest.setValue("text/event-stream", forHTTPHeaderField: "Accept")
        urlRequest.httpBody = try? JSONSerialization.data(withJSONObject: ["message": message])

        return AsyncThrowingStream { continuation in
            let task = Task {
                do {
                    let (bytes, response) = try await URLSession.shared.bytes(for: urlRequest)
                    guard let http = response as? HTTPURLResponse else {
                        throw HermesAPIError.serverError("응답 없음")
                    }
                    guard http.statusCode == 200 else {
                        if http.statusCode == 401 {
                            throw HermesAPIError.unauthorized
                        } else {
                            throw HermesAPIError.serverError("HTTP \(http.statusCode)")
                        }
                    }

                    var parser = SSEParser()
                    for try await line in bytes.lines {
                        switch parser.feed(line) {
                        case .none:
                            continue
                        case .emit(let updates):
                            for update in updates {
                                continuation.yield(update)
                            }
                        case .finish:
                            continuation.finish()
                            return
                        case .fail(let err):
                            // catch 블록에서 HermesAPIError 채널로 원형 보존한다
                            throw err
                        }
                    }
                    // 바이트 EOF. `run.completed`를 봤으면 프로토콜상 정상 종료; 못 봤으면
                    // 조기 절단이다 — `networkConnectionLost`로 승격해 상위가 회수 폴링을
                    // 시도하게 한다 (부분 본문/도구 이벤트가 있었더라도 마찬가지).
                    if parser.sawRunCompleted {
                        continuation.finish()
                    } else {
                        throw URLError(.networkConnectionLost)
                    }
                } catch is CancellationError {
                    // 상위 Task 취소는 에러로 승격하지 않는다 — 사용자 중지가 "네트워크 오류"로
                    // 보이지 않도록 소비자(send)가 정상 종료로 인식하게 한다 (T-149).
                    continuation.finish()
                } catch let apiErr as HermesAPIError {
                    // 401/서버 오류/`event: error`/파서 에러 — 원본 타입 보존.
                    // 이 경로는 상위 `ChatViewModel`이 회수 대상에서 제외해 즉시 표면화한다.
                    continuation.finish(throwing: apiErr)
                } catch let urlError as URLError where urlError.code == .cancelled {
                    continuation.finish()
                } catch {
                    continuation.finish(throwing: HermesAPIError.network(error))
                }
            }
            continuation.onTermination = { _ in task.cancel() }
        }
    }

    // MARK: - Private HTTP Helpers

    /// URL 경로 세그먼트로 안전하게 인코딩 — telegram:..., slack:... 등 특수문자 대응
    nonisolated static func encodeSegment(_ raw: String) -> String {
        var allowed = CharacterSet.urlPathAllowed
        allowed.remove(charactersIn: "/:?#[]@!$&'()*+,;=")
        return raw.addingPercentEncoding(withAllowedCharacters: allowed) ?? raw
    }

    private func get(_ path: String, query: [String: String]? = nil) async throws -> Data {
        var url = baseURL.appendingPathComponent(path)
        if let query,
           var comps = URLComponents(url: url, resolvingAgainstBaseURL: false) {
            comps.queryItems = query.map { URLQueryItem(name: $0.key, value: $0.value) }
            url = comps.url ?? url
        }
        var request = URLRequest(url: url)
        request.setValue("Bearer \(apiKey)", forHTTPHeaderField: "Authorization")
        let (data, response) = try await URLSession.shared.data(for: request)
        try validate(response, data: data)
        return data
    }

    private func post(_ path: String, body: [String: Any] = [:]) async throws -> Data {
        var request = URLRequest(url: baseURL.appendingPathComponent(path))
        request.httpMethod = "POST"
        request.setValue("Bearer \(apiKey)", forHTTPHeaderField: "Authorization")
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.httpBody = try JSONSerialization.data(withJSONObject: body)
        let (data, response) = try await URLSession.shared.data(for: request)
        try validate(response, data: data)
        return data
    }

    private func patch(_ path: String, body: [String: Any]) async throws -> Data {
        var request = URLRequest(url: baseURL.appendingPathComponent(path))
        request.httpMethod = "PATCH"
        request.setValue("Bearer \(apiKey)", forHTTPHeaderField: "Authorization")
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.httpBody = try JSONSerialization.data(withJSONObject: body)
        let (data, response) = try await URLSession.shared.data(for: request)
        try validate(response, data: data)
        return data
    }

    private func delete(_ path: String) async throws {
        var request = URLRequest(url: baseURL.appendingPathComponent(path))
        request.httpMethod = "DELETE"
        request.setValue("Bearer \(apiKey)", forHTTPHeaderField: "Authorization")
        let (data, response) = try await URLSession.shared.data(for: request)
        try validate(response, data: data)
    }

    private func validate(_ response: URLResponse, data: Data) throws {
        guard let http = response as? HTTPURLResponse else {
            throw HermesAPIError.serverError("HTTP 응답 없음")
        }
        switch http.statusCode {
        case 200..<300: return
        case 401: throw HermesAPIError.unauthorized
        default:
            let msg = String(data: data, encoding: .utf8) ?? "알 수 없는 오류"
            throw HermesAPIError.serverError("HTTP \(http.statusCode): \(msg)")
        }
    }
}
