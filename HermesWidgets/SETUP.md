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
