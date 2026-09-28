import AVFoundation
import Foundation
import SwiftUI

/// Live 탭(Pure Gemini Live) 오케스트레이션 (T-155).
/// `GeminiLiveService`(speech-to-speech)를 구동하고, 양측 자막을 `ChatMessage` 버블로 누적해
/// 화면에 보여주며, 대화를 `LiveSessionStore`에 로컬 저장한다(이어가기·검색 지원).
@MainActor
final class LiveViewModel: ObservableObject {
    @Published private(set) var state: LiveConnectionState = .disconnected
    @Published private(set) var messages: [ChatMessage] = []
    /// 사용자에게 보여줄 일시적 오류 배너
    @Published var errorBanner: String?
    /// 글라스 사진·영상 감시 중 (Gemini 연결 중 + 전체 사진 접근일 때)
    @Published private(set) var isWatchingMedia = false

    private let appSettings: AppSettings
    private let store = LiveSessionStore.shared
    private var service: GeminiLiveService?
    private var hermesService: HermesLiveService?
    /// 글라스 촬영물이 카메라 롤에 동기화되면 Gemini에 바로 보여준다
    private let mediaWatcher = PhotoImportWatcher()

    /// 현재 편집 중인 LiveSession (저장 단위)
    private var session: LiveSession
    /// 누적 중인 사용자/모델 버블 id (자막 델타를 같은 버블에 합친다)
    private var currentUserID: UUID?
    private var currentAssistantID: UUID?

    var isConnected: Bool { state.isActive }

    /// 새 대화로 시작
    init(appSettings: AppSettings) {
        self.appSettings = appSettings
        self.session = LiveSession(
            voice: appSettings.geminiLiveVoice,
            backend: appSettings.liveVoiceBackend
        )
    }

    /// 저장된 대화 이어가기 — 기존 메시지를 싣고 연결 시 컨텍스트로 시드한다
    func load(_ existing: LiveSession) {
        session = existing
        messages = existing.messages
    }

    /// 연결 전에 보이스 선택 — 연결 중에는 무시(서버 setup에 고정됨)
    func setVoice(_ voice: String) {
        guard case .disconnected = state else { return }
        session.voice = voice
    }

    /// 연결 전에 백엔드 선택 (T-160) — 연결 중에는 무시
    func setBackend(_ backend: LiveVoiceBackend) {
        guard case .disconnected = state else { return }
        session.backend = backend
    }

    // MARK: - 연결 제어

    func connect() {
        guard case .disconnected = state else { return }

        // 백엔드별 사전 검증 (T-160)
        let key = appSettings.geminiAPIKey.trimmingCharacters(in: .whitespacesAndNewlines)
        if session.backend == .gemini, key.isEmpty {
            errorBanner = "설정 > Gemini Live에서 API 키를 입력하세요."
            return
        }

        state = .connecting
        Task {
            // 마이크 권한을 실제로 요청해야 iOS 설정 > 개인정보 보호에 앱이 등록된다.
            // 기존엔 GeminiLiveService.startRecording()에서 권한 상태만 확인하고 요청은
            // 한 번도 하지 않아 .undetermined 상태로 남아 설정 목록에 안 보이는 문제가 있었다.
            guard await AVAudioApplication.requestRecordPermission() else {
                self.errorBanner = "마이크 권한이 필요합니다. 설정 > 개인정보 보호 및 보안 > 마이크에서 허용해주세요."
                self.state = .disconnected
                return
            }
            switch self.session.backend {
            case .gemini: self.startConnection(apiKey: key)
            case .hermes: self.startHermesConnection()
            }
        }
    }

    /// Hermes 백엔드 연결 (T-162) — 온디바이스 STT → 게이트웨이 SSE → 문장 단위 TTS
    private func startHermesConnection() {
        guard case .connecting = state else { return }

        // TTS 프로바이더 구성: 서버 모드 + 유효한 엔드포인트일 때만 서버 TTS, 아니면 로컬
        let tts: LiveTTSProvider
        let endpointString = appSettings.hermesLiveTTSEndpoint.trimmingCharacters(in: .whitespacesAndNewlines)
        if appSettings.hermesLiveTTSMode == .server, !endpointString.isEmpty, let endpoint = URL(string: endpointString) {
            tts = ServerTTSProvider(
                endpoint: endpoint,
                apiKey: appSettings.hermesClient.apiKey,
                voice: appSettings.hermesLiveTTSVoice,
                fallback: LocalTTSProvider()
            )
        } else {
            tts = LocalTTSProvider()
        }

        let svc = HermesLiveService(
            client: appSettings.hermesClient,
            existingSessionId: session.hermesSessionId,
            systemPrompt: appSettings.hermesLiveSystemPrompt,
            tts: tts
        )
        wireHermes(svc)
        hermesService = svc
        Task { await svc.start() }
    }

