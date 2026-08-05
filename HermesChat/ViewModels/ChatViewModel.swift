import Foundation
import UIKit

/// 전송 대기 중인 첨부 파일 (업로드는 send 시점에 일괄 수행)
struct PendingAttachment: Identifiable, Equatable {
    let id = UUID()
    let filename: String
    let data: Data
    /// 이미지 첨부의 미리보기 — addAttachment 시점에 1회 생성 (T-108)
    let thumbnail: UIImage?

    static func == (lhs: PendingAttachment, rhs: PendingAttachment) -> Bool {
        lhs.id == rhs.id
    }
}

@MainActor
final class ChatViewModel: ObservableObject {
    @Published var messages: [ChatMessage] = []
    @Published var inputText: String = ""
    @Published var isWorking: Bool = false
    @Published var isLoadingHistory: Bool = false
    @Published var attachments: [PendingAttachment] = []
    @Published var historyError: String?
    /// 글라스 사진 자동 전송 모드(Phase 16) 활성 여부 — ChatView 토글이 켜고 끈다
    @Published var glassesCaptureActive = false
    /// 이번 모드에서 감지한 글라스 사진 수 (T-129, 서버 응답과 무관한 감지 가시성)
    @Published private(set) var glassesPhotosDetected = 0
    /// 가장 최근 감지한 글라스 사진 파일명 (T-129)
    @Published private(set) var lastGlassesPhotoName: String?

    /// Bridge 업로드 한도와 동일 (server/hermes_bridge.py MAX_UPLOAD)
    static let maxAttachmentBytes = 50 * 1024 * 1024

    /// 글라스 사진 도착 후 사용자가 질문하지 않을 때 쓰는 기본 프롬프트 (T-126)
    static let glassesPhotoPrompt = "방금 찍은 사진이야. 무엇이 보이는지 설명해줘."

    /// 음성 자동 낭독/핸즈프리(T-118)가 스트리밍 응답을 구독하는 후킹 —
    /// (지금까지 누적된 본문, 스트림 완료 여부). 음성 기능을 안 쓸 땐 nil
    var voiceStreamHandler: ((String, Bool) -> Void)?

    let sessionId: String
    let appSettings: AppSettings

    init(sessionId: String, appSettings: AppSettings) {
        self.sessionId = sessionId
        self.appSettings = appSettings
        Task { await loadHistory() }
    }

    /// 채팅 화면에 렌더할 메시지 (T-103) — tool/system 제외,
    /// 사고 과정(<think>)만 있고 보일 내용이 없는 어시스턴트 버블 제외.
    /// 단, **스트리밍 중인 어시스턴트 버블은 사고만 있어 보일 내용이 없어도 항상 포함**한다 (T-116).
    /// (제외하면 사고 단계 내내 화면에 말풍선이 없어 "응답 생성 중…"에서 멈춘 듯 보이고,
    ///  답이 끝나 재진입(loadHistory)해야 보이던 회귀가 발생한다.)
    /// 스트리밍 갱신은 원본 `messages`의 인덱스를 그대로 쓰므로 여기는 읽기 전용 필터다.
    var displayMessages: [ChatMessage] {
        let streamingID = streamingAssistantID
        return messages.filter { message in
            switch message.role {
            case .user:
                return true
            case .assistant:
                if message.id == streamingID { return true }
                let visible = MarkdownLite.strippingThink(message.content)
                    .trimmingCharacters(in: .whitespacesAndNewlines)
                return !visible.isEmpty || !(message.toolCalls?.isEmpty ?? true)
            case .system, .tool:
                return false
            }
        }
    }

    /// 지금 스트리밍 중인 어시스턴트 버블 id (send()가 마지막에 append한 것). 없으면 nil. (T-116)
    private var streamingAssistantID: UUID? {
        guard isWorking, let last = messages.last, last.role == .assistant else { return nil }
        return last.id
    }

    private func loadHistory() async {
        if appSettings.isDemoMode {
            messages = DemoData.messages(for: sessionId)
            return
        }
        isLoadingHistory = true
        do {
            messages = try await appSettings.hermesClient.fetchMessages(sessionId: sessionId)
            historyError = nil
        } catch {
            historyError = "대화 기록을 불러오지 못했습니다: \(error.localizedDescription)"
        }
        isLoadingHistory = false
    }

