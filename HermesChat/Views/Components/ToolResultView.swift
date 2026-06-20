import SwiftUI

/// 접힌 도구 실행 요약 칩 (T-104) — 기본은 "도구 N회 실행" 한 줄,
/// 탭하면 ToolResultView 목록을 펼쳐 인자를 확인할 수 있다.
struct ToolCallsChip: View {
    let toolCalls: [ToolCall]
    @State private var expanded = false

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            Button {
                withAnimation(.easeInOut(duration: 0.2)) { expanded.toggle() }
            } label: {
                HStack(spacing: 5) {
                    Image(systemName: "wrench.and.screwdriver.fill")
                    Text(String(format: String(localized: "tool.calls.count %lld"), Int64(toolCalls.count)))
                    Image(systemName: expanded ? "chevron.up" : "chevron.down")
                        .font(.caption2)
                }
                .font(.footnote)
                .foregroundStyle(.secondary)
                .padding(.horizontal, 10)
                .padding(.vertical, 5)
                .background(.ultraThinMaterial)
                .clipShape(Capsule())
            }
            .buttonStyle(.plain)

            if expanded {
                ForEach(toolCalls) { tool in
                    ToolResultView(tool: tool)
                }
            }
        }
    }
}

struct ToolResultView: View {
    let tool: ToolCall

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            Label(tool.name, systemImage: "wrench.and.screwdriver.fill")
                .font(.footnote.bold())
            if let arguments = tool.arguments, !arguments.isEmpty {
                Text(arguments.map { "\($0.key): \($0.value)" }
                    .joined(separator: "\n"))
                    .font(.system(.footnote, design: .monospaced))
                    .textSelection(.enabled)
                    .padding(8)
                    .background(.ultraThinMaterial)
                    .clipShape(RoundedRectangle(cornerRadius: 10))
            }
        }
    }
}
