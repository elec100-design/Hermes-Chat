import Foundation

// MARK: - Server Response Types (private)

private struct SessionListResponse: Decodable {
    let data: [ServerSession]
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
    let data: [ServerMessage]
}

private struct ServerMessage: Decodable {
    let id: Int
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

    func fetchSessions() async throws -> [Session] {
        let data = try await get("/api/sessions")
        let response = try JSONDecoder().decode(SessionListResponse.self, from: data)
        return response.data
            .map { $0.asSession }
            .sorted { $0.updatedAt > $1.updatedAt }
    }

    func createSession(model: String? = nil, systemPrompt: String? = nil) async throws -> Session {
        var body: [String: Any] = [:]
        if let model { body["model"] = model }
        if let sp = systemPrompt, !sp.isEmpty { body["system_prompt"] = sp }
        let data = try await post("/api/sessions", body: body)
        let serverSession = try JSONDecoder().decode(ServerSession.self, from: data)
        return serverSession.asSession
    }

    func deleteSession(id: String) async throws {
        try await delete("/api/sessions/\(id)")
    }

    func updateSessionTitle(id: String, title: String) async throws {
        _ = try await patch("/api/sessions/\(id)", body: ["title": title])
    }

    // MARK: Messages

    func fetchMessages(sessionId: String) async throws -> [ChatMessage] {
        let data = try await get("/api/sessions/\(sessionId)/messages")
        let response = try JSONDecoder().decode(MessageListResponse.self, from: data)
        return response.data.map { $0.asChatMessage() }
    }

    // MARK: Streaming Chat

    func streamChat(sessionId: String, message: String) -> AsyncThrowingStream<StreamUpdate, Error> {
        var urlRequest = URLRequest(url: baseURL.appendingPathComponent("/api/sessions/\(sessionId)/chat/stream"))
        urlRequest.httpMethod = "POST"
        urlRequest.setValue("Bearer \(apiKey)", forHTTPHeaderField: "Authorization")
        urlRequest.setValue("application/json", forHTTPHeaderField: "Content-Type")
        urlRequest.setValue("text/event-stream", forHTTPHeaderField: "Accept")
        urlRequest.httpBody = try? JSONSerialization.data(withJSONObject: ["message": message])

        return AsyncThrowingStream { continuation in
            let task = URLSession.shared.dataTask(with: urlRequest) { data, response, error in
                if let error {
                    continuation.finish(throwing: HermesAPIError.network(error))
                    return
                }
                guard let http = response as? HTTPURLResponse else {
                    continuation.finish(throwing: HermesAPIError.serverError("응답 없음"))
                    return
                }
                guard http.statusCode == 200 else {
                    if http.statusCode == 401 {
                        continuation.finish(throwing: HermesAPIError.unauthorized)
                    } else {
                        continuation.finish(throwing: HermesAPIError.serverError("HTTP \(http.statusCode)"))
                    }
                    return
                }
                guard let data else {
                    continuation.finish(throwing: HermesAPIError.serverError("빈 응답"))
                    return
                }

                let text = String(decoding: data, as: UTF8.self)
                for line in text.split(separator: "\n") where line.hasPrefix("data:") {
                    let payload = line.dropFirst(5).trimmingCharacters(in: .whitespaces)
                    if payload == "[DONE]" { continuation.finish(); return }
                    if let chunkData = payload.data(using: .utf8),
                       let chunk = try? JSONDecoder().decode(StreamChunk.self, from: chunkData),
                       let choice = chunk.choices?.first {
                        if let content = choice.delta.content, !content.isEmpty {
                            continuation.yield(.content(content))
                        }
                        for tool in choice.delta.toolCalls ?? [] {
                            continuation.yield(.toolCallUpdate(
                                id: tool.id ?? UUID().uuidString,
                                name: tool.name ?? "",
                                argumentsDelta: tool.argumentsChunk ?? ""
                            ))
                        }
                    }
                }
                continuation.finish()
            }
            task.resume()
        }
    }

    // MARK: - Private HTTP Helpers

    private func get(_ path: String) async throws -> Data {
        var request = URLRequest(url: baseURL.appendingPathComponent(path))
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