    func addAttachment(filename: String, data: Data) {
        guard data.count <= Self.maxAttachmentBytes else {
            messages.append(ChatMessage(
                role: .assistant,
                content: "[에러] \(filename): 50MB를 초과해 첨부할 수 없습니다.",
                toolCalls: nil,
                createdAt: .now
            ))
            return
        }
        // HEIC/HEIF는 LLM 비전 API가 거부하므로 업로드 전 JPEG로 정규화한다 (T-130).
        // 세 진입점(사진선택·파일·글라스 워처)이 모두 여기를 거치므로 한 곳에서 해결된다.
        let (data, filename) = Self.normalizedImageForUpload(data: data, filename: filename)
        // 이미지만 디코딩 (비이미지 파일의 불필요한 UIImage 시도 회피)
        let thumbnail = ChatImageSource.isImagePath(filename)
            ? UIImage(data: data)?.preparingThumbnail(of: CGSize(width: 72, height: 72))
            : nil
        attachments.append(PendingAttachment(filename: filename, data: data, thumbnail: thumbnail))
    }

    /// HEIC/HEIF 이미지를 JPEG로 변환하고 확장자를 `.jpg`로 바꾼다 (T-130).
    /// HEIC가 아니거나 변환 실패면 원본을 그대로 돌려준다(방어). PNG/JPEG/WebP/GIF·비이미지는 무변환.
    /// UIImage가 EXIF 방향을 반영해 디코드하고 jpegData가 정방향으로 기록하므로 회전 문제는 없다.
    private static func normalizedImageForUpload(data: Data, filename: String) -> (Data, String) {
        let ext = (filename as NSString).pathExtension.lowercased()
        guard ext == "heic" || ext == "heif" else { return (data, filename) }
        guard let jpeg = UIImage(data: data)?.jpegData(compressionQuality: 0.85) else {
            return (data, filename)
        }
        let base = (filename as NSString).deletingPathExtension
        return (jpeg, base + ".jpg")
    }

    func removeAttachment(id: UUID) {
        attachments.removeAll { $0.id == id }
    }

    /// 글라스로 찍은 사진이 보관함에 도착했을 때 (PhotoImportWatcher 콜백, T-126).
    /// 사진을 대기 첨부로 붙이고, 음성 컨트롤러에 도착 알림+청취 흐름을 위임한다.
    /// (전송은 컨트롤러 경로로 일원화해 음성 루프와의 이중 전송을 피한다.)
    func handleCapturedPhoto(filename: String, data: Data) {
        let before = attachments.count
        addAttachment(filename: filename, data: data)
        // 50MB 초과 등으로 첨부가 거부됐으면 알림/전송을 진행하지 않는다
        guard attachments.count > before else { return }
        // 감지 가시성: 카운트·파일명 기록 + 햅틱 (서버 응답과 무관하게 "도착"을 알 수 있게, T-129)
        glassesPhotosDetected += 1
        lastGlassesPhotoName = filename
        UINotificationFeedbackGenerator().notificationOccurred(.success)
        VoiceConversationController.shared.announcePhotoArrival(viewModel: self)
    }

    /// 글라스 모드를 끌 때 감지 표시를 초기화한다 (T-129)
    func resetGlassesStatus() {
        glassesPhotosDetected = 0
        lastGlassesPhotoName = nil
    }

