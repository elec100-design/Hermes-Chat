import Foundation

struct ChatMessage: Identifiable, Codable, Equatable {
    let id: UUID
    var role: Role
    var content: String
    var toolCalls: [ToolCall]?
    var createdAt: Date

    init(id: UUID = UUID(), role: Role, content: String, toolCalls: [ToolCall]? = nil, createdAt: Date = Date()) {
        self.id = id
        self.role = role
        self.content = content
        self.toolCalls = toolCalls
        self.createdAt = createdAt
    }

    enum Role: String, Codable, CaseIterable {
        case system, user, assistant, tool
    }
}

struct ToolCall: Identifiable, Codable, Equatable {
    let id: String
    let name: String
    let arguments: [String: String]?
    let result: String?
}
