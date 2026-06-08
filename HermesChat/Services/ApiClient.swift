import Foundation
import Network

enum HermesAPIError: LocalizedError {
    case invalidURL
    case network(Error)
    case unauthorized
    case serverError(String)
    case decoding(Error)
    case vpnRequired

    var errorDescription: String? {
        switch self {
        case .invalidURL: return "서버 주소가 올바르지 않습니다."
        case .network(let error): return "네트워크 오류: \(error.localizedDescription)"
        case .unauthorized: return "API Key가 유효하지 않습니다."
        case .serverError(let message): return "서버 오류: \(message)"
        case .decoding(let error): return "응답 처리 오류: \(error.localizedDescription)"
        case .vpnRequired: return "Tailscale VPN 연결이 필요합니다."
        }
    }
}

struct ChatRequest: Codable {
    let model: String
    let messages: [ChatRequestMessage]
    let stream: Bool = true
    let sessionId: String?

    enum CodingKeys: String, CodingKey {
        case model
        case messages
        case stream
        case sessionId = "session_id"
    }
}

struct ChatRequestMessage: Codable {
    let role: String
    let content: String
}

struct StreamChunk: Codable {
    let choices: [StreamChoice]?
    let sessionId: String?

    enum CodingKeys: String, CodingKey {
        case choices
        case sessionId = "session_id"
    }
}

struct StreamChoice: Codable {
    let delta: StreamDelta
    let finishReason: String?

    enum CodingKeys: String, CodingKey {
        case delta
        case finishReason = "finish_reason"
    }
}

struct StreamDelta: Codable {
    let role: String?
    let content: String?
    let toolCalls: [StreamToolCallChunk]?

    enum CodingKeys: String, CodingKey {
        case role
        case content
        case toolCalls = "tool_calls"
    }
}

struct StreamToolCallChunk: Codable {
    let index: Int
    let id: String?
    let type: String?
    let name: String?
    let argumentsChunk: String?

    enum CodingKeys: String, CodingKey {
        case index
        case id
        case type
        case name = "function.name"
        case argumentsChunk = "function.arguments"
    }
}

struct APIClient {
    static let shared = APIClient(
        baseURL: URL(string: "http://localhost:8642")!,
        apiKey: ""
    )

    let baseURL: URL
    let apiKey: String

    func streamChat(request: ChatRequest) -> AsyncThrowingStream<StreamUpdate, Error> {
        var urlRequest = URLRequest(url: baseURL.appendingPathComponent("/v1/chat/completions"))
        urlRequest.httpMethod = "POST"
        urlRequest.setValue("Bearer \(apiKey)", forHTTPHeaderField: "Authorization")
        urlRequest.setValue("application/json", forHTTPHeaderField: "Content-Type")
        urlRequest.setValue("text/event-stream", forHTTPHeaderField: "Accept")
        urlRequest.httpBody = try? JSONEncoder().encode(request)

        return AsyncThrowingStream { continuation in
            let task = URLSession.shared.dataTask(with: urlRequest) { data, response, error in
                if let error {
                    continuation.finish(throwing: HermesAPIError.network(error))
                    return
                }

                guard let httpResponse = response as? HTTPURLResponse else {
                    continuation.finish(throwing: HermesAPIError.serverError("No HTTP response"))
                    return
                }

                guard httpResponse.statusCode == 200 else {
                    if httpResponse.statusCode == 401 {
                        continuation.finish(throwing: HermesAPIError.unauthorized)
                    } else {
                        continuation.finish(throwing: HermesAPIError.serverError("HTTP \(httpResponse.statusCode)"))
                    }
                    return
                }

                guard let data else {
                    continuation.finish(throwing: HermesAPIError.serverError("Empty response"))
                    return
                }

                let text = String(decoding: data, as: UTF8.self)
                for line in text.split(separator: "\n") where line.hasPrefix("data:") {
                    let payload = line.dropFirst(5).trimmingCharacters(in: .whitespaces)
                    if payload == "[DONE]" {
                        continuation.finish()
                        return
                    }

                    if let data = payload.data(using: .utf8),
                       let chunk = try? JSONDecoder().decode(StreamChunk.self, from: data),
                       let choice = chunk.choices?.first {
                        if let content = choice.delta.content, !content.isEmpty {
                            continuation.yield(.content(content))
                        }

                        for tool in choice.delta.toolCalls ?? [] {
                            continuation.yield(
                                .toolCallUpdate(
                                    id: tool.id ?? UUID().uuidString,
                                    name: tool.name ?? "",
                                    argumentsDelta: tool.argumentsChunk ?? ""
                                )
                            )
                        }
                    }
                }

                continuation.finish()
            }

            task.resume()
        }
    }
}

enum StreamUpdate {
    case content(String)
    case toolCallUpdate(id: String, name: String, argumentsDelta: String)
}
