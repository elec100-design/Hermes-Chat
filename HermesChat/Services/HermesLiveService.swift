import Combine
import Foundation

/// Live 탭 Hermes 백엔드 (T-162).
/// hermes-agent에는 실시간 오디오 API가 없으므로(PLAN §0 검증된 사실) 파이프라인은:
/// **온디바이스 STT(SpeechService) → 게이트웨이 텍스트 SSE(streamChat) → 문장 단위 TTS(LiveTTSProvider)**.
/// 핸즈프리 루프: 청취(침묵 1.8s 자동 전송) → 스트리밍 문장 낭독 → 자동 재청취.
/// VoiceConversationController와 같은 상태 머신 구조지만 ChatViewModel에 묶이지 않고
/// Live 대화 전용 게이트웨이 세션을 lazy 생성/재사용한다.
/// 오디오 자원(세션·엔진)은 SpeechService 소유 — 여기는 상태 머신만.
@MainActor
final class HermesLiveService {

    enum State: Equatable {
        case idle
        case connecting       // 권한·오디오 준비
        case listening        // 마이크 청취 중
        case waitingResponse  // 전송 후 첫 문장 대기
        case speaking         // 문장 큐 낭독 중
    }

    // MARK: 콜백 (GeminiLiveService와 동형 표면 — LiveViewModel이 배선)

    var onStateChange: ((State) -> Void)?
    /// 확정된 사용자 발화 1건 — 사용자 버블로 추가
    var onUserUtterance: ((String) -> Void)?
    /// 지금까지 누적된 가시 텍스트 전체(strippingThink) — 현재 어시스턴트 버블 내용 교체
    var onAssistantText: ((String) -> Void)?
    var onTurnComplete: (() -> Void)?
    /// 게이트웨이 세션 확보 — LiveSession.hermesSessionId로 저장해 이어가기에 쓴다
    var onSessionEstablished: ((String) -> Void)?
    var onError: ((String) -> Void)?
    /// 서버 TTS 강등 등 치명적이지 않은 안내
    var onNotice: ((String) -> Void)?

    // MARK: 상태

    private(set) var state: State = .idle {
        didSet { if state != oldValue { onStateChange?(state) } }
    }

    private let speech = SpeechService.shared
    private let client: HermesAPIClient
    private let systemPrompt: String
    private let tts: LiveTTSProvider
    private var sessionId: String?

    private var transcriptCancellable: AnyCancellable?
    private var silenceTask: Task<Void, Never>?
    private var noSpeechTask: Task<Void, Never>?
    private var streamTask: Task<Void, Never>?
    private var splitter = StreamingSentenceSplitter()
    private var streamFinished = false
    /// 큐에 들어갔으나 아직 정산되지 않은 문장 수
    private var pendingSentences = 0
    /// barge-in 후 남은 스트림 문장의 낭독 생략
    private var skipRemainingTTS = false

    init(client: HermesAPIClient, existingSessionId: String?, systemPrompt: String, tts: LiveTTSProvider) {
        self.client = client
        self.sessionId = existingSessionId
        self.systemPrompt = systemPrompt
        self.tts = tts
    }

    // MARK: - 수명

    func start() async {
        guard state == .idle else { return }
        state = .connecting

        guard await speech.ensureVoicePermissions() else {
            fail("설정 > 개인정보 보호에서 마이크와 음성 인식 권한을 허용해주세요.")
            return
        }

        // 채팅 탭 음성(VCC)이 오디오 세션·SpeechService 콜백을 잡고 있으면 먼저 정리.
        // 이후 우리가 콜백 슬롯을 획득한다 (VCC는 자기 세션 시작 시 재획득 — claim 구조, T-162)
        VoiceConversationController.shared.stop()
        claimSpeechCallbacks()

        do {
            try speech.voiceModeBegin()
            try tts.begin()
        } catch {
            speech.voiceModeEnd()
            fail("음성 모드를 시작할 수 없습니다: \(error.localizedDescription)")
            return
        }

        tts.onSentenceFinished = { [weak self] in self?.sentenceFinished() }
        tts.onUnavailable = { [weak self] reason in self?.onNotice?(reason) }

        beginListening()
    }

    /// 멱등 — 타이머·스트림·오디오를 전부 정리하고 idle로
    func stop() {
        guard state != .idle else { return }
        silenceTask?.cancel(); silenceTask = nil
        noSpeechTask?.cancel(); noSpeechTask = nil
        transcriptCancellable = nil
        streamTask?.cancel(); streamTask = nil
        speech.voiceListenStop()
        tts.end(cancel: true)
        speech.voiceModeEnd()
        resetTurn()
        state = .idle
    }

    /// barge-in(화면 버튼): 낭독·스트림을 끊고 바로 재청취.
    /// 핫마이크 barge-in은 지원하지 않는다 — 재생 중 탭은 폐기 탭이고 에코가 STT를 오염시킨다.
    /// (리모트 커맨드 barge-in은 VCC와의 타깃 충돌을 피해 후속 과제 — TASKS T-162 비고)
    func interrupt() {
        guard state == .speaking || state == .waitingResponse else { return }
        skipRemainingTTS = true
        streamTask?.cancel(); streamTask = nil
        tts.flush()
        pendingSentences = 0
        beginListening()
    }

    private func fail(_ message: String) {
        onError?(message)
        stop()
    }

    private func resetTurn() {
        splitter.reset()
        streamFinished = false
        pendingSentences = 0
        skipRemainingTTS = false
    }

