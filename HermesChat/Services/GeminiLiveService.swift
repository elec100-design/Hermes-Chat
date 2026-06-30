import AVFoundation
import Foundation

/// Gemini Live 양방향 WebSocket(BidiGenerateContent) speech-to-speech 서비스 (T-154).
/// Iris(elec100-design/Iris) `Services/GeminiLiveService.swift`를 HermesChat에 맞게 이식:
/// - 마이크 16kHz mono PCM16 → base64 `realtimeInput.audio` 전송
/// - 응답 24kHz PCM 오디오를 별도 AVAudioEngine + AVAudioPlayerNode로 재생
/// - `input/output audio transcription`을 켜 양측 자막을 받아 챗 버블로 보여준다
/// - 서버 `interrupted` 시 재생 리셋(barge-in), 재생 중 마이크 흡수 차단(에코 방지)
///
/// 콜백은 모두 메인 스레드에서 호출된다(ViewModel이 @MainActor에서 소비).
/// SWIFT_VERSION 5.9 — strict concurrency 미적용이라 클로저 콜백 패턴을 그대로 쓴다.
final class GeminiLiveService: NSObject {

    // WebSocket
    private var webSocket: URLSessionWebSocketTask?
    private var urlSession: URLSession?

    // 설정
    private let apiKey: String
    private let model: String
    private let voice: String
    private let systemPrompt: String
    /// 재개 시 컨텍스트로 주입할 직전 대화(있으면 setupComplete 후 1회 전송)
    private let seedTurns: [ChatMessage]

    // 녹음 엔진
    private var audioEngine: AVAudioEngine?
    private let recordTargetFormat = AVAudioFormat(standardFormatWithSampleRate: 16000, channels: 1)
    private var recordConverter: AVAudioConverter?

    // 재생 엔진(별도)
    private var playbackEngine: AVAudioEngine?
    private var playerNode: AVAudioPlayerNode?
    private let playbackAudioFormat = AVAudioFormat(standardFormatWithSampleRate: 24000, channels: 1)

    // 오디오 청크 버퍼링
    private var audioBuffer = Data()
    private var isCollectingAudio = false
    private var audioChunkCount = 0
    private let minChunksBeforePlay = 2
    private var hasStartedPlaying = false
    private var isPlaybackEngineRunning = false

    // 콜백 (메인 스레드)
    var onConnected: (() -> Void)?
    var onUserTranscript: ((String) -> Void)?
    var onModelTranscript: ((String) -> Void)?
    var onTurnComplete: (() -> Void)?
    var onSpeakingStarted: (() -> Void)?
    var onSpeakingStopped: (() -> Void)?
    var onError: ((String) -> Void)?

    // 상태
    private var isRecording = false
    private var isSessionConfigured = false
    private var isPlayingBack = false   // Gemini 재생 중 → 마이크 흡수 차단

    init(apiKey: String, model: String, voice: String, systemPrompt: String, seedTurns: [ChatMessage] = []) {
        self.apiKey = apiKey
        self.model = model
        self.voice = voice
        self.systemPrompt = systemPrompt
        self.seedTurns = seedTurns
        super.init()
        audioEngine = AVAudioEngine()
        setupPlaybackEngine()
    }

    // MARK: - 오디오 세션/엔진

    private func setupPlaybackEngine() {
        playbackEngine = AVAudioEngine()
        playerNode = AVAudioPlayerNode()
        guard let playbackEngine, let playerNode, let playbackAudioFormat else { return }
        playbackEngine.attach(playerNode)
        playbackEngine.connect(playerNode, to: playbackEngine.mainMixerNode, format: playbackAudioFormat)
        playbackEngine.prepare()
    }

    private func configureAudioSession() {
        do {
            let session = AVAudioSession.sharedInstance()
            try session.setCategory(
                .playAndRecord, mode: .voiceChat,
                options: [.allowBluetoothHFP, .defaultToSpeaker, .duckOthers]
            )
            if let inputs = session.availableInputs,
               let bt = inputs.first(where: {
                   $0.portType == .bluetoothHFP || $0.portType == .bluetoothLE
               }) {
                try? session.setPreferredInput(bt)
            }
            try session.setActive(true, options: [.notifyOthersOnDeactivation])
        } catch {
            onError?("오디오 세션 설정 실패: \(error.localizedDescription)")
        }
    }

