import SwiftUI
import UIKit

// MARK: - 경량 마크다운 파서 (T-090)
//
// 코드펜스(```)만 직접 분리하고, 텍스트 구간은 내장 AttributedString 마크다운 파서에 맡긴다.
// `.full` 해석은 SwiftUI Text가 블록 인텐트(코드블록 등)를 렌더링하지 못해 쓸 수 없고,
// `.inlineOnlyPreservingWhitespace`는 볼드/이탤릭/링크/인라인코드 + 개행 보존을 지원한다.
// 외부 패키지 없음 — pbxproj를 여러 에이전트가 수동 편집하는 구조라 SPM 의존성을 피한다.

/// 메시지 본문을 코드펜스 기준으로 분할한 구간
struct MarkdownSegment: Identifiable {
    enum Kind {
        case text(AttributedString)
        case code(language: String?, code: String)
    }
    let id: Int
    let kind: Kind
}

enum MarkdownLite {
    /// ```lang ... ``` 펜스를 분리한다. 마지막 펜스가 안 닫혀 있으면(스트리밍 중) 그 구간은 코드로 취급.
    static func segments(from raw: String) -> [MarkdownSegment] {
        var segments: [MarkdownSegment] = []
        var textLines: [Substring] = []
        var codeLines: [Substring] = []
        var language: String?
        var inCode = false

        func flushText() {
            guard !textLines.isEmpty else { return }
            let joined = textLines.joined(separator: "\n")
            textLines.removeAll()
            guard !joined.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else { return }
            segments.append(MarkdownSegment(id: segments.count, kind: .text(inline(joined))))
        }
        func flushCode() {
            let code = codeLines.joined(separator: "\n")
            codeLines.removeAll()
            defer { language = nil }
            guard !code.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else { return }
            segments.append(MarkdownSegment(id: segments.count, kind: .code(language: language, code: code)))
        }

        for line in raw.split(separator: "\n", omittingEmptySubsequences: false) {
            let trimmed = line.trimmingCharacters(in: .whitespaces)
            if trimmed.hasPrefix("```") {
                if inCode {
                    flushCode()
                } else {
                    flushText()
                    let lang = trimmed.dropFirst(3).trimmingCharacters(in: .whitespaces)
                    language = lang.isEmpty ? nil : lang
                }
                inCode.toggle()
                continue
            }
            if inCode {
                codeLines.append(line)
            } else {
                textLines.append(line)
            }
        }
        if inCode { flushCode() } else { flushText() }
        return segments
    }

    /// 인라인 마크다운 → AttributedString. 파싱 실패 시 평문 폴백.
    static func inline(_ text: String) -> AttributedString {
        (try? AttributedString(
            markdown: text,
            options: AttributedString.MarkdownParsingOptions(
                interpretedSyntax: .inlineOnlyPreservingWhitespace
            )
        )) ?? AttributedString(text)
    }

    /// 마크다운 기호를 제거한 평문 — TTS 읽어주기(T-101)·전체 복사용.
    static func plainText(from raw: String) -> String {
        segments(from: raw).map { segment in
            switch segment.kind {
            case .text(let attributed): return String(attributed.characters)
            case .code(_, let code): return code
            }
        }
        .joined(separator: "\n")
    }
}

// MARK: - Views

/// 어시스턴트 메시지 본문 렌더러: 텍스트 구간 + 코드블록을 세로로 쌓는다.
struct MarkdownText: View {
    let content: String

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            ForEach(MarkdownLite.segments(from: content)) { segment in
                switch segment.kind {
                case .text(let attributed):
                    Text(attributed)
                        .textSelection(.enabled)
                case .code(let language, let code):
                    CodeBlockView(language: language, code: code)
                }
            }
        }
    }
}

/// 코드블록: 언어 라벨 + 복사 버튼 헤더, 가로 스크롤 모노스페이스 본문.
struct CodeBlockView: View {
    let language: String?
    let code: String
    @State private var copied = false

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            HStack {
                Text(language ?? "code")
                    .font(.caption2)
                    .foregroundStyle(.secondary)
                Spacer()
                Button {
                    UIPasteboard.general.string = code
                    copied = true
                    Task {
                        try? await Task.sleep(nanoseconds: 1_500_000_000)
                        copied = false
                    }
                } label: {
                    Image(systemName: copied ? "checkmark" : "doc.on.doc")
                        .font(.caption)
                }
                .buttonStyle(.plain)
                .foregroundStyle(copied ? .green : .secondary)
            }
            .padding(.horizontal, 10)
            .padding(.vertical, 6)
            Divider()
            ScrollView(.horizontal, showsIndicators: false) {
                Text(code)
                    .font(.system(.footnote, design: .monospaced))
                    .textSelection(.enabled)
                    .padding(10)
            }
        }
        .background(Color(.secondarySystemBackground))
        .clipShape(RoundedRectangle(cornerRadius: 10, style: .continuous))
    }
}
