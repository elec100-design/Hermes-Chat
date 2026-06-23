import ActivityKit
import Foundation

/// 채팅 응답 Live Activity의 수명을 관리한다 (T-150, Phase C-1: 포그라운드 구동).
///
/// `ChatViewModel.send()`가 전송 시 `start`, 본문 콜백에서 `update`, 완료/회수 후 `end`를 호출한다.
/// Live Activity 미지원·비활성(사용자가 설정에서 끔, 위젯 익스텐션 타깃 미생성 등)이면 모든
/// 호출이 조용히 no-op 한다 — 채팅 흐름에는 전혀 영향이 없다. 위젯 익스텐션 타깃이 만들어지면
/// (HermesWidgets/SETUP.md) 자동으로 잠금화면/다이내믹 아일랜드에 표시된다.
///
/// 백그라운드/종료 상태 갱신은 ActivityKit 푸시 토큰 → APNs(Phase B/T-151)가 필요하며 C-2로
/// 적층한다. C-1은 앱이 포그라운드인 동안의 직접 갱신만 담당한다.
@MainActor
final class LiveActivityManager {
    static let shared = LiveActivityManager()
    private init() {}

    private var activity: Activity<HermesChatActivityAttributes>?
    /// 업데이트 폭주 방지 — 최소 간격(초). done 단계는 즉시 반영한다.
    private var lastUpdate = Date.distantPast
    private static let minUpdateInterval: TimeInterval = 0.7

    private var isEnabled: Bool {
        ActivityAuthorizationInfo().areActivitiesEnabled
    }

    /// 응답 생성 시작 — 잠금화면/다이내믹 아일랜드에 "사고 중" 활동을 띄운다.
    func start(profileName: String, sessionTitle: String) {
        guard isEnabled, activity == nil else { return }
        let attributes = HermesChatActivityAttributes(
            profileName: profileName,
            sessionTitle: sessionTitle,
            startedAt: .now
        )
        let state = HermesChatActivityAttributes.ContentState(phase: .thinking, preview: "")
        do {
            activity = try Activity.request(
                attributes: attributes,
                content: ActivityContent(state: state, staleDate: nil)
            )
        } catch {
            activity = nil // 미지원/거부 — 조용히 무시
        }
    }

    /// 스트리밍 원문으로 진행 상태를 갱신한다. 활동이 없거나 스로틀에 걸리면 즉시 반환해
    /// think/마크다운 가공을 건너뛴다 — 위젯 타깃이 없어 활동이 안 떠 있는 일반 케이스에서
    /// 긴 응답의 매 청크 O(n) 가공 비용을 들이지 않는다.
    func updateStreaming(rawContent: String) {
        guard activity != nil,
              Date.now.timeIntervalSince(lastUpdate) >= Self.minUpdateInterval else { return }
        let visible = MarkdownLite.strippingThink(rawContent)
            .trimmingCharacters(in: .whitespacesAndNewlines)
        update(phase: visible.isEmpty ? .thinking : .streaming,
               preview: MarkdownLite.plainText(from: rawContent))
    }

    /// 진행 상태 갱신 — thinking/streaming은 스로틀, done은 즉시.
    func update(phase: HermesChatActivityAttributes.ContentState.Phase, preview: String) {
        guard let activity else { return }
        if phase != .done {
            guard Date.now.timeIntervalSince(lastUpdate) >= Self.minUpdateInterval else { return }
        }
        lastUpdate = .now
        let state = HermesChatActivityAttributes.ContentState(
            phase: phase,
            preview: String(preview.prefix(120))
        )
        Task { await activity.update(ActivityContent(state: state, staleDate: nil)) }
    }

    /// 활동 종료 — 완료 미리보기를 잠깐 남기고 닫는다.
    func end(preview: String) {
        guard let activity else { return }
        self.activity = nil
        let state = HermesChatActivityAttributes.ContentState(
            phase: .done,
            preview: String(preview.prefix(120))
        )
        Task {
            await activity.end(
                ActivityContent(state: state, staleDate: nil),
                dismissalPolicy: .after(.now + 4)
            )
        }
    }
}
