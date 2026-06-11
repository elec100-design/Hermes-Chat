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
| T-011 | BridgeClient.swift 신규 (**pbxproj 등록!** HANDOFF §4) — profiles/restart/soul/upload/kanban | `Services/BridgeClient.swift` (신규) | DONE (06-10, T-012와 같은 빌드로 검증·실기기 SOUL 저장 확인) |
| T-012 | 프로필 상세 화면: 모델 선택, SOUL.md 편집기, Gateway restart 버튼(확인 다이얼로그) | `Views/ProfileDetailView.swift` (신규) | DONE (Hermes, 06-10) |
| T-013 | Skills & Toolsets 읽기전용 화면 (`GET /v1/skills`, `/v1/toolsets`) | `Views/SkillsView.swift` (신규) | DONE (06-11) |
| T-014 | 설정에 Bridge URL/토큰 필드 + Bridge 기반 프로필 목록(포트스캔은 폴백) | `Views/SettingsView.swift`, `Services/AppDefaults.swift` | DONE (06-10, T-012와 같은 빌드로 검증·실기기 확인) |

## Phase 4 — 첨부

| ID | 작업 | 파일 | 상태 |
|----|------|------|------|
| T-020 | 업로드 → 절대경로를 메시지에 prepend 하는 전송 흐름 | `ViewModels/ChatViewModel.swift` | DONE (06-11) |
| T-021 | 입력창 `+` 버튼: PhotosPicker + fileImporter(드라이브 포함), 첨부 칩 UI | `Views/ChatView.swift` | DONE (06-11) |
| T-022 | Info.plist NSPhotoLibraryUsageDescription | `Resources/Info.plist` | DONE (06-11) |

## Phase 5 — 프로필 보드

| ID | 작업 | 파일 | 상태 |
|----|------|------|------|
| T-040 | ProfileBoardView 2×3 그리드 (온라인 상태 프로브 포함) | `Views/ProfileBoardView.swift` (신규) | DONE (06-11) |
| T-041 | 루트 TabView 전환 (보드/세션/칸반/설정) | `HermesChatApp.swift` | DONE (06-11 — 칸반 탭은 T-051에서 추가) |

## Phase 6 — 칸반

| ID | 작업 | 파일 | 상태 |
|----|------|------|------|
| T-050 | KanbanBoard/KanbanTask/KanbanStatus 모델 (PLAN §3 Phase 6 JSON 스키마와 일치) | `Models/KanbanModels.swift` (신규) | DONE (06-11) |
| T-051 | KanbanView: 보드 선택 + 페이지 스와이프 컬럼 + 카드 이동/편집, GET-병합-PUT 저장 | `Views/KanbanView.swift` (신규) | DONE (06-11 — 칸반 탭도 추가됨) |
| T-052 | 맥미니 Hermes에 칸반 스킬 등록 (HANDOFF 부록 B 내용) | (맥미니) | DONE (06-11 — 부록 B를 내장 칸반 기준 v2로 재작성해 직접 배포, Phase 9 참조) |

## Phase 7 — 터미널/파일

| ID | 작업 | 파일 | 상태 |
|----|------|------|------|
| T-060 | 대시보드(:8000) WKWebView 임베드 탭 | `Views/DashboardWebView.swift` (신규) | DONE (06-11) |
| T-061 | Bridge 읽기전용 /files, /logs 확장 + 네이티브 파일 브라우저 | `server/hermes_bridge.py`, `Views/FileBrowserView.swift` (신규) | DONE (06-11 — **브리지 재배포 필요**, 프로필 상세에 로그 보기 추가) |

## Phase 8 — 품질

| ID | 작업 | 상태 |
|----|------|------|
| T-070 | API Key/토큰 Keychain 이전 | DONE (06-11 — 전역 apiKey/bridgeToken 이관, 프로필별 apiKey는 UserDefaults 잔존) |
| T-071 | SSE 실시간 스트리밍 (`URLSession.bytes`) + 미사용 `ApiClient.swift` 정리 | DONE (06-11 — tool_calls 디코딩 버그도 수정) |
| T-072 | 세션 페이지네이션 (limit/offset/has_more) | DONE (06-11 — 50개 단위, 목록 끝 도달 시 자동 로드) |
| T-073 | iPad 레이아웃·다크모드 점검 | DONE (06-11 — 코드 차원 수정 완료, 최종 확인은 실기기에서) |
| T-074 | 세션 탭 상단 메뉴를 소스 필터 전용으로 (프로필 선택은 보드 탭으로 일원화, 제목=프로필명) | DONE (06-11 — 사용자 요청) |
| T-075 | 새 세션 만들기 디코딩 실패 수정 ("The data couldn't be read...") — 생성 응답 형식 단계적 해석 | DONE (06-11 — 버그 수정) |

