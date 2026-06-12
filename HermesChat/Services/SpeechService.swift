import AVFoundation
import Foundation
import Speech

/// 음성 입력(받아쓰기, T-100)과 응답 읽어주기(TTS, T-101)를 담당한다.
/// AVAudioSession은 이 클래스가 단일 소유한다 — 받아쓰기와 재생을 동시에 쓰지 않는다.
@MainActor
final class SpeechService: NSObject, ObservableObject {
    static let shared = SpeechService()

    @Published private(set) var isRecording = false
    /// 현재 받아쓰기 결과 (부분 결과 포함, 갱신될 때마다 전체 문자열로 교체됨)
    @Published private(set) var transcript = ""
    /// 지금 읽어주는 중인 메시지 id (nil이면 재생 없음)
    @Published private(set) var speakingMessageID: UUID?
    @Published var errorMessage: String?

    private let audioEngine = AVAudioEngine()
    private let recognizer = SFSpeechRecognizer(locale: Locale(identifier: "ko-KR"))
    private var recognitionRequest: SFSpeechAudioBufferRecognitionRequest?
    private var recognitionTask: SFSpeechRecognitionTask?
    private let synthesizer = AVSpeechSynthesizer()

    /// 음성 대화 모드(T-118) 동안 true — 세션·엔진을 모드 수명 내내 유지한다
    private(set) var voiceModeActive = false

    /// BT 기기 분리(oldDeviceUnavailable)로 입력 라우트를 잃었을 때 (T-117)
    var onRouteLost: (() -> Void)?
    /// 전화 등 인터럽션 종료 시 — 인자는 시스템의 재개 권고(shouldResume) (T-117)
    var onInterruptionEnded: ((Bool) -> Void)?

    private override init() {
        super.init()
        synthesizer.delegate = self
        registerSessionObservers()
    }

    // MARK: - 오디오 세션 (T-117)

    /// 세션 프로필 — 마이크가 필요한 모든 동작(받아쓰기·음성 모드)은 .voice,
    /// 단독 읽어주기는 .playback(A2DP 고음질 유지)
    private enum SessionProfile { case voice, playback }
    private var activeProfile: SessionProfile?

    /// 멱등 — 같은 프로필이면 setCategory를 다시 호출하지 않는다 (BT 라우트 재협상 방지)
    private func activateSession(_ profile: SessionProfile) throws {
        let session = AVAudioSession.sharedInstance()
        if activeProfile != profile {
            switch profile {
            case .voice:
                // HFP 고정: BT 마이크와 출력이 같은 링크 — 듣기↔말하기 전환 시 무재협상.
                // .allowBluetoothA2DP는 일부러 뺀다: playAndRecord+A2DP는 입력이 내장 마이크로 떨어짐
                try session.setCategory(
                    .playAndRecord, mode: .voiceChat,
                    options: [.allowBluetoothHFP, .defaultToSpeaker, .duckOthers]
                )
            case .playback:
                try session.setCategory(.playback, mode: .spokenAudio, options: .duckOthers)
            }
            activeProfile = profile
        }
        try session.setActive(true, options: .notifyOthersOnDeactivation)
    }

    /// 녹음·재생·음성 모드가 전부 아닐 때만 세션을 내려놓는다
    private func deactivateSessionIfIdle() {
        guard !isRecording, speakingMessageID == nil, !voiceModeActive else { return }
        try? AVAudioSession.sharedInstance().setActive(false, options: .notifyOthersOnDeactivation)
        activeProfile = nil
    }

    private func registerSessionObservers() {
        let center = NotificationCenter.default
        center.addObserver(
            forName: AVAudioSession.routeChangeNotification, object: nil, queue: .main
        ) { [weak self] note in
            guard
                let raw = note.userInfo?[AVAudioSessionRouteChangeReasonKey] as? UInt,
                let reason = AVAudioSession.RouteChangeReason(rawValue: raw)
            else { return }
            Task { @MainActor [weak self] in self?.handleRouteChange(reason) }
        }
        center.addObserver(
            forName: AVAudioSession.interruptionNotification, object: nil, queue: .main
        ) { [weak self] note in
            guard
                let raw = note.userInfo?[AVAudioSessionInterruptionTypeKey] as? UInt,
                let type = AVAudioSession.InterruptionType(rawValue: raw)
            else { return }
            let optionsRaw = note.userInfo?[AVAudioSessionInterruptionOptionKey] as? UInt ?? 0
            Task { @MainActor [weak self] in
                self?.handleInterruption(type, options: .init(rawValue: optionsRaw))
            }
        }
    }

    /// 에어팟 분리 등으로 쓰던 라우트가 사라지면 녹음을 정리하고 컨트롤러에 알린다
    private func handleRouteChange(_ reason: AVAudioSession.RouteChangeReason) {
        guard reason == .oldDeviceUnavailable else { return }
        let wasActive = isRecording || voiceModeActive
        if isRecording { stopRecording() }
        if wasActive { onRouteLost?() }
    }

