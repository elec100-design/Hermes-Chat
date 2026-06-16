# BUILD_STATUS

> 맥에서 빌드/실기기 검증이 필요한 항목 추적. 상세는 `docs/TASKS.md`가 단일 진실원본.

## 현재 대기 (2026-06-16)

브랜치 `claude/profile-cronjob-config-ui-bhh2ip` — 프로필 생성/삭제/모델 카탈로그/크론잡:

- **T-138** 프로필별 크론잡 조회·편집 (신규 `CronModels.swift`/`CronJobsView.swift`/`CronJobEditView.swift` — **pbxproj 등록 확인 필요**)
- **T-139~143** 프로필 생성('+' 카드) + 모델 카탈로그 선택. 생성은 `hermes profile create --clone-from default`로 확정 (신규 `CreateProfileView.swift` — pbxproj 등록 필요)
- **T-144** 생성 시 config.yaml 누락 진단/보정 (서버만, env 주입 + 검증/detail)
- **T-145** 프로필 삭제 (`DELETE /profiles/<n>`, ProfileDetailView 삭제 버튼)

검증 순서:
1. **브리지 재배포** — `cp ./server/hermes_bridge.py ~/.hermes/bridge/` + LaunchAgent 재기동 (HANDOFF §2.5). 안 하면 신규 엔드포인트가 "브리지 HTTP 404".
2. **Xcode 빌드** — 신규 Swift 파일(`CronModels`/`CronJobsView`/`CronJobEditView`/`CreateProfileView`)이 pbxproj에 등록됐는지 확인 후 빌드.
3. **실기기** — 프로필 생성 시 `~/.hermes/profiles/<name>/config.yaml` 생성 확인, 모델 드롭다운·저장, 삭제 버튼, 크론잡 편집.

> 생성이 또 실패하면 앱 에러배너에 hermes의 stdout/stderr가 그대로 뜨므로(T-144) 그 메시지로 원인 파악.

---

## 과거 기록

### T-012 블로커 — 해소됨 (2026-06-10, Claude)
- 증상: xcodebuild가 `Services/ProfileDetailView.swift`를 빌드 입력으로 요구하나 실제 파일은 `Views/ProfileDetailView.swift`였음.
- 원인 1: pbxproj에서 fileRef가 Views가 아닌 Services 그룹 children에 있었음 → Views 그룹으로 이동.
- 원인 2: T-012 커밋이 T-011의 BridgeClient.swift pbxproj 등록 4곳을 덮어써 유실 → 재등록.
- 추가: 빈 껍데기였던 ProfileDetailView를 실기능(모델 선택/SOUL 편집/Gateway 재시작)으로 교체하고 설정 화면 프로필 행에 ⓘ 버튼 추가.
