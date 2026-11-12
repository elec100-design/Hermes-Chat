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

// MARK: - SSE Parser (T-149)

/// SSE 라인 하나를 처리한 결과. HermesAPIError가 Equatable이 아니라 자동 Equatable은 안 되므로
/// 테스트는 패턴 매칭으로 케이스를 확인한다.
enum SSEParseAction {
    /// 아직 완성된 이벤트가 아니라 아무것도 방출하지 않는다 (event: 라인, 주석, run.completed, ...)
    case none
    /// 스트림 소비자에게 방출할 업데이트들
    case emit([StreamUpdate])
    /// 스트림 정상 종료 (`data: [DONE]`, `event: done`)
    case finish
    /// 스트림 종료 + 에러 (`event: error` 페이로드나 프로토콜 위반)
    case fail(HermesAPIError)
}

/// 상태를 가진 SSE 파서 — 네이티브 Hermes 이벤트와 레거시 OpenAI choices 청크를 함께 소화한다.
///
/// **네이티브 이벤트** (현행 Hermes 게이트웨이):
/// - `event: assistant.delta`  data: `{"delta": "..."}`  ← 스트리밍 본문
/// - `event: assistant.completed`  data: `{"content": "..."}` ← 전체 본문. 델타를 이미
///    받았으면 중복 방출을 막기 위해 무시한다. 델타가 하나도 없었으면 이걸로 대체한다.
/// - `event: tool.started` / `event: tool.completed` / `event: tool.failed`
///   data(deployed): `{"message_id":..,"run_id":..,"tool_name":..,"tool_args":..,"sequence":..}`
///   data(source):   `{"message_id":..,"run_id":..,"tool_name":..,"args":..,"seq":..}`
///   두 스키마 모두 수용. `message_id`는 **턴 전체**의 식별자라 도구별 식별자가 아니므로
///   started 발생 시마다 별도 합성 ID를 만들고 같은 이름의 completed/failed에 FIFO로 매칭한다.
/// - `event: run.completed` — 실행이 성공적으로 끝났다는 프로토콜 마커. 서버가 아직 `done`을
///    보낼 수 있으므로 여기서 파이프를 닫지 않는다. `sawRunCompleted`만 세워두고 EOF 후
///    소비자(HermesAPIClient)가 "정상 종료 vs 조기 절단"을 판정한다.
/// - `event: done`, `data: [DONE]` — 실제 종료 신호
/// - `event: error` data: `{"message": "..."}` — 서버가 명시적으로 실패를 통지
///
/// **레거시 이벤트** (구 OpenAI 호환 배포):
/// - 이벤트명 없이 `data:` 라인에 `{"choices":[{"delta":{...}}]}` 청크가 온다 → StreamChunk로 디코드
///
/// 알 수 없는 이름 붙은 이벤트는 조용히 무시한다 — 완료 신호까지 파이프를 살려둔다.
struct SSEParser {
    private var currentEvent: String = ""
    private(set) var sawAssistantDelta: Bool = false
    /// `run.completed`을 본 적이 있으면 true — 이 뒤의 바이트 EOF는 프로토콜상 정상 종료다.
    /// 반대로 false 상태에서 EOF가 오면 조기 절단으로 판정해 회수 폴링을 유발한다.
    private(set) var sawRunCompleted: Bool = false

    /// 같은 이름의 tool.started 발생마다 새 합성 ID를 만들어 큐에 쌓고,
    /// completed/failed는 큐에서 FIFO로 뽑아 짝을 맞춘다. 프로토콜이 도구별 call_id를
    /// 안 실어보내기 때문에 이 방식이 지금 시점에서 안전한 최선(수용 기준).
    private var pendingToolIDs: [String: [String]] = [:]

