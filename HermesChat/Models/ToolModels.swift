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
