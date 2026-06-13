# TASKS — 작업 상태 보드 (단일 진실원본)

> 규칙: 작업을 시작하면 status를 `DOING(에이전트명, 날짜)`으로 바꾸고 **같은 커밋**에 포함.
> 끝나면 `NEEDS-BUILD`(맥에서 빌드 미검증) 또는 `DONE`(빌드+실기기 확인). 막히면 `BLOCKED(사유)`.
> 상태: `TODO` `DOING` `NEEDS-BUILD` `BLOCKED` `DONE`

> **2026-06-11 현황**: Phase 10~14 전체가 PR #1(`claude/multi-agent-discussion-bcnnbt` → `main`)로
> 병합되고 사용자 Xcode 빌드 + 실기기에서 Deep think 토론 정상 동작 확인. **main이 최신 기준선.**
>
> **2026-06-12 현황**:
> - **브랜치 전략 확정** — main 기준선 + 세션별 피처 브랜치(완료 시 PR로 main 병합).
>   구 `claude/busy-meitner-lhc5os`는 폐기(삭제 예정). CLAUDE.md·HANDOFF.md 갱신 완료.
> - **T-116** 채팅 답이 화면에 안 뜨고 세션 재진입해야 보이던 버그 수정 — 핵심 원인은 SSE가
>   실기기에서 답을 안 흘리는 버그(T-114와 동종). 일반 채팅에 폴링 폴백 이식 + 스트리밍 버블
>   라이브 표시. `NEEDS-BUILD`(실기기 확인: 재진입 없이 답 도착하는지).
>
> **다음 세션 예정 작업** (사용자 지정):
> 1. T-116 실기기 확인 — 전송 즉시 말풍선 "생각 중" 표시 → 본문 라이브 스트리밍(재진입 불필요)
> 2. **Phase 15 빌드 + 실기기 검증** — T-117~120 핸즈프리 음성 대화 (체크리스트는 Phase 15 절 참조, 브랜치 `claude/clever-wozniak-oairxi`)
> 3. 음성 입출력 실기기 기능 확인 — T-100~102 (받아쓰기, 읽어주기, 에어팟/글라스 라우팅)
> 4. 사진/파일 입출력 실기기 기능 확인 — T-020~022 첨부 전송, T-105~108 썸네일 (T-105 브리지 재배포 여부 포함)
> 5. 남은 TODO: T-096(칸반 events 최적화), T-097/T-098(Bridge config + 툴셋 토글)

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
| T-090 | 마크다운/코드블록 렌더링 — 코드펜스 자체 분리 + 인라인은 `AttributedString(markdown:)`. 코드블록 모노스페이스+배경+복사 버튼, 미닫힌 펜스는 코드 취급(스트리밍 안전). SPM 의존성 없음 | `Views/Components/MarkdownText.swift` (신규, pbxproj 등록됨), `Views/Components/MessageView.swift` | DONE (06-11 빌드 검증 · main 병합 — 다크모드/스트리밍 세부는 사용 중 확인) |
| T-091 | 메시지 컨텍스트 메뉴: 복사(UIPasteboard)·공유(ShareLink) + 어시스턴트는 "평문 복사(마크다운 제거)" 추가 | `Views/Components/MessageView.swift` | DONE (06-11 빌드 검증 · main 병합) |
| T-092 | 세션 fork — `POST /api/sessions/{id}/fork` 메서드(createSession의 단계적 응답 해석을 parseSessionResponse로 공용 추출) + ChatView 툴바 ⋯ 메뉴 "이 세션 분기" + SessionListView leading 스와이프 "분기" | `Services/HermesAPIClient.swift`, `Services/AppDefaults.swift`, `Views/ChatView.swift`, `Views/SessionListView.swift` | DONE (06-11 빌드 검증 · main 병합) |