    /// 전화 등 인터럽션 — 시작 시 전부 중단, 종료 시 재개 여부를 컨트롤러에 위임
    private func handleInterruption(
        _ type: AVAudioSession.InterruptionType,
        options: AVAudioSession.InterruptionOptions
    ) {
        switch type {
        case .began:
            if isRecording { stopRecording() }
            if speakingMessageID != nil || synthesizer.isSpeaking { stopSpeaking() }
        case .ended:
            onInterruptionEnded?(options.contains(.shouldResume))
        @unknown default:
            break
        }
    }

    // MARK: - 읽어주기 (TTS)

    /// 평문을 읽어준다. 받아쓰기·기존 재생 중이면 먼저 중단한다.
    func speak(_ text: String, messageID: UUID) {
        if isRecording { stopRecording() }
        if speakingMessageID != nil { stopSpeaking() }
        let plain = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !plain.isEmpty else { return }

        do {
            // 음성 모드 중에는 .voice 유지(라우트 재협상 방지), 평소엔 A2DP 고음질 재생
            try activateSession(voiceModeActive ? .voice : .playback)
        } catch {
            errorMessage = "오디오를 재생할 수 없습니다: \(error.localizedDescription)"
            return
        }

        let utterance = AVSpeechUtterance(string: plain)
        utterance.voice = AVSpeechSynthesisVoice(language: "ko-KR")
        speakingMessageID = messageID
        synthesizer.speak(utterance)
    }

    func stopSpeaking() {
        synthesizer.stopSpeaking(at: .immediate)
        finishSpeaking()
    }

    private func finishSpeaking() {
        speakingMessageID = nil
        deactivateSessionIfIdle()
    }

    // MARK: - 받아쓰기

    func startRecording() async {
        guard !isRecording else { return }
        errorMessage = nil

        guard await Self.requestSpeechAuthorization(), await Self.requestMicPermission() else {
            errorMessage = "설정 > 개인정보 보호에서 마이크와 음성 인식 권한을 허용해주세요."
            return
        }
        guard let recognizer, recognizer.isAvailable else {
            errorMessage = "음성 인식을 지금 사용할 수 없습니다."
            return
        }

        do {
            // .voice 프로필: BT HFP 마이크 허용(T-102) + 통합 세션으로 라우트 재협상 방지(T-117)
            try activateSession(.voice)

            let request = SFSpeechAudioBufferRecognitionRequest()
            request.shouldReportPartialResults = true
            recognitionRequest = request

            let inputNode = audioEngine.inputNode
            let format = inputNode.outputFormat(forBus: 0)
            inputNode.removeTap(onBus: 0)
            inputNode.installTap(onBus: 0, bufferSize: 1024, format: format) { buffer, _ in
                request.append(buffer)
            }
            audioEngine.prepare()
            try audioEngine.start()

            transcript = ""
            isRecording = true

            recognitionTask = recognizer.recognitionTask(with: request) { [weak self] result, error in
                Task { @MainActor [weak self] in
                    guard let self else { return }
                    if let result {
                        self.transcript = result.bestTranscription.formattedString
                    }
                    if error != nil || result?.isFinal == true {
                        self.stopRecording()
                    }
                }
            }
        } catch {
            errorMessage = "녹음을 시작할 수 없습니다: \(error.localizedDescription)"
            stopRecording()
        }
    }

    /// 멱등 — 인식 태스크의 종료 콜백에서 재진입해도 안전하다.
    func stopRecording() {
        audioEngine.stop()
        audioEngine.inputNode.removeTap(onBus: 0)
        recognitionRequest?.endAudio()
        recognitionRequest = nil
        let task = recognitionTask
        recognitionTask = nil
        task?.cancel()
        isRecording = false
        deactivateSessionIfIdle()
    }

    // MARK: - 권한

    nonisolated private static func requestSpeechAuthorization() async -> Bool {
        await withCheckedContinuation { continuation in
            SFSpeechRecognizer.requestAuthorization { status in
                continuation.resume(returning: status == .authorized)
            }
        }
    }

    nonisolated private static func requestMicPermission() async -> Bool {
        await AVAudioApplication.requestRecordPermission()
    }
}

extension SpeechService: AVSpeechSynthesizerDelegate {
    nonisolated func speechSynthesizer(
        _ synthesizer: AVSpeechSynthesizer, didFinish utterance: AVSpeechUtterance
    ) {
        Task { @MainActor in self.finishSpeaking() }
    }

    nonisolated func speechSynthesizer(
        _ synthesizer: AVSpeechSynthesizer, didCancel utterance: AVSpeechUtterance
    ) {
        Task { @MainActor in self.finishSpeaking() }
    }
}
