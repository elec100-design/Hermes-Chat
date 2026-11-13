# HermesChatTests

T-149 회귀 커버리지. `SSEParserTests`는 네이티브 Hermes SSE 이벤트 파싱과 레거시 OpenAI
청크 병존을 잠그고, `ChatRecoveryTests`는 스트림이 도중에 끊긴 뒤의 회수 판정 규칙
(`DiscussionViewModel.missedReply` 앵커링과 `ChatViewModel.recoveryDeadline`)을 잠근다.

## 테스트 타깃 (`HermesChatTests`)

`HermesChat.xcodeproj/project.pbxproj`에 `HermesChatTests` iOS Unit Testing Bundle 타깃이 등록되어 있으며 `SSEParserTests.swift`·`ChatRecoveryTests.swift`를 포함한다.

```bash
xcodebuild test -project HermesChat.xcodeproj -scheme HermesChat -destination 'platform=iOS Simulator,name=iPhone 17' CODE_SIGNING_ALLOWED=NO
```

## 왜 XCUITest / ChatViewModel 통합 테스트가 아닌가

`ChatViewModel.send()`는 `HermesAPIClient`(concrete final), URLSession, `AVAudioSession`
싱글턴에 얽혀 있어 프로토콜 도입 없이는 격리하기 어렵다. T-149의 범위는 "버그 재현
+ 회수" 단일 이슈이므로 스코프 폭발을 피해 순수 로직만 여기서 잠그고, 실기기 회귀는
docs/TASKS.md T-149의 시나리오로 검증한다.