    private func startPlaybackEngine() {
        guard let playbackEngine, !isPlaybackEngineRunning else { return }
        do {
            try playbackEngine.start()
            isPlaybackEngineRunning = true
        } catch {
            onError?("재생 엔진 시작 실패: \(error.localizedDescription)")
        }
    }

    private func stopPlaybackEngine() {
        guard let playbackEngine, isPlaybackEngineRunning else { return }
        playerNode?.stop()
        playerNode?.reset()
        playbackEngine.stop()
        isPlaybackEngineRunning = false
    }

    // MARK: - 연결

    func connect() {
        let base = "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent"
        guard let url = URL(string: "\(base)?key=\(apiKey)") else {
            onError?("잘못된 URL")
            return
        }
        urlSession = URLSession(configuration: .default, delegate: self, delegateQueue: OperationQueue())
        webSocket = urlSession?.webSocketTask(with: url)
        webSocket?.resume()
        receiveMessage()
    }

    func disconnect() {
        webSocket?.cancel(with: .goingAway, reason: nil)
        webSocket = nil
        urlSession?.invalidateAndCancel()
        urlSession = nil
        stopRecording()
        stopPlaybackEngine()
        isSessionConfigured = false
        deactivateSession()
    }

    private func deactivateSession() {
        try? AVAudioSession.sharedInstance().setActive(false, options: .notifyOthersOnDeactivation)
    }

    // MARK: - 세션 설정

    private func configureSession() {
        guard !isSessionConfigured else { return }
        let setup: [String: Any] = [
            "setup": [
                "model": "models/\(model)",
                "generation_config": [
                    "response_modalities": ["AUDIO"],
                    "speech_config": [
                        "voice_config": [
                            "prebuilt_voice_config": ["voice_name": voice]
                        ]
                    ]
                ],
                "system_instruction": [
                    "parts": [["text": systemPrompt]]
                ],
                // 양측 자막을 받아 챗 버블로 표시 (T-156)
                "input_audio_transcription": [String: Any](),
                "output_audio_transcription": [String: Any]()
            ]
        ]
        sendJSON(setup)
    }

    /// 재개 대화: 직전 turn들을 컨텍스트로 1회 주입해 이어가기 (T-156)
    private func seedHistoryIfNeeded() {
        guard !seedTurns.isEmpty else { return }
        let turns: [[String: Any]] = seedTurns.compactMap { msg in
            guard !msg.content.isEmpty else { return nil }
            let role = (msg.role == .user) ? "user" : "model"
            return ["role": role, "parts": [["text": msg.content]]]
        }
        guard !turns.isEmpty else { return }
        sendJSON(["clientContent": ["turns": turns, "turnComplete": false]])
    }

    // MARK: - 녹음

    func startRecording() {
        guard !isRecording else { return }
        guard AVAudioApplication.shared.recordPermission == .granted else {
            onError?("마이크 권한이 필요합니다.")
            return
        }
        if let engine = audioEngine, engine.isRunning {
            engine.stop()
            engine.inputNode.removeTap(onBus: 0)
        }
        configureAudioSession()
        guard let engine = audioEngine else { return }
        let inputNode = engine.inputNode
        let inputFormat = inputNode.outputFormat(forBus: 0)
        if let recordTargetFormat {
            recordConverter = AVAudioConverter(from: inputFormat, to: recordTargetFormat)
        }
        inputNode.installTap(onBus: 0, bufferSize: 4096, format: inputFormat) { [weak self] buffer, _ in
            self?.processAudioBuffer(buffer, inputFormat: inputFormat)
        }
        engine.prepare()
        do {
            try engine.start()
            isRecording = true
        } catch {
            onError?("녹음 시작 실패: \(error.localizedDescription)")
        }
    }

