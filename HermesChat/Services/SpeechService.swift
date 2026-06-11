import AVFoundation
import Foundation
import Speech

/// 음성 입력(받아쓰기)을 담당한다 (T-100). 읽어주기(TTS)는 T-101에서 이 클래스에 추가.
/// AVAudioSession은 이 클래스가 단일 소유한다 — 받아쓰기와 재생을 동시에 쓰지 않는다.
@MainActor
final class SpeechService: NSObject, ObservableObject {
    static let shared = SpeechService()

    @Published private(set) var isRecording = false
    /// 현재 받아쓰기 결과 (부분 결과 포함, 갱신될 때마다 전체 문자열로 교체됨)
    @Published private(set) var transcript = ""
    @Published var errorMessage: String?

    private let audioEngine = AVAudioEngine()
    private let recognizer = SFSpeechRecognizer(locale: Locale(identifier: "ko-KR"))
    private var recognitionRequest: SFSpeechAudioBufferRecognitionRequest?
    private var recognitionTask: SFSpeechRecognitionTask?

    private override init() {
        super.init()
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
            let session = AVAudioSession.sharedInstance()
            try session.setCategory(.record, mode: .measurement, options: .duckOthers)
            try session.setActive(true, options: .notifyOthersOnDeactivation)

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
        try? AVAudioSession.sharedInstance().setActive(false, options: .notifyOthersOnDeactivation)
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
        await withCheckedContinuation { continuation in
            AVAudioSession.sharedInstance().requestRecordPermission { granted in
                continuation.resume(returning: granted)
            }
        }
    }
}
