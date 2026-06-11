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
    @State private var forkedSession: Session?
    @State private var isForking = false
    @State private var forkError: String?
    @ObservedObject private var speech = SpeechService.shared
    /// 받아쓰기 시작 시점의 입력창 내용 — 부분 결과가 갱신될 때마다 그 뒤에 이어 붙인다
    @State private var dictationBase = ""

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
                    Text(workingStatusText)
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
            ToolbarItem(placement: .topBarTrailing) {
                Menu {
                    Button {
                        forkSession()
                    } label: {
                        Label("이 세션 분기", systemImage: "arrow.triangle.branch")
                    }
                    .disabled(isForking || viewModel.isWorking)
                } label: {
                    if isForking {
                        ProgressView()
                    } else {
                        Image(systemName: "ellipsis.circle")
                    }
                }
                .accessibilityLabel("세션 메뉴")
            }
            ToolbarItem(placement: .keyboard) {
                Button("완료") { isInputFocused = false }
            }
        }
        // 분기된 세션으로 push — 부모 NavigationStack(SessionListView)의 path를 건드리지 않는다
        .navigationDestination(item: $forkedSession) { session in
            ChatView(sessionId: session.id, appSettings: appSettings)
        }
        .alert("세션 분기 실패", isPresented: .init(
            get: { forkError != nil },
            set: { if !$0 { forkError = nil } }
        )) {
            Button("확인", role: .cancel) {}
        } message: {
            Text(forkError ?? "")
        }
        .alert("음성 입력 오류", isPresented: .init(
            get: { speech.errorMessage != nil },
            set: { if !$0 { speech.errorMessage = nil } }
        )) {
            Button("확인", role: .cancel) {}
        } message: {
            Text(speech.errorMessage ?? "")
        }
        .onChange(of: speech.transcript) { transcript in
            guard !transcript.isEmpty else { return }
            viewModel.inputText = dictationBase.isEmpty
                ? transcript
                : dictationBase + " " + transcript
        }
        .onDisappear {
            if speech.isRecording { speech.stopRecording() }
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

    private func forkSession() {
        guard !isForking else { return }
        isForking = true
        Task {
            do {
                forkedSession = try await appSettings.forkSession(id: sessionId)
            } catch {
                forkError = error.localizedDescription
            }
            isForking = false
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

    /// 사고 중(<think> 미닫힘)이면 "생각 중...", 보일 내용이 생기면 "응답 중" (T-103)
    private var workingStatusText: String {
        guard let last = viewModel.messages.last, last.role == .assistant else {
            return "응답 생성 중..."
        }
        if MarkdownLite.hasOpenThink(last.content) { return "생각 중..." }
        let visible = MarkdownLite.strippingThink(last.content)
            .trimmingCharacters(in: .whitespacesAndNewlines)
        return visible.isEmpty ? "응답 생성 중..." : "응답 중"
    }

    private var messageList: some View {
        ScrollViewReader { proxy in
            List {
                ForEach(viewModel.displayMessages) { message in
                    MessageBubble(message: message)
                        .listRowSeparator(.hidden)
                        .listRowInsets(.init(top: 6, leading: 0, bottom: 6, trailing: 0))
                        .listRowBackground(Color.clear)
                }
            }
            .listStyle(.plain)
            // 말풍선 썸네일(ChatImageView)이 Bridge로 이미지를 받도록 주입 (T-106)
            .environment(\.bridgeClient, appSettings.bridgeClient)
            .onChange(of: viewModel.displayMessages.count) { _ in scrollToBottom(proxy: proxy) }
            .onChange(of: viewModel.displayMessages.last?.content) { _ in scrollToBottom(proxy: proxy) }
        }
    }

    private func scrollToBottom(proxy: ScrollViewProxy) {
        guard let last = viewModel.displayMessages.last?.id else { return }
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

                Button {
                    if speech.isRecording {
                        speech.stopRecording()
                    } else {
                        dictationBase = viewModel.inputText
                            .trimmingCharacters(in: .whitespacesAndNewlines)
                        Task { await speech.startRecording() }
                    }
                } label: {
                    Image(systemName: speech.isRecording ? "mic.fill" : "mic")
                        .font(.system(size: 20))
                        .foregroundStyle(speech.isRecording ? Color.red : Color.accentColor)
                }
                .disabled(viewModel.isWorking)
                .accessibilityLabel(speech.isRecording ? "받아쓰기 중지" : "음성 입력")

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
