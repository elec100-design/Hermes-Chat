import AVFoundation
import Foundation

// MARK: - LiveTTSProvider (T-161)

/// Live 탭 Hermes 백엔드의 문장 단위 TTS 추상화.
/// HermesLiveService가 스트리밍 문장을 넘기면 순서대로 낭독하고,
/// 문장 1건이 끝나거나 취소될 때마다 `onSentenceFinished`로 정산한다.
@MainActor
protocol LiveTTSProvider: AnyObject {
    /// 문장 1건 재생 완료/취소 — 호출 측이 pending 카운트를 정산한다
    var onSentenceFinished: (() -> Void)? { get set }
    /// 서버 TTS 사용 불가로 로컬로 강등됨 (1회, 사유 전달)
    var onUnavailable: ((String) -> Void)? { get set }

    func begin() throws
    func enqueue(_ sentence: String)
    /// barge-in: 대기·재생 중인 문장을 비운다 (각 문장은 onSentenceFinished로 정산)
    func flush()
    func end(cancel: Bool)
}

// MARK: - LocalTTSProvider

/// 기기 내장 AVSpeechSynthesizer — SpeechService 문장 큐(T-118) 래퍼. 검증된 기본값.
@MainActor
final class LocalTTSProvider: LiveTTSProvider {
    var onSentenceFinished: (() -> Void)?
    var onUnavailable: ((String) -> Void)?

    private let speech = SpeechService.shared
    private var active = false

    func begin() throws {
        // SpeechService.onSentenceFinished는 단일 슬롯 — 시작 시점에 획득한다.
        // (VoiceConversationController도 시작 시 재획득하므로 서로 밀어내는 구조. T-162 참조)
        speech.onSentenceFinished = { [weak self] in self?.onSentenceFinished?() }
        try speech.beginSentenceReading()
        active = true
    }

    func enqueue(_ sentence: String) {
        guard active else { return }
        speech.enqueueSentence(sentence)
    }

    func flush() {
        guard active else { return }
        speech.flushSentenceQueue()
    }

    func end(cancel: Bool) {
        guard active else { return }
        active = false
        speech.endSentenceReading(cancel: cancel)
    }
}

// MARK: - ServerTTSProvider

/// 게이트웨이 경유 OpenAI TTS — 실제 엔드포인트 경로는 저장소에 검증된 사실이 없어
/// 설정(hermesLiveTTSEndpoint)에서 주입받는다. 문장마다 POST → 오디오 바이트 → 순차 재생.
/// HTTP/디코드/재생 실패 시 `onUnavailable` 1회 통지 후 남은/이후 문장을 전부
/// LocalTTSProvider로 위임한다(대화 무중단 강등).
@MainActor
final class ServerTTSProvider: NSObject, LiveTTSProvider {
    var onSentenceFinished: (() -> Void)?
    var onUnavailable: ((String) -> Void)?

    private let endpoint: URL
    private let apiKey: String
    private let voice: String
    private let fallback: LocalTTSProvider

    private var active = false
    private var degraded = false
    private var queue: [String] = []
    private var isProcessing = false
    private var fetchTask: Task<Void, Never>?
    private var player: AVAudioPlayer?
    private var playbackContinuation: CheckedContinuation<Void, Never>?

    init(endpoint: URL, apiKey: String, voice: String, fallback: LocalTTSProvider) {
        self.endpoint = endpoint
        self.apiKey = apiKey
        self.voice = voice
        self.fallback = fallback
    }

    func begin() throws {
        // 재생 경로는 SpeechService가 잡아 둔 활성 AVAudioSession을 그대로 쓴다.
        active = true
        degraded = false
    }

    func enqueue(_ sentence: String) {
        guard active else { return }
        if degraded {
            fallback.enqueue(sentence)
            return
        }
        queue.append(sentence)
        processNextIfIdle()
    }

    func flush() {
        guard active else { return }
        if degraded {
            fallback.flush()
            return
        }
        // 대기 중 문장은 각각 정산하고, 재생 중 문장은 중단 → didFinish 경로로 정산된다
        let dropped = queue.count
        queue.removeAll()
        fetchTask?.cancel()
        fetchTask = nil
        stopPlayback()
        for _ in 0..<dropped { onSentenceFinished?() }
    }