    func send() async {
        let text = inputText.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !text.isEmpty || !attachments.isEmpty, !isWorking else {
            voiceStreamHandler?("", true)
            return
        }

        if appSettings.isDemoMode {
            await sendDemoResponse(userText: text.isEmpty ? "(첨부 파일)" : text)
            return
        }

        isWorking = true
        defer { isWorking = false }

        var outgoing = text
        if !attachments.isEmpty {
            do {
                outgoing = try await uploadAttachmentsAndPrepend(to: text)
            } catch {
                messages.append(ChatMessage(
                    role: .assistant,
                    content: "[에러] 첨부 업로드 실패: \(error.localizedDescription)",
                    toolCalls: nil,
                    createdAt: .now
                ))
                voiceStreamHandler?("", true)
                return
            }
        }

        let isFirstMessage = messages.isEmpty
        inputText = ""
        messages.append(ChatMessage(role: .user, content: outgoing, toolCalls: nil, createdAt: .now))

        let startedAt = Date.now
        let stream = appSettings.hermesClient.streamChat(sessionId: sessionId, message: outgoing)

        // 대기 어시스턴트 버블은 do-scope 바깥에 유지한다 — 스트림이 도중에 끊겨도(catch)
        // 회수 폴링이 성공하면 이 버블에 최종 답을 채우고, 실패했을 때만 에러 버블로 대체한다.
        // 이렇게 하면 "일시적 연결 끊김 → 서버가 답을 저장 → 앱은 [에러]"인 회귀를 막을 수 있다 (T-149).
        //
        // ⚠️ 인덱스가 아닌 **id**로 자리표시자를 추적한다. await 사이에 이 배열이 다른
        // 경로(예: `addAttachment`가 50MB 초과 알림, `handleCapturedPhoto`, history 재로드)로
        // 변경되면 인덱스가 밀려 잘못된 버블을 덮어쓸 수 있다. 매번 id로 재해석하고 사라졌으면
        // 조용히 건너뛴다.
        var assistant = ChatMessage(role: .assistant, content: "", toolCalls: [], createdAt: .now)
        let assistantId = assistant.id
        messages.append(assistant)
        var toolDictionary: [String: ToolCall] = [:]
        var caughtError: Error?

        do {
            for try await update in stream {
                switch update {
                case .content(let chunk):
                    assistant.content += chunk
                    voiceStreamHandler?(assistant.content, false)
                case .toolCallUpdate(let id, let name, let argumentsDelta):
                    if let existing = toolDictionary[id] {
                        let merged = (existing.arguments ?? [:])
                            .merging(["_delta": argumentsDelta], uniquingKeysWith: { cur, _ in cur })
                        toolDictionary[id] = ToolCall(id: existing.id, name: existing.name, arguments: merged, result: existing.result)
                    } else {
                        toolDictionary[id] = ToolCall(id: id, name: name, arguments: ["_delta": argumentsDelta], result: nil)
                    }
                }
                // 배열이 흔들려도 안전: id로 재해석 후 갱신. 사라졌으면 이번 청크는 버린다.
                if let idx = messages.firstIndex(where: { $0.id == assistantId }) {
                    messages[idx].content = assistant.content
                    // 도구 실행 중에도 칩 카운트가 실시간 갱신되도록 (T-104)
                    messages[idx].toolCalls = Array(toolDictionary.values)
                }
            }
        } catch is CancellationError {
            finalizeCancellation(assistantId: assistantId, assistantContent: assistant.content)
            return
        } catch {
            caughtError = error
        }

        // 사용자 중지는 두 경로로 도착한다:
        //  ① 스트림이 CancellationError를 throw (위 catch)
        //  ② HermesAPIClient.streamChat이 URLError.cancelled를 조용히 finish로 승격 → for-await가 자연 종료
        // ②의 경우 for-await는 던지지 않으므로 Task.isCancelled로 잡는다. 이렇게 하지 않으면
        // 사용자 중지가 300초 회수 폴링으로 이어져 화면이 "생성 중" 상태에 머문다 (T-149 수용 기준).
        if Task.isCancelled {
            finalizeCancellation(assistantId: assistantId, assistantContent: assistant.content)
            return
        }

        // 스트리밍이 정상 종료했든, 도중에 끊겼든 여기서 지금까지의 버퍼를 확정한다.
        if let idx = messages.firstIndex(where: { $0.id == assistantId }) {
            messages[idx].content = assistant.content
            messages[idx].toolCalls = Array(toolDictionary.values)
        }

        // 회수 판정 (T-116/T-149):
        // ① 스트림은 정상 종료됐지만 보이는 본문이 비었을 때 — 게이트웨이가 세션에는 쓰지만
        //    SSE로는 안 보내는 실기기 버그(T-114와 동종). 폴링해 회수한다.
        // ② 스트림이 좁은 화이트리스트에 해당하는 **전송 오류**(networkConnectionLost/timedOut,
        //    조기 EOF 포함)로 끊겼을 때 — 서버 백그라운드 태스크가 계속 돌아 몇 초 뒤 state.db에
        //    답을 기록한 사례 확인됨 (2026-08-05 aiohttp ClientConnectionResetError).
        // ③ 명시적 서버/인증/프로토콜 오류(HermesAPIError.serverError/.unauthorized/파서 에러)는
        //    폴링 대상이 아니다 — 즉시 원본 오류로 표면화한다. (`event: error`, 401, 5xx 등)
        let streamedVisible = MarkdownLite.strippingThink(assistant.content)
            .trimmingCharacters(in: .whitespacesAndNewlines)
        let hasTool: Bool
        if let idx = messages.firstIndex(where: { $0.id == assistantId }) {
            hasTool = !(messages[idx].toolCalls?.isEmpty ?? true)
        } else {
            hasTool = false
        }
        let isRecoverableTransport = caughtError.map(Self.isRecoverableTransportError) ?? false
        let shouldRecover = isRecoverableTransport || (caughtError == nil && streamedVisible.isEmpty && !hasTool)

        if shouldRecover {
            let deadline: TimeInterval = Self.recoveryDeadline(
                caughtError: caughtError,
                streamedContent: assistant.content
            )
            if let recovered = await pollForMissedReply(deadline: deadline) {
                if let idx = messages.firstIndex(where: { $0.id == assistantId }) {
                    messages[idx].content = recovered
                }
                assistant.content = recovered
                caughtError = nil  // 회수 성공 — 에러 없이 진행
            }
            // 회수 실패 시 원본 오류는 보존됨 (caughtError). 폴링 자체의 취소는 nil로 조용히 종료.
            // 폴링 중 사용자가 중지했으면 조용히 마무리 (에러 버블 방지)
            if Task.isCancelled {
                finalizeCancellation(assistantId: assistantId, assistantContent: assistant.content)
                return
            }
        }

        if let error = caughtError {
            // 실행 가능한 에러 버블을 남긴다. 대기 자리표시자가 비어 있으면 정리한다.
            if let idx = messages.firstIndex(where: { $0.id == assistantId }),
               messages[idx].content.isEmpty && (messages[idx].toolCalls?.isEmpty ?? true) {
                messages.remove(at: idx)
            }
            messages.append(ChatMessage(
                role: .assistant,
                content: "[에러] \(error.localizedDescription)",
                toolCalls: nil,
                createdAt: .now
            ))
            voiceStreamHandler?("", true)
            return
        }

        // 정상 종료 또는 회수 성공. TTS·알림·자동 제목은 정확히 한 번씩 실행된다.
        voiceStreamHandler?(assistant.content, true)

        if let idx = messages.firstIndex(where: { $0.id == assistantId }),
           messages[idx].content.isEmpty && (messages[idx].toolCalls?.isEmpty ?? true) {
            messages.remove(at: idx)
        }

        notifyCompletionIfBackground(startedAt: startedAt, responseText: assistant.content)

        if isFirstMessage {
            await updateAutoTitle(from: text)
        }
    }

