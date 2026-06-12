import Combine
import Foundation
import MediaPlayer

/// 음성 대화 오케스트레이션 (T-118).
/// 두 가지 동작 모드가 같은 문장 분할·낭독 엔진을 공유한다:
/// 1. 자동 낭독 — 받아쓰기로 보낸 메시지의 응답을 문장 단위로 읽어주고 끝(재청취 없음)
/// 2. 핸즈프리 — 청취(침묵 1.8초 자동 전송) → 응답 스트리밍 낭독 → 자동 재청취 루프
/// 오디오 자원(세션·엔진·합성기)은 전부 SpeechService 소유 — 여기는 상태 머신만.
@MainActor
final class VoiceConversationController: ObservableObject {
    static let shared = VoiceConversationController()

    enum State: Equatable {
        case idle
        case listening        // 마이크 청취 중 (핸즈프리 전용)
        case waitingResponse  // 전송 후 첫 문장 대기
        case speaking         // 문장 큐 낭독 중
    }

    @Published private(set) var state: State = .idle
    /// 청취 중 실시간 부분 인식 결과 (배너 표시용)
    @Published private(set) var liveTranscript = ""
    /// true면 핸즈프리 루프, false면 받아쓰기 자동 낭독 1회
    private(set) var handsFree = false

    /// 부분 결과가 이 시간 동안 멈추면 발화가 끝난 것으로 보고 자동 전송
    static let silenceInterval: TimeInterval = 1.8
    /// 발화가 전혀 없으면 모드 종료 (배터리 보호)
    static let noSpeechTimeout: TimeInterval = 60

    private let speech = SpeechService.shared
    private weak var viewModel: ChatViewModel?
    private var transcriptCancellable: AnyCancellable?
    private var silenceTask: Task<Void, Never>?
    private var noSpeechTask: Task<Void, Never>?
    private var splitter = StreamingSentenceSplitter()
    private var streamFinished = false
    /// 큐에 들어갔으나 아직 didFinish/didCancel로 정산되지 않은 문장 수
    private var pendingSentences = 0
    /// 바지-인 후 남은 스트림 문장의 낭독 생략 (T-119)
    private var skipRemainingTTS = false
    /// 해제를 위해 보관하는 리모트 커맨드 타깃 (T-119)
    private var commandTargets: [(MPRemoteCommand, Any)] = []

    private init() {
        speech.onSentenceFinished = { [weak self] in self?.sentenceFinished() }
        speech.onVoiceListenEnded = { [weak self] in self?.listenEndedSpontaneously() }
        // BT 기기 분리·전화 인터럽션·단독 읽어주기/받아쓰기 선점 — 전부 모드 종료로 정리
        speech.onRouteLost = { [weak self] in self?.stop() }
        speech.onInterruptionBegan = { [weak self] in self?.stop() }
        speech.onPreempted = { [weak self] in self?.stop() }
    }

    // MARK: - 진입/종료

    /// 받아쓰기로 보낸 메시지의 응답을 자동으로 읽어준다 — viewModel.send() 직전에 호출
    func autoRead(viewModel: ChatViewModel) {
        if state != .idle { stop() }
        do {
            try speech.beginSentenceReading()
        } catch {
            speech.errorMessage = "오디오를 재생할 수 없습니다: \(error.localizedDescription)"
            return
        }
        self.viewModel = viewModel
        handsFree = false
        resetTurn()
        attachStreamHandler(to: viewModel)
        enableRemoteCommands()
        setNowPlaying()
        state = .waitingResponse
    }

    /// 핸즈프리 대화 모드 시작 — 진입 멘트 후 청취 루프로 들어간다
    func start(viewModel: ChatViewModel) async {
        if state != .idle { stop() }
        guard await speech.ensureVoicePermissions() else {
            speech.errorMessage = "설정 > 개인정보 보호에서 마이크와 음성 인식 권한을 허용해주세요."
            return
        }
        do {
            try speech.voiceModeBegin()
            try speech.beginSentenceReading()
        } catch {
            speech.voiceModeEnd()
            speech.errorMessage = "음성 모드를 시작할 수 없습니다: \(error.localizedDescription)"
            return
        }
        self.viewModel = viewModel
        handsFree = true
        resetTurn()
        attachStreamHandler(to: viewModel)
        enableRemoteCommands()
        setNowPlaying()
        // 진입 멘트 — 청취 시작의 청각 피드백 + 실제 재생으로 now-playing 지위 확보 (T-119).
        // (didFinish 정산이 beginListening으로 이어진다)
        streamFinished = true
        pendingSentences = 1
        state = .speaking
        speech.enqueueSentence("말씀하세요")
    }

