import SwiftUI

struct MessageBubble: View {
    let message: ChatMessage

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
                    ForEach(toolCalls) { tool in
                        ToolResultView(tool: tool)
                    }
                }
            }
            .padding(.horizontal, 14)
            .padding(.vertical, 10)
            .background(isUser ? Color.accentColor : Color(.tertiarySystemBackground))
            .foregroundStyle(isUser ? .white : .primary)
            .clipShape(RoundedRectangle(cornerRadius: 18, style: .continuous))
            if !isUser { Spacer(minLength: 40) }
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 4)
    }
}