| T-103 | 사고과정 숨김 — `MarkdownLite.strippingThink`(미닫힌 `<think>`·부분 태그 토큰까지 스트리밍 안전 처리, segments 진입부 적용 → 렌더·복사·TTS·알림 모두 정리) + `displayMessages`(tool/system 비렌더, think만 있는 버블 숨김) + 작업 바 "생각 중..." | `Views/Components/MarkdownText.swift`, `ViewModels/ChatViewModel.swift`, `Views/ChatView.swift` | DONE (06-11 빌드 검증 · main 병합 — 토론 실사용에서 think 숨김 정상 확인) |
| T-104 | 도구 호출 접힌 칩 — "도구 N회 실행" 캡슐, 탭 시 ToolResultView 목록 펼침, 스트리밍 중 카운트 라이브 갱신 | `Views/Components/ToolResultView.swift`, `Views/Components/MessageView.swift`, `ViewModels/ChatViewModel.swift` | DONE (06-11 빌드 검증 · main 병합) |
| T-105 | Bridge `GET /files/raw?path=` 바이너리 응답 (이미지 썸네일용) — safe_subpath/is_hidden_path 재사용, 20MB 상한 413, mimetypes Content-Type, 무인증 401 | `server/hermes_bridge.py` | DONE (06-11 빌드 검증 · main 병합 — 브리지 재배포 여부·사진 썸네일 기능 확인은 사진/파일 검증 세션에서) |
| T-106 | ChatImageView + NSCache(64MB) + `BridgeClient.fetchRawFile` + `\.bridgeClient` Environment 주입 — 맥 절대경로의 `.hermes/` 마커 뒤를 상대경로로 변환, 800pt 다운스케일, 실패/404/미설정은 placeholder 강등(에러 알럿 금지) | `Views/Components/ChatImageView.swift` (신규, pbxproj 등록됨), `Services/BridgeClient.swift`, `Views/ChatView.swift` | DONE (06-11 빌드 검증 · main 병합) |
| T-107 | 본문 이미지 세그먼트 — `![alt](src)`·`[첨부: 경로]` 파싱(.image/.file), 스트리밍 꼬리 미완성 토큰 보류(512자 한도, 미닫힌 코드펜스 안은 제외), 사용자 버블 선두 첨부 줄 썸네일 분리 | `Views/Components/MarkdownText.swift`, `Views/Components/MessageView.swift` | DONE (06-11 빌드 검증 · main 병합 — 사진/파일 기능 확인은 새 세션에서) |
| T-108 | 입력창 첨부 칩 썸네일 — PendingAttachment.thumbnail(이미지만 72px 1회 생성, Equatable은 id 기준), 칩에 36pt 표시(비이미지는 기존 paperclip) | `ViewModels/ChatViewModel.swift`, `Views/ChatView.swift` | DONE (06-11 빌드 검증 · main 병합) |
| T-116 | 채팅 응답이 화면에 안 뜨고 재진입해야 보이던 버그 수정 — **핵심: SSE 미전송 실기기 버그 폴백**. 일반 채팅 `send()`가 스트림이 빈(또는 think-only) 채 끝나면 세션 기록을 2초 간격 폴링(빈 300초/think-only 6초)해 답을 회수(`pollForMissedReply` + 토론룸 `DiscussionViewModel.missedReply` 재사용, 게이트웨이가 세션엔 쓰지만 SSE로는 안 보내는 버그 — T-114와 동종). 보조: `displayMessages`가 스트리밍 중 버블을 항상 포함(`streamingAssistantID`) + `ThinkingIndicator`(점 3개)로 "생각 중" 표시. T-103 think 숨김·T-104 tool 칩 보존, 새 파일 없음(pbxproj 무수정) | `ViewModels/ChatViewModel.swift`, `Views/Components/MessageView.swift` | NEEDS-BUILD (실기기 확인: 전송 후 재진입 없이 답이 같은 화면에 도착하는지) |

## Phase 11 — 알림 (로컬 알림 + 폴링, APNs 없음 — Bridge 무수정이 본선)

