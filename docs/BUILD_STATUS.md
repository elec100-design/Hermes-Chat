# BUILD_STATUS

> 맥에서 빌드/실기기 검증이 필요한 항목 추적. 상세는 `docs/TASKS.md`가 단일 진실원본.

## 현재 대기 (2026-06-16)

브랜치 `claude/busy-ritchie-cx2ohx` (PR #5) — 세션 핀·이름변경 (T-147, Phase 20):

- **T-147** 세션 목록 trailing 스와이프에 **이름변경**(pencil·파랑)·**고정/고정해제**(pin/pin.slash·주황)
  버튼 추가(삭제 왼편) + **풀 스와이프 자동 삭제 비활성화**(`allowsFullSwipe: false`). Pin은 서버
  미지원이라 `pinnedSessionIDs`(UserDefaults)에 로컬 보관 — 고정 세션 맨 위 정렬·행 `pin.fill` 표시.
  이름변경은 기존 `updateSessionTitle` API + `updateSession` 로컬 갱신을 alert로 연결.
  **기존 파일만 수정 → pbxproj 무수정, 브리지 무관.**

검증 순서:
1. **Xcode 빌드** — 기존 파일만 변경(`SessionListView.swift`·`AppDefaults.swift`)이라 그대로 빌드.
2. **실기기** — ①세션 행을 끝까지 밀어도 **자동 삭제 안 됨**(삭제 버튼 눌러야 삭제), ②이름변경
   alert로 제목 변경 → 즉시 반영·새로고침 후 유지(서버 반영), ③Pin 시 맨 위 이동+핀 아이콘,
   앱 재실행 후에도 핀 유지.

---

브랜치 `claude/youthful-archimedes-aqigr4` — 크론 중앙 관리 화면 (T-146, Phase 18 후속):

- **T-146** 프로필마다 흩어져있던 크론 시트를 **한 화면(CronManagerView)**으로 통합. 상단 드롭다운으로
  프로필 필터링, 각 잡에 **재개/일시정지 · 지금 실행 · 편집 · 삭제** 버튼 + 상태 배지·사람이 읽는 스케줄·최근/다음
  실행 시각. 우상단 **CREATE(+)** 로 새 잡 생성. 기존 파일만 수정 — `CronJobsView.swift` 내용을 `CronManagerView`로
  재작성(**pbxproj 무수정**), `CronJobEditView`를 생성·편집 양용으로 일반화.
  - 서버(브리지): `POST /profiles/<n>/cron`(새 잡 추가), `POST /profiles/<n>/cron/<id>/run`(즉시 실행 — `hermes cron run <id>`), `DELETE /profiles/<n>/cron/<id>`(제거), PUT 화이트리스트 `name` 추가.
  - 진입점: 프로필 보드 툴바 "크론 관리"(전체) + 카드 시계 버튼(해당 프로필로 필터된 같은 화면).

> ⚠️ **"지금 실행"의 CLI 형태(`hermes cron run <id>`)는 hermes-agent 버전에 따라 다를 수 있음** — 실패 시
> 앱 배너에 hermes stdout/stderr가 그대로 뜨므로 그 메시지로 실제 서브커맨드 확인 후 보정.

이전 브랜치 `claude/profile-cronjob-config-ui-bhh2ip` — 프로필 생성/삭제/모델 카탈로그/크론잡 (PR #3 병합):

- **T-138** 프로필별 크론잡 조회·편집 (신규 `CronModels.swift`/`CronJobsView.swift`/`CronJobEditView.swift`)
- **T-139~143** 프로필 생성('+' 카드) + 모델 카탈로그 선택. 생성은 `hermes profile create --clone-from default`로 확정
- **T-144** 생성 시 config.yaml 누락 진단/보정 (서버만, env 주입 + 검증/detail)
- **T-145** 프로필 삭제 (`DELETE /profiles/<n>`, ProfileDetailView 삭제 버튼)

검증 순서:
1. **브리지 재배포** — `cp ./server/hermes_bridge.py ~/.hermes/bridge/` + LaunchAgent 재기동 (HANDOFF §2.5). 안 하면 신규 엔드포인트가 "브리지 HTTP 404".
2. **Xcode 빌드** — pbxproj 무수정(기존 파일만 변경)이라 추가 등록 불필요. 그대로 빌드.
3. **실기기** — 프로필 보드 "크론 관리" → 드롭다운 필터, 잡 일시정지/재개·삭제·편집, "지금 실행" 결과 배너 확인.

> "지금 실행" 외 동작(필터·일시정지·편집·삭제)은 jobs.json 파일 조작이라 CLI 의존성 없음 — 우선 검증 가능.

---

## 과거 기록

### T-012 블로커 — 해소됨 (2026-06-10, Claude)
- 증상: xcodebuild가 `Services/ProfileDetailView.swift`를 빌드 입력으로 요구하나 실제 파일은 `Views/ProfileDetailView.swift`였음.
- 원인 1: pbxproj에서 fileRef가 Views가 아닌 Services 그룹 children에 있었음 → Views 그룹으로 이동.
- 원인 2: T-012 커밋이 T-011의 BridgeClient.swift pbxproj 등록 4곳을 덮어써 유실 → 재등록.
- 추가: 빈 껍데기였던 ProfileDetailView를 실기능(모델 선택/SOUL 편집/Gateway 재시작)으로 교체하고 설정 화면 프로필 행에 ⓘ 버튼 추가.
