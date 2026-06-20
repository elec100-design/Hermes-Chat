import SwiftUI
import UIKit

struct MessageBubble: View {
    let message: ChatMessage
    @ObservedObject private var speech = SpeechService.shared

    var isUser: Bool {
        message.role == .user
    }

    var body: some View {
        HStack {
            if isUser { Spacer(minLength: 40) }
            VStack(alignment: isUser ? .trailing : .leading, spacing: 4) {
                if isUser {
                    // 선두의 [첨부: ...] 줄은 썸네일/파일 칩으로 분리 (T-107)
                    let parts = Self.splitUserAttachments(message.content)
                    ForEach(Array(parts.attachments.enumerated()), id: \.offset) { _, attachment in
                        switch attachment {
                        case .image(let source):
                            ChatImageView(source: source, maxHeight: 160)
                        case .file(let name):
                            Label(name, systemImage: "doc")
                                .font(.footnote)
                        }
                    }
                    // 사용자 버블은 accent 배경+흰 글자라 링크 색/코드 배경이 깨져 평문 유지
                    if !parts.rest.isEmpty {
                        Text(parts.rest)
                            .textSelection(.enabled)
                    }
                } else {
                    // 스트리밍 사고(<think>) 단계엔 보일 내용이 없다 — 빈 버블 대신 "생각 중" 표시 (T-116)
                    let visible = MarkdownLite.strippingThink(message.content)
                        .trimmingCharacters(in: .whitespacesAndNewlines)
                    if visible.isEmpty && (message.toolCalls?.isEmpty ?? true) {
                        ThinkingIndicator()
                    } else {
                        MarkdownText(content: message.content)
                    }
                }
                if let toolCalls = message.toolCalls, !toolCalls.isEmpty {
                    ToolCallsChip(toolCalls: toolCalls)
                }
            }
            .padding(.horizontal, 14)
            .padding(.vertical, 10)
            .background(isUser ? Color.accentColor : Color(.tertiarySystemBackground))
            .foregroundStyle(isUser ? .white : .primary)
            .clipShape(RoundedRectangle(cornerRadius: 18, style: .continuous))
            .contextMenu {
                if !message.content.isEmpty {
                    Button {
                        UIPasteboard.general.string = message.content
                    } label: {
                        Label("message.copy", systemImage: "doc.on.doc")
                    }
                    if !isUser {
                        Button {
                            UIPasteboard.general.string = MarkdownLite.plainText(from: message.content)
                        } label: {
                            Label("message.copy.plain", systemImage: "doc.plaintext")
                        }
                        if speech.speakingMessageID == message.id {
                            Button {
                                speech.stopSpeaking()
                            } label: {
                                Label("message.tts.stop", systemImage: "stop.circle")
                            }
                        } else {
                            Button {
                                speech.speak(
                                    MarkdownLite.plainText(from: message.content),
                                    messageID: message.id
                                )
                            } label: {
                                Label("message.tts.read", systemImage: "speaker.wave.2")
                            }
                        }
                    }
                    ShareLink(item: message.content) {
                        Label("discuss.room.share", systemImage: "square.and.arrow.up")
                    }
                }
            }
            if !isUser { Spacer(minLength: 40) }
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 4)
    }

    enum UserAttachment {
        case image(ChatImageSource)
        case file(name: String)
    }

    /// 사용자 메시지 선두의 `[첨부: 경로]` 줄들(ChatViewModel.uploadAttachmentsAndPrepend 형식)을
    /// 분리한다. 첨부가 아닌 줄을 만나면 거기서부터는 본문.
    static func splitUserAttachments(_ content: String) -> (attachments: [UserAttachment], rest: String) {
        var attachments: [UserAttachment] = []
        var lines = ArraySlice(content.split(separator: "\n", omittingEmptySubsequences: false))
        while let first = lines.first {
            let trimmed = first.trimmingCharacters(in: .whitespaces)
            guard trimmed.hasPrefix("[첨부:"), trimmed.hasSuffix("]") else { break }
            let path = String(trimmed.dropFirst("[첨부:".count).dropLast())
                .trimmingCharacters(in: .whitespaces)
            if !path.isEmpty {
                if ChatImageSource.isImagePath(path) {
                    attachments.append(.image(ChatImageSource.parse(path)))
                } else {
                    attachments.append(.file(name: (path as NSString).lastPathComponent))
                }
            }
            lines = lines.dropFirst()
        }
        let rest = lines.joined(separator: "\n")
            .trimmingCharacters(in: .whitespacesAndNewlines)
        return (attachments, rest)
    }
}

/// 스트리밍 사고(<think>) 단계에서 빈 버블 대신 보이는 애니메이션 점 3개 (T-116).
/// 보일 내용이 도착하면 MessageBubble이 MarkdownText로 전환한다.
struct ThinkingIndicator: View {
    @State private var phase = 0
    private let timer = Timer.publish(every: 0.35, on: .main, in: .common).autoconnect()

    var body: some View {
        HStack(spacing: 5) {
            ForEach(0..<3, id: \.self) { index in
                Circle()
                    .frame(width: 6, height: 6)
                    .opacity(phase == index ? 1 : 0.3)
            }
        }
        .foregroundStyle(.secondary)
        .onReceive(timer) { _ in phase = (phase + 1) % 3 }
        .accessibilityLabel("message.thinking")
    }
}