| ID | 작업 | 파일 | 상태 |
|----|------|------|------|
| T-093 | NotificationService: UN 권한 요청, 칸반 스냅샷(taskID→status) UserDefaults 보존, 기존 `GET /kanban/<board>` 폴링 diff → done/blocked 전이 시 로컬 알림 (포그라운드 60초 폴링, 첫 폴링은 기록만). 포그라운드 배너 델리게이트 포함 | `Services/NotificationService.swift` (신규, pbxproj 등록됨), `HermesChatApp.swift` | DONE (06-11 빌드 검증 · main 병합) |
| T-094 | 채팅 긴 응답 완료 알림 — 스트림 완료 시 앱 비활성(UIApplication.applicationState)이고 10초 이상 경과면 로컬 알림 (본문은 MarkdownLite.plainText 80자 미리보기) | `ViewModels/ChatViewModel.swift` | DONE (06-11 빌드 검증 · main 병합) |
| T-095 | BGAppRefreshTask 백그라운드 폴링 — Info.plist `BGTaskSchedulerPermittedIdentifiers`(`ai.hermes.chat.refresh`)·`UIBackgroundModes(fetch)` 추가, 백그라운드 진입 시 예약(15분 후 최조기), `.backgroundTask(.appRefresh)`에서 diff 1회 후 재예약 | `HermesChatApp.swift`, `Resources/Info.plist` | DONE (06-11 빌드 검증 · main 병합 — 백그라운드 폴링 실기기 관찰은 추후) |
| T-096 | (선택·최적화) Bridge `GET /kanban/<board>/events?since=` — events 테이블 존재/스키마 방어적 확인, 미지원 시 `{"supported": false}` → 앱은 diff 유지. **착수 전 맥 에이전트가 `sqlite3 ~/.hermes/kanban.db ".schema events"` 결과를 이 행 비고에 기록할 것** | `server/hermes_bridge.py`, `Services/BridgeClient.swift` | TODO (Bridge 수정 → 완료 시 `NEEDS-BUILD(브리지 재배포 필요)`) |

## Phase 12 — 설정 심화

| ID | 작업 | 파일 | 상태 |
|----|------|------|------|
| T-097 | Bridge config 엔드포인트: GET(key/token/secret 줄 마스킹) + PATCH(`toolsets` 키 화이트리스트만, 라인 단위 블록 치환, `.bak` 백업, 비정형이면 400 거부 — stdlib만, yaml 파서 없음). **착수 전 실제 config.yaml의 toolsets 블록 형태를 맥 에이전트가 이 행 비고에 기록할 것** | `server/hermes_bridge.py` | TODO (완료 시 `NEEDS-BUILD(브리지 재배포 필요)`) |
| T-098 | T-031 본편: SkillsView 툴셋 토글 → "적용" → Bridge PATCH → 재시작 안내+restart 버튼. Bridge 404 시 읽기전용 강등 | `Services/BridgeClient.swift`, `Views/SkillsView.swift` | TODO (T-097 뒤) |
| T-099 | 프로필별 apiKey Keychain 이관 — `profileApiKey.<name>` 키, persistProfiles()는 JSON에 빈 문자열 직렬화, 로드 시 구버전 평문 감지하면 Keychain 이관 후 재직렬화, 프로필 삭제 시 Keychain도 정리 (ProfileModels는 무수정 — 메모리 모델은 그대로) | `Services/AppDefaults.swift` | DONE (06-11 빌드 검증 · main 병합) |

## Phase 13 — 음성 입출력 (내장 프레임워크만)

| ID | 작업 | 파일 | 상태 |
|----|------|------|------|
| T-100 | 음성 입력 — SpeechService(SFSpeechRecognizer+AVAudioEngine, ko-KR, 싱글턴 — AVAudioSession 단일 소유), inputBar 마이크 버튼(녹음 중 빨간 mic.fill), 부분 결과를 입력창에 실시간 반영(기존 입력 뒤에 이어붙임), Info.plist 권한 키 2종 | `Services/SpeechService.swift` (신규, pbxproj 등록됨), `Views/ChatView.swift`, `Resources/Info.plist` | DONE (06-11 빌드 검증 · main 병합) |
| T-101 | 응답 읽어주기 — AVSpeechSynthesizer(ko-KR), 어시스턴트 메시지 컨텍스트 메뉴 "읽어주기/중지", 입력은 `MarkdownLite.plainText(from:)`. 받아쓰기/재생 상호 배타(AVAudioSession 단일 소유), 종료 시 세션 해제 | `Services/SpeechService.swift`, `Views/Components/MessageView.swift` | DONE (06-11 빌드 검증 · main 병합) |
| T-102 | 받아쓰기 블루투스 마이크(HFP) 허용 — `.record` 카테고리에 `.allowBluetooth` 추가. 에어팟·메타(레이밴) 글라스 마이크 입력 지원 (TTS 출력은 `.playback`이 A2DP 기본 허용이라 무수정). 비고: HFP 협대역이라 내장 마이크 대비 인식 정확도 소폭 저하 가능, Meta AI("Hey Meta")와는 표준 BT 라우팅이라 비간섭 | `Services/SpeechService.swift` | DONE (06-11 빌드 검증 · main 병합 — 에어팟/글라스 라우팅은 음성 검증 세션에서) |