## Phase 9 — 내장 칸반 통합 (2026-06-11)

> 배경: 부록 B의 JSON 파일 칸반은 hermes-agent 내장 칸반(kanban.db + 게이트웨이 디스패처 +
> 대시보드 `:8000/kanban`)과 별개라서, 폰에서 만든 보드가 대시보드에 안 보이고 작업도
> 실행되지 않았다. Bridge·앱·스킬을 모두 내장 칸반으로 전환.

| ID | 작업 | 파일 | 상태 |
|----|------|------|------|
| T-080 | Bridge 칸반 API를 내장 칸반으로 교체 — 읽기는 kanban.db sqlite 직접, 쓰기(create/promote/block/unblock/complete/archive/comment)는 `hermes kanban` CLI 경유. PUT 전체교체 제거 | `server/hermes_bridge.py` | DONE (06-11 — 재배포 + curl 검증 완료) |
| T-081 | 앱 칸반을 내장 칸반 스키마로 전환 — 상태 7개(scheduled/running 추가), 보드 목록 카운트, 카드 액션 메뉴(실행/보류/완료/아카이브), 새 작업 시트(담당 프로필 + 시작 방식), GET-병합-PUT 제거 | `Models/KanbanModels.swift`, `Services/BridgeClient.swift`, `Views/KanbanView.swift` | DONE (06-11 — 빌드 검증 완료) |
| T-082 | 칸반 스킬 v2 배포 (`~/.hermes/skills/kanban/SKILL.md`) + HANDOFF 부록 B 갱신 + PLAN Phase 6 갱신 | `docs/HANDOFF.md`, `docs/PLAN.md`, (맥미니) | DONE (06-11) |

## Phase 10 — 채팅 UX 고도화 (계획: PLAN.md §3 Phase 10)

| ID | 작업 | 파일 | 상태 |
|----|------|------|------|
| T-090 | 마크다운/코드블록 렌더링 — 코드펜스 자체 분리 + 인라인은 `AttributedString(markdown:)`. 코드블록 모노스페이스+배경+복사 버튼, 미닫힌 펜스는 코드 취급(스트리밍 안전). SPM 의존성 없음 | `Views/Components/MarkdownText.swift` (신규, pbxproj 등록됨), `Views/Components/MessageView.swift` | NEEDS-BUILD (Claude Code, 06-11 — 실기기 확인 항목: 다크모드 코드블록 가독, 스트리밍 중 깜빡임) |
| T-091 | 메시지 컨텍스트 메뉴: 복사(UIPasteboard)·공유(ShareLink) + 어시스턴트는 "평문 복사(마크다운 제거)" 추가 | `Views/Components/MessageView.swift` | NEEDS-BUILD (Claude Code, 06-11) |
| T-092 | 세션 fork — `POST /api/sessions/{id}/fork` 메서드(createSession의 단계적 응답 해석을 parseSessionResponse로 공용 추출) + ChatView 툴바 ⋯ 메뉴 "이 세션 분기" + SessionListView leading 스와이프 "분기" | `Services/HermesAPIClient.swift`, `Services/AppDefaults.swift`, `Views/ChatView.swift`, `Views/SessionListView.swift` | NEEDS-BUILD (Claude Code, 06-11 — 실기기 확인: fork 응답 형식이 단계적 해석에 걸리는지, 분기 세션에 히스토리 보존 여부) |

## Phase 11 — 알림 (로컬 알림 + 폴링, APNs 없음 — Bridge 무수정이 본선)