    func stopRecording() {
        guard isRecording else { return }
        audioEngine?.inputNode.removeTap(onBus: 0)
        audioEngine?.stop()
        isRecording = false
    }

    private func processAudioBuffer(_ buffer: AVAudioPCMBuffer, inputFormat: AVAudioFormat) {
        guard !isPlayingBack else { return }   // 재생 중엔 전송 안 함(에코 방지)
        guard let recordConverter, let recordTargetFormat else { return }

        let ratio = recordTargetFormat.sampleRate / inputFormat.sampleRate
        let cap = AVAudioFrameCount((Double(buffer.frameLength) * ratio).rounded(.up))
        guard let converted = AVAudioPCMBuffer(pcmFormat: recordTargetFormat, frameCapacity: max(1, cap)) else { return }

        var provided = false
        var error: NSError?
        let status = recordConverter.convert(to: converted, error: &error) { _, outStatus in
            if provided { outStatus.pointee = .noDataNow; return nil }
            provided = true
            outStatus.pointee = .haveData
            return buffer
        }
        guard error == nil, status != .error, let channel = converted.floatChannelData?.pointee else { return }

        let frameLength = Int(converted.frameLength)
        var int16 = [Int16](repeating: 0, count: frameLength)
        for i in 0..<frameLength {
            let s = max(-1.0, min(1.0, channel[i]))
            int16[i] = Int16(s * 32767.0)
        }
        let data = Data(bytes: int16, count: frameLength * MemoryLayout<Int16>.size)
        sendRealtimeInput(audioData: data.base64EncodedString())
    }

    // MARK: - 송신

    private func sendJSON(_ json: [String: Any]) {
        guard let data = try? JSONSerialization.data(withJSONObject: json),
              let string = String(data: data, encoding: .utf8) else { return }
        webSocket?.send(.string(string)) { [weak self] error in
            if let error { self?.dispatchError("전송 실패: \(error.localizedDescription)") }
        }
    }

    private func sendRealtimeInput(audioData: String) {
        sendJSON(["realtimeInput": ["audio": ["mimeType": "audio/pcm;rate=16000", "data": audioData]]])
    }

    // MARK: - 수신

    private func receiveMessage() {
        webSocket?.receive { [weak self] result in
            switch result {
            case .success(let message):
                self?.handleMessage(message)
                self?.receiveMessage()
            case .failure(let error):
                self?.dispatchError("수신 오류: \(error.localizedDescription)")
            }
        }
    }

    private func handleMessage(_ message: URLSessionWebSocketTask.Message) {
        switch message {
        case .string(let text): handleServerEvent(text)
        case .data(let data):
            if let text = String(data: data, encoding: .utf8) { handleServerEvent(text) }
        @unknown default: break
        }
    }

    private func handleServerEvent(_ jsonString: String) {
        guard let data = jsonString.data(using: .utf8),
              let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else { return }

        DispatchQueue.main.async { [weak self] in
            guard let self else { return }
            if json["setupComplete"] != nil {
                self.isSessionConfigured = true
                self.seedHistoryIfNeeded()
                self.onConnected?()
                return
            }
            if let serverContent = json["serverContent"] as? [String: Any] {
                self.handleServerContent(serverContent)
                return
            }
            if let error = json["error"] as? [String: Any] {
                self.onError?(error["message"] as? String ?? "알 수 없는 오류")
            }
        }
    }

