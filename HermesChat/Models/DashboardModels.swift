import Foundation

struct DashboardChatSummary: Identifiable, Codable, Equatable {
    let id: String
    let title: String
    let role: String
    let content: String
    let updatedAt: Date
}

struct DashboardStats: Codable, Equatable {
    let activeSessions: Int
    let recentInteractions: [DashboardChatSummary]
}
