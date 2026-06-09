import Foundation

struct Profile: Identifiable, Codable, Equatable {
    var id: UUID
    var name: String
    var model: String
    var systemPrompt: String

    init(id: UUID = UUID(), name: String, model: String = "hermes-agent", systemPrompt: String = "") {
        self.id = id
        self.name = name
        self.model = model
        self.systemPrompt = systemPrompt
    }
}
