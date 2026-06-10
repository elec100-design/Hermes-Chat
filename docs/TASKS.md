# TASKS — 작업 상태 보드 (단일 진실원본)

> 규칙: 작업을 시작하면 status를 `DOING(에이전트명, 날짜)`으로 바꾸고 **같은 커밋**에 포함.
> 끝나면 `NEEDS-BUILD`(맥에서 빌드 미검증) 또는 `DONE`(빌드+실기기 확인). 막히면 `BLOCKED(사유)`.
> 상태: `TODO` `DOING` `NEEDS-BUILD` `BLOCKED` `DONE`

## 즉시 (사람 또는 맥미니 Hermes가 1회 수행)

| ID | 작업 | 상태 |
|----|------|------|
| T-000 | 맥미니에서 `bash scripts/setup_profiles_api.sh <API_KEY>` 실행 → 프로필별 API 서버 활성화 | DONE (06-10, default+6프로필 8642~8648 헬스체크 통과) |
| T-001 | Xcode에서 `claude/busy-meitner-lhc5os` 브랜치 빌드 + 실기기에서 프로필 전환 확인 | DONE (Hermes, 06-10) |

## Phase 2-2 / 2-3 — 프로필 전환 · 자동제목 · 검색

| ID | 작업 | 파일 | 상태 |
|----|------|------|------|
| T-002 | HermesProfile 모델 (name+port) | `Models/ProfileModels.swift` | DONE (06-10) |
| T-003 | AppSettings 프로필 영속화·전환·세대카운터·포트스캔 자동검색 | `Services/AppDefaults.swift` | DONE (06-10) |
| T-004 | 세션 목록 프로필 드롭다운 + `.searchable` 검색 | `Views/SessionListView.swift` | DONE (06-10) |
| T-005 | 설정 프로필 섹션 (목록/추가/삭제/자동검색) | `Views/SettingsView.swift` | DONE (06-10) |
| T-006 | 첫 메시지 자동 제목 (PATCH /api/sessions) | `ViewModels/ChatViewModel.swift`, `Services/HermesAPIClient.swift` | DONE (06-10) |

## Phase 3 — 설정 확장 + Bridge

| ID | 작업 | 파일 | 상태 |
|----|------|------|------|
| T-010 | Bridge를 맥미니 LaunchAgent로 배포 (`server/README.md` 절차) | (맥미니) | DONE (06-10, :8765 기동·7프로필 응답 확인) |
| T-011 | BridgeClient.swift 신규 (**pbxproj 등록!** HANDOFF §4) — profiles/restart/soul/upload/kanban | `Services/BridgeClient.swift` (신규) | NEEDS-BUILD (Claude, 06-10) |
| T-012 | 프로필 상세 화면: 모델 선택, SOUL.md 편집기, Gateway restart 버튼(확인 다이얼로그) | `Views/ProfileDetailView.swift` (신규) | TODO |
| T-013 | Skills & Toolsets 읽기전용 화면 (`GET /v1/skills`, `/v1/toolsets`) | `Views/SkillsView.swift` (신규) | TODO |
| T-014 | 설정에 Bridge URL/토큰 필드 + Bridge 기반 프로필 목록(포트스캔은 폴백) | `Views/SettingsView.swift`, `Services/AppDefaults.swift` | TODO |

## Phase 4 — 첨부

| ID | 작업 | 파일 | 상태 |
|----|------|------|------|
| T-020 | 업로드 → 절대경로를 메시지에 prepend 하는 전송 흐름 | `ViewModels/ChatViewModel.swift` | TODO |
| T-021 | 입력창 `+` 버튼: PhotosPicker + fileImporter(드라이브 포함), 첨부 칩 UI | `Views/ChatView.swift` | TODO |
| T-022 | Info.plist NSPhotoLibraryUsageDescription | `Resources/Info.plist` | TODO |

## Phase 5 — 프로필 보드

| ID | 작업 | 파일 | 상태 |
|----|------|------|------|
| T-040 | ProfileBoardView 2×3 그리드 (온라인 상태 프로브 포함) | `Views/ProfileBoardView.swift` (신규) | TODO |
| T-041 | 루트 TabView 전환 (보드/세션/칸반/설정) | `HermesChatApp.swift` | TODO |

## Phase 6 — 칸반

| ID | 작업 | 파일 | 상태 |
|----|------|------|------|
| T-050 | KanbanBoard/KanbanTask/KanbanStatus 모델 (PLAN §3 Phase 6 JSON 스키마와 일치) | `Models/KanbanModels.swift` (신규) | TODO |
| T-051 | KanbanView: 보드 선택 + 페이지 스와이프 컬럼 + 카드 이동/편집, GET-병합-PUT 저장 | `Views/KanbanView.swift` (신규) | TODO |
| T-052 | 맥미니 Hermes에 칸반 스킬 등록 (HANDOFF 부록 B 내용) | (맥미니) | TODO |

## Phase 7 — 터미널/파일

| ID | 작업 | 파일 | 상태 |
|----|------|------|------|
| T-060 | 대시보드(:8000) WKWebView 임베드 탭 | `Views/DashboardWebView.swift` (신규) | TODO |
| T-061 | Bridge 읽기전용 /files, /logs 확장 + 네이티브 파일 브라우저 | `server/hermes_bridge.py`, `Views/FileBrowserView.swift` (신규) | TODO |

## Phase 8 — 품질

| ID | 작업 | 상태 |
|----|------|------|
| T-070 | API Key/토큰 Keychain 이전 | TODO |
| T-071 | SSE 실시간 스트리밍 (`URLSession.bytes`) + 미사용 `ApiClient.swift` 정리 | TODO |
| T-072 | 세션 페이지네이션 (limit/offset/has_more) | TODO |
| T-073 | iPad 레이아웃·다크모드 점검 | TODO |

## 빌드 검증 기록 (검증자가 갱신)

| 날짜 | 브랜치/커밋 | 결과 | 비고 |
|------|------------|------|------|
| 06-10 | claude/busy-meitner-lhc5os @ 9a9d64b | BUILD SUCCEEDED | Hermes 검증, 실기기 프로필 전환 확인 (T-001) |