    /// 멱등 — 모든 타이머·구독·오디오 자원을 정리하고 idle로
    func stop() {
        silenceTask?.cancel(); silenceTask = nil
        noSpeechTask?.cancel(); noSpeechTask = nil
        transcriptCancellable = nil
        viewModel?.voiceStreamHandler = nil
        liveTranscript = ""
        speech.voiceListenStop()
        speech.endSentenceReading(cancel: true)
        speech.voiceModeEnd()
        disableRemoteCommands()
        clearNowPlaying()
        handsFree = false
        resetTurn()
        state = .idle
    }

    private func resetTurn() {
        splitter.reset()
        streamFinished = false
        pendingSentences = 0
        skipRemainingTTS = false
    }

    // MARK: - 청취 (핸즈프리)

    private func beginListening() {
        guard handsFree else { return }
        resetTurn()
        liveTranscript = ""
        do {
            try speech.voiceListenStart()
        } catch {
            speech.errorMessage = error.localizedDescription
            stop()
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
        liveTranscript = text
        noSpeechTask?.cancel(); noSpeechTask = nil
        silenceTask?.cancel()
        silenceTask = Task { [weak self] in
            try? await Task.sleep(nanoseconds: UInt64(Self.silenceInterval * 1_000_000_000))
            guard !Task.isCancelled else { return }
            self?.finishListening()
        }
    }

    private func armNoSpeechTimer() {
        noSpeechTask?.cancel()
        noSpeechTask = Task { [weak self] in
            try? await Task.sleep(nanoseconds: UInt64(Self.noSpeechTimeout * 1_000_000_000))
            guard !Task.isCancelled else { return }
            self?.stop()
        }
    }

    /// 발화 종료 — 받아 적은 내용을 전송하고 응답 대기로
    private func finishListening() {
        guard state == .listening else { return }
        silenceTask?.cancel(); silenceTask = nil
        noSpeechTask?.cancel(); noSpeechTask = nil
        transcriptCancellable = nil
        speech.voiceListenStop()
        let text = speech.transcript.trimmingCharacters(in: .whitespacesAndNewlines)
        liveTranscript = ""
        guard !text.isEmpty else {
            beginListening()
            return
        }
        guard let viewModel else {
            stop()
            return
        }
        state = .waitingResponse
        viewModel.inputText = text
        Task { [weak viewModel] in
            guard let viewModel else { return }
            // 직전 턴 send()의 마무리(자동 제목 갱신 등)가 끝나길 잠깐 대기 — 발화 유실 방지.
            // 초과 시 그냥 보낸다 — 가드에 걸리면 send()가 즉시 완료를 알려 루프가 복귀한다
            var waited = 0
            while viewModel.isWorking, waited < 50 {
                try? await Task.sleep(nanoseconds: 100_000_000)
                waited += 1
            }
            await viewModel.send()
        }
    }

    /// 청취만 조용히 접는다 (전송 없음) — 청취 중 수동 텍스트 전송이 일어난 경우
    private func cancelListening() {
        silenceTask?.cancel(); silenceTask = nil
        noSpeechTask?.cancel(); noSpeechTask = nil
        transcriptCancellable = nil
        speech.voiceListenStop()
        liveTranscript = ""
    }

    /// 인식이 스스로 끝남(1분 한도·오류) — 내용이 있으면 전송, 없으면 모드 종료
    private func listenEndedSpontaneously() {
        guard state == .listening else { return }
        if speech.transcript.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
            stop()
        } else {
            finishListening()
        }
    }

    // MARK: - 응답 낭독

    private func attachStreamHandler(to viewModel: ChatViewModel) {
        viewModel.voiceStreamHandler = { [weak self] accumulated, finished in
            self?.handleStream(accumulated, finished: finished)
        }
    }

    private func handleStream(_ accumulated: String, finished: Bool) {
        guard state != .idle else { return }
        // 청취 중 수동 전송이 흘러온 경우 — 청취를 접고 그 응답을 낭독한다
        if state == .listening {
            cancelListening()
            resetTurn()
            state = .waitingResponse
        }
        let sentences = splitter.consume(accumulated, isFinal: finished)
        if !skipRemainingTTS {
            for sentence in sentences {
                pendingSentences += 1
                speech.enqueueSentence(sentence)
            }
            if pendingSentences > 0 { state = .speaking }
        }
        if finished {
            streamFinished = true
            advanceIfDone()
        }
    }

    private func sentenceFinished() {
        pendingSentences = max(0, pendingSentences - 1)
        advanceIfDone()
    }

    // MARK: - 에어팟 스템 탭 / 글라스 탭 (T-119)

    /// 에어팟 스템 탭·메타 글라스 탭은 같은 AVRCP play/pause로 들어온다.
    /// 리모트 커맨드는 now-playing 앱에만 전달되므로 진입 멘트/첫 문장 재생이 지위를 확보한다.
    private func enableRemoteCommands() {
        guard commandTargets.isEmpty else { return }
        let center = MPRemoteCommandCenter.shared()
        for command in [center.togglePlayPauseCommand, center.playCommand, center.pauseCommand] {
            command.isEnabled = true
            let target = command.addTarget { [weak self] _ in
                Task { @MainActor [weak self] in self?.handleRemoteToggle() }
                return .success
            }
            commandTargets.append((command, target))
        }
    }

