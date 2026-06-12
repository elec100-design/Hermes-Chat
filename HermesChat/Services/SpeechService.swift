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
    /// 음성 모드에서 인식 탭이 설치되어 청취 중인지 (T-118)
    private(set) var isVoiceListening = false
    /// 컨트롤러 주도 문장 큐 낭독이 진행 중인지 — 자동 낭독·핸즈프리 공용 (T-118)
    private(set) var isReadingSentences = false

    /// BT 기기 분리(oldDeviceUnavailable)로 입력 라우트를 잃었을 때 (T-117)
    var onRouteLost: (() -> Void)?
    /// 전화 등 인터럽션 시작 — 음성 모드 정리는 컨트롤러 몫 (T-118)
    var onInterruptionBegan: (() -> Void)?
    /// 전화 등 인터럽션 종료 시 — 인자는 시스템의 재개 권고(shouldResume) (T-117)
    var onInterruptionEnded: ((Bool) -> Void)?
    /// 문장 큐의 utterance 하나가 끝나거나 취소될 때마다 (T-118)
    var onSentenceFinished: (() -> Void)?
    /// 음성 모드 청취가 스스로 끝났을 때 — SFSpeech 1분 한도·인식 오류 (T-118)
    var onVoiceListenEnded: (() -> Void)?
    /// 단독 읽어주기/받아쓰기가 음성 모드를 선점할 때 — 컨트롤러가 stop()으로 정리 (T-118)
    var onPreempted: (() -> Void)?

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

    /// 녹음·재생·음성 모드·문장 낭독이 전부 아닐 때만 세션을 내려놓는다
    private func deactivateSessionIfIdle() {
        guard !isRecording, speakingMessageID == nil, !voiceModeActive, !isReadingSentences
        else { return }
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
            onInterruptionBegan?()
        case .ended:
            onInterruptionEnded?(options.contains(.shouldResume))
        @unknown default:
            break
        }
    }

    // MARK: - 음성 대화 모드 (T-118)

    /// 음성 모드 오류 — 컨트롤러가 사용자 메시지로 변환한다
    enum VoiceModeError: LocalizedError {
        case recognizerUnavailable
        var errorDescription: String? { "음성 인식을 지금 사용할 수 없습니다." }
    }

    /// 음성 모드 시작 — .voice 세션 + 엔진 상시 가동(폐기 탭). 턴마다 엔진을 껐다 켜면
    /// BT 라우트가 출렁이므로 모드 수명 내내 유지하고 탭만 교체한다. LLM 응답 대기 중에도
    /// 오디오 I/O가 살아 있어 백그라운드에서 앱이 정지되지 않는다(T-120 audio 모드의 전제).
    /// 그동안 마이크 프라이버시 표시등이 계속 켜진다 — 의도된 동작.
    func voiceModeBegin() throws {
        guard !voiceModeActive else { return }
        if isRecording { stopRecording() }
        if speakingMessageID != nil { stopSpeaking() }
        try activateSession(.voice)
        installDiscardTap()
        audioEngine.prepare()
        try audioEngine.start()
        voiceModeActive = true
    }

    func voiceModeEnd() {
        guard voiceModeActive else { return }
        voiceListenStop()
        audioEngine.stop()
        audioEngine.inputNode.removeTap(onBus: 0)
        voiceModeActive = false
        deactivateSessionIfIdle()
    }

    /// 엔진은 그대로 두고 탭만 인식 요청으로 교체해 청취를 시작한다
    func voiceListenStart() throws {
        guard voiceModeActive, !isVoiceListening else { return }
        guard let recognizer, recognizer.isAvailable else {
            throw VoiceModeError.recognizerUnavailable
        }

        let request = SFSpeechAudioBufferRecognitionRequest()
        request.shouldReportPartialResults = true
        recognitionRequest = request

        let inputNode = audioEngine.inputNode
        let format = inputNode.outputFormat(forBus: 0)
        inputNode.removeTap(onBus: 0)
        inputNode.installTap(onBus: 0, bufferSize: 1024, format: format) { buffer, _ in
            request.append(buffer)
        }

        transcript = ""
        isVoiceListening = true

        recognitionTask = recognizer.recognitionTask(with: request) { [weak self] result, error in
            Task { @MainActor [weak self] in
                guard let self, self.isVoiceListening else { return }
                if let result {
                    self.transcript = result.bestTranscription.formattedString
                }
                if error != nil || result?.isFinal == true {
                    self.voiceListenStop()
                    self.onVoiceListenEnded?()
                }
            }
        }
    }

    /// 멱등 — 인식만 끝내고 탭을 폐기 탭으로 환원, 엔진·세션은 유지한다
    func voiceListenStop() {
        guard isVoiceListening else { return }
        isVoiceListening = false
        recognitionRequest?.endAudio()
        recognitionRequest = nil
        let task = recognitionTask
        recognitionTask = nil
        task?.cancel()
        if voiceModeActive { installDiscardTap() }
    }

    /// 인식하지 않을 때 엔진을 살려 두기 위한 버퍼 폐기 탭
    private func installDiscardTap() {
        let inputNode = audioEngine.inputNode
        let format = inputNode.outputFormat(forBus: 0)
        inputNode.removeTap(onBus: 0)
        inputNode.installTap(onBus: 0, bufferSize: 1024, format: format) { _, _ in }
    }

    // MARK: - 문장 큐 낭독 (T-118, 자동 낭독·핸즈프리 공용)

    /// 문장 큐 낭독 시작 — 핸즈프리면 .voice 유지(라우트 무재협상), 자동 낭독이면 A2DP 고음질
    func beginSentenceReading() throws {
        if speakingMessageID != nil { stopSpeaking() }
        try activateSession(voiceModeActive ? .voice : .playback)
        isReadingSentences = true
    }

    /// 완성된 문장 하나를 합성 큐에 추가한다 — 세션 재구성 없음
    func enqueueSentence(_ text: String) {
        guard isReadingSentences else { return }
        let utterance = AVSpeechUtterance(string: text)
        utterance.voice = AVSpeechSynthesisVoice(language: "ko-KR")
        synthesizer.speak(utterance)
    }

    /// 낭독 종료 — cancel이면 현재 문장과 큐를 즉시 비운다
    func endSentenceReading(cancel: Bool) {
        guard isReadingSentences else { return }
        isReadingSentences = false
        if cancel { synthesizer.stopSpeaking(at: .immediate) }
        deactivateSessionIfIdle()
    }

    /// 바지-인 — 큐를 비우되 낭독 모드는 유지한다. 취소된 utterance마다
    /// didCancel → onSentenceFinished가 호출돼 컨트롤러 카운트가 자연 정산된다 (T-119)
    func flushSentenceQueue() {
        guard isReadingSentences else { return }
        synthesizer.stopSpeaking(at: .immediate)
    }

    // MARK: - 읽어주기 (TTS)

    /// 평문을 읽어준다. 받아쓰기·기존 재생·음성 모드가 있으면 먼저 중단한다.
    func speak(_ text: String, messageID: UUID) {
        if voiceModeActive || isReadingSentences { onPreempted?() }
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
        if voiceModeActive { onPreempted?() }
        errorMessage = nil

        guard await ensureVoicePermissions() else {
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

    /// 마이크 + 음성 인식 권한 — 받아쓰기와 음성 모드(T-118)가 공용
    func ensureVoicePermissions() async -> Bool {
        guard await Self.requestSpeechAuthorization() else { return false }
        return await Self.requestMicPermission()
    }

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
        Task { @MainActor in self.utteranceEnded() }
    }

    nonisolated func speechSynthesizer(
        _ synthesizer: AVSpeechSynthesizer, didCancel utterance: AVSpeechUtterance
    ) {
        Task { @MainActor in self.utteranceEnded() }
    }

    /// 문장 큐 낭독 중이면 컨트롤러에 정산을 맡기고, 단독 읽어주기면 기존 정리 경로
    private func utteranceEnded() {
        if isReadingSentences {
            onSentenceFinished?()
        } else {
            finishSpeaking()
        }
    }
}