## Phase 14 — Deep think 멀티 에이전트 토론룸 (계획: PLAN.md §3 Phase 14)

| ID | 작업 | 파일 | 상태 |
|----|------|------|------|
| T-110 | 토론 모델 + 로컬 보관소 — DiscussionPhase/DiscussionEntry/SavedDiscussion/DiscussionStore(UserDefaults `deepThinkDiscussions`, 최대 20건) + 발언자 색 팔레트 | `Models/DiscussionModels.swift` (신규, pbxproj 등록됨) | DONE (06-11 빌드 검증 · main 병합) |
| T-111 | 토론 오케스트레이션 — 참가자별 게이트웨이 클라이언트/세션 생성(`[Deep think]` 제목), 순차 스트리밍 라운드 루프(라운드 k>1은 타인 최신 발언만 전달), 게이트웨이 오류 탈락·활성<2 중단·취소(부분 발언 보존) 정책, 사회자 결론(탈락 시 승계), 완료 저장, 프롬프트 템플릿(도구 허용 토글 반영) | `ViewModels/DiscussionViewModel.swift` (신규, pbxproj 등록됨) | DONE (06-11 빌드 검증 · main 병합) |
| T-112 | 토론룸 UI — setup(참가자 칩 그리드/주제/라운드 Stepper 1~5/사회자 Picker/도구 토글+경고) → running(발언 카드 스트림+라운드 캡슐+"발언 중" 바+중지) → finished(결론 강조 카드+복사/공유/새 토론), 지난 토론 목록/상세(컨텍스트 메뉴 삭제), 진행 중 isIdleTimerDisabled·닫기 confirmationDialog | `Views/DiscussionView.swift` (신규, pbxproj 등록됨) | DONE (06-11 빌드+실기기 토론 검증 · main 병합) |
| T-113 | 프로필 보드 진입점 — 툴바 "Deep think" 버튼(topBarTrailing, brain.head.profile) + fullScreenCover | `Views/ProfileBoardView.swift` | DONE (06-11 빌드 검증 · main 병합) |
| T-114 | 발언 미수신 폴백 — 스트림이 빈 채 끝나면(게이트웨이가 세션에는 답을 쓰지만 SSE로는 안 보내는 실기기 버그) 세션 기록을 2초 간격 폴링(빈 스트림 300초 / think-only 6초)해 회수. 판정은 "마지막 user 메시지 뒤 visible assistant" + userTurns 앵커 검증(직전 턴 오인 방지). "(응답 없음)" 발언 채택 제거 — 타임아웃은 탈락 처리 | `ViewModels/DiscussionViewModel.swift` | DONE (06-11 빌드 검증 · main 병합) |
| T-115 | 라운드 동시 진행 — 라운드 시작 시 직전 발언 스냅샷 → 참가자별 메시지 사전 조립 → 빈 카드 사전 추가(순서 고정) → withTaskGroup 병렬 스트리밍(비던지는 그룹: 한 참가자 실패가 형제를 취소하지 않음). currentSpeakerName → speakingNames("N명 발언 중..."), 스크롤 defaultScrollAnchor(.bottom), 발언 길이 5~10문장 완화, 라운드 1 선발언 전달 제거 | `ViewModels/DiscussionViewModel.swift`, `Views/DiscussionView.swift` | DONE (06-11 빌드+실기기 토론 검증 · main 병합 — 동시 발언·폴백 회수 정상 확인) |

## Phase 15 — 핸즈프리 음성 대화 (에어팟·메타 글라스 자연스러운 음성 입출력)

