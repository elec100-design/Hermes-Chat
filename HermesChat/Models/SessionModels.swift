import Foundation

struct Session: Identifiable, Codable, Equatable {
    let id: String
    var title: String
    var updatedAt: Date
}

struct SessionListItem: Identifiable, Codable, Equatable {
    let id: String
    var title: String
    var createdAt: Date
    var updatedAt: Date
}