    /// 음악 앱 등에 제어권을 돌려준다
    private func disableRemoteCommands() {
        for (command, target) in commandTargets {
            command.removeTarget(target)
            command.isEnabled = false
        }
        commandTargets = []
    }

    /// 탭 한 번의 의미 — 상태별로 "가장 자연스러운 다음 행동"
    private func handleRemoteToggle() {
        switch state {
        case .idle:
            // 커맨드 해제 직전의 늦은 탭 등 — 연결된 화면이 있으면 모드 재시작
            if let viewModel {
                Task { await start(viewModel: viewModel) }
            }
        case .listening:
            if liveTranscript.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
                stop()  // 아직 아무 말 안 함 — 탭은 "그만 듣기"
            } else {
                finishListening()  // 말하던 중 탭 — "끝, 바로 보내"
            }
        case .speaking:
            guard handsFree else {
                stop()  // 자동 낭독 — 탭은 "그만 읽기"
                return
            }
            // 바지-인: 남은 낭독을 끊고 듣기로 — 취소 정산은 didCancel→sentenceFinished가 처리
            skipRemainingTTS = true
            speech.flushSentenceQueue()
            pendingSentences = 0
            if streamFinished {
                beginListening()
            }
            // 스트림 진행 중이면 handleStream(finished:)의 advanceIfDone이 재청취로 이어준다
        case .waitingResponse:
            if !handsFree { stop() }  // 자동 낭독 대기 중 취소; 핸즈프리는 응답을 기다린다
        }
    }

    private func setNowPlaying() {
        MPNowPlayingInfoCenter.default().nowPlayingInfo = [
            MPMediaItemPropertyTitle: "Hermes 음성 대화",
            MPNowPlayingInfoPropertyPlaybackRate: 1.0
        ]
    }

    private func clearNowPlaying() {
        MPNowPlayingInfoCenter.default().nowPlayingInfo = nil
    }

    /// 스트림과 낭독 큐가 모두 끝났을 때 — 핸즈프리면 재청취, 자동 낭독이면 종료
    private func advanceIfDone() {
        guard streamFinished, pendingSentences == 0,
              state == .speaking || state == .waitingResponse
        else { return }
        if handsFree {
            beginListening()
        } else {
            stop()
        }
    }
}

/// 스트리밍으로 누적되는 본문에서 새로 완성된 문장만 잘라낸다 (T-118).
/// `MarkdownLite.strippingThink` 결과는 prefix-stable(미완성 꼬리만 보류)이므로
/// 소비한 문자 수(consumed) 추적이 안전하다.
struct StreamingSentenceSplitter {
    private var consumed = 0

    /// 즉시 분할하는 종결 문자
    private static let hardTerminators: Set<Character> = ["。", "？", "！", "…", "\n"]
    /// 다음 문자가 공백일 때만 분할 — "1.8", "v2.0" 같은 토큰 보호
    private static let softTerminators: Set<Character> = [".", "?", "!"]

    mutating func reset() { consumed = 0 }

    /// - Parameters:
    ///   - raw: 지금까지 누적된 원문 전체 (think 블록 포함 가능)
    ///   - isFinal: 스트림 종료 — 잔여분을 마지막 문장으로 플러시
    /// - Returns: 새로 완성된 문장들 (마크다운 제거된 평문)
    mutating func consume(_ raw: String, isFinal: Bool) -> [String] {
        let visible = Array(MarkdownLite.strippingThink(raw))
        guard consumed <= visible.count else {
            consumed = visible.count
            return []
        }

        var sentences: [String] = []
        var start = consumed
        var i = consumed
        while i < visible.count {
            let ch = visible[i]
            var isBoundary = Self.hardTerminators.contains(ch)
            if !isBoundary, Self.softTerminators.contains(ch),
               i + 1 < visible.count, visible[i + 1].isWhitespace {
                isBoundary = true
            }
            guard isBoundary else {
                i += 1
                continue
            }
            // 연속 종결 부호("?!", "...")는 한 문장으로 흡수
            var end = i
            while end + 1 < visible.count,
                  Self.hardTerminators.contains(visible[end + 1])
                  || Self.softTerminators.contains(visible[end + 1]) {
                end += 1
            }
            appendPlain(String(visible[start...end]), to: &sentences)
            start = end + 1
            i = end + 1
        }
        consumed = start

        if isFinal, start < visible.count {
            appendPlain(String(visible[start...]), to: &sentences)
            consumed = visible.count
        }
        return sentences
    }

    private func appendPlain(_ piece: String, to sentences: inout [String]) {
        let plain = MarkdownLite.plainText(from: piece)
            .trimmingCharacters(in: .whitespacesAndNewlines)
        if !plain.isEmpty { sentences.append(plain) }
    }
}
