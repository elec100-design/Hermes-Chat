import Foundation
import UIKit

/// 전송 대기 중인 첨부 파일 (업로드는 send 시점에 일괄 수행)
struct PendingAttachment: Identifiable, Equatable {
    let id = UUID()
    let filename: String
    let data: Data
}

@MainActor
final class ChatViewModel: ObservableObject {
    @Published var messages: [ChatMessage] = []
    @Published var inputText: String = ""
    @Published var isWorking: Bool = false
    @Published var isLoadingHistory: Bool = false
    @Published var attachments: [PendingAttachment] = []

    /// Bridge 업로드 한도와 동일 (server/hermes_bridge.py MAX_UPLOAD)
    static let maxAttachmentBytes = 50 * 1024 * 1024

    let sessionId: String
    let appSettings: AppSettings

    init(sessionId: String, appSettings: AppSettings) {
        self.sessionId = sessionId
        self.appSettings = appSettings
        Task { await loadHistory() }
    }

    /// 채팅 화면에 렌더할 메시지 (T-103) — tool/system 제외,
    /// 사고 과정(<think>)만 있고 보일 내용이 없는 어시스턴트 버블 제외.
    /// 스트리밍 갱신은 원본 `messages`의 인덱스를 그대로 쓰므로 여기는 읽기 전용 필터다.
    var displayMessages: [ChatMessage] {
        messages.filter { message in
            switch message.role {
            case .user:
                return true
            case .assistant:
                let visible = MarkdownLite.strippingThink(message.content)
                    .trimmingCharacters(in: .whitespacesAndNewlines)
                return !visible.isEmpty || !(message.toolCalls?.isEmpty ?? true)
            case .system, .tool:
                return false
            }
        }
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

    func addAttachment(filename: String, data: Data) {
        guard data.count <= Self.maxAttachmentBytes else {
            messages.append(ChatMessage(
                role: .assistant,
                content: "[에러] \(filename): 50MB를 초과해 첨부할 수 없습니다.",
                toolCalls: nil,
                createdAt: .now
            ))
            return
        }
        attachments.append(PendingAttachment(filename: filename, data: data))
    }

    func removeAttachment(id: UUID) {
        attachments.removeAll { $0.id == id }
    }

    func send() async {
        let text = inputText.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !text.isEmpty || !attachments.isEmpty, !isWorking else { return }

        isWorking = true
        defer { isWorking = false }

        var outgoing = text
        if !attachments.isEmpty {
            do {
                outgoing = try await uploadAttachmentsAndPrepend(to: text)
            } catch {
                messages.append(ChatMessage(
                    role: .assistant,
                    content: "[에러] 첨부 업로드 실패: \(error.localizedDescription)",
                    toolCalls: nil,
                    createdAt: .now
                ))
                return
            }
        }

        let isFirstMessage = messages.isEmpty
        inputText = ""
        messages.append(ChatMessage(role: .user, content: outgoing, toolCalls: nil, createdAt: .now))

        let startedAt = Date.now
        let stream = appSettings.hermesClient.streamChat(sessionId: sessionId, message: outgoing)

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

            notifyCompletionIfBackground(startedAt: startedAt, responseText: assistant.content)

            if isFirstMessage {
                await updateAutoTitle(from: text)
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

    /// 첨부를 Bridge로 업로드하고 맥미니 절대경로를 메시지 앞에 붙인다.
    /// 게이트웨이 chat API는 텍스트만 받으므로, Hermes가 자기 파일 도구로
    /// 경로를 읽게 하는 것이 정석 흐름이다 (PLAN §3 Phase 4).
    private func uploadAttachmentsAndPrepend(to text: String) async throws -> String {
        guard let bridge = appSettings.bridgeClient else {
            throw HermesAPIError.serverError(
                "첨부를 보내려면 설정 화면의 Hermes Bridge 섹션에 URL과 토큰을 입력하세요."
            )
        }
        var lines: [String] = []
        for attachment in attachments {
            let path = try await bridge.upload(
                data: attachment.data,
                filename: attachment.filename,
                profile: appSettings.selectedProfile.name
            )
            lines.append("[첨부: \(path)]")
        }
        attachments = []
        let header = lines.joined(separator: "\n")
        return text.isEmpty ? header : header + "\n\n" + text
    }

    /// 앱이 비활성(백그라운드/전환 중)이고 응답에 10초 이상 걸렸으면 로컬 알림 (T-094).
    /// 짧은 응답은 돌아왔을 때 바로 보이므로 알리지 않는다.
    private func notifyCompletionIfBackground(startedAt: Date, responseText: String) {
        guard UIApplication.shared.applicationState != .active,
              Date.now.timeIntervalSince(startedAt) >= 10 else { return }
        let preview = MarkdownLite.plainText(from: responseText)
            .trimmingCharacters(in: .whitespacesAndNewlines)
        NotificationService.shared.notify(
            title: "\(appSettings.selectedProfile.name) 응답 완료",
            body: preview.isEmpty ? "응답이 도착했습니다." : String(preview.prefix(80)),
            id: "chat-done-\(sessionId)"
        )
    }

    /// 첫 메시지의 앞부분으로 세션 제목을 자동 설정한다.
    private func updateAutoTitle(from text: String) async {
        let words = text.split(separator: " ").prefix(6).joined(separator: " ")
        let title = String(words.prefix(40))
        guard !title.isEmpty else { return }

        try? await appSettings.hermesClient.updateSessionTitle(id: sessionId, title: title)
        if var session = appSettings.sessions.first(where: { $0.id == sessionId }) {
            session.title = title
            appSettings.updateSession(session)
        }
    }
}
