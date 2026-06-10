import Foundation

struct MCPToolItem: Identifiable, Codable, Equatable {
    let id: String
    let name: String
    let description: String?
    let inputSchema: [String: String]?
}

struct MCPToolCall: Codable, Identifiable, Equatable {
    let id: String
    let toolName: String
    let arguments: [String: String]?
}

/// 게이트웨이의 스킬 또는 툴셋 한 항목 (`GET /v1/skills`, `/v1/toolsets` 정규화 결과)
struct GatewayCapability: Identifiable, Equatable {
    let name: String
    let detail: String?
    /// 툴셋의 활성화 상태. 서버가 안 알려주면 nil.
    let enabled: Bool?

    var id: String { name }
}
