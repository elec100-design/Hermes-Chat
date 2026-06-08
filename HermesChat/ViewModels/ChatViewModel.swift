import Foundation
import Combine

@MainActor
final class ChatViewModel: ObservableObject {
    @Published var messages: [ChatMessage] = []
    @Published var inputText: String = ""
    @Published var isWorking: Bool = false
    let sessionId: String
    let appSettings: AppSettings

    init(sessionId: String, appSettings: AppSettings) {
        self.sessionId = sessionId
        self.appSettings = appSettings
    }

    func send() async {
        let text = inputText.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !text.isEmpty, isWorking == false else { return }

        inputText = ""
        let userMessage = ChatMessage(
            role: .user,
            content: text,
            toolCalls: nil,
            createdAt: .init()
        )
        messages.append(userMessage)

        isWorking = true
        defer { isWorking = false }

        let client = APIClient(
            baseURL: URL(string: appSettings.serverHost)!,
            apiKey: appSettings.apiKey
        )

        let request = ChatRequest(
            model: appSettings.selectedModel,
            messages: messages.map { .init(role: $0.role.rawValue, content: $0.content) },
            sessionId: sessionId
        )

        do {
            let stream = client.streamChat(request: request)

            var assistant = ChatMessage(role: .assistant, content: "", toolCalls: [], createdAt: .init())
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
                            .merging(["_delta": argumentsDelta], uniquingKeysWith: { current, _ in current })
                        toolDictionary[id] = ToolCall(id: existing.id, name: existing.name, arguments: merged, result: existing.result)
                    } else {
                        toolDictionary[id] = ToolCall(id: id, name: name, arguments: ["_delta": argumentsDelta], result: nil)
                    }
                }
            }

            messages[assistantIndex].content = assistant.content
            messages[assistantIndex].toolCalls = Array(toolDictionary.values)

            if messages[assistantIndex].content.isEmpty && (messages[assistantIndex].toolCalls?.isEmpty ?? true) {
                messages.remove(at: assistantIndex)
            }
        } catch {
            let errorMessage = ChatMessage(
                role: .assistant,
                content: "[에러] \(error.localizedDescription)",
                toolCalls: nil,
                createdAt: .init()
            )
            messages.append(errorMessage)
        }
    }
}
