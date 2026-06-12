import Foundation

// MARK: - 공용 에러

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

// MARK: - SSE 스트리밍 모델

/// 스트리밍 중 UI에 전달되는 업데이트 단위
enum StreamUpdate {
    case content(String)
    case toolCallUpdate(id: String, name: String, argumentsDelta: String)
}

struct StreamChunk: Codable {
    let choices: [StreamChoice]?
    let sessionId: String?

    enum CodingKeys: String, CodingKey {
        case choices
        case sessionId = "session_id"
    }
}

/// 게이트웨이 `event: error`의 페이로드 — {"message": "..."} (T-122)
struct StreamErrorPayload: Codable {
    let message: String?
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

/// OpenAI 호환 델타의 tool_calls 항목. function은 중첩 객체이므로
/// 중첩 구조체로 디코딩한다 ("function.name" 점 표기 CodingKey는 동작하지 않음 — 과거 버그).
struct StreamToolCallChunk: Codable {
    let index: Int?
    let id: String?
    let type: String?
    let function: FunctionChunk?

    struct FunctionChunk: Codable {
        let name: String?
        let arguments: String?
    }

    var name: String? { function?.name }
    var argumentsChunk: String? { function?.arguments }
}