    /// 사용자가 중지했을 때의 마무리 — 부분 발언은 살리고 완전히 빈 버블만 제거한다 (T-149).
    /// 회수 폴링 없이, 에러 버블 없이 조용히 종료한다. id 기반 재해석으로 인덱스 밀림에 안전.
    private func finalizeCancellation(assistantId: UUID, assistantContent: String) {
        if let idx = messages.firstIndex(where: { $0.id == assistantId }) {
            messages[idx].content = assistantContent
            if messages[idx].content.isEmpty && (messages[idx].toolCalls?.isEmpty ?? true) {
                messages.remove(at: idx)
            }
        }
        voiceStreamHandler?(assistantContent, true)
    }

    /// 회수 폴링을 시도할 만한 **좁은** 전송 오류만 통과시킨다 (T-149 수용 기준).
    /// 서버/인증/프로토콜 오류는 여기서 걸러져 즉시 사용자에게 표면화된다.
    /// - `networkConnectionLost`: 조기 EOF·연결 리셋(HermesAPIClient가 승격) 포함
    /// - `timedOut`: 유휴 시간 초과
    /// 다른 URL 에러(도메인 해석 실패 등)나 `HermesAPIError.serverError`/`.unauthorized`는
    /// 폴링 대상이 아니다.
    nonisolated static func isRecoverableTransportError(_ error: Error) -> Bool {
        if case HermesAPIError.network(let underlying) = error,
           let urlError = underlying as? URLError {
            switch urlError.code {
            case .networkConnectionLost, .timedOut:
                return true
            default:
                return false
            }
        }
        return false
    }

    /// 회수 폴링 데드라인 (T-149) —
    /// - 스트림이 끊긴(caughtError) 경우 서버가 계속 답을 쓰고 있을 수 있으므로 넉넉히,
    ///   단 이미 부분 본문이 있으면 그 위에 이어질 가능성이 낮아 짧게.
    /// - 정상 종료했지만 보이는 내용이 비면 T-116의 정책을 그대로 유지한다.
    /// 이 함수는 회수 대상 판정을 먼저 통과한 뒤에만 호출된다 — 여기서 다시 판정하지 않는다.
    nonisolated static func recoveryDeadline(caughtError: Error?, streamedContent: String) -> TimeInterval {
        if caughtError != nil {
            return streamedContent.isEmpty ? 300 : 30
        }
        return streamedContent.isEmpty ? 300 : 6
    }