    private func wireHermes(_ svc: HermesLiveService) {
        svc.onStateChange = { [weak self] hermesState in
            guard let self, self.hermesService != nil else { return }
            switch hermesState {
            case .idle:
                // 서비스가 스스로 종료(오류·무발화 타임아웃 등) — 대화 내용은 보존
                self.finalizeBubbles()
                self.persist()
                self.state = .disconnected
            case .connecting:      self.state = .connecting
            case .listening:       self.state = .listening
            case .waitingResponse: self.state = .thinking
            case .speaking:        self.state = .speaking
            }
        }
        svc.onUserUtterance = { [weak self] text in
            guard let self else { return }
            self.finalizeBubbles()
            self.messages.append(ChatMessage(role: .user, content: text))
        }
        svc.onAssistantText = { [weak self] visible in
            self?.replaceAssistant(with: visible)
        }
        svc.onTurnComplete = { [weak self] in
            self?.finalizeBubbles()
            self?.persist()
        }
        svc.onSessionEstablished = { [weak self] id in
            guard let self else { return }
            self.session.hermesSessionId = id
            self.persist()
        }
        svc.onNotice = { [weak self] notice in
            self?.errorBanner = notice
        }
        svc.onError = { [weak self] message in
            self?.errorBanner = message
        }
    }

    /// Hermes 백엔드 barge-in — 낭독을 끊고 바로 재청취 (T-162)
    func interrupt() {
        hermesService?.interrupt()
    }

    private func startConnection(apiKey: String) {
        guard case .connecting = state else { return }

        // 세션 음성(Hermes TTS/받아쓰기)이 오디오 세션을 잡고 있으면 먼저 정리
        VoiceConversationController.shared.stop()

        let svc = GeminiLiveService(
            apiKey: apiKey,
            model: appSettings.geminiLiveModel,
            voice: session.voice,
            systemPrompt: appSettings.geminiLiveSystemPrompt,
            seedTurns: Array(messages.suffix(12))   // 재개: 최근 12턴만 컨텍스트로
        )
        wire(svc)
        service = svc
        svc.connect()
    }

    func disconnect() {
        stopMediaWatch()
        service?.disconnect()
        service = nil
        hermesService?.stop()
        hermesService = nil
        finalizeBubbles()
        persist()
        state = .disconnected
    }

    private func wire(_ svc: GeminiLiveService) {
        svc.onConnected = { [weak self] in
            guard let self else { return }
            self.state = .listening
            self.service?.startRecording()
            self.startMediaWatch()
        }
        svc.onUserTranscript = { [weak self] delta in self?.appendUser(delta) }
        svc.onModelTranscript = { [weak self] delta in self?.appendAssistant(delta) }
        svc.onSpeakingStarted = { [weak self] in
            if self?.state != .disconnected { self?.state = .speaking }
        }
        svc.onSpeakingStopped = { [weak self] in
            if self?.state == .speaking { self?.state = .listening }
        }
        svc.onTurnComplete = { [weak self] in
            self?.finalizeBubbles()
            self?.persist()
        }
        svc.onError = { [weak self] message in
            guard let self else { return }
            self.errorBanner = message
            self.state = .error(message)
            self.stopMediaWatch()
            self.service?.disconnect()
            self.service = nil
        }
    }

    // MARK: - 글라스 사진·영상

    private func startMediaWatch() {
        mediaWatcher.onNewPhoto = { [weak self] _, data in self?.sendPhoto(data) }
        mediaWatcher.onNewVideo = { [weak self] asset in self?.sendVideo(asset) }
        mediaWatcher.onNotice = { [weak self] notice in self?.errorBanner = notice }
        Task {
            let result = await mediaWatcher.start(since: .now)
            guard self.service != nil else { mediaWatcher.stop(); return }   // 권한 대기 중 종료됨
            switch result {
            case .authorized: isWatchingMedia = true
            case .limited: errorBanner = "글라스 사진을 보려면 설정 > 사진에서 '전체 접근'을 허용하세요."
            case .denied: errorBanner = "사진 접근이 거부돼 글라스 사진·영상은 볼 수 없어요. 음성 대화는 계속됩니다."
            }
        }
    }

    private func stopMediaWatch() {
        mediaWatcher.stop()
        isWatchingMedia = false
    }

    private func sendPhoto(_ data: Data) {
        guard let image = UIImage(data: data), let jpeg = Self.jpeg(image, maxSide: 1024) else { return }
        deliverMedia([jpeg], label: "📷 글라스 사진",
                     prompt: "방금 글라스로 찍은 사진이야. 무엇이 보이는지 짧게 말해줘.")
    }

