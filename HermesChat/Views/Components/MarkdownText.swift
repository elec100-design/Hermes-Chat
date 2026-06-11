import SwiftUI
import UIKit

// MARK: - 경량 마크다운 파서 (T-090)
//
// 코드펜스(```)만 직접 분리하고, 텍스트 구간은 내장 AttributedString 마크다운 파서에 맡긴다.
// `.full` 해석은 SwiftUI Text가 블록 인텐트(코드블록 등)를 렌더링하지 못해 쓸 수 없고,
// `.inlineOnlyPreservingWhitespace`는 볼드/이탤릭/링크/인라인코드 + 개행 보존을 지원한다.
// 외부 패키지 없음 — pbxproj를 여러 에이전트가 수동 편집하는 구조라 SPM 의존성을 피한다.

/// 메시지 본문을 코드펜스·이미지·첨부 기준으로 분할한 구간
struct MarkdownSegment: Identifiable {
    enum Kind {
        case text(AttributedString)
        case code(language: String?, code: String)
        /// `![alt](src)` 또는 이미지 확장자의 `[첨부: 경로]` (T-107)
        case image(source: ChatImageSource, alt: String?)
        /// 비이미지 `[첨부: 경로]` — 파일 칩으로 표시
        case file(name: String)
    }
    let id: Int
    let kind: Kind
}

enum MarkdownLite {
    /// `<think>...</think>` 사고 과정을 제거한다 (T-103).
    /// 미닫힌 `<think>`(스트리밍 중)는 그 지점부터 끝까지 숨기고, 말미에 걸친
    /// 부분 열림 태그("<", "<t" … "<think")도 보류해 토큰 경계에서 한 글자도 새지 않게 한다.
    static func strippingThink(_ raw: String) -> String {
        var result = ""
        var rest = Substring(raw)
        while let open = rest.range(of: "<think>") {
            result += rest[..<open.lowerBound]
            guard let close = rest.range(of: "</think>", range: open.upperBound..<rest.endIndex) else {
                return result  // 미닫힘 — 이후는 전부 사고 중인 내용
            }
            rest = rest[close.upperBound...]
        }
        result += rest
        return trimmingPartialOpenTag(result)
    }

    /// 지금 사고 중인가 (미닫힌 `<think>` 존재) — 작업 바 "생각 중..." 표시용
    static func hasOpenThink(_ raw: String) -> Bool {
        guard let lastOpen = raw.range(of: "<think>", options: .backwards) else { return false }
        return raw.range(of: "</think>", range: lastOpen.upperBound..<raw.endIndex) == nil
    }

    /// 끝에 걸쳐 있는 "<think>"의 접두 부분을 잘라낸다 (다음 토큰이 태그가 아니면 곧 복원됨)
    private static func trimmingPartialOpenTag(_ text: String) -> String {
        let tag = "<think>"
        let maxLen = min(tag.count - 1, text.count)
        guard maxLen >= 1 else { return text }
        for length in stride(from: maxLen, through: 1, by: -1) {
            if tag.hasPrefix(text.suffix(length)) {
                return String(text.dropLast(length))
            }
        }
        return text
    }

