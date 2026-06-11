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
                    // 사용자 버블은 accent 배경+흰 글자라 링크 색/코드 배경이 깨져 평문 유지
                    Text(message.content)
                        .textSelection(.enabled)
                } else {
                    MarkdownText(content: message.content)
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
                        Label("복사", systemImage: "doc.on.doc")
                    }
                    if !isUser {
                        Button {
                            UIPasteboard.general.string = MarkdownLite.plainText(from: message.content)
                        } label: {
                            Label("평문 복사 (마크다운 제거)", systemImage: "doc.plaintext")
                        }
                        if speech.speakingMessageID == message.id {
                            Button {
                                speech.stopSpeaking()
                            } label: {
                                Label("읽기 중지", systemImage: "stop.circle")
                            }
                        } else {
                            Button {
                                speech.speak(
                                    MarkdownLite.plainText(from: message.content),
                                    messageID: message.id
                                )
                            } label: {
                                Label("읽어주기", systemImage: "speaker.wave.2")
                            }
                        }
                    }
                    ShareLink(item: message.content) {
                        Label("공유", systemImage: "square.and.arrow.up")
                    }
                }
            }
            if !isUser { Spacer(minLength: 40) }
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 4)
    }
}