    private func handleServerContent(_ content: [String: Any]) {
        // 모델 turn (오디오 + 가능 시 텍스트)
        if let modelTurn = content["modelTurn"] as? [String: Any],
           let parts = modelTurn["parts"] as? [[String: Any]] {
            for part in parts {
                if let text = part["text"] as? String { onModelTranscript?(text) }
                if let inline = part["inlineData"] as? [String: Any],
                   let mime = inline["mimeType"] as? String, mime.contains("audio"),
                   let b64 = inline["data"] as? String, let audio = Data(base64Encoded: b64) {
                    handleAudioChunk(audio)
                }
            }
        }
        // 사용자 음성 자막
        if let input = content["inputTranscription"] as? [String: Any],
           let text = input["text"] as? String, !text.isEmpty {
            onUserTranscript?(text)
        }
        // 모델 음성 자막
        if let output = content["outputTranscription"] as? [String: Any],
           let text = output["text"] as? String, !text.isEmpty {
            onModelTranscript?(text)
        }
        // 턴 종료
        if let done = content["turnComplete"] as? Bool, done {
            finishAudioPlayback()
            onTurnComplete?()
        }
        // 중단(barge-in)
        if let interrupted = content["interrupted"] as? Bool, interrupted {
            stopPlaybackEngine()
            setupPlaybackEngine()
            isPlayingBack = false
            onSpeakingStopped?()
        }
    }

    // MARK: - 재생

    private func handleAudioChunk(_ audioData: Data) {
        if !isCollectingAudio {
            isCollectingAudio = true
            isPlayingBack = true
            audioBuffer = Data()
            audioChunkCount = 0
            hasStartedPlaying = false
            onSpeakingStarted?()
            if isPlaybackEngineRunning {
                stopPlaybackEngine()
                setupPlaybackEngine()
            }
            startPlaybackEngine()
            playerNode?.play()
        }
        audioChunkCount += 1
        if !hasStartedPlaying {
            audioBuffer.append(audioData)
            if audioChunkCount >= minChunksBeforePlay {
                hasStartedPlaying = true
                playAudio(audioBuffer)
                audioBuffer = Data()
            }
        } else {
            playAudio(audioData)
        }
    }

    private func finishAudioPlayback() {
        if !audioBuffer.isEmpty { playAudio(audioBuffer); audioBuffer = Data() }
        isCollectingAudio = false
        isPlayingBack = false
        audioChunkCount = 0
        hasStartedPlaying = false
        onSpeakingStopped?()
    }

    private func playAudio(_ audioData: Data) {
        guard let playerNode, let playbackAudioFormat else { return }
        if !isPlaybackEngineRunning {
            startPlaybackEngine()
            playerNode.play()
        } else if !playerNode.isPlaying {
            playerNode.play()
        }
        guard let pcm = createPCMBuffer(from: audioData, format: playbackAudioFormat) else { return }
        playerNode.scheduleBuffer(pcm)
    }

    private func createPCMBuffer(from data: Data, format: AVAudioFormat) -> AVAudioPCMBuffer? {
        let frameCount = data.count / 2
        guard frameCount > 0,
              let buffer = AVAudioPCMBuffer(pcmFormat: format, frameCapacity: AVAudioFrameCount(frameCount)),
              let channel = buffer.floatChannelData else { return nil }
        buffer.frameLength = AVAudioFrameCount(frameCount)
        data.withUnsafeBytes { (raw: UnsafeRawBufferPointer) in
            guard let base = raw.baseAddress else { return }
            let int16 = base.assumingMemoryBound(to: Int16.self)
            let dst = channel.pointee
            for i in 0..<frameCount { dst[i] = Float(int16[i]) / 32768.0 }
        }
        return buffer
    }

    private func dispatchError(_ message: String) {
        DispatchQueue.main.async { [weak self] in self?.onError?(message) }
    }
}

// MARK: - URLSessionWebSocketDelegate

extension GeminiLiveService: URLSessionWebSocketDelegate {
    func urlSession(_ session: URLSession, webSocketTask: URLSessionWebSocketTask,
                    didOpenWithProtocol protocol: String?) {
        DispatchQueue.main.async { [weak self] in self?.configureSession() }
    }

    func urlSession(_ session: URLSession, webSocketTask: URLSessionWebSocketTask,
                    didCloseWith closeCode: URLSessionWebSocketTask.CloseCode, reason: Data?) {
        guard closeCode != .normalClosure && closeCode != .goingAway else { return }
        let reasonString = reason.flatMap { String(data: $0, encoding: .utf8) } ?? "unknown"
        dispatchError("연결 끊김 (code: \(closeCode.rawValue), \(reasonString))")
    }
}
