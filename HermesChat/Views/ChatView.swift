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
    @ObservedObject private var coordinator = VoiceEntryCoordinator.shared
    /// 외부 진입(Siri·위젯·URL·글라스)의 arm을 보이는 뷰에서만 소비하기 위한 가시성 추적 (T-131)
    @State private var isVisible = false
    /// Live 전체화면(T-152) 표시 여부 — 핸즈프리 진입 시 자동으로 뜨고, 최소화하면 내려간다
    @State private var showLive = false
    /// 글라스 사진 자동 전송 감시자 (Phase 16) — 세션 화면 수명 동안만 산다
    @StateObject private var photoWatcher = PhotoImportWatcher()
    /// 사진 권한 부족 안내 (제한 접근/거부) — nil이 아니면 알럿 표시
    @State private var photoAccessAlert: String?

    init(sessionId: String, appSettings: AppSettings) {
        self.sessionId = sessionId
        self.appSettings = appSettings
        self._viewModel = StateObject(wrappedValue: .init(sessionId: sessionId, appSettings: appSettings))
    }

    var body: some View {
        VStack(spacing: 0) {
            if viewModel.isLoadingHistory {
                ProgressView("chat.loading_history")
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

            if viewModel.glassesCaptureActive {
                Divider()
                glassesStatusBanner
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
                        Label("chat.fork_session", systemImage: "arrow.triangle.branch")
                    }
                    .disabled(isForking || viewModel.isWorking)

                    // 채팅 안에서 다른 프로필의 새 세션으로 바로 전환 (T-151). 데모 모드에선 숨김.
                    let otherProfiles = appSettings.profiles.filter { $0.id != appSettings.selectedProfile.id }
                    if !appSettings.isDemoMode, !otherProfiles.isEmpty {
                        Section("chat.switch.title") {
                            ForEach(otherProfiles) { profile in
                                Button {
                                    switchProfile(to: profile)
                                } label: {
                                    Label(profile.name, systemImage: "person.crop.circle")
                                }
                                // 진행 중 프로필이 바뀌면 폴백 폴링이 엉뚱한 게이트웨이로 간다
                                .disabled(isForking || viewModel.isWorking)
                            }
                        }
                    }
                } label: {
                    if isForking {
                        ProgressView()
                    } else {
                        Image(systemName: "ellipsis.circle")
                    }
                }
                .accessibilityLabel("chat.session_menu")
            }
        }
        // 분기된 세션으로 push — 부모 NavigationStack(SessionListView)의 path를 건드리지 않는다
        .navigationDestination(item: $forkedSession) { session in
            ChatView(sessionId: session.id, appSettings: appSettings)
        }
        .alert("chat.fork_failed", isPresented: .init(
            get: { forkError != nil },
            set: { if !$0 { forkError = nil } }
        )) {
            Button("common.ok", role: .cancel) {}
        } message: {
            Text(forkError ?? "")
        }
        .alert("chat.voice_error", isPresented: .init(
            get: { speech.errorMessage != nil },
            set: { if !$0 { speech.errorMessage = nil } }
        )) {
            Button("common.ok", role: .cancel) {}
        } message: {
            Text(speech.errorMessage ?? "")
        }
        .onAppear {
            isVisible = true
            // 외부 진입으로 arm된 경우 — 실제 viewModel이 준비된 지금 음성 모드 시작 (T-131)
            startVoiceIfArmed()
            // idle이어도 글라스 더블탭(AVRCP)으로 음성을 바로 켤 수 있도록 리모트 커맨드 무장 (T-134)
            voice.armRemoteControl(viewModel: viewModel)
            // 글라스 사진 도착 → 첨부 + 도착 음성 알림 + 후속 질문 흐름 (Phase 16)
            photoWatcher.onNewPhoto = { filename, data in
                viewModel.handleCapturedPhoto(filename: filename, data: data)
            }
        }
        // 이미 보이는 채팅에 진입 요청이 들어온 경우(탭 전환 없이) onChange로 반응 (T-131)
        .onChange(of: coordinator.armChatVoiceStart) { _, _ in
            guard isVisible else { return }
            startVoiceIfArmed()
        }
        // 음성 세션이 끝나 idle로 돌아와도 화면이 떠 있으면 다시 무장 — 더블탭 재시작 보장 (T-134)
        .onChange(of: voice.state) { _, state in
            if state == .idle, isVisible { voice.armRemoteControl(viewModel: viewModel) }
        }
        // 핸즈프리가 켜지면(waveform 버튼·Siri·위젯·글라스 모두) 이 화면이 보이고 내 세션일 때 Live 표시 (T-152)
        .onChange(of: voice.handsFree) { _, on in
            if on {
                if isVisible, voice.boundSessionId == viewModel.sessionId { showLive = true }
            } else {
                showLive = false
            }
        }
        .fullScreenCover(isPresented: $showLive, onDismiss: {
            // cover가 내려갔는데 화면이 이미 사라진 상태(뒤로가기 등)면 그때 음성을 정리한다
            if !isVisible { cleanupVoice() }
        }) {
            LiveVoiceView(viewModel: viewModel, title: liveTitle)
        }
        .onDisappear {
            // cover가 뜰 때도 presenter의 onDisappear가 불릴 수 있다 — 그때는 화면이 사라진 게
            // 아니므로 isVisible도 유지하고 음성도 끊지 않는다 (T-152)
            guard !showLive else { return }
            isVisible = false
            cleanupVoice()
        }
        .alert("chat.photo_access.title", isPresented: .init(
            get: { photoAccessAlert != nil },
            set: { if !$0 { photoAccessAlert = nil } }
        )) {
            Button("common.ok", role: .cancel) {}
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

    /// 화면을 떠날 때의 음성·글라스 정리 (T-152: onDisappear에서 추출)
    private func cleanupVoice() {
        // 내 세션에 묶인 음성만 정리 — 라우팅 재진입으로 다른 ChatView가 이미
        // 시작한 음성은 끄지 않는다 (T-131)
        if voice.state != .idle, voice.boundSessionId == viewModel.sessionId { voice.stop() }
        // idle 리모트 무장 해제 (내 세션에 묶여 있을 때만) (T-134)
        voice.disarmRemoteControl(for: viewModel.sessionId)
        photoWatcher.stop()
        viewModel.glassesCaptureActive = false
    }

    /// Live 화면 제목 — 세션 제목이 있으면 그것, 없으면 선택된 프로필 이름
    private var liveTitle: String {
        let sessionTitle = appSettings.sessions.first(where: { $0.id == sessionId })?.title
        if let sessionTitle, !sessionTitle.isEmpty { return sessionTitle }
        return appSettings.selectedProfile.name
    }

    /// 다른 프로필의 새 세션으로 전환 — AppDefaults/HermesChatApp의 라우팅과 같은 방식으로
    /// 세션 탭 경로를 새 세션 하나로 교체한다 (T-151). 실패 시 분기 실패 알럿 재사용.
    private func switchProfile(to profile: HermesProfile) {
        guard !isForking, !viewModel.isWorking else { return }
        isForking = true
        let previous = appSettings.selectedProfile  // 실패 시 복원용
        Task {
            do {
                appSettings.selectProfile(profile)
                let session = try await appSettings.createSession()
                coordinator.sessionsPath = NavigationPath()
                coordinator.sessionsPath.append(session)
            } catch {
                if previous.id != profile.id { appSettings.selectProfile(previous) }
                forkError = error.localizedDescription
            }
            isForking = false
        }
    }

    /// 외부 진입(Siri·위젯·URL·글라스)으로 arm된 음성 시작 신호를 1회 소비해 핸즈프리 모드 진입 (T-131)
    private func startVoiceIfArmed() {
        guard coordinator.consumeChatVoiceStart() else { return }
        Task { await voice.start(viewModel: viewModel) }
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
            viewModel.resetGlassesStatus()
            return
        }
        Task {
            switch await photoWatcher.start(since: .now) {
            case .authorized:
                viewModel.glassesCaptureActive = true
            case .limited:
                photoAccessAlert = String(localized: "chat.photo_access.limited")
            case .denied:
                photoAccessAlert = String(localized: "chat.photo_access.denied")
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

    private var workingStatusText: String {
        guard let last = viewModel.messages.last, last.role == .assistant else {
            return String(localized: "chat.working.generating")
        }
        if MarkdownLite.hasOpenThink(last.content) { return String(localized: "chat.working.thinking") }
        let visible = MarkdownLite.strippingThink(last.content)
            .trimmingCharacters(in: .whitespacesAndNewlines)
        return visible.isEmpty ? String(localized: "chat.working.generating") : String(localized: "chat.working.responding")
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
            .accessibilityLabel(voice.handsFree ? String(localized: "chat.voice.stop") : String(localized: "chat.voice.read_stop"))
        }
        .padding(.horizontal)
        .padding(.vertical, 8)
        .background(.thinMaterial)
        // 최소화한 Live를 배너 탭으로 다시 연다 (핸즈프리 + 내 세션일 때만) (T-152)
        .contentShape(Rectangle())
        .onTapGesture {
            if voice.handsFree, voice.boundSessionId == viewModel.sessionId { showLive = true }
        }
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
        case .listening: return String(localized: "chat.voice.listening")
        case .waitingResponse: return String(localized: "chat.voice.thinking")
        case .speaking: return String(localized: "chat.voice.speaking")
        case .idle: return ""
        }
    }

    /// 글라스 사진 모드 상태 배너 (T-129) — 서버 응답과 무관하게 감시/감지 상태를 보여준다
    private var glassesStatusBanner: some View {
        HStack(spacing: 8) {
            Image(systemName: viewModel.lastGlassesPhotoName == nil ? "eyeglasses" : "camera.fill")
                .foregroundStyle(Color.green)
            if let name = viewModel.lastGlassesPhotoName {
                Text(verbatim: String(format: NSLocalizedString("chat.glasses.detected", comment: ""), viewModel.glassesPhotosDetected, name))
                    .font(.subheadline)
                    .lineLimit(1)
                    .truncationMode(.middle)
            } else {
                Text("chat.glasses.watching")
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
            }
            Spacer()
        }
        .padding(.horizontal)
        .padding(.vertical, 8)
        .background(.thinMaterial)
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
                        Label("chat.attach.photo", systemImage: "photo")
                    }
                    Button {
                        showFileImporter = true
                    } label: {
                        Label("chat.attach.file", systemImage: "folder")
                    }
                } label: {
                    Image(systemName: "plus.circle.fill")
                        .font(.system(size: 22))
                }
                .disabled(viewModel.isWorking)
                .accessibilityLabel("chat.attach.add")

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
                .accessibilityLabel(voice.handsFree ? String(localized: "chat.voice.stop") : String(localized: "chat.voice.start"))

                Button {
                    toggleGlassesCapture()
                } label: {
                    Image(systemName: "eyeglasses")
                        .font(.system(size: 20))
                        .foregroundStyle(viewModel.glassesCaptureActive ? Color.green : Color.accentColor)
                }
                .accessibilityLabel(viewModel.glassesCaptureActive ? String(localized: "chat.glasses.disable") : String(localized: "chat.glasses.enable"))

                TextField("chat.message.placeholder", text: $viewModel.inputText, axis: .vertical)
                    .textFieldStyle(.roundedBorder)
                    .focused($isInputFocused)

                Button {
                    if viewModel.isWorking {
                        viewModel.cancelSend()  // 응답 중단 (T-150)
                    } else {
                        Task { await viewModel.send() }
                        isInputFocused = false
                    }
                } label: {
                    Image(systemName: viewModel.isWorking ? "stop.circle.fill" : "paperplane.fill")
                        .font(.system(size: 18, weight: .semibold))
                }
                .disabled(
                    !viewModel.isWorking
                        && viewModel.inputText.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
                        && viewModel.attachments.isEmpty
                )
                .accessibilityLabel(viewModel.isWorking ? LocalizedStringKey("chat.stop") : LocalizedStringKey("Send"))
            }
        }
    }
}
