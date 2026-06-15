import SwiftUI
import WidgetKit

/// HermesChat 위젯 번들 (T-135).
/// 홈 화면/잠금화면 위젯과 (iOS 18+) 제어센터 컨트롤을 한데 노출한다.
/// 각 탭 요소는 `StartVoiceInputIntent`를 실행해 앱을 띄우고 음성 대기 모드로 진입시킨다.
@main
struct HermesWidgetBundle: WidgetBundle {
    var body: some Widget {
        VoiceInputWidget()
        if #available(iOS 18.0, *) {
            VoiceControl()
        }
    }
}