| ID | 작업 | 파일 | 상태 |
|----|------|------|------|
| T-093 | NotificationService: UN 권한 요청, 칸반 스냅샷(taskID→status) UserDefaults 보존, 기존 `GET /kanban/<board>` 폴링 diff → done/blocked 전이 시 로컬 알림 (포그라운드 60초 폴링, 첫 폴링은 기록만). 포그라운드 배너 델리게이트 포함 | `Services/NotificationService.swift` (신규, pbxproj 등록됨), `HermesChatApp.swift` | NEEDS-BUILD (Claude Code, 06-11) |
| T-094 | 채팅 긴 응답 완료 알림 — 스트림 완료 시 앱 비활성(UIApplication.applicationState)이고 10초 이상 경과면 로컬 알림 (본문은 MarkdownLite.plainText 80자 미리보기) | `ViewModels/ChatViewModel.swift` | NEEDS-BUILD (Claude Code, 06-11) |
| T-095 | BGAppRefreshTask 백그라운드 폴링 — Info.plist `BGTaskSchedulerPermittedIdentifiers`(`ai.hermes.chat.refresh`)·`UIBackgroundModes(fetch)` 추가, 백그라운드 진입 시 예약(15분 후 최조기), `.backgroundTask(.appRefresh)`에서 diff 1회 후 재예약 | `HermesChatApp.swift`, `Resources/Info.plist` | NEEDS-BUILD (Claude Code, 06-11 — 실기기 확인: 잠금 후 수 시간 내 백그라운드 폴링 1회 이상 실행) |
| T-096 | (선택·최적화) Bridge `GET /kanban/<board>/events?since=` — events 테이블 존재/스키마 방어적 확인, 미지원 시 `{"supported": false}` → 앱은 diff 유지. **착수 전 맥 에이전트가 `sqlite3 ~/.hermes/kanban.db ".schema events"` 결과를 이 행 비고에 기록할 것** | `server/hermes_bridge.py`, `Services/BridgeClient.swift` | TODO (Bridge 수정 → 완료 시 `NEEDS-BUILD(브리지 재배포 필요)`) |

## Phase 12 — 설정 심화

| ID | 작업 | 파일 | 상태 |
|----|------|------|------|
| T-097 | Bridge config 엔드포인트: GET(key/token/secret 줄 마스킹) + PATCH(`toolsets` 키 화이트리스트만, 라인 단위 블록 치환, `.bak` 백업, 비정형이면 400 거부 — stdlib만, yaml 파서 없음). **착수 전 실제 config.yaml의 toolsets 블록 형태를 맥 에이전트가 이 행 비고에 기록할 것** | `server/hermes_bridge.py` | TODO (완료 시 `NEEDS-BUILD(브리지 재배포 필요)`) |
| T-098 | T-031 본편: SkillsView 툴셋 토글 → "적용" → Bridge PATCH → 재시작 안내+restart 버튼. Bridge 404 시 읽기전용 강등 | `Services/BridgeClient.swift`, `Views/SkillsView.swift` | TODO (T-097 뒤) |
| T-099 | 프로필별 apiKey Keychain 이관 — `profileApiKey.<name>` 키, persistProfiles()는 JSON에 빈 문자열 직렬화, 로드 시 구버전 평문 감지하면 Keychain 이관 후 재직렬화, 프로필 삭제 시 Keychain도 정리 (ProfileModels는 무수정 — 메모리 모델은 그대로) | `Services/AppDefaults.swift` | NEEDS-BUILD (Claude Code, 06-11) |

## Phase 13 — 음성 입출력 (내장 프레임워크만)

| ID | 작업 | 파일 | 상태 |
|----|------|------|------|
| T-100 | 음성 입력 — SpeechService(SFSpeechRecognizer+AVAudioEngine, ko-KR), inputBar 마이크 버튼(녹음 중 시각 피드백), Info.plist `NSSpeechRecognitionUsageDescription`·`NSMicrophoneUsageDescription` | `Services/SpeechService.swift` (신규, pbxproj 등록!), `Views/ChatView.swift`, `Resources/Info.plist` | TODO |
| T-101 | 응답 읽어주기 — AVSpeechSynthesizer, 메시지 컨텍스트 메뉴 "읽어주기/중지", 입력은 `MarkdownLite.plainText(from:)`(T-090 파서 재사용). AVAudioSession은 SpeechService 단일 소유 | `Services/SpeechService.swift`, `Views/Components/MessageView.swift` | TODO (T-090·T-091 뒤) |

## 빌드 검증 기록 (검증자가 갱신)

| 날짜 | 브랜치/커밋 | 결과 | 비고 |
|------|------------|------|------|
| 06-10 | claude/busy-meitner-lhc5os @ 9a9d64b | BUILD SUCCEEDED | Hermes 검증, 실기기 프로필 전환 확인 (T-001) |
| 06-10 | claude/busy-meitner-lhc5os @ 8438b64 | BUILD SUCCEEDED | Hermes(codex) 검증 (T-011/T-012/T-014), 실기기 SOUL.md 저장 확인 |
| 06-11 | claude/busy-meitner-lhc5os @ 66bdc93 | BUILD SUCCEEDED | Hermes 빌드검증, NEEDS-BUILD 전수 DONE(T-013/T-020~022/040~041/050~051/060~061/070~075) |
