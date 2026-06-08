import SwiftUI

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
