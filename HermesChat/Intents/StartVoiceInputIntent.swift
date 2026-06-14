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
/// 모든 phrase에 `\(.applicationName)`(앱 표시 이름 "헤르메스")이 들어가야 한다(Apple 필수).
/// "헤르메스"는 모음 종결이라 "으로" 대신 띄어쓰기/에게를 써 어색한 "헤르메스으로"를 피한다.
struct HermesShortcuts: AppShortcutsProvider {
    static var appShortcuts: [AppShortcut] {
        AppShortcut(
            intent: StartVoiceInputIntent(),
            phrases: [
                "\(.applicationName) 음성 입력 시작",
                "\(.applicationName) 음성 입력",
                "\(.applicationName) 시작",
                "\(.applicationName)에게 말하기",
                "Start voice input in \(.applicationName)",
                "Talk to \(.applicationName)"
            ],
            shortTitle: "음성 입력",
            systemImageName: "waveform"
        )
    }
}
