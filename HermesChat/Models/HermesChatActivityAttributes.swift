import ActivityKit
import Foundation

/// 채팅 응답 생성 상태를 잠금화면/다이내믹 아일랜드에 라이브 표시하기 위한 Live Activity 속성 (T-150).
///
/// 이 타입은 **앱 타깃과 위젯 익스텐션 타깃 양쪽 멤버십**에 속해야 한다
/// (HermesWidgets/SETUP.md §2의 StartVoiceInputIntent 공유 패턴과 동일):
/// 앱은 `Activity.request/update/end`에, 위젯은 `ActivityConfiguration` UI에 같은 타입을 참조한다.
struct HermesChatActivityAttributes: ActivityAttributes {
    /// 활동 도중 갱신되는 동적 상태
    public struct ContentState: Codable, Hashable {
        /// 현재 진행 단계
        var phase: Phase
        /// 지금까지 생성된 응답 미리보기 (think/마크다운 제거된 평문, 짧게 자름)
        var preview: String

        enum Phase: String, Codable, Hashable {
            case thinking   // 사고 중(<think> 또는 본문 출력 전)
            case streaming  // 본문 생성 중
            case done       // 완료
        }
    }

    /// 프로필명 (활동 수명 동안 고정)
    var profileName: String
    /// 세션 제목 (없으면 빈 문자열)
    var sessionTitle: String
    /// 시작 시각 — 경과시간 타이머 표시용
    var startedAt: Date
}
