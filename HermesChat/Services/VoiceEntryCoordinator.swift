import SwiftUI

/// 외부 트리거(App Intent·Siri·URL 스킴·위젯)에서 음성 입력 대기 모드로 진입시키는 라우팅 코디네이터 (T-131).
///
/// 메타 글라스 템플 더블탭으로 앱을 cold-launch 하는 것은 메타 공개 API로 불가능하므로
/// (템플 탭은 OS 예약, MWDAT는 탭 제스처 이벤트 미제공), 앱 구동 경로는 Siri/위젯/URL이고
/// 글라스 탭은 앱이 실행/백그라운드 중일 때 AVRCP로 들어온다 (T-134).
///
/// 라우팅 상태는 순수 UI성·휘발성이라 무거운 `AppSettings`와 분리해 둔다 —
/// `HermesChatApp`·`SessionListView`·`ChatView`가 동시에 관찰한다.
@MainActor
final class VoiceEntryCoordinator: ObservableObject {
    static let shared = VoiceEntryCoordinator()

    /// 트리거가 세운 진입 요청 → 라우터가 소비. 라우팅(탭 전환·세션 push) 시작 시 즉시 비워
    /// 콜드 런치에서 `.task`와 `.onChange`가 모두 도는 재진입을 막는다.
    @Published var pendingVoiceEntry = false
    /// 라우터가 세운 발동 신호 → ChatView가 소비. push된 ChatView의 실제 ChatViewModel이
    /// 생성된 뒤 `voice.start`를 정확히 1회만 발동하기 위한 핸드오프.
    /// @Published라 이미 보이는 ChatView도 onChange로 즉시 반응한다.
    @Published private(set) var armChatVoiceStart = false
    /// 특정 세션을 지정한 경우(예: hermes://voice?session=ID). nil이면 "최근 세션 resume(없으면 신규)".
    @Published var targetSessionId: String?
    /// 세션 탭의 내비게이션 경로 — 코디네이터가 보유해 앱 레벨에서 프로그램적으로 push 한다.
    /// (SessionListView의 로컬 @State에서 승격)
    @Published var sessionsPath = NavigationPath()

    private init() {}

    /// 모든 트리거의 단일 진입점.
    func requestVoiceEntry(sessionId: String? = nil) {
        targetSessionId = sessionId
        pendingVoiceEntry = true
    }

    /// 라우터 진입 — pending을 즉시 비워 재진입을 막고 ChatView가 소비할 arm을 세운다.
    /// - Returns: 라우팅을 진행해야 하면 true (이미 다른 경로가 가져갔으면 false)
    func beginRouting() -> Bool {
        guard pendingVoiceEntry else { return false }
        pendingVoiceEntry = false
        armChatVoiceStart = true
        return true
    }

    /// 라우팅 실패(세션 생성 오류 등) 시 arm 취소.
    func cancelRouting() {
        armChatVoiceStart = false
        targetSessionId = nil
    }

    /// ChatView.onAppear가 호출 — arm되어 있으면 비우고 true 반환(`voice.start` 1회 발동).
    func consumeChatVoiceStart() -> Bool {
        guard armChatVoiceStart else { return false }
        armChatVoiceStart = false
        return true
    }
}
