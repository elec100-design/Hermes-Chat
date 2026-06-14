import AppIntents

/// "음성 입력 시작" — Siri·단축어·위젯에서 앱을 띄우고 음성 대기 모드로 진입시키는 App Intent (T-132).
///
/// 메타 글라스 템플 더블탭으로 앱을 cold-launch 하는 것은 메타 공개 API로 불가능하므로,
/// 강제 종료 상태에서의 "구동" 경로는 이 인텐트(Siri/위젯)와 URL 스킴이다.
/// `openAppWhenRun = true`라 perform()은 앱이 포그라운드로 뜬 뒤 호스트 앱 안에서 실행되어
/// `VoiceEntryCoordinator.shared` 참조가 정상 동작한다.
struct StartVoiceInputIntent: AppIntent {
    static var title: LocalizedStringResource = "음성 입력 시작"
    static var description = IntentDescription("Hermes Chat을 열고 음성 입력 대기 모드로 들어갑니다.")
    static var openAppWhenRun = true

    @MainActor
    func perform() async throws -> some IntentResult {
        VoiceEntryCoordinator.shared.requestVoiceEntry()
        return .result()
    }
}

/// Siri/단축어 앱에 인텐트를 노출. 사용자가 직접 추가하지 않아도 음성 명령으로 호출 가능 (T-132).
/// 모든 phrase에 `\(.applicationName)`(앱 표시 이름 "헤르메스챗", Info.plist CFBundleDisplayName)이
/// 들어가야 한다(Apple 필수). 사용자가 실제로 말하는 "헤르메스챗 실행해"와 정확히 맞도록
/// 표시 이름을 "헤르메스챗"으로 두고 "실행/실행해/열어" 등 자연스러운 한국어 동사를 모두 등록한다.
struct HermesShortcuts: AppShortcutsProvider {
    static var appShortcuts: [AppShortcut] {
        AppShortcut(
            intent: StartVoiceInputIntent(),
            phrases: [
                "\(.applicationName) 실행해",
                "\(.applicationName) 실행",
                "\(.applicationName) 열어",
                "\(.applicationName) 열어줘",
                "\(.applicationName) 시작",
                "\(.applicationName) 음성 입력 시작",
                "\(.applicationName) 음성 입력",
                "\(.applicationName)에게 말하기",
                "Start voice input in \(.applicationName)",
                "Open \(.applicationName)",
                "Talk to \(.applicationName)"
            ],
            shortTitle: "음성 입력",
            systemImageName: "waveform"
        )
    }
}
