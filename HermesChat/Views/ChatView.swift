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
    @ObservedObject private var voice = VoiceConversationController.shared
    /// 글라스 사진 자동 전송 감시자 (Phase 16) — 세션 화면 수명 동안만 산다
    @StateObject private var photoWatcher = PhotoImportWatcher()
    /// 사진 권한 부족 안내 (제한 접근/거부) — nil이 아니면 알럿 표시
    @State private var photoAccessAlert: String?
    /// 받아쓰기 시작 시점의 입력창 내용 — 부분 결과가 갱신될 때마다 그 뒤에 이어 붙인다
    @State private var dictationBase = ""
    /// 현재 입력에 받아쓰기가 쓰였는지 — true면 전송 시 응답을 자동 낭독한다 (T-118)
    @State private var usedDictation = false

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
                if let err = viewModel.historyError {
                    Text(err)
                        .font(.caption)
                        .foregroundStyle(.red)
                        .padding(8)
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .background(.thinMaterial)
                }
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

            if voice.state != .idle {
                Divider()
                voiceStatusBanner
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
        .onChange(of: speech.transcript) { _, transcript in
            // 핸즈프리 부분 결과는 컨트롤러가 소비 — 입력창에는 받아쓰기만 반영
            guard voice.state == .idle, !transcript.isEmpty else { return }
            usedDictation = true
            viewModel.inputText = dictationBase.isEmpty
                ? transcript
                : dictationBase + " " + transcript
        }
        .onChange(of: viewModel.inputText) { _, text in
            // 입력을 비우면(전송 포함) 받아쓰기 흔적도 초기화
            if text.isEmpty { usedDictation = false }
        }
        .onChange(of: isInputFocused) { _, focused in
            // 자동 낭독 중 입력창을 만지면 조용히 멈춘다 — 끝까지 듣게 강요하지 않는다
            if focused, voice.state != .idle, !voice.handsFree { voice.stop() }
        }
        .onAppear {
            // 글라스 사진 도착 → 첨부 + 도착 음성 알림 + 후속 질문 흐름 (Phase 16)
            photoWatcher.onNewPhoto = { filename, data in
                viewModel.handleCapturedPhoto(filename: filename, data: data)
            }
        }
        .onDisappear {
            if speech.isRecording { speech.stopRecording() }
            if voice.state != .idle { voice.stop() }
            photoWatcher.stop()
            viewModel.glassesCaptureActive = false
        }
        .alert("사진 접근 권한", isPresented: .init(
            get: { photoAccessAlert != nil },
            set: { if !$0 { photoAccessAlert = nil } }
        )) {
            Button("확인", role: .cancel) {}
        } message: {
            Text(photoAccessAlert ?? "")
        }
        .photosPicker(
            isPresented: $showPhotoPicker,
            selection: $photoItems,
            maxSelectionCount: 5,
            matching: .images
        )
        .onChange(of: photoItems) { _, items in
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

    /// 글라스 사진 자동 전송 모드 토글 — 켤 때 전체 사진 접근을 요청하고, 부족하면 안내한다 (Phase 16)
    private func toggleGlassesCapture() {
        if viewModel.glassesCaptureActive {
            photoWatcher.stop()
            viewModel.glassesCaptureActive = false
            return
        }
        Task {
            switch await photoWatcher.start(since: .now) {
            case .authorized:
                viewModel.glassesCaptureActive = true
            case .limited:
                photoAccessAlert = "글라스 사진 자동 전송은 사진 '전체 접근' 권한이 필요합니다. "
                    + "설정 > 개인정보 보호 및 보안 > 사진에서 Hermes Chat을 '전체 접근'으로 바꿔주세요."
            case .denied:
                photoAccessAlert = "사진 접근 권한이 필요합니다. 설정 > 개인정보 보호 및 보안 > 사진에서 허용해주세요."
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

    /// 음성 대화 상태 배너 (T-118) — 듣는 중/생각 중/말하는 중 + 실시간 받아쓰기 한 줄
    private var voiceStatusBanner: some View {
        HStack(spacing: 8) {
            Image(systemName: voiceStatusIcon)
                .foregroundStyle(voice.state == .listening ? Color.red : Color.accentColor)
            Text(voiceStatusText)
                .font(.subheadline)
            if voice.state == .listening, !voice.liveTranscript.isEmpty {
                Text(voice.liveTranscript)
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
                    .lineLimit(1)
                    .truncationMode(.head)
            }
            Spacer()
            Button {
                voice.stop()
            } label: {
                Image(systemName: "xmark.circle.fill")
                    .foregroundStyle(.secondary)
            }
            .accessibilityLabel(voice.handsFree ? "음성 대화 종료" : "낭독 중지")
        }
        .padding(.horizontal)
        .padding(.vertical, 8)
        .background(.thinMaterial)
    }

    private var voiceStatusIcon: String {
        switch voice.state {
        case .listening: return "mic.fill"
        case .waitingResponse: return "ellipsis.bubble"
        case .speaking: return "speaker.wave.2.fill"
        case .idle: return "waveform"
        }
    }

    private var voiceStatusText: String {
        switch voice.state {
        case .listening: return "듣는 중…"
        case .waitingResponse: return "생각 중…"
        case .speaking: return "말하는 중…"
        case .idle: return ""
        }
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
            .onChange(of: viewModel.displayMessages.count) { scrollToBottom(proxy: proxy) }
            .onChange(of: viewModel.displayMessages.last?.content) { scrollToBottom(proxy: proxy) }
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
                                if let thumbnail = attachment.thumbnail {
                                    Image(uiImage: thumbnail)
                                        .resizable()
                                        .scaledToFill()
                                        .frame(width: 36, height: 36)
                                        .clipShape(RoundedRectangle(cornerRadius: 6, style: .continuous))
                                } else {
                                    Image(systemName: "paperclip")
                                }
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
                        if voice.state != .idle { voice.stop() }  // 자동 낭독 중이면 멈추고 받아쓰기
                        dictationBase = viewModel.inputText
                            .trimmingCharacters(in: .whitespacesAndNewlines)
                        Task { await speech.startRecording() }
                    }
                } label: {
                    Image(systemName: speech.isRecording ? "mic.fill" : "mic")
                        .font(.system(size: 20))
                        .foregroundStyle(speech.isRecording ? Color.red : Color.accentColor)
                }
                .disabled(viewModel.isWorking || voice.handsFree)
                .accessibilityLabel(speech.isRecording ? "받아쓰기 중지" : "음성 입력")

                Button {
                    if voice.handsFree {
                        voice.stop()
                    } else {
                        isInputFocused = false
                        Task { await voice.start(viewModel: viewModel) }
                    }
                } label: {
                    Image(systemName: voice.handsFree ? "waveform.slash" : "waveform")
                        .font(.system(size: 20))
                        .foregroundStyle(voice.handsFree ? Color.red : Color.accentColor)
                }
                .accessibilityLabel(voice.handsFree ? "음성 대화 종료" : "음성 대화 시작")

                Button {
                    toggleGlassesCapture()
                } label: {
                    Image(systemName: "eyeglasses")
                        .font(.system(size: 20))
                        .foregroundStyle(viewModel.glassesCaptureActive ? Color.green : Color.accentColor)
                }
                .accessibilityLabel(viewModel.glassesCaptureActive ? "글라스 사진 자동 전송 끄기" : "글라스 사진 자동 전송 켜기")

                TextField("메시지를 입력하세요", text: $viewModel.inputText, axis: .vertical)
                    .textFieldStyle(.roundedBorder)
                    .focused($isInputFocused)

                Button {
                    if speech.isRecording { speech.stopRecording() }  // 말하다 바로 전송해도 부드럽게
                    if usedDictation {
                        // 음성으로 물었으면 응답도 음성으로 — 문장 단위 자동 낭독 (T-118)
                        voice.autoRead(viewModel: viewModel)
                        usedDictation = false
                    }
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