    private func sendVideo(_ asset: AVAsset?) {
        guard let asset else {
            errorBanner = "글라스 영상을 불러오지 못했어요."
            return
        }
        Task {
            let frames: [Data]
            do {
                frames = try await Self.sampleFrames(asset)
            } catch {
                errorBanner = "영상 프레임 추출 실패: \(error.localizedDescription)"
                return
            }
            deliverMedia(frames, label: "🎥 글라스 영상 (\(frames.count)프레임)",
                         prompt: "방금 글라스로 찍은 짧은 영상에서 시간순으로 뽑은 프레임들이야. 무슨 장면인지 짧게 말해줘.")
        }
    }

    private func deliverMedia(_ jpegs: [Data], label: String, prompt: String) {
        guard let service else { return }
        finalizeBubbles()
        messages.append(ChatMessage(role: .user, content: label))
        persist()
        UINotificationFeedbackGenerator().notificationOccurred(.success)
        service.sendImages(jpegs, prompt: prompt)
    }

    /// 영상에서 1초당 1장, 최대 10장을 균등 추출한다.
    /// ponytail: 오디오 트랙은 무시(프레임만). 영상 속 말소리까지 필요하면 PCM 추출해 realtimeInput.audio로 추가.
    private static func sampleFrames(_ asset: AVAsset) async throws -> [Data] {
        let duration = try await asset.load(.duration).seconds
        guard duration > 0 else { throw CocoaError(.fileReadCorruptFile) }
        let count = min(10, max(1, Int(duration.rounded(.up))))
        let generator = AVAssetImageGenerator(asset: asset)
        generator.appliesPreferredTrackTransform = true
        generator.maximumSize = CGSize(width: 768, height: 768)
        var frames: [Data] = []
        var lastError: Error?
        for i in 0..<count {
            let t = CMTime(seconds: duration * (Double(i) + 0.5) / Double(count), preferredTimescale: 600)
            do {
                let cg = try await generator.image(at: t).image
                if let jpeg = UIImage(cgImage: cg).jpegData(compressionQuality: 0.7) { frames.append(jpeg) }
            } catch {
                lastError = error
            }
        }
        if frames.isEmpty { throw lastError ?? CocoaError(.fileReadCorruptFile) }
        return frames
    }

    private static func jpeg(_ image: UIImage, maxSide: CGFloat) -> Data? {
        let longSide = max(image.size.width, image.size.height)
        guard longSide > maxSide else { return image.jpegData(compressionQuality: 0.7) }
        let scale = maxSide / longSide
        let size = CGSize(width: (image.size.width * scale).rounded(), height: (image.size.height * scale).rounded())
        let format = UIGraphicsImageRendererFormat.default()
        format.scale = 1
        return UIGraphicsImageRenderer(size: size, format: format)
            .image { _ in image.draw(in: CGRect(origin: .zero, size: size)) }
            .jpegData(compressionQuality: 0.7)
    }

    // MARK: - 버블 누적
    // 자막은 증분 델타로 도착한다고 보고 같은 버블에 이어 붙인다.

    private func appendUser(_ delta: String) {
        currentAssistantID = nil  // 사용자가 말하기 시작하면 모델 턴은 종료
        if let id = currentUserID, let idx = messages.firstIndex(where: { $0.id == id }) {
            messages[idx].content += delta
        } else {
            let msg = ChatMessage(role: .user, content: delta)
            currentUserID = msg.id
            messages.append(msg)
        }
        if state != .disconnected, state != .speaking { state = .listening }
    }

    private func appendAssistant(_ delta: String) {
        currentUserID = nil  // 모델이 말하기 시작하면 사용자 턴 종료
        if let id = currentAssistantID, let idx = messages.firstIndex(where: { $0.id == id }) {
            messages[idx].content += delta
        } else {
            let msg = ChatMessage(role: .assistant, content: delta)
            currentAssistantID = msg.id
            messages.append(msg)
        }
    }

    /// Hermes 백엔드용 (T-162) — 델타가 아니라 누적 전체 텍스트로 현재 어시스턴트 버블을 교체
    private func replaceAssistant(with visible: String) {
        currentUserID = nil
        if let id = currentAssistantID, let idx = messages.firstIndex(where: { $0.id == id }) {
            messages[idx].content = visible
        } else {
            let msg = ChatMessage(role: .assistant, content: visible)
            currentAssistantID = msg.id
            messages.append(msg)
        }
    }

    private func finalizeBubbles() {
        currentUserID = nil
        currentAssistantID = nil
    }

    // MARK: - 저장

    private func persist() {
        session.messages = messages
        store.save(session)
    }
}
