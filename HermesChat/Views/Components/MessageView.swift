import SwiftUI

struct MessageBubble: View {
    let message: ChatMessage

    var isUser: Bool {
        message.role == .user
    }

    var body: some View {
        HStack {
            Spacer()
            VStack(alignment: .trailing, spacing: 4) {
                Text(message.content)
                    .textSelection(.enabled)
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
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 4)
    }
}