> 배경: T-100~102는 동작하지만 녹음·재생 시마다 세션 카테고리를 전환해 BT 라우트 재협상(끊김)이
> 발생하고, 음성으로 물어봐도 답을 읽어주지 않았다. 받아쓰기 전송 시 응답 자동 낭독을 기본 동작으로,
> 그 위에 핸즈프리 대화 루프(침묵 자동 전송→문장 단위 낭독→자동 재청취)를 얹는다.
> 개발 브랜치: `claude/clever-wozniak-oairxi`

| ID | 작업 | 파일 | 상태 |
|----|------|------|------|
| T-117 | 오디오 세션 통일 — 멱등 프로필 2종(.voice=`.playAndRecord/.voiceChat/HFP`(A2DP는 의도적 제외: 입력이 내장 마이크로 떨어짐), .playback=기존 A2DP 고음질) + 라우트 분리(oldDeviceUnavailable→녹음 정리·onRouteLost)·인터럽션(전화→중단, 종료 시 onInterruptionEnded) 옵저버 | `Services/SpeechService.swift` | NEEDS-BUILD |
| T-118 | 핸즈프리 음성 대화 — VoiceConversationController(신규, **pbxproj 등록**): ①받아쓰기 전송 시 응답 문장 단위 자동 낭독(기본 동작), ②핸즈프리 루프(waveform 버튼: 침묵 1.8초 자동 전송→think-안전 문장 분할 스트리밍 TTS→자동 재청취, 무발화 60초 종료). 음성 모드 중 엔진 상시 가동(탭만 교체). ChatViewModel voiceStreamHandler 후킹, ChatView 상태 배너 | `Services/VoiceConversationController.swift`(신규), `Services/SpeechService.swift`, `ViewModels/ChatViewModel.swift`, `Views/ChatView.swift` | NEEDS-BUILD |
| T-119 | 에어팟 스템 탭/글라스 탭 제어 — MPRemoteCommandCenter(play/pause/toggle): idle=모드 시작, 청취 중=즉시 전송(발화 없으면 종료), 낭독 중=바지-인 재청취. Now Playing 등록("Hermes 음성 대화"). 자동 낭독 중 탭은 "그만 읽기" | `Services/VoiceConversationController.swift` | NEEDS-BUILD |
| T-120 | 백그라운드 음성 — `UIBackgroundModes`에 `audio` 추가 (잠금화면·주머니 속에서 음성 대화 유지, 생존 메커니즘은 T-118 엔진 상시 가동) | `Resources/Info.plist` | NEEDS-BUILD |
| T-121 | 챗 응답 미수신 폴백 — 스트림이 보일 내용 없이 끝나면 세션 기록 2초 간격 폴링(빈 스트림 300초/think-only 6초)으로 회수. 게이트웨이가 답을 세션에는 쓰지만 SSE로는 안 보내는 실기기 버그(토론룸 T-114와 동일 원인)의 일반 챗 버전 — "챗을 나갔다 와야 답이 보이고 TTS도 안 됨" 증상 해결. 판정은 `DiscussionViewModel.missedReply` 재사용, 회수 본문으로 자동 낭독(T-118)도 정상 동작 | `ViewModels/ChatViewModel.swift` | NEEDS-BUILD |
| T-122 | SSE `event: error` 표면화 — 게이트웨이가 에러 이벤트({"message": ...})를 보내면 StreamChunk 디코딩 실패로 조용히 버려져 "무반응"으로 보이던 것을 serverError throw로 전환 → 챗은 `[에러]` 말풍선, 토론은 탈락 처리, 음성 루프는 정상 복귀. 실사례: safety 게이트웨이가 hermes-agent 업데이트 전 스테일 프로세스로 돌며 매 요청 import 오류를 SSE error로 응답(증상: safety만 앱에서 무반응, 조치: 게이트웨이 재시작) | `Services/HermesAPIClient.swift`, `StreamModels.swift` | NEEDS-BUILD |
| T-123 | 자동 검색 이름 동기화 — 맥에서 프로필 폴더명을 바꾸면(codex→builder) 같은 포트가 이미 등록돼 있어 새 이름이 영영 안 나타나던 것을, 같은 포트 항목의 이름을 서버 보고(Bridge 폴더명/MODEL_NAME)에 맞춰 갱신하도록 수정. 프로필별 apiKey Keychain·선택 저장명도 새 이름으로 이전. 주의: 맥에서 이름 변경 시 .env의 API_SERVER_MODEL_NAME 갱신 + 게이트웨이 서비스 재등록 필요(`setup_profiles_api.sh` 재실행 권장) | `Services/AppDefaults.swift`, `Views/SettingsView.swift` | NEEDS-BUILD |
| T-124 | setup_profiles_api.sh 포트 중복 배정 버그 수정 — 포트 없는 프로필에 NEXT_PORT를 줄 때 뒤따르는 "이미 그 포트가 박힌" 프로필과 중복되던 것을, 2패스(명시 포트 먼저 예약 → 나머지에 빈 포트 배정)로 교체. 실사례: builder(8643)+codex(부활,포트없음)+designer(8644)가 codex/designer 8644 충돌. **비고: codex 폴더가 삭제 후에도 부활하는 건 스크립트가 아니라 hermes 프로필 레지스트리에 codex가 남아서임 — 폴더 삭제만으론 안 되고 hermes 레지스트리/설정에서 codex 제거 필요(스크립트 무관)** | `scripts/setup_profiles_api.sh` | 스크립트(빌드 무관) |

