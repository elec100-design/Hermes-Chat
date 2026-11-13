import Foundation
import Combine

/// 봇 DM/채널 대화 하나의 상태 — 메시지 목록과 진행 중인 봇 응답(잡 SSE) 추적.
///
/// 서버 모델: 메시지는 평평한 타임라인이고, 봇 응답은 `queued/running` 상태로 먼저 생긴 뒤
/// 잡 스트림(`/jobs/{id}/stream`)으로 본문이 흘러온다. 스트림은 **구독할 때마다 처음부터 재생**하므로
/// 구독 시작 시 버퍼를 비운다. 잡이 끝나면 메시지를 다시 읽어 확정본으로 교체하고,
/// 봇→봇 연쇄로 새로 생긴 queued/running 메시지는 그 자리에서 다시 구독한다.
/// 서버에 잡 취소 API 가 없어 중단 기능은 없다 — `stopFollowing()` 은 수신만 끊는다.
@MainActor
final class BotChatViewModel: ObservableObject {
    @Published var messages: [BotMessage] = []
    /// 메시지 id → 지금까지 흘러온 본문(확정 전)
    @Published var streaming: [Int: String] = [:]
    @Published var input: String = ""
    @Published var isLoading = false
    @Published var isSending = false
    /// 로드/전송 실패 — 화면에 인라인으로 표시(알럿 없음)
    @Published var errorMessage: String?
    /// 오류는 아니지만 알려야 할 때(멘션 없음 등)
    @Published var notice: String?

    let channelId: Int
    let isChannel: Bool
    private let client: BotClient

    /// 잡 id → 구독 Task (같은 잡 중복 구독 방지)
    private var followTasks: [Int: Task<Void, Never>] = [:]
    /// 잡 id → 구독 시작 횟수. 서버 상태가 어긋나 end 직후에도 계속 running 으로 보일 때 무한 재구독을 막는다.
    private var followCounts: [Int: Int] = [:]
    private let maxFollowsPerJob = 4

    init(client: BotClient, channelId: Int, isChannel: Bool) {
        self.client = client
        self.channelId = channelId
        self.isChannel = isChannel
    }

    // MARK: - Load

    func load() async {
        isLoading = true
        defer { isLoading = false }
        do {
            let list = try await client.fetchMessages(channelId: channelId)
            messages = list
            errorMessage = nil
            // 재접속/연쇄 대응: 진행 중인 봇 메시지마다 구독
            for message in list where message.isInFlight {
                if let jobId = message.jobId {
                    follow(jobId: jobId, messageId: message.id)
                }
            }
            // 더 이상 진행 중이 아닌 메시지의 버퍼는 정리
            let live = Set(list.filter { $0.isInFlight }.map { $0.id })
            streaming = streaming.filter { live.contains($0.key) }
        } catch {
            errorMessage = error.localizedDescription
        }
    }

    // MARK: - Send

    func send() async {
        let text = input.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !text.isEmpty, !isSending else { return }
        isSending = true
        notice = nil
        errorMessage = nil
        input = ""
        do {
            let result = try await client.postMessage(channelId: channelId, body: text)
            if let error = result.error, !error.isEmpty {
                notice = error
            } else if result.jobId == nil && isChannel {
                notice = NSLocalizedString("bot.chat.no_mention", comment: "")
            }
        } catch {
            errorMessage = error.localizedDescription
            input = text  // 실패하면 입력을 되돌려 다시 보낼 수 있게
        }
        isSending = false
        await load()
    }

    // MARK: - Job stream

    /// 잡 스트림을 구독해 본문을 누적한다. 같은 잡을 이미 구독 중이면 무시한다.
    func follow(jobId: Int, messageId: Int) {
        guard followTasks[jobId] == nil else { return }
        let count = (followCounts[jobId] ?? 0) + 1
        guard count <= maxFollowsPerJob else { return }
        followCounts[jobId] = count

        // 스트림은 처음부터 재생된다 — 이전 버퍼가 남아 있으면 내용이 중복되므로 비운다
        streaming[messageId] = ""

        followTasks[jobId] = Task { [weak self] in
            guard let self else { return }
            do {
                for try await event in self.client.jobStream(jobId: jobId) {
                    if Task.isCancelled { return }
                    switch event {
                    case .delta(let text):
                        self.streaming[messageId, default: ""] += text
                    case .end:
                        break  // 루프가 곧 끝난다 — 아래에서 확정본으로 교체
                    }
                }
            } catch {
                if Task.isCancelled { return }
                self.errorMessage = error.localizedDescription
            }
            if Task.isCancelled { return }
            // end 직후에는 서버가 메시지 확정·연쇄 잡 생성을 마치지 않았을 수 있다(구버전 서버 포함).
            // 곧바로 다시 읽으면 아직 running 으로 보여 재구독 횟수를 헛되이 쓴다 — 잠깐 기다린다.
            try? await Task.sleep(for: .milliseconds(700))
            if Task.isCancelled { return }
            self.followTasks[jobId] = nil
            // 확정본으로 교체하고(연쇄로 생긴 새 잡은 load 안에서 재구독), 버퍼 제거
            await self.load()
            // load 가 같은 메시지를 다시 구독했다면(서버 상태 지연) 새 버퍼는 건드리지 않는다
            if self.followTasks[jobId] == nil { self.streaming[messageId] = nil }
        }
    }

    /// 화면을 떠날 때 — 수신만 끊는다(서버의 잡은 계속 돈다). 돌아오면 load() 가 다시 구독한다.
    func stopFollowing() {
        for task in followTasks.values { task.cancel() }
        followTasks.removeAll()
        followCounts.removeAll()
    }
}