    func end(cancel: Bool) {
        guard active else { return }
        active = false
        if degraded {
            fallback.end(cancel: cancel)
            return
        }
        if cancel {
            queue.removeAll()
            fetchTask?.cancel()
            fetchTask = nil
            stopPlayback()
        }
    }

    // MARK: 순차 처리

    private func processNextIfIdle() {
        guard !isProcessing, !queue.isEmpty, active, !degraded else { return }
        isProcessing = true
        let sentence = queue.removeFirst()
        fetchTask = Task { [weak self] in
            await self?.speakOne(sentence)
            guard let self else { return }
            self.isProcessing = false
            self.fetchTask = nil
            self.processNextIfIdle()
        }
    }

    private func speakOne(_ sentence: String) async {
        do {
            let data = try await fetchAudio(for: sentence)
            guard active, !degraded, !Task.isCancelled else {
                onSentenceFinished?()
                return
            }
            try await play(data)
            onSentenceFinished?()
        } catch is CancellationError {
            onSentenceFinished?()
        } catch {
            // 정산하지 않는다 — 실패한 문장은 fallback이 다시 읽고 그쪽에서 정산된다
            degrade(reason: error.localizedDescription, retrying: sentence)
        }
    }

    private func fetchAudio(for sentence: String) async throws -> Data {
        var request = URLRequest(url: endpoint, timeoutInterval: 15)
        request.httpMethod = "POST"
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        if !apiKey.isEmpty {
            request.setValue("Bearer \(apiKey)", forHTTPHeaderField: "Authorization")
        }
        var body: [String: Any] = ["text": sentence]
        if !voice.isEmpty { body["voice"] = voice }
        request.httpBody = try JSONSerialization.data(withJSONObject: body)

        let (data, response) = try await URLSession.shared.data(for: request)
        guard let http = response as? HTTPURLResponse, (200...299).contains(http.statusCode), !data.isEmpty else {
            let code = (response as? HTTPURLResponse)?.statusCode ?? -1
            throw ServerTTSError.badResponse(code)
        }
        return data
    }

    private func play(_ data: Data) async throws {
        let p = try AVAudioPlayer(data: data)
        p.delegate = self
        player = p
        guard p.play() else { throw ServerTTSError.playbackFailed }
        await withCheckedContinuation { (cont: CheckedContinuation<Void, Never>) in
            playbackContinuation = cont
        }
    }

    private func stopPlayback() {
        player?.stop()
        player = nil
        playbackContinuation?.resume()
        playbackContinuation = nil
    }

    /// 서버 TTS를 포기하고 이후 문장을 전부 로컬로 — 실패한 문장부터 다시 읽는다
    private func degrade(reason: String, retrying sentence: String?) {
        guard !degraded else { return }
        degraded = true
        let pending = queue
        queue.removeAll()
        stopPlayback()

        fallback.onSentenceFinished = { [weak self] in self?.onSentenceFinished?() }
        do {
            try fallback.begin()
        } catch {
            onUnavailable?("음성 합성을 시작할 수 없습니다: \(error.localizedDescription)")
            return
        }
        onUnavailable?("서버 TTS 호출 실패 — 기기 내장 음성으로 전환합니다. (\(reason))")
        if let sentence { fallback.enqueue(sentence) }
        for s in pending { fallback.enqueue(s) }
    }

    enum ServerTTSError: LocalizedError {
        case badResponse(Int)
        case playbackFailed
        var errorDescription: String? {
            switch self {
            case .badResponse(let code): return "TTS HTTP \(code)"
            case .playbackFailed:        return "오디오 재생 실패"
            }
        }
    }
}

extension ServerTTSProvider: AVAudioPlayerDelegate {
    nonisolated func audioPlayerDidFinishPlaying(_ player: AVAudioPlayer, successfully flag: Bool) {
        Task { @MainActor [weak self] in
            guard let self else { return }
            self.player = nil
            self.playbackContinuation?.resume()
            self.playbackContinuation = nil
        }
    }
}