    /// 스트림이 빈 채 끝났을 때(SSE 미전송 실기기 버그) 세션 기록을 2초 간격으로 폴링해
    /// 마지막 user 메시지 뒤의 "보이는" assistant 답을 회수한다 (T-116).
    /// 판정은 토론룸 폴백과 동일한 `DiscussionViewModel.missedReply`를 재사용한다 — 직전 턴
    /// 답을 오인하지 않도록 지금까지 보낸 user 메시지 수로 앵커링한다. 타임아웃/취소 시 nil.
    private func pollForMissedReply(deadline: TimeInterval) async -> String? {
        let expectedUserCount = messages.filter { $0.role == .user }.count
        let limit = Date.now.addingTimeInterval(deadline)
        while Date.now < limit {
            if let server = try? await appSettings.hermesClient.fetchMessages(sessionId: sessionId),
               let reply = DiscussionViewModel.missedReply(in: server, expectedUserCount: expectedUserCount) {
                return reply
            }
            do {
                try await Task.sleep(nanoseconds: 2_000_000_000)
            } catch {
                return nil // 취소
            }
        }
        return nil
    }

    /// 첨부를 Bridge로 업로드하고 맥미니 절대경로를 메시지 앞에 붙인다.
    /// 게이트웨이 chat API는 텍스트만 받으므로, Hermes가 자기 파일 도구로
    /// 경로를 읽게 하는 것이 정석 흐름이다 (PLAN §3 Phase 4).
    private func uploadAttachmentsAndPrepend(to text: String) async throws -> String {
        guard let bridge = appSettings.bridgeClient else {
            throw HermesAPIError.serverError(
                "첨부를 보내려면 설정 화면의 Hermes Bridge 섹션에 URL과 토큰을 입력하세요."
            )
        }
        var lines: [String] = []
        for attachment in attachments {
            let path = try await bridge.upload(
                data: attachment.data,
                filename: attachment.filename,
                profile: appSettings.selectedProfile.name
            )
            lines.append("[첨부: \(path)]")
        }
        attachments = []
        let header = lines.joined(separator: "\n")
        return text.isEmpty ? header : header + "\n\n" + text
    }

    /// 앱이 비활성(백그라운드/전환 중)이고 응답에 10초 이상 걸렸으면 로컬 알림 (T-094).
    /// 짧은 응답은 돌아왔을 때 바로 보이므로 알리지 않는다.
    private func notifyCompletionIfBackground(startedAt: Date, responseText: String) {
        guard UIApplication.shared.applicationState != .active,
              Date.now.timeIntervalSince(startedAt) >= 10 else { return }
        let preview = MarkdownLite.plainText(from: responseText)
            .trimmingCharacters(in: .whitespacesAndNewlines)
        NotificationService.shared.notify(
            title: "\(appSettings.selectedProfile.name) 응답 완료",
            body: preview.isEmpty ? "응답이 도착했습니다." : String(preview.prefix(80)),
            id: "chat-done-\(sessionId)"
        )
    }

    /// 첫 메시지의 앞부분으로 세션 제목을 자동 설정한다.
    private func updateAutoTitle(from text: String) async {
        let words = text.split(separator: " ").prefix(6).joined(separator: " ")
        let title = String(words.prefix(40))
        guard !title.isEmpty else { return }

        try? await appSettings.hermesClient.updateSessionTitle(id: sessionId, title: title)
        if var session = appSettings.sessions.first(where: { $0.id == sessionId }) {
            session.title = title
            appSettings.updateSession(session)
        }
    }

    // MARK: - Demo Mode

    @MainActor
    private func sendDemoResponse(userText: String) async {
        isWorking = true
        defer { isWorking = false }

        inputText = ""
        attachments = []
        messages.append(ChatMessage(role: .user, content: userText, createdAt: .now))

        var assistant = ChatMessage(role: .assistant, content: "", createdAt: .now)
        let assistantIndex = messages.count
        messages.append(assistant)

        let response = await DemoData.nextResponse()
        // 스트리밍처럼 보이게 단어 단위로 삽입
        let words = response.split(separator: " ", omittingEmptySubsequences: false).map(String.init)
        for word in words {
            try? await Task.sleep(for: .milliseconds(30))
            assistant.content += (assistant.content.isEmpty ? "" : " ") + word
            messages[assistantIndex] = assistant
            voiceStreamHandler?(assistant.content, false)
        }
        voiceStreamHandler?(assistant.content, true)
    }
}
