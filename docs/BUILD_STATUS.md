# BUILD_STATUS

## T-012 블로커 (2026-06-10)

- 증상: xcodebuild가 `.../HermesChat/Services/ProfileDetailView.swift` 를 빌드 입력으로 요구하지만 실제 파일은 `HermesChat/Views/ProfileDetailView.swift`
- 현재 상태: pbxproj에 Views 그룹 child로 ProfileDetailView.swift를 추가했으나 여전히 빌드 시스템이 Services 경로를 요구하는 상태
