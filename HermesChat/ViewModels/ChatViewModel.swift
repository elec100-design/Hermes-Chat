import Foundation

@MainActor
final class ChatViewModel: ObservableObject {
    @Published var messages: [ChatMessage] = []
    @Published var inputText: String = ""
    @Published var isWorking: Bool = false
    @Published var isLoadingHistory: Bool = false

    let sessionId: String
    let appSettings: AppSettings

    init(sessionId: String, appSettings: AppSettings) {
        self.sessionId = sessionId
        self.appSettings = appSettings
        Task { await loadHistory() }
    }

    private func loadHistory() async {
        isLoadingHistory = true
        do {
            messages = try await appSettings.hermesClient.fetchMessages(sessionId: sessionId)
        } catch {
            // New session or unreachable — start empty
        }
        isLoadingHistory = false
    }

    func send() async {
        let text = inputText.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !text.isEmpty, !isWorking else { return }

        inputText = ""
        messages.append(ChatMessage(role: .user, content: text, toolCalls: nil, createdAt: .now))

        isWorking = true
        defer { isWorking = false }

        let stream = appSettings.hermesClient.streamChat(sessionId: sessionId, message: text)

        do {
            var assistant = ChatMessage(role: .assistant, content: "", toolCalls: [], createdAt: .now)
            let assistantIndex = messages.count
            messages.append(assistant)
            var toolDictionary: [String: ToolCall] = [:]

            for try await update in stream {
                switch update {
                case .content(let chunk):
                    assistant.content += chunk
                case .toolCallUpdate(let id, let name, let argumentsDelta):
                    if var existing = toolDictionary[id] {
                        let merged = (existing.arguments ?? [:])
                            .merging(["_delta": argumentsDelta], uniquingKeysWith: { cur, _ in cur })
                        toolDictionary[id] = ToolCall(id: existing.id, name: existing.name, arguments: merged, result: existing.result)
                    } else {
                        toolDictionary[id] = ToolCall(id: id, name: name, arguments: ["_delta": argumentsDelta], result: nil)
                    }
                }
                messages[assistantIndex].content = assistant.content
            }

            messages[assistantIndex].content = assistant.content
            messages[assistantIndex].toolCalls = Array(toolDictionary.values)

            if messages[assistantIndex].content.isEmpty && (messages[assistantIndex].toolCalls?.isEmpty ?? true) {
                messages.remove(at: assistantIndex)
            }
        } catch {
            messages.append(ChatMessage(
                role: .assistant,
                content: "[에러] \(error.localizedDescription)",
                toolCalls: nil,
                createdAt: .now
            ))
        }
    }
}