    /// SpeechService 콜백 단일 슬롯 획득 (VCC.claimSpeechCallbacks와 대칭, T-162)
    private func claimSpeechCallbacks() {
        speech.onVoiceListenEnded = { [weak self] in self?.listenEndedSpontaneously() }
        speech.onRouteLost = { [weak self] in self?.stop() }
        speech.onInterruptionBegan = { [weak self] in self?.stop() }
        speech.onPreempted = { [weak self] in self?.stop() }
        // onSentenceFinished는 LiveTTSProvider(Local)가 begin()에서 획득한다
    }

    // MARK: - 청취

    private func beginListening() {
        guard state != .idle else { return }
        resetTurn()
        do {
            try speech.voiceListenStart()
        } catch {
            fail(error.localizedDescription)
            return
        }
        state = .listening
        transcriptCancellable = speech.$transcript
            .dropFirst()
            .sink { [weak self] text in self?.transcriptUpdated(text) }
        armNoSpeechTimer()
    }

    private func transcriptUpdated(_ text: String) {
        guard state == .listening, !text.isEmpty else { return }
        noSpeechTask?.cancel(); noSpeechTask = nil
        silenceTask?.cancel()
        silenceTask = Task { [weak self] in
            try? await Task.sleep(nanoseconds: UInt64(VoiceConversationController.silenceInterval * 1_000_000_000))
            guard !Task.isCancelled else { return }
            self?.finishListening()
        }
    }

    private func armNoSpeechTimer() {
        noSpeechTask?.cancel()
        noSpeechTask = Task { [weak self] in
            try? await Task.sleep(nanoseconds: UInt64(VoiceConversationController.noSpeechTimeout * 1_000_000_000))
            guard !Task.isCancelled else { return }
            self?.stop()
        }
    }

    /// 발화 종료 — 확정 텍스트를 게이트웨이로 보내고 응답 낭독으로
    private func finishListening() {
        guard state == .listening else { return }
        silenceTask?.cancel(); silenceTask = nil
        noSpeechTask?.cancel(); noSpeechTask = nil
        transcriptCancellable = nil
        speech.voiceListenStop()
        let text = speech.transcript.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !text.isEmpty else {
            beginListening()
            return
        }
        onUserUtterance?(text)
        state = .waitingResponse
        streamTask = Task { [weak self] in
            await self?.sendAndStream(text)
        }
    }

    /// 인식이 스스로 끝남(1분 한도·오류) — 내용이 있으면 전송, 없으면 종료
    private func listenEndedSpontaneously() {
        guard state == .listening else { return }
        if speech.transcript.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
            stop()
        } else {
            finishListening()
        }
    }

    // MARK: - 전송/스트리밍

    private func sendAndStream(_ text: String) async {
        // 게이트웨이 세션 lazy 확보 — 첫 발화 앞부분을 제목으로.
        // 게이트웨이는 세션 제목의 전역 유일성을 요구한다("invalid_title"). 같은 첫 마디로
        // 시작하면 이전 Live 세션과 충돌하므로, 초 단위 시각을 붙여 실질적으로 유일하게 만들고,
        // 그래도 실패하면 제목 없이 재시도(서버 자동 제목)해 대화가 절대 막히지 않게 한다.
        if sessionId == nil {
            let session: Session
            let stamp = Date().formatted(.dateTime.hour().minute().second())
            let title = "[Live] " + String(text.prefix(20)) + " · " + stamp
            do {
                session = try await client.createSession(systemPrompt: systemPrompt, title: title)
            } catch {
                do {
                    session = try await client.createSession(systemPrompt: systemPrompt, title: nil)
                } catch {
                    fail("세션을 만들 수 없습니다: \(error.localizedDescription)")
                    return
                }
            }
            sessionId = session.id
            onSessionEstablished?(session.id)
        }
        guard let sessionId, state == .waitingResponse else { return }

        var accumulated = ""
        do {
            for try await update in client.streamChat(sessionId: sessionId, message: text) {
                guard !Task.isCancelled, state != .idle else { return }
                switch update {
                case .content(let delta):
                    accumulated += delta
                    handleStreamProgress(accumulated, finished: false)
                case .toolCallUpdate:
                    continue  // 도구 실행은 낭독하지 않는다
                }
            }
            guard !Task.isCancelled, state != .idle else { return }
            handleStreamProgress(accumulated, finished: true)
        } catch is CancellationError {
            return
        } catch {
            guard state != .idle else { return }
            onError?(error.localizedDescription)
            // 대화는 유지 — 다시 청취로 복귀해 재시도 기회를 준다
            tts.flush()
            pendingSentences = 0
            beginListening()
        }
    }

    private func handleStreamProgress(_ accumulated: String, finished: Bool) {
        let visible = MarkdownLite.strippingThink(accumulated)
        if !visible.isEmpty { onAssistantText?(visible) }

        let sentences = splitter.consume(accumulated, isFinal: finished)
        if !skipRemainingTTS {
            for sentence in sentences {
                pendingSentences += 1
                tts.enqueue(sentence)
            }
            if pendingSentences > 0 { state = .speaking }
        }
        if finished {
            streamFinished = true
            streamTask = nil
            advanceIfDone()
        }
    }

    private func sentenceFinished() {
        pendingSentences = max(0, pendingSentences - 1)
        advanceIfDone()
    }

    /// 스트림과 낭독 큐가 모두 끝났을 때 — 턴 완료 후 자동 재청취
    private func advanceIfDone() {
        guard streamFinished, pendingSentences == 0,
              state == .speaking || state == .waitingResponse
        else { return }
        onTurnComplete?()
        beginListening()
    }
}
