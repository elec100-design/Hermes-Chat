# HermesWidgets 위젯 익스텐션 설정 (T-135)

이 폴더의 Swift 파일들은 **새 Widget Extension 타깃**에 속해야 빌드된다.
objectVersion 77 pbxproj에 신규 앱 익스텐션 타깃을 손으로 추가하면 프로젝트 파싱이
깨질 위험이 커서, 타깃 생성은 **Xcode GUI로** 하는 것을 권장한다. 절차:

## 1. 타깃 생성 (Xcode)
1. Xcode에서 `HermesChat.xcodeproj` 열기
2. File ▸ New ▸ Target… ▸ **Widget Extension** 선택
3. Product Name: `HermesWidgets`
   - **Include Live Activity** 체크 해제
   - **Include Configuration App Intent** 체크 해제 (StaticConfiguration 사용)
   - Embed in Application: `HermesChat`
4. 생성 시 Xcode가 만든 기본 `HermesWidgets.swift`/번들 파일은 삭제하고,
   이 폴더의 파일들(`HermesWidgetBundle.swift`, `VoiceInputWidget.swift`,
   `VoiceControl.swift`)을 타깃에 추가한다. `Info.plist`도 이 폴더 것으로 교체.

## 2. 공유 소스 멤버십 (중요)
위젯 타깃이 컴파일되려면 다음 두 파일이 **앱 타깃과 위젯 타깃 모두**에 속해야 한다
(File Inspector ▸ Target Membership에서 `HermesWidgets` 체크):
- `HermesChat/Intents/StartVoiceInputIntent.swift`
- `HermesChat/Services/VoiceEntryCoordinator.swift`

이유: 위젯의 `Button(intent:)` / `ControlWidgetButton(action:)`이 `StartVoiceInputIntent`
타입을 참조하고, 그 `perform()`은 `VoiceEntryCoordinator`를 참조한다.
`openAppWhenRun = true`라 실제 `perform()`은 **앱 프로세스**에서 실행되므로 코디네이터
싱글턴은 앱 인스턴스로 해석된다(위젯 프로세스는 앱을 띄우기만 함). 위젯 타깃에는 타입이
컴파일되기만 하면 된다.

## 3. 빌드 설정
- 위젯 타깃 `IPHONEOS_DEPLOYMENT_TARGET = 17.0` (앱과 동일). `VoiceControl`은
  `@available(iOS 18.0, *)`로 분기되어 17에서도 빌드된다.
- 번들 ID는 `com.hermes.chatios.HermesWidgets` 형태로 자동 설정됨.

## 4. 검증
```
xcodebuild -project HermesChat.xcodeproj -scheme HermesChat \
  -destination 'generic/platform=iOS Simulator' CODE_SIGNING_ALLOWED=NO build
```
앱 스킴 빌드 시 위젯 appex가 임베드된다. 신규 타깃이 프로젝트 파싱을 깨면 여기서 즉시 드러난다.

실기기: 홈 화면에 "Hermes 음성 입력" 위젯 추가 → 탭 → 앱이 뜨고 음성 대기 진입.
잠금화면 accessory 위젯, iOS 18 제어센터 컨트롤도 동일 동작 확인.

## 5. Live Activity (T-150, Phase C-1)

채팅 응답 생성 상태를 잠금화면/다이내믹 아일랜드에 라이브로 띄우는 Live Activity가 추가됐다.
앱측 코드(`HermesChat/Models/HermesChatActivityAttributes.swift`, `HermesChat/Services/LiveActivityManager.swift`)는
앱 타깃에 등록돼 **빌드에 포함**되고, `ChatViewModel.send()`가 start/update/end를 호출한다.
위젯이 없으면 매니저가 조용히 no-op 하므로 앱은 정상 동작한다.

**실제 표시되게 하려면** (위 1번에서 위젯 익스텐션 타깃을 만든 뒤):
1. `HermesWidgets/HermesChatLiveActivity.swift`를 **위젯 익스텐션 타깃**에 추가.
2. `HermesChat/Models/HermesChatActivityAttributes.swift`를 **앱 타깃 + 위젯 타깃 양쪽 멤버십**에 추가
   (2번 절의 StartVoiceInputIntent와 동일 — 앱은 `Activity.request`, 위젯은 `ActivityConfiguration`에 사용).
3. `HermesWidgetBundle`의 `body`에 `HermesChatLiveActivity()`를 추가.
4. 앱 `Info.plist`에 `NSSupportsLiveActivities = YES`가 이미 들어가 있다(등록 완료).
5. 빌드 후 실기기에서 채팅 전송 → 잠금화면/다이내믹 아일랜드에 "생성 중 · 경과 · 미리보기" 확인.

백그라운드(앱 종료 시)에서도 Live Activity를 갱신하려면 ActivityKit 푸시 토큰 → APNs(T-151/T-152)가
필요하다. C-1은 앱이 떠 있는 동안의 직접 갱신만 담당한다.
