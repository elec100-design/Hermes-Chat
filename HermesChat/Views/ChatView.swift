import PhotosUI
import SwiftUI
import UniformTypeIdentifiers

struct ChatView: View {
    @ObservedObject var appSettings: AppSettings
    let sessionId: String
    @StateObject private var viewModel: ChatViewModel
    @FocusState private var isInputFocused: Bool
    @State private var photoItems: [PhotosPickerItem] = []
    @State private var showPhotoPicker = false
    @State private var showFileImporter = false

    init(sessionId: String, appSettings: AppSettings) {
        self.sessionId = sessionId
        self.appSettings = appSettings
        self._viewModel = StateObject(wrappedValue: .init(sessionId: sessionId, appSettings: appSettings))
    }

    var body: some View {
        VStack(spacing: 0) {
            if viewModel.isLoadingHistory {
                ProgressView("대화 기록 불러오는 중...")
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
            } else {
                messageList
            }

            if viewModel.isWorking {
                Divider()
                HStack {
                    Spacer()
                    ProgressView().padding(.trailing, 4)
                    Text(viewModel.messages.last?.content.isEmpty == true ? "응답 생성 중..." : "응답 중")
                    Spacer()
                }
                .padding(.vertical, 6)
                .background(.thinMaterial)
            }

            Divider()
            inputBar
                .padding()
                .background(.background)
        }
        .navigationTitle("Hermes Chat")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .keyboard) {
                Button("완료") { isInputFocused = false }
            }
        }
        .photosPicker(
            isPresented: $showPhotoPicker,
            selection: $photoItems,
            maxSelectionCount: 5,
            matching: .images
        )
        .onChange(of: photoItems) { items in
            guard !items.isEmpty else { return }
            photoItems = []
            Task { await loadPhotos(items) }
        }
        .fileImporter(
            isPresented: $showFileImporter,
            allowedContentTypes: [.item],
            allowsMultipleSelection: true
        ) { result in
            guard case .success(let urls) = result else { return }
            for url in urls {
                let accessed = url.startAccessingSecurityScopedResource()
                defer { if accessed { url.stopAccessingSecurityScopedResource() } }
                if let data = try? Data(contentsOf: url) {
                    viewModel.addAttachment(filename: url.lastPathComponent, data: data)
                }
            }
        }
    }

    private func loadPhotos(_ items: [PhotosPickerItem]) async {
        for item in items {
            guard let data = try? await item.loadTransferable(type: Data.self) else { continue }
            let ext = item.supportedContentTypes.first?.preferredFilenameExtension ?? "jpg"
            let name = "photo_\(Int(Date.now.timeIntervalSince1970))_\(Int.random(in: 100...999)).\(ext)"
            viewModel.addAttachment(filename: name, data: data)
        }
    }

    private var messageList: some View {
        ScrollViewReader { proxy in
            List {
                ForEach(viewModel.messages) { message in
                    MessageBubble(message: message)
                        .listRowSeparator(.hidden)
                        .listRowInsets(.init(top: 6, leading: 0, bottom: 6, trailing: 0))
                        .listRowBackground(Color.clear)
                }
            }
            .listStyle(.plain)
            .onChange(of: viewModel.messages.count) { _ in scrollToBottom(proxy: proxy) }
            .onChange(of: viewModel.messages.last?.content) { _ in scrollToBottom(proxy: proxy) }
        }
    }

    private func scrollToBottom(proxy: ScrollViewProxy) {
        guard let last = viewModel.messages.last?.id else { return }
        withAnimation(.easeInOut) { proxy.scrollTo(last, anchor: .bottom) }
    }

    private var inputBar: some View {
        VStack(alignment: .leading, spacing: 8) {
            if !viewModel.attachments.isEmpty {
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack(spacing: 8) {
                        ForEach(viewModel.attachments) { attachment in
                            HStack(spacing: 4) {
                                Image(systemName: "paperclip")
                                Text(attachment.filename)
                                    .lineLimit(1)
                                Button {
                                    viewModel.removeAttachment(id: attachment.id)
                                } label: {
                                    Image(systemName: "xmark.circle.fill")
                                        .foregroundStyle(.secondary)
                                }
                            }
                            .font(.caption)
                            .padding(.horizontal, 8)
                            .padding(.vertical, 5)
                            .background(Color.secondary.opacity(0.15))
                            .clipShape(Capsule())
                        }
                    }
                }
            }

            HStack(alignment: .bottom, spacing: 12) {
                Menu {
                    Button {
                        showPhotoPicker = true
                    } label: {
                        Label("사진", systemImage: "photo")
                    }
                    Button {
                        showFileImporter = true
                    } label: {
                        Label("파일 (드라이브 포함)", systemImage: "folder")
                    }
                } label: {
                    Image(systemName: "plus.circle.fill")
                        .font(.system(size: 22))
                }
                .disabled(viewModel.isWorking)
                .accessibilityLabel("첨부 추가")

                TextField("메시지를 입력하세요", text: $viewModel.inputText, axis: .vertical)
                    .textFieldStyle(.roundedBorder)
                    .focused($isInputFocused)

                Button {
                    Task { await viewModel.send() }
                    isInputFocused = false
                } label: {
                    Image(systemName: viewModel.isWorking ? "ellipsis" : "paperplane.fill")
                        .font(.system(size: 18, weight: .semibold))
                }
                .disabled(
                    (viewModel.inputText.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
                        && viewModel.attachments.isEmpty)
                    || viewModel.isWorking
                )
                .accessibilityLabel("Send")
            }
        }
    }
}