**Phase 15 실기기 검증 체크리스트** (맥 빌드 후 에어팟·메타 글라스로):
1. 받아쓰기 단독(에어팟→글라스): BT 마이크 사용, 종료 후 덕킹된 음악 복귀
2. **받아쓰기→전송→응답 자동 낭독**: 마이크로 말하고 전송하면 별도 조작 없이 문장 단위로 읽힘. 키보드 입력 전송은 낭독 없음. 낭독 중 입력창 터치/마이크 탭 시 즉시 조용히 중단
3. 읽어주기 단독(컨텍스트 메뉴): A2DP 고음질 유지
4. 핸즈프리 루프 연속 5턴(waveform 버튼): 말하기→1.8초 침묵 자동 전송→스트리밍 낭독→자동 재청취, 턴 사이 라우트 끊김 없음
5. think 많은 응답: think 내용 낭독 안 됨, 루프 정상 복귀
6. 탭 제어: 낭독 중 탭=바지-인 재청취, 청취 중 탭=즉시 전송(무발화면 종료), 뮤직 앱 안 뜸
7. 잠금화면/주머니: 잠근 채 30초+ 응답 대기 포함 풀 턴 완료 (마이크 표시등 상시 점등은 의도된 동작)
8. 에어팟 분리 중 청취: 모드 정상 종료, 크래시 없음 / 낭독 중 전화 수신: 중단 후 통화 종료 시 복구
9. 메타 글라스: 마이크 라우팅 + 탭 제어 end-to-end, "Hey Meta" 충돌 없음
10. 회귀: dictationBase 이어붙이기, 컨텍스트 메뉴 읽어주기, Deep think 토론방 정상

## 빌드 검증 기록 (검증자가 갱신)

| 날짜 | 브랜치/커밋 | 결과 | 비고 |
|------|------------|------|------|
| 06-10 | claude/busy-meitner-lhc5os @ 9a9d64b | BUILD SUCCEEDED | Hermes 검증, 실기기 프로필 전환 확인 (T-001) |
| 06-10 | claude/busy-meitner-lhc5os @ 8438b64 | BUILD SUCCEEDED | Hermes(codex) 검증 (T-011/T-012/T-014), 실기기 SOUL.md 저장 확인 |
| 06-11 | claude/busy-meitner-lhc5os @ 66bdc93 | BUILD SUCCEEDED | Hermes 빌드검증, NEEDS-BUILD 전수 DONE(T-013/T-020~022/040~041/050~051/060~061/070~075) |
| 06-11 | main @ 879b47e (PR #1 병합) | BUILD SUCCEEDED | 사용자 Xcode 빌드 + 실기기 확인 — Deep think 토론(동시 발언·폴백 회수·결론) 정상 동작. T-090~115 DONE 전환 (음성·사진/파일 기능 확인은 별도 세션 예정) |
