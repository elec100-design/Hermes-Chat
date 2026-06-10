# BUILD_STATUS

## T-012 블로커 — 해소됨 (2026-06-10, Claude)

- 원래 증상: xcodebuild가 `.../HermesChat/Services/ProfileDetailView.swift` 를 빌드 입력으로
  요구하지만 실제 파일은 `HermesChat/Views/ProfileDetailView.swift`
- 원인 1: pbxproj에서 ProfileDetailView.swift의 fileRef가 **Services 그룹의 children**에
  들어가 있었다 (Views 그룹이 아니라). pbxproj 경로는 그룹 경로 + path로 계산되므로
  Services/ProfileDetailView.swift를 찾게 된다. → Views 그룹으로 이동 완료.
- 원인 2: T-012 커밋이 T-011의 BridgeClient.swift pbxproj 등록 4곳을 덮어써서 유실시켰다.
  ProfileDetailView가 BridgeClient를 참조하므로 그룹을 고쳐도 컴파일이 깨졌을 것. → 재등록 완료.
- 추가: ProfileDetailView가 빈 껍데기(컴파일 불가 코드 포함)였던 것을 실제 기능
  (모델 선택 / SOUL.md 편집 / Gateway 재시작)으로 교체했고, **설정 화면 프로필 행에
  ⓘ 버튼**을 달아 화면 진입 경로를 만들었다 (이게 없으면 빌드돼도 폰에서 안 보인다).

다음 빌드 검증 대상: T-011, T-012, T-014 (모두 NEEDS-BUILD)
