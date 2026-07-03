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

    private let appSettings: AppSettings
    private let store = LiveSessionStore.shared
    private var service: GeminiLiveService?

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

    /// Hermes 백엔드 연결 (T-160 자리 — T-162에서 HermesLiveService로 대체)
    private func startHermesConnection() {
        errorBanner = "Hermes 음성 백엔드는 다음 업데이트에서 활성화됩니다."
        state = .disconnected
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
        service?.disconnect()
        service = nil
        finalizeBubbles()
        persist()
        state = .disconnected
    }

    private func wire(_ svc: GeminiLiveService) {
        svc.onConnected = { [weak self] in
            guard let self else { return }
            self.state = .listening
            self.service?.startRecording()
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
            self.service?.disconnect()
            self.service = nil
        }
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
