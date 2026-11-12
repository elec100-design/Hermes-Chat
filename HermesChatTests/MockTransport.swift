import Foundation
@testable import HermesChat

/// URLSession.shared 요청을 가로채 테스트 응답을 돌려주는 URLProtocol.
/// URLProtocol.registerClass로 등록하면 URLSessionConfiguration.protocolClasses가
/// nil인 세션(shared 포함)에서 모든 요청을 가로챈다.
final class MockURLProtocol: URLProtocol {
    static var requestHandler: ((URLRequest) throws -> (HTTPURLResponse, Data))?

    override class func canInit(with request: URLRequest) -> Bool { true }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }

    override func startLoading() {
        guard let handler = MockURLProtocol.requestHandler else {
            client?.urlProtocol(self, didFailWithError: URLError(.unknown))
            return
        }
        do {
            let (response, data) = try handler(request)
            client?.urlProtocol(self, didReceive: response, cacheStoragePolicy: .notAllowed)
            client?.urlProtocol(self, didLoad: data)
            client?.urlProtocolDidFinishLoading(self)
        } catch {
            client?.urlProtocol(self, didFailWithError: error)
        }
    }

    override func stopLoading() {}
}

/// DiscussionTransport 모의 구현. 스트림 시나리오/에러 주입과 호출 기록을 제공한다.
///
/// 실서버와 비슷하게 동작하게 만들기 위해, streamChat은 받은 메시지를 user 메시지로,
/// 정상 수신한 내용은 assistant 메시지로 `messagesBySession`에 기록한다 —
/// 오케스트레이터의 폴백 폴링(missedReply)이 실제 서버 기록을 회수하는 경로를 그대로 검증할 수 있다.
@MainActor
final class MockTransport: DiscussionTransport {
    // MARK: 호출 기록
    private(set) var createdSessions: [(model: String?, systemPrompt: String?)] = []
    private(set) var updatedTitles: [(id: String, title: String)] = []
    private(set) var streamedMessages: [(sessionID: String, message: String)] = []
    private(set) var fetchedSessionIDs: [String] = []
    /// createSession이 만든 세션 id (생성 순서 = 참가 순서)
    private(set) var sessionIDs: [String] = []

    // MARK: 제어
    /// 모든 스트림에 방출할 content 청크
    var defaultStreamChunks: [String] = []
    /// 세션 생성 순서(0-base)별 createSession 에러
    var createSessionErrorAtIndex: [Int: Error] = [:]
    /// 참가 순서(0-base)별 스트림 에러
    var streamErrorsBySessionIndex: [Int: Error] = [:]
    /// fetchMessages가 던질 에러
    var fetchMessagesError: Error?
    /// 스트림이 빈 채 끝났을 때 대신 기록할 assistant 응답 (폴백 회수 시나리오)
    var persistReplyOnEmptyStream: String?
    /// true면 스트림을 열어두고 닫지 않는다 (취소 테스트용) — `finishAllStreams()`로 닫는다
    var hang: Bool = false
    /// streamChat마다 user 메시지를 세션 기록에 남길지 (실서버 동작 시뮬레이션)
    var autoAppendUserMessage = true

    /// 세션별 서버 메시지 기록 (fetchMessages가 반환)
    var messagesBySession: [String: [ChatMessage]] = [:]

    private var createCallIndex = -1
    private var sessionCounter = 0
    private var openContinuations: [AsyncThrowingStream<StreamUpdate, Error>.Continuation] = []

    // MARK: DiscussionTransport

    func createSession(model: String?, systemPrompt: String?) async throws -> Session {
        createCallIndex += 1
        createdSessions.append((model, systemPrompt))
        if let error = createSessionErrorAtIndex[createCallIndex] {
            throw error
        }
        sessionCounter += 1
        let id = "mock-session-\(sessionCounter)"
        sessionIDs.append(id)
        return Session(id: id, title: nil, preview: nil, updatedAt: .now, source: nil)
    }

    func updateSessionTitle(id: String, title: String) async throws {
        updatedTitles.append((id, title))
    }

    func streamChat(sessionId: String, message: String) -> AsyncThrowingStream<StreamUpdate, Error> {
        streamedMessages.append((sessionID: sessionId, message: message))
        if autoAppendUserMessage {
            messagesBySession[sessionId, default: []].append(ChatMessage(role: .user, content: message))
        }

        if let idx = sessionIDs.firstIndex(of: sessionId),
           let error = streamErrorsBySessionIndex[idx] {
            return AsyncThrowingStream { continuation in
                continuation.finish(throwing: error)
            }
        }

        if hang {
            return AsyncThrowingStream { continuation in
                self.openContinuations.append(continuation)
            }
        }

        let chunks = defaultStreamChunks
        let persistReply = persistReplyOnEmptyStream
        return AsyncThrowingStream { continuation in
            if !chunks.isEmpty {
                for chunk in chunks {
                    continuation.yield(.content(chunk))
                }
                messagesBySession[sessionId, default: []].append(
                    ChatMessage(role: .assistant, content: chunks.joined())
                )
            } else if let persistReply, !persistReply.isEmpty {
                // 서버가 답변은 기록했지만 SSE로는 안 보낸 상황
                messagesBySession[sessionId, default: []].append(
                    ChatMessage(role: .assistant, content: persistReply)
                )
            }
            continuation.finish()
        }
    }

    func fetchMessages(sessionId: String) async throws -> [ChatMessage] {
        fetchedSessionIDs.append(sessionId)
        if let error = fetchMessagesError { throw error }
        return messagesBySession[sessionId] ?? []
    }

    /// hang 스트림을 모두 정상 종료한다 (취소 테스트에서 stop() 후 호출)
    func finishAllStreams() {
        for continuation in openContinuations {
            continuation.finish()
        }
        openContinuations.removeAll()
    }
}
