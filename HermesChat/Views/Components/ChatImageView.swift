import SwiftUI
import UIKit

// MARK: - 이미지 소스 (T-106)

/// 채팅에 표시할 이미지의 출처. 맥 로컬 경로는 Bridge `/files/raw`로 받는다.
enum ChatImageSource: Equatable, Hashable {
    case http(URL)
    /// HERMES_HOME(~/.hermes) 기준 상대경로
    case bridge(relativePath: String)
    /// 가져올 수 없는 경로(.hermes 밖 등) — placeholder 전용
    case unavailable(name: String)

    static let imageExtensions: Set<String> = [
        "jpg", "jpeg", "png", "gif", "heic", "heif", "webp", "bmp",
    ]

    var cacheKey: String {
        switch self {
        case .http(let url): return "http:\(url.absoluteString)"
        case .bridge(let path): return "bridge:\(path)"
        case .unavailable(let name): return "unavailable:\(name)"
        }
    }

    var displayName: String {
        switch self {
        case .http(let url): return url.lastPathComponent
        case .bridge(let path): return (path as NSString).lastPathComponent
        case .unavailable(let name): return name
        }
    }

    /// 문자열 src(URL 또는 맥 절대경로)를 소스로 분류한다.
    static func parse(_ src: String) -> ChatImageSource {
        let trimmed = src.trimmingCharacters(in: .whitespacesAndNewlines)
        if trimmed.lowercased().hasPrefix("http://") || trimmed.lowercased().hasPrefix("https://"),
           let url = URL(string: trimmed) {
            return .http(url)
        }
        if let relative = bridgeRelativePath(fromMacPath: trimmed) {
            return .bridge(relativePath: relative)
        }
        return .unavailable(name: (trimmed as NSString).lastPathComponent)
    }

    /// 맥 절대경로에서 `.hermes/` 마커 뒤를 HERMES_HOME 상대경로로 변환한다.
    /// 예: "/Users/macmini/.hermes/uploads/a.jpg" → "uploads/a.jpg".
    /// 마커가 없으면(= ~/.hermes 밖) nil — Bridge가 어차피 접근을 차단하는 경로다.
    static func bridgeRelativePath(fromMacPath path: String) -> String? {
        guard let range = path.range(of: ".hermes/") else { return nil }
        let relative = String(path[range.upperBound...])
        return relative.isEmpty ? nil : relative
    }

    /// 파일명/경로가 이미지 확장자인가
    static func isImagePath(_ path: String) -> Bool {
        imageExtensions.contains((path as NSString).pathExtension.lowercased())
    }
}

// MARK: - 메모리 캐시

/// 다운스케일 완료된 UIImage 메모리 캐시. 디스크 캐시 없음 — 세션 재진입 시 재요청을 수용한다.
final class ChatImageCache {
    static let shared = ChatImageCache()
    private let cache = NSCache<NSString, UIImage>()

    private init() {
        cache.totalCostLimit = 64 * 1024 * 1024
    }

    func image(for key: String) -> UIImage? {
        cache.object(forKey: key as NSString)
    }

    func insert(_ image: UIImage, for key: String) {
        let cost = Int(image.size.width * image.size.height * image.scale * image.scale * 4)
        cache.setObject(image, forKey: key as NSString, cost: cost)
    }
}

// MARK: - Environment 주입 (MessageBubble까지 이니셜라이저 변경 없이 전달)

private struct BridgeClientKey: EnvironmentKey {
    static let defaultValue: BridgeClient? = nil
}

extension EnvironmentValues {
    var bridgeClient: BridgeClient? {
        get { self[BridgeClientKey.self] }
        set { self[BridgeClientKey.self] = newValue }
    }
}

// MARK: - 뷰

/// 채팅 말풍선 안의 이미지 썸네일. 로드 실패·Bridge 미설정/구버전(404)·접근 불가 경로는
/// 전부 placeholder로 강등하고 에러를 띄우지 않는다.
struct ChatImageView: View {
    let source: ChatImageSource
    var maxHeight: CGFloat = 220

    @Environment(\.bridgeClient) private var bridge
    @State private var image: UIImage?
    @State private var failed = false

    init(source: ChatImageSource, maxHeight: CGFloat = 220) {
        self.source = source
        self.maxHeight = maxHeight
        // 캐시 히트는 동기 초기화 — List 행 재사용/스트리밍 리렌더 시 로딩 깜빡임 방지
        _image = State(initialValue: ChatImageCache.shared.image(for: source.cacheKey))
        if case .unavailable = source {
            _failed = State(initialValue: true)
        }
    }

    var body: some View {
        Group {
            if let image {
                Image(uiImage: image)
                    .resizable()
                    .scaledToFit()
                    .frame(maxHeight: maxHeight)
                    .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
            } else if failed {
                placeholder
            } else {
                RoundedRectangle(cornerRadius: 12, style: .continuous)
                    .fill(Color.secondary.opacity(0.1))
                    .frame(width: 160, height: 120)
                    .overlay { ProgressView() }
                    .task(id: source.cacheKey) { await load() }
            }
        }
    }

    private var placeholder: some View {
        VStack(spacing: 6) {
            Image(systemName: "photo.badge.exclamationmark")
                .font(.title2)
                .foregroundStyle(.secondary)
            Text(source.displayName)
                .font(.caption2)
                .foregroundStyle(.secondary)
                .lineLimit(1)
        }
        .frame(width: 160, height: 120)
        .background(Color.secondary.opacity(0.1))
        .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
    }

    private func load() async {
        let key = source.cacheKey
        if let cached = ChatImageCache.shared.image(for: key) {
            image = cached
            return
        }
        let data: Data?
        switch source {
        case .http(let url):
            data = try? await URLSession.shared.data(from: url).0
        case .bridge(let path):
            data = try? await bridge?.fetchRawFile(path: path)
        case .unavailable:
            data = nil
        }
        guard let data, let decoded = UIImage(data: data) else {
            failed = true
            return
        }
        // 대형 HEIC 메모리 피크 방지 — 표시용 크기로 다운스케일 (실패 시 원본 사용)
        let downscaled = await decoded.byPreparingThumbnail(
            ofSize: CGSize(width: 800, height: 800)
        )
        let thumbnail = downscaled ?? decoded
        ChatImageCache.shared.insert(thumbnail, for: key)
        image = thumbnail
    }
}