    /// ```lang ... ``` 펜스를 분리한다. 마지막 펜스가 안 닫혀 있으면(스트리밍 중) 그 구간은 코드로 취급.
    /// 사고 과정(`<think>`)은 진입 시점에 제거된다 — 렌더·복사·TTS·알림 미리보기 모두 동일 적용.
    static func segments(from raw: String) -> [MarkdownSegment] {
        var cleaned = strippingThink(raw)
        // 스트리밍 꼬리의 미완성 이미지/첨부 토큰 보류 — 단, 꼬리가 미닫힌 코드펜스 안이면 건드리지 않는다
        let fenceCount = cleaned.split(separator: "\n", omittingEmptySubsequences: false)
            .filter { $0.trimmingCharacters(in: .whitespaces).hasPrefix("```") }
            .count
        if fenceCount % 2 == 0 {
            cleaned = trimmingIncompleteTail(cleaned)
        }
        var segments: [MarkdownSegment] = []
        var textLines: [Substring] = []
        var codeLines: [Substring] = []
        var language: String?
        var inCode = false

        func flushText() {
            guard !textLines.isEmpty else { return }
            let joined = textLines.joined(separator: "\n")
            textLines.removeAll()
            appendTextBlock(joined, to: &segments)
        }
        func flushCode() {
            let code = codeLines.joined(separator: "\n")
            codeLines.removeAll()
            defer { language = nil }
            guard !code.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else { return }
            segments.append(MarkdownSegment(id: segments.count, kind: .code(language: language, code: code)))
        }

        for line in cleaned.split(separator: "\n", omittingEmptySubsequences: false) {
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

    // MARK: 이미지/첨부 세그먼트 (T-107)

    private static let imageRegex = try! NSRegularExpression(
        pattern: "!\\[([^\\]]*)\\]\\(([^)\\s]+)\\)"
    )

    /// 코드펜스 밖 텍스트 블록에서 `[첨부: 경로]` 전체 줄과 `![alt](src)`를 분리한다.
    private static func appendTextBlock(_ text: String, to segments: inout [MarkdownSegment]) {
        var plainLines: [String] = []
        func flushPlain() {
            let joined = plainLines.joined(separator: "\n")
            plainLines = []
            appendInlineImages(joined, to: &segments)
        }
        for line in text.split(separator: "\n", omittingEmptySubsequences: false) {
            let trimmed = line.trimmingCharacters(in: .whitespaces)
            if trimmed.hasPrefix("[첨부:"), trimmed.hasSuffix("]") {
                let path = String(trimmed.dropFirst("[첨부:".count).dropLast())
                    .trimmingCharacters(in: .whitespaces)
                flushPlain()
                guard !path.isEmpty else { continue }
                if ChatImageSource.isImagePath(path) {
                    segments.append(MarkdownSegment(
                        id: segments.count,
                        kind: .image(source: ChatImageSource.parse(path), alt: nil)
                    ))
                } else {
                    segments.append(MarkdownSegment(
                        id: segments.count,
                        kind: .file(name: (path as NSString).lastPathComponent)
                    ))
                }
                continue
            }
            plainLines.append(String(line))
        }
        flushPlain()
    }

    /// 텍스트 안의 `![alt](src)`를 이미지 세그먼트로 분리하고, 나머지는 인라인 마크다운으로.
    private static func appendInlineImages(_ text: String, to segments: inout [MarkdownSegment]) {
        let ns = text as NSString
        var cursor = 0
        for match in imageRegex.matches(in: text, range: NSRange(location: 0, length: ns.length)) {
            appendPlain(ns.substring(with: NSRange(location: cursor, length: match.range.location - cursor)),
                        to: &segments)
            let alt = ns.substring(with: match.range(at: 1))
            let src = ns.substring(with: match.range(at: 2))
            segments.append(MarkdownSegment(
                id: segments.count,
                kind: .image(source: ChatImageSource.parse(src), alt: alt.isEmpty ? nil : alt)
            ))
            cursor = match.range.location + match.range.length
        }
        appendPlain(ns.substring(from: cursor), to: &segments)
    }

    private static func appendPlain(_ text: String, to segments: inout [MarkdownSegment]) {
        guard !text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else { return }
        segments.append(MarkdownSegment(id: segments.count, kind: .text(inline(text))))
    }

    /// 스트리밍 꼬리의 미완성 토큰 보류 — 닫힘 문자가 도착하는 프레임에 한 번에 나타나
    /// 중간 상태가 평문으로 번쩍이지 않게 한다. 오검출 방지로 꼬리 512자 이내만 보류.
    static func trimmingIncompleteTail(_ text: String) -> String {
        var result = text
        // 미완성 마크다운 이미지: 마지막 "![" 뒤에 ")"가 아직 없으면 그 지점부터 보류
        if let bang = result.range(of: "![", options: .backwards),
           result.range(of: ")", range: bang.upperBound..<result.endIndex) == nil,
           result.distance(from: bang.lowerBound, to: result.endIndex) <= 512 {
            result = String(result[..<bang.lowerBound])
        }
        // 미완성 첨부 줄: 마지막 줄이 "[첨부:"로 시작하는데 "]"가 없으면 그 줄을 보류
        let lastLineStart = result.range(of: "\n", options: .backwards)?.upperBound ?? result.startIndex
        let lastLine = result[lastLineStart...]
        if lastLine.trimmingCharacters(in: .whitespaces).hasPrefix("[첨부:"), !lastLine.contains("]"),
           lastLine.count <= 512 {
            result = String(result[..<lastLineStart])
        }
        return result
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
            case .image(let source, let alt): return alt ?? source.displayName
            case .file(let name): return name
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
                case .image(let source, _):
                    ChatImageView(source: source)
                case .file(let name):
                    Label(name, systemImage: "doc")
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                        .padding(.horizontal, 10)
                        .padding(.vertical, 5)
                        .background(.ultraThinMaterial)
                        .clipShape(Capsule())
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
