import AppIntents
import SwiftUI
import WidgetKit

/// 제어센터 / 잠금화면 컨트롤 (iOS 18+) (T-135).
/// 버튼을 누르면 `StartVoiceInputIntent`가 실행되어 앱을 띄우고 음성 대기 모드로 진입한다.
@available(iOS 18.0, *)
struct VoiceControl: ControlWidget {
    let kind = "VoiceControl"

    var body: some ControlWidgetConfiguration {
        StaticControlConfiguration(kind: kind) {
            ControlWidgetButton(action: StartVoiceInputIntent()) {
                Label("Hermes 음성 입력", systemImage: "waveform")
            }
        }
        .displayName("Hermes 음성 입력")
        .description("Hermes Chat을 열고 음성 입력 대기 모드로 들어갑니다.")
    }
}