    /// SSE 라인 하나를 처리한다. 빈 라인·주석·`event:` 라인은 `.none`을 돌려주고 상태만 갱신한다.
    /// `data:` 라인에서 사용된 event 이름은 소비 후 초기화된다 (SSE 규격: 다음 data에만 적용).
    mutating func feed(_ line: String) -> SSEParseAction {
        if line.isEmpty { return .none }
        if line.hasPrefix(":") { return .none }  // SSE 주석
        if line.hasPrefix("event:") {
            currentEvent = String(line.dropFirst(6)).trimmingCharacters(in: .whitespaces)
            return .none
        }
        guard line.hasPrefix("data:") else { return .none }
        let payload = String(line.dropFirst(5)).trimmingCharacters(in: .whitespaces)
        let eventName = currentEvent
        currentEvent = ""

        if payload == "[DONE]" { return .finish }

        switch eventName {
        case "error":
            let message = Self.decodeErrorMessage(payload) ?? payload
            return .fail(.serverError(message))
        case "assistant.delta":
            if let delta = Self.decodeStringField(payload, keys: ["delta", "content", "text"]),
               !delta.isEmpty {
                sawAssistantDelta = true
                return .emit([.content(delta)])
            }
            return .none
        case "assistant.completed":
            // 델타가 하나도 없었을 때만 완성본으로 fallback — 중복 방지 (수용 기준).
            guard !sawAssistantDelta,
                  let full = Self.decodeStringField(payload, keys: ["content", "text", "message"]),
                  !full.isEmpty else { return .none }
            sawAssistantDelta = true  // 이후 잘못된 delta 재전송 방어
            return .emit([.content(full)])
        case "tool.started":
            guard let tc = Self.decodeToolEvent(payload) else { return .none }
            let name = tc.name ?? ""
            let id = enqueueToolID(forName: name)
            return .emit([.toolCallUpdate(id: id, name: name, argumentsDelta: tc.arguments ?? "")])
        case "tool.completed", "tool.failed":
            guard let tc = Self.decodeToolEvent(payload) else { return .none }
            let name = tc.name ?? ""
            let id = dequeueToolID(forName: name)
            return .emit([.toolCallUpdate(id: id, name: name, argumentsDelta: tc.arguments ?? "")])
        case "run.completed":
            // 성공적 종료 마커. 이 뒤에 `done`이 더 올 수 있으므로 파이프는 살려둔다 (T-149).
            sawRunCompleted = true
            return .none
        case "done":
            return .finish
        case "":
            // 이벤트명 없는 data: — 레거시 OpenAI 청크로 시도
            return Self.parseLegacyChunk(payload, sawDelta: &sawAssistantDelta)
        default:
            // 미지의 named event — 완료 신호를 놓치지 않게 스트림을 살려둔다
            return .none
        }
    }

    private mutating func enqueueToolID(forName name: String) -> String {
        let id = "sse-tool-\(UUID().uuidString)"
        pendingToolIDs[name, default: []].append(id)
        return id
    }

    private mutating func dequeueToolID(forName name: String) -> String {
        if var queue = pendingToolIDs[name], !queue.isEmpty {
            let id = queue.removeFirst()
            pendingToolIDs[name] = queue.isEmpty ? nil : queue
            return id
        }
        // started 없이 completed만 도착한 예외 상황 — 병합 못 하지만 스트림은 살린다.
        return "sse-tool-\(UUID().uuidString)"
    }

    private static func parseLegacyChunk(_ payload: String, sawDelta: inout Bool) -> SSEParseAction {
        guard let data = payload.data(using: .utf8),
              let chunk = try? JSONDecoder().decode(StreamChunk.self, from: data),
              let choice = chunk.choices?.first else { return .none }
        var updates: [StreamUpdate] = []
        if let content = choice.delta.content, !content.isEmpty {
            sawDelta = true
            updates.append(.content(content))
        }
        for tool in choice.delta.toolCalls ?? [] {
            updates.append(.toolCallUpdate(
                id: tool.id ?? UUID().uuidString,
                name: tool.name ?? "",
                argumentsDelta: tool.argumentsChunk ?? ""
            ))
        }
        return updates.isEmpty ? .none : .emit(updates)
    }

    private static func decodeStringField(_ payload: String, keys: [String]) -> String? {
        guard let data = payload.data(using: .utf8),
              let object = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else {
            return nil
        }
        for key in keys {
            if let value = object[key] as? String { return value }
        }
        return nil
    }

    private static func decodeErrorMessage(_ payload: String) -> String? {
        if let decoded = try? JSONDecoder().decode(StreamErrorPayload.self, from: Data(payload.utf8)),
           let m = decoded.message, !m.isEmpty {
            return m
        }
        return decodeStringField(payload, keys: ["message", "error", "detail"])
    }

    /// deployed(`tool_name`,`tool_args`,`sequence`)와 source(`tool_name`,`args`,`seq`) 스키마를
    /// 모두 수용한다. arguments는 문자열 그대로거나 객체면 JSON 문자열화. `message_id`는
    /// 턴-전체 식별자라 도구 식별에 쓰지 않는다 — FIFO 큐가 대신 짝을 맞춘다.
    private static func decodeToolEvent(_ payload: String) -> (name: String?, arguments: String?)? {
        guard let data = payload.data(using: .utf8),
              let object = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else {
            return nil
        }
        let name = (object["tool_name"] ?? object["name"] ?? object["tool"]) as? String
        var arguments: String?
        let argsValue = object["tool_args"] ?? object["args"] ?? object["arguments"]
        if let raw = argsValue as? String {
            arguments = raw
        } else if let dict = argsValue as? [String: Any],
                  let d = try? JSONSerialization.data(withJSONObject: dict),
                  let s = String(data: d, encoding: .utf8) {
            arguments = s
        } else if let input = object["input"] as? String {
            arguments = input
        }
        return (name, arguments)
    }
}

// MARK: - StreamUpdate Equatable (테스트 편의)

extension StreamUpdate: Equatable {
    static func == (lhs: StreamUpdate, rhs: StreamUpdate) -> Bool {
        switch (lhs, rhs) {
        case (.content(let a), .content(let b)):
            return a == b
        case let (.toolCallUpdate(id: a1, name: a2, argumentsDelta: a3),
                  .toolCallUpdate(id: b1, name: b2, argumentsDelta: b3)):
            return a1 == b1 && a2 == b2 && a3 == b3
        default:
            return false
        }
    }
}
