import SwiftUI

/// 맥미니 `~/.hermes` 하위를 읽기전용으로 탐색하는 파일 브라우저 (Bridge /files).
/// 숨김 파일(.env 등)은 브리지가 목록/내용 모두 차단한다.
struct FileBrowserView: View {
    @ObservedObject var appSettings: AppSettings
    var path: String = ""

    @State private var entries: [BridgeFileEntry] = []
    @State private var isLoading = true
    @State private var loadError: String?

    var body: some View {
        List {
            if isLoading {
                HStack(spacing: 8) {
                    ProgressView()
                    Text("common.loading")
                        .foregroundStyle(.secondary)
                }
            } else if let loadError {
                HStack {
                    Image(systemName: "exclamationmark.triangle.fill")
                        .foregroundStyle(.orange)
                    Text(loadError)
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                    Spacer()
                    Button("common.retry") {
                        Task { await load() }
                    }
                    .font(.footnote.bold())
                }
            } else if entries.isEmpty {
                Text("common.empty")
                    .foregroundStyle(.secondary)
            } else {
                ForEach(entries) { entry in
                    row(entry)
                }
            }
        }
        .navigationTitle(path.isEmpty ? "~/.hermes" : (path as NSString).lastPathComponent)
        .navigationBarTitleDisplayMode(.inline)
        .refreshable { await load() }
        .task { await load() }
    }

    @ViewBuilder
    private func row(_ entry: BridgeFileEntry) -> some View {
        let childPath = path.isEmpty ? entry.name : "\(path)/\(entry.name)"
        if entry.isDir {
            NavigationLink {
                FileBrowserView(appSettings: appSettings, path: childPath)
            } label: {
                Label(entry.name, systemImage: "folder")
            }
        } else {
            NavigationLink {
                FileContentView(appSettings: appSettings, path: childPath)
            } label: {
                HStack {
                    Label(entry.name, systemImage: "doc.text")
                    Spacer()
                    if let size = entry.size {
                        Text(Self.sizeString(size))
                            .font(.caption)
                            .foregroundStyle(.secondary)
                    }
                }
            }
        }
    }

    private func load() async {
        guard let bridge = appSettings.bridgeClient else {
            loadError = String(localized: "files.bridge.required")
            isLoading = false
            return
        }
        isLoading = true
        loadError = nil
        do {
            entries = try await bridge.listFiles(path: path)
        } catch {
            loadError = error.localizedDescription
        }
        isLoading = false
    }

    static func sizeString(_ bytes: Int) -> String {
        ByteCountFormatter.string(fromByteCount: Int64(bytes), countStyle: .file)
    }
}

/// 텍스트 파일 내용 보기 (512KB 제한 — 초과 시 브리지가 413 반환)
struct FileContentView: View {
    @ObservedObject var appSettings: AppSettings
    let path: String

    @State private var content: String?
    @State private var loadError: String?

    var body: some View {
        Group {
            if let content {
                ScrollView {
                    Text(content)
                        .font(.system(.footnote, design: .monospaced))
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .padding()
                        .textSelection(.enabled)
                }
            } else if let loadError {
                ContentUnavailableView(
                    "files.cannot.open",
                    systemImage: "doc.questionmark",
                    description: Text(loadError)
                )
            } else {
                ProgressView("common.loading")
            }
        }
        .navigationTitle((path as NSString).lastPathComponent)
        .navigationBarTitleDisplayMode(.inline)
        .task {
            guard let bridge = appSettings.bridgeClient else {
                loadError = String(localized: "files.bridge.not.configured")
                return
            }
            do {
                content = try await bridge.fetchFileContent(path: path)
            } catch {
                loadError = error.localizedDescription
            }
        }
    }
}
