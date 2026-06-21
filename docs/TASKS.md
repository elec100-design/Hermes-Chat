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
> **2026-06-16 현황**:
> - **T-147** 세션 목록 trailing 스와이프에 **이름변경**·**고정(Pin)** 버튼 추가(삭제 왼편) +
>   **풀 스와이프 자동 삭제 비활성화**(`allowsFullSwipe: false` — 삭제 버튼을 눌러야만 삭제).
>   Pin은 서버 미지원이라 로컬(UserDefaults) 보관·목록 맨 위 정렬·행 핀 아이콘. 브랜치
>   `claude/busy-ritchie-cx2ohx`, `NEEDS-BUILD`(맥 빌드 미검증). 상세는 Phase 20 절.
>
> **2026-06-20 현황 (Phase A/B)**:
> - **Phase A (App Store 컴플라이언스)**: T-A01~A06 DONE. T-A07/A08 NEEDS-BUILD (PR #12 대기).
> - **Phase B (클라우드 SaaS 인프라)**: T-B01/B03/B05 DONE — `server/` 하위 Docker 인프라·cloud_gateway.py 완성.
>   브랜치 `claude/hopeful-edison-1p5q91` — 사용자 Docker 빌드 검증 완료. PR 생성 후 main 병합 예정.
>   T-B02(Supabase 대시보드)·T-B04(클라우드 배포)는 코드 아님.
>
> **2026-06-21 현황 (Phase C)**:
> - **Phase C (iOS SaaS 전환)**: T-C01~C05 코드 완성. 브랜치 `claude/sleepy-bardeen-x86kpk` (PR #14).
>   사용자 맥 빌드 성공 + 실기기(iPhone17,4 / iOS 26.5) 설치 완료 (98ad910).
>   유료 Apple Developer 계정(C4LUZYK8L5) + Supabase Apple OAuth 설정 완료.
>   런타임 검증 대기: Sign in with Apple 실제 흐름 (T-C01), StoreKit 샌드박스 테스트 (T-C03).
>
> **다음 세션 예정 작업**:
> 1. PR #14 (`claude/sleepy-bardeen-x86kpk` → `main`) 병합 후 기준선 갱신
> 2. T-C01 Sign in with Apple 실기기 런타임 검증 (Supabase id_token 흐름)
> 3. T-C03 StoreKit 샌드박스 테스트 (App Store Connect 제품 등록 후)
> 4. T-B02 Supabase Apple OAuth 심사 통과 확인

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

## Phase 16 — 메타 글라스 사진 자동 전송 + 음성 후속 질의

> 배경: 음성 입력 중 글라스로 사진을 찍으면 자동 전송하고 그에 대해 음성으로 이어 묻고 싶다.
> Meta Wearables DAT 조사 결과 **물리 '촬영' 버튼·더블탭은 3rd-party 앱이 못 가로채고**(더블탭은
> 표준 BT AVRCP로, 이미 T-119가 받는 그 신호일 뿐 사진을 주지 않음), DAT 카메라 스트림은 Meta
> 개발자 등록·개발자 프리뷰라 도입 안 함(사용자 결정). 대신 글라스 사진이 Meta AI 앱을 통해 카메라
> 롤에 동기화되는 것을 `PHPhotoLibrary` 변화 감지로 포착해 기존 첨부·음성 파이프라인으로 넘긴다.
> 동기화 지연(수초~수십초)이 있어, 도착 시 **"사진이 도착했습니다" 음성 알림** 후 사용자의 음성
> 질문을 사진과 함께 전송한다. 개발 브랜치: `claude/happy-gauss-0p39f0`

| ID | 작업 | 파일 | 상태 |
|----|------|------|------|
| T-125 | PhotoImportWatcher 신규(**pbxproj 등록**) — PHPhotoLibrary 전체 접근(.authorized만) 요청 + 변화 감지로 시작 시각 이후·이미지·비스크린샷·미처리 에셋만 포착, `requestImageDataAndOrientation`로 데이터·원본 파일명 콜백. 제한 접근(.limited)은 비활성 반환 | `Services/PhotoImportWatcher.swift` (신규) | NEEDS-BUILD |
| T-126 | ChatViewModel 글라스 사진 처리 — `glassesCaptureActive`, `glassesPhotoPrompt`, `handleCapturedPhoto`(기존 `addAttachment`로 대기 첨부 후 컨트롤러에 위임). 50MB 가드 통과 시에만 진행 | `ViewModels/ChatViewModel.swift` | NEEDS-BUILD |
| T-127 | VoiceConversationController `announcePhotoArrival` — idle이면 음성 세션 시작/listening이면 청취 접고/진행 중이면 플래그만, "사진이 도착했습니다" 문장 큐 낭독 후 핸즈프리 청취 진입. 사용자 질문은 기존 `finishListening`→`send()`가 대기 첨부와 함께 전송, 응답 자동 낭독·재청취. 무발화 타임아웃은 `noSpeechTimedOut`이 기본 프롬프트로 사진 전송(폴백). 음성 불가 시 `sendPhotoFallback`. 전송을 컨트롤러 단일 경로로 일원화해 이중 전송 방지 | `Services/VoiceConversationController.swift` | NEEDS-BUILD |
| T-128 | ChatView UI — 입력 바 `eyeglasses` 토글(활성 시 초록), 권한 부족 안내 알럿, `onAppear`에서 `onNewPhoto`→`handleCapturedPhoto` 연결, `onDisappear`에서 워처 stop. Info.plist `NSPhotoLibraryUsageDescription` 문구 보강(전체 접근 필요 명시) | `Views/ChatView.swift`, `Resources/Info.plist` | NEEDS-BUILD |
| T-129 | 글라스 사진 감지 가시성 — 서버 응답과 무관하게 "감시 중/사진 감지됨 N장·최근 파일명" 상태 배너 + 도착 햅틱(`UINotificationFeedbackGenerator`). 첫 실기기 테스트에서 서버 import 오류(T-122 표면화, 앱 무관)와 사진 미감지를 구분 못 한 UX 공백 대응. 모드 OFF 시 리셋 | `ViewModels/ChatViewModel.swift`, `Views/ChatView.swift` | NEEDS-BUILD |
| T-130 | HEIC 첨부 자동 JPEG 변환 — 주요 LLM 비전 API(Claude/OpenAI/Gemini)가 HEIC를 사실상 거부해 "분석 불가"가 나던 문제. `addAttachment`에서 확장자 heic/heif면 `UIImage.jpegData(0.85)`로 변환·파일명 `.jpg`로 교체(세 진입점 공통 통로). 변환 실패 시 원본 유지, PNG/JPEG/비이미지는 무변환. 일반 아이폰 사진(기본 HEIC)도 함께 해결 | `ViewModels/ChatViewModel.swift` | NEEDS-BUILD |

**Phase 16 실기기 검증 체크리스트** (맥 빌드 후 메타 글라스 + Meta AI 앱 사진 동기화 ON):
1. 사진 권한을 **전체 접근**으로 허용. 제한 접근으로 주면 안내 알럿이 뜨고 모드가 안 켜지는지.
2. 글라스 모드 ON → 글라스 '촬영' 버튼으로 사진 → 수초~수십초 내 카메라 롤 동기화 →
   **"사진이 도착했습니다" 음성 알림**이 글라스로 들리는지 → 음성으로 질문 → 사진+질문 동시 전송,
   Hermes 응답 자동 낭독, 재청취로 복귀해 후속 질문 가능한지.
3. 무발화 폴백: 알림 후 질문 없이 기다리면(60초) 기본 프롬프트로 사진이 자동 전송돼 설명을 받는지.
4. 핸즈프리 대화 중 사진 촬영 → 도착 알림 후 루프가 이중 전송으로 멈추지 않고 이어지는지.
5. 스크린샷·기존 사진이 자동 전송 대상에서 제외되는지. 모드 OFF면 어떤 사진도 안 보내지는지.
6. 회귀: 기존 PhotosPicker/파일 첨부, 받아쓰기 자동 낭독, Deep think 토론 정상.

## Phase 17 — Siri·위젯·글라스 더블탭 음성 진입 (브랜치 `claude/meta-glasses-double-tap-hermes-dkemd0`, Phase 16 위에 적층)

> 목표: 메타 레이밴 글라스로 손 안 대고 Hermes를 구동하고 음성 입력 대기 모드까지 진입.
>
> **핵심 제약(메타 공식 FAQ 검증):** Meta Wearables Device Access Toolkit은 3P 앱에 **템플 탭/더블탭
> 제스처 이벤트를 제공하지 않는다**(템플 탭은 OS 예약). FAQ 원문: *"while custom gesture controls
> like taps and swipes aren't offered, you can listen for standard events like pause, resume, and
> stop."* → **메타 SDK 미도입.** 대신 ①Siri/위젯/URL이 앱 구동+음성 대기 진입, ②앱 실행 중 글라스
> 템플 탭은 AVRCP 미디어 커맨드(싱글탭≈play/pause, 더블탭≈next track)로 들어와 음성 켜기/바지-인.
> **한계: 강제 종료된 앱은 글라스 탭으로 cold-launch 불가** — 런처는 Siri/위젯/URL. 음성 모드가
> 완전히 idle로 끝나면 리모트 커맨드가 해제되어 그 뒤 글라스 탭 재시작 불가(Siri/위젯/URL로 재진입).
>
> (이 작업은 처음에 main에서 분기돼 사진 Phase 16(happy-gauss)과 T-125~130 번호가 겹쳤으나,
> happy-gauss 위로 재베이스하며 T-131~135로 재번호함.)

| ID | 작업 | 파일 | 상태 |
|----|------|------|------|
| T-131 | 음성 진입 코디네이터 — `VoiceEntryCoordinator`(신규): `requestVoiceEntry`·`beginRouting`(재진입 가드)·`consumeChatVoiceStart`·`sessionsPath`. `SessionListView` navigationPath를 코디네이터로 승격, `HermesChatApp` 라우팅(onChange+런치 task: .sessions 탭→최근 세션 resume(없으면 createSession)→push; 이미 채팅 중이면 보이는 ChatView가 arm 소비), `ChatView` onAppear/onChange로 `voice.start`, onDisappear는 `voice.boundSessionId==sessionId`일 때만 정리 | `Services/VoiceEntryCoordinator.swift`(신규), `HermesChatApp.swift`, `Views/SessionListView.swift`, `Views/ChatView.swift`, `Services/VoiceConversationController.swift` | NEEDS-BUILD |
| T-132 | App Intent + Siri — `StartVoiceInputIntent`(openAppWhenRun) + `HermesShortcuts`(한/영 phrases). **한글 호출 수정**: 영어 앱 이름이라 한국어 Siri가 못 맞춰 웹 검색되던 것을, `CFBundleDisplayName="헤르메스"`로 표시 이름을 한글화(→ "시리야 헤르메스 음성 입력 시작"). 모음 종결이라 "으로" 제거, 모든 phrase에 앱 이름 유지(Apple 필수) | `Intents/StartVoiceInputIntent.swift`(신규), `Resources/Info.plist` | NEEDS-BUILD |
| T-133 | URL 스킴 — Info.plist `CFBundleURLTypes`(scheme `hermes`) + `HermesChatApp.onOpenURL`가 `hermes://voice`(옵션 `?session=`) 파싱 → 코디네이터 | `Resources/Info.plist`, `HermesChatApp.swift` | NEEDS-BUILD |
| T-134 | 글라스 더블탭 매핑(T-119 확장) — `enableRemoteCommands`에 `next/previousTrackCommand` 추가 → `handleRemoteAdvance`(idle=시작, listening=즉시 전송, speaking+handsFree=바지-인), `bargeIn()` 공통 추출, 0.3초 디바운스로 싱글+더블탭 겹침 방지. **실기기 검증 필요: 더블탭이 next/previous 중 무엇으로 들어오는지** | `Services/VoiceConversationController.swift` | NEEDS-BUILD |
| T-135 | 위젯 익스텐션 — 홈/잠금화면 위젯+iOS18 제어센터 컨트롤(`Button(intent:)`). 소스는 `HermesWidgets/`. **신규 Widget Extension 타깃은 pbxproj 수기 위험 → Xcode GUI 생성**(`HermesWidgets/SETUP.md`). `StartVoiceInputIntent`·`VoiceEntryCoordinator`를 위젯 타깃에 멤버십 공유 | `HermesWidgets/*`(신규) | NEEDS-BUILD(타깃은 Xcode GUI) |
| T-137 | 앱 아이콘 — 사용자가 푸시한 `logo.png`(흑백 일러스트, 1772×1799)로 앱 아이콘 생성. 알파 제거(흰 배경 합성)·정사각 패딩·1024 리사이즈해 `HermesChat/Resources/Assets.xcassets/AppIcon.appiconset/AppIcon.png`(단일 1024 아이콘, iOS17 지원). **신규 에셋 카탈로그 + PBXResourcesBuildPhase를 pbxproj에 수기 등록**(기존엔 Resources 빌드 페이즈 자체가 없었음). `ASSETCATALOG_COMPILER_APPICON_NAME=AppIcon`은 이미 설정돼 있었음 | `HermesChat/Resources/Assets.xcassets/*`(신규), `HermesChat.xcodeproj/project.pbxproj` | NEEDS-BUILD |
| T-136 | **사용자 피드백 3건 (2026-06-14)** — ①Siri "헤르메스챗 실행해" 미작동: 표시 이름을 `헤르메스`→`헤르메스챗`(사용자가 실제로 부르는 이름)으로 바꾸고 `실행해/실행/열어/열어줘` 등 자연스러운 한국어 동사 phrase 추가(T-132 보강). ②글라스 더블탭이 갓 켠 화면(idle)에서 무시되던 것: idle에선 viewModel이 nil이고 리모트 커맨드가 미등록이라 `handleRemoteAdvance(.idle)`가 빈 동작이던 근본 원인 수정 — `armRemoteControl`/`disarmRemoteControl`로 ChatView가 떠 있는 동안 커맨드 등록+현재 채팅 바인딩+now-playing 정보 유지, 세션 종료(idle 복귀) 시 `onChange(voice.state)`로 재무장(T-134 보강). ③세션 창 받아쓰기(마이크) 버튼 제거 — `음성입력`(waveform 핸즈프리)과 중복이라 삭제, 관련 dictationBase/usedDictation/transcript onChange/자동낭독-on-send 정리. **실기기 검증 필요**: 더블탭은 앱이 now-playing 지위를 가질 때만 AVRCP가 전달됨 — 첫 음성 상호작용(TTS) 이후 재진입은 확실, 완전 콜드 idle 첫 탭은 사전 오디오가 없으면 미전달일 수 있음 | `Intents/StartVoiceInputIntent.swift`, `Resources/Info.plist`, `Services/VoiceConversationController.swift`, `Views/ChatView.swift` | NEEDS-BUILD |

**Phase 17 실기기 검증 체크리스트** (맥 빌드 후 메타 글라스로):
1. Siri "헤르메스챗 실행해"(및 "헤르메스챗 시작"/"음성 입력 시작", 한/영) 강제종료 상태에서 → 앱 뜨고 세션 진입 → 청취("말씀하세요"). 홈 아이콘 라벨이 "헤르메스챗"으로 바뀌었는지.
2. `hermes://voice`(Safari/메모) cold+warm 동일. `?session=<id>`로 해당 세션 오픈.
3. 홈 위젯/잠금화면 accessory/iOS18 제어센터 컨트롤 탭 → 앱 포그라운드+음성 시작.
4. 글라스 싱글 vs 더블탭 상태별: idle(시작 — **갓 켠 채팅 화면에서도 바로 청취 진입**, T-136), 청취 무발화(중지)/발화중(전송), 낭독 핸즈프리(바지-인 재청취), 응답대기(무시). 음악 앱 안 뜸. (콜드 idle 첫 탭이 안 들어오면 한 번 음성 상호작용 후 재시도 — now-playing 지위 필요)
5. 바지-인: TTS 답변 중 더블탭 → 답변 중단+재청취, 루프 복귀.
6. 강제 종료: 탭으로 실행 안 됨(정상) — Siri/위젯/URL은 됨.
7. 통합 회귀: 사진 도착 음성 알림(Phase 16)과 글라스 더블탭(Phase 17)이 같은 음성 세션에서 안 부딪히는지. waveform 수동 시작·세션 포크 정상. (받아쓰기 마이크 버튼은 T-136에서 제거됨 — 입력 바에 +/waveform/eyeglasses/전송만 남는지)

## Phase 18 — 프로필별 크론잡 조회·편집 (계획: `.claude/plans/` cron-jobs)

| ID | 작업 | 파일 | 상태 |
|----|------|------|------|
| T-138 | 프로필별 크론잡 조회·편집 — hermes-agent 대시보드(:8000)의 Cron 화면을 네이티브로 재현. 프로필 보드 카드의 시계 버튼 → 잡 목록(시트) → 잡 편집(프롬프트·스케줄·전달대상·스킬·활성화). **Bridge 신규 엔드포인트** `GET /profiles/<name>/cron`(cron/jobs.json 잡 목록), `PUT /profiles/<name>/cron/<job_id>`(편집된 필드만 read-modify-write, id·mode·script·실행상태 등 나머지 필드 보존, .bak 백업 + 원자적 교체). 쓰기 CLI 미확인이라 파일 직접 수정. 신규 3파일 pbxproj 등록. **빌드 검증 필요(맥)**. 후속: 생성/삭제/즉시실행/실행리포트 뷰어 | `server/hermes_bridge.py`, `Models/CronModels.swift`(신규), `Services/BridgeClient.swift`, `Views/CronJobsView.swift`(신규), `Views/CronJobEditView.swift`(신규), `Views/ProfileBoardView.swift`, `HermesChat.xcodeproj/project.pbxproj` | NEEDS-BUILD |
| T-146 | **크론 중앙 관리 화면** (T-138 후속, 사용자 요청 + 대시보드 스크린샷 참고). 프로필마다 흩어져 있던 크론 시트를 **한 화면(`CronManagerView`)**으로 통합 — 상단 **드롭다운으로 프로필 필터링**, 각 잡에 **재개/일시정지 · 지금 실행 · 편집 · 삭제** 버튼 + **상태 배지(scheduled/paused/running)** + **사람이 읽는 스케줄("매일 08:00")** + **최근/다음 실행 시각**(대시보드 :8000 Cron 화면 재현). 우상단 **CREATE(+)** 로 새 잡 생성(프로필 선택 포함). 전 프로필 jobs.json을 동시 로드해 프로필별 섹션으로 표시. 일시정지/재개=`enabled` 토글(PUT), 편집/생성=`CronJobEditView`(생성·편집 양용, 시트). **Bridge 신규**: `POST /profiles/<n>/cron`(새 잡 추가 — 기존 잡을 구조 템플릿 삼아 스키마 적응, id=이름 슬러그), `POST /profiles/<n>/cron/<id>/run`(즉시 실행 — `hermes [--profile <n>] cron run <id>`, ⚠️CLI 형태 버전별 상이 가능·실패 시 stdout/stderr 노출), `DELETE /profiles/<n>/cron/<id>`(제거), PUT 화이트리스트에 `name` 추가. 모두 .bak+원자적. 진입: 프로필 보드 툴바 "크론 관리"(전체) + 카드 시계 버튼(해당 프로필 필터). **기존 파일만 수정 → pbxproj 무수정**(`CronJobsView.swift`→`CronManagerView` 재작성). **빌드 검증 필요(맥)** + **브리지 재배포 필요** | `server/hermes_bridge.py`, `Models/CronModels.swift`, `Services/BridgeClient.swift`, `Views/CronJobsView.swift`, `Views/CronJobEditView.swift`, `Views/ProfileBoardView.swift` | NEEDS-BUILD |

## Phase 19 — 프로필 추가('+' 카드) + 모델 카탈로그 선택

| ID | 작업 | 파일 | 상태 |
|----|------|------|------|
| T-139 | 프로필 보드 '+' 카드 → 새 프로필 **백엔드 완전 생성**(Bridge `POST /profiles`: 디렉터리+.env+SOUL.md + `hermes --profile <name> gateway install/restart` + 헬스폴링, 포트 자동할당=최대+1). 모델 선택을 **카탈로그 기반**으로 교체 — Bridge `GET/PUT /profiles/<name>/model`(현재값=config.yaml, 카탈로그=`<profile>/cache/model_catalog.json`). ProfileDetailView 모델 섹션을 `/v1/models`(무의미·1개) 대신 카탈로그 Picker+저장(재시작)로 교체. 신규 `CreateProfileView` + pbxproj 등록. **추가**: 브리지 수정 시 맥미니 기동본 교체+재기동 절차 강조(HANDOFF §2.5 경고 + README) — 안 하면 앱 "브리지 HTTP 404". **빌드 검증 필요(맥)** + **브리지 재배포 필요** | `server/hermes_bridge.py`, `Services/BridgeClient.swift`, `Views/CreateProfileView.swift`(신규), `Views/ProfileBoardView.swift`, `Views/ProfileDetailView.swift`, `docs/HANDOFF.md`, `README.md`, `HermesChat.xcodeproj/project.pbxproj` | NEEDS-BUILD |
| T-140 | 모델 선택 버그 수정 + 적용 경로 확정. ①`model_catalog.json`이 평면 리스트가 아니라 `{providers:{<p>:{models:[{id}]}}}` 중첩이라 카탈로그가 빈 목록이던 것 수정 — `read_model_catalog`를 providers 순회로 고쳐 모든 provider의 모델 id 수집(중복제거·정렬, 구포맷 fallback 유지). ②config.yaml의 `model:`은 블록이고 실제 값은 `model.default` — `_locate_model`로 `model.default`(또는 인라인 `model:`) **값만** 교체, provider·base_url·fallback 등 주변 보존(.bak+원자적). ③footer에 provider/base_url 미변경 한계 명시. **빌드 검증 필요(맥)** + **브리지 재배포 필요** | `server/hermes_bridge.py`, `Views/ProfileDetailView.swift` | NEEDS-BUILD |
| T-141 | 새 프로필 모델 카탈로그 폴백 — 새로 만든 프로필(예 Worker:8649)은 자기 `cache/model_catalog.json`이 없어(재시작해도 자동 생성 안 됨) 드롭다운이 안 떴음. `read_model_catalog`를 `_parse_catalog_file(path)` 헬퍼로 분리하고, 프로필별 결과가 비면 **default 프로필 카탈로그(`~/.hermes/cache/model_catalog.json`)로 폴백**(모든 프로필 동일 목록 공유). 앱 변경 없음(서버만). **브리지 재배포 필요** | `server/hermes_bridge.py` | NEEDS-BUILD |
| T-142 | 프로필 생성 시 config.yaml 누락 → 모델 저장 400. 원인: `POST /profiles`가 config.yaml을 안 만들어 새 프로필이 default 설정을 런타임 상속, 모델 변경 대상 파일이 없었음. ①`ensure_profile_config(name)`: 프로필 config.yaml 없으면 **default 프로필 config.yaml을 템플릿으로 복사**(config.yaml엔 API 설정 없음 — env 전용이라 안전). ②`POST /profiles`에서 생성 시 복사 + model 지정 시 `model.default` 교체. ③`write_config_model`도 없으면 default 복사 + `_ensure_model_default`로 값 교체/블록 추가 → **이미 만든 Worker도 재생성 없이 모델 저장 한 번으로 복구**. 앱 변경 없음(서버만). **브리지 재배포 필요** | `server/hermes_bridge.py` | NEEDS-BUILD |
| T-143 | 프로필 생성을 `hermes profile create`로 전환 (T-142 보강). 근본 원인: `POST /profiles`가 수동 mkdir+.env만 작성 → hermes의 프로필 구조(config.yaml 등)가 안 생김. **`hermes profile create <name> --clone-from default`**(config.yaml/.env/SOUL.md/skills 복제)로 교체하고, 클론된 .env의 API 서버 키만 `set_env_values` 머지로 덮어씀(PORT=새포트·HOST·ENABLED·MODEL_NAME, KEY는 제공 시에만 — 클론 KEY 보존). 포트는 create 전 `next_free_port`로 산정. `write_env_file` 제거. 이름은 hermes가 소문자 영숫자 요구 → 거부 시 stderr 반환. 기존 "Worker"(대문자·config 없음)는 재생성 권장(또는 모델 저장 안전망). 앱 변경 없음(서버만). **브리지 재배포 필요** | `server/hermes_bridge.py` | NEEDS-BUILD |
| T-144 | 생성 시 config.yaml 누락 진단/보정 (T-143 후에도 앱 생성분에 config.yaml 미생성). ①create subprocess에 **명시적 env**(`hermes_env`: HOME/HERMES_HOME 보정 — launchd는 셸 env 미상속) 전달, start_gateway도 동일. ②create 후 **config.yaml 존재 검증** — 없으면 500 + hermes stdout/stderr를 `detail`로 반환(조용한 클론 실패를 가시화). 성공 응답에도 detail 포함. 앱 변경 없음(서버만). **브리지 재배포 필요** | `server/hermes_bridge.py` | NEEDS-BUILD |
| T-145 | 프로필 삭제 기능. Bridge `DELETE /profiles/<name>` → `hermes profile delete <name> -y`(env 보정), default/미존재 거부. `BridgeClient.deleteProfile`. ProfileDetailView에 파괴적 "프로필 삭제" 버튼(confirmationDialog) → 성공 시 `removeProfiles`로 앱 목록 제거 + 화면 pop. default 프로필엔 버튼 숨김. **빌드 검증 필요(맥)** + **브리지 재배포 필요** | `server/hermes_bridge.py`, `Services/BridgeClient.swift`, `Views/ProfileDetailView.swift` | NEEDS-BUILD |

## Phase 20 — 세션 관리(핀·이름변경) (브랜치 `claude/busy-ritchie-cx2ohx`)

| ID | 작업 | 파일 | 상태 |
|----|------|------|------|
| T-147 | 세션 목록 trailing 스와이프 강화 — 삭제 버튼 왼편에 **이름변경**(pencil·파랑)·**고정/고정해제**(pin/pin.slash·주황) 아이콘 버튼 추가. **고정(Pin)**: 서버 미지원이라 `pinnedSessionIDs`(UserDefaults 키 `pinnedSessionIDs`)에 로컬 보관 — `isPinned`/`togglePin` + `filteredSessions`가 고정 세션을 맨 위로 안정 정렬(검색 경로도 적용), 행 제목 옆 `pin.fill` 표시, 앱 재실행 후 유지. **이름변경**: 기존 `updateSessionTitle` API(HermesAPIClient) + `updateSession` 로컬 갱신을 alert(TextField)로 연결(SettingsView 프로필 rename 패턴 재사용). **풀 스와이프 자동 삭제 비활성화**(`allowsFullSwipe: false`) — 버튼 확인하려 길게 밀어도 자동 삭제 안 되고 삭제 버튼을 눌러야만 삭제(사용자 보고 사고 대응). leading(분기) 스와이프는 그대로. 기존 파일만 수정 → pbxproj 무수정 | `Views/SessionListView.swift`, `Services/AppDefaults.swift` | NEEDS-BUILD |

## Phase 21 — 대시보드 핀치 줌 + 데스크톱 모드 (브랜치 `claude/focused-hypatia-w558o2`)

| ID | 작업 | 파일 | 상태 |
|----|------|------|------|
| T-148 | 대시보드 탭에 **두 손가락 핀치 줌**과 **데스크톱 모드 토글** 추가(사용자 요청 — 모바일 화면에서 작아서 안 눌리거나 레이아웃에 숨겨져 접근 안 되는 버튼 대응). 대시보드 페이지(:8000)는 맥미니가 서빙해 HTML 직접 수정 불가 → 앱에서 보조. **핀치 줌**: 페이지의 `<meta viewport>`가 보통 `user-scalable=no`라 막혀 있어, `WKUserScript`(.atDocumentEnd) + 네비 완료 시 `evaluateJavaScript`로 viewport를 `user-scalable=yes, maximum-scale=10`으로 덮어씀. **데스크톱 모드**: 우상단 툴바 토글(iphone/desktopcomputer 아이콘) → `customUserAgent`를 macOS Safari로 바꾸고 viewport `width=1024`로 전체 데스크톱 레이아웃을 불러옴(숨겨진 버튼 노출) + 핀치 줌으로 탐색. 상태는 `@AppStorage("dashboardDesktopMode")`로 영속화. 모드 전환 시 UA 교체 후 `reload()`, 기존 host/port 변경 재로드 로직 보존. 기존 파일만 수정 → **pbxproj 무수정**. **빌드 검증 필요(맥)** | `Views/DashboardWebView.swift` | NEEDS-BUILD |

## Phase A — 앱스토어 상품화 Phase 0: 컴플라이언스 + 다국어 기반

> 상업화 전체 계획은 `docs/COMMERCIALIZATION.md` 참조. Phase B~D 태스크는 이 파일 하단에 추가됨.
> 브랜치 `claude/cool-maxwell-jbo5w1`(T-A01~A06), `claude/sweet-meitner-6xlqeq`(T-A07~A08+)

| ID | 작업 | 파일 | 상태 |
|----|------|------|------|
| T-A01 | `PrivacyInfo.xcprivacy` 생성 (Apple 2024 Privacy Manifest 요건) + pbxproj 등록. `NSPrivacyTracking: false`, `NSPrivacyAccessedAPITypes`: UserDefaults(CA92.1), FileTimestamp(C617.1), SystemBootTime(35F9.1) | `HermesChat/Resources/PrivacyInfo.xcprivacy`, `project.pbxproj` | DONE (2026-06-20) |
| T-A02 | `Info.plist`: `armv7` → `arm64` 수정 | `HermesChat/Resources/Info.plist` | DONE (2026-06-20) |
| T-A03 | `OnboardingView.swift` 신설 (3단계: 환영→서버연결→완료). `AppDefaults.isFirstLaunchComplete` 추가 + `HermesChatApp.swift`에 `.fullScreenCover` 연결 | `Views/OnboardingView.swift`, `Services/AppDefaults.swift`, `HermesChatApp.swift`, `project.pbxproj` | DONE (2026-06-20) |
| T-A04 | 다국어 기반 설정: `ko.lproj/`, `en.lproj/`, `zh-Hans.lproj/` + `Localizable.strings`/`InfoPlist.strings` 생성. 탭 레이블 LocalizedStringKey 전환 | `Resources/{ko,en,zh-Hans}.lproj/*`, `HermesChatApp.swift`, `project.pbxproj` | DONE (2026-06-20) |
| T-A05 | `SettingsView.swift`에 "개인정보 처리방침" 링크 추가 (Privacy Policy URL placeholder) | `Views/SettingsView.swift` | DONE (2026-06-20) |
| T-A06 | Sign in with Apple 엔타이틀먼트 추가 (Phase 2 계정 시스템 준비용, 코드 미사용). **주의:** Personal Team(무료 계정)에서 빌드 오류 발생으로 엔타이틀먼트를 임시 제거. T-C01 구현 + 유료 Apple Developer 계정 등록 시 재추가 필요. | `HermesChat.entitlements` | DONE (2026-06-20) |
| T-A07 | 전체 Views 한국어 하드코딩 → `LocalizedStringKey` 전환 완료. ProfileDetailView(soulSection·modelSection·confirmDialog), CronJobsView, CronJobEditView, DiscussionView, FileBrowserView, Components(MessageView·ToolResultView) 포함 전체 완료. | 전체 `Views/`, `Resources/{ko,en,zh-Hans}.lproj/Localizable.strings` | NEEDS-BUILD (2026-06-20) |
| T-A08 | ATS 정리: `Info.plist` NSAllowsArbitraryLoads 유지 + localhost 중복 예외 제거. App Store 심사 노트 템플릿은 `docs/COMMERCIALIZATION.md` 참조. | `HermesChat/Resources/Info.plist` | NEEDS-BUILD (2026-06-20) |

## Phase B — 클라우드 SaaS 인프라 (백엔드, Phase 0 제출 후 병행)

> 전체 아키텍처: `docs/COMMERCIALIZATION.md` §Phase 1 참조.
> per-user Docker 컨테이너, Supabase Auth, API Gateway 프록시.

| ID | 작업 | 파일 | 상태 |
|----|------|------|------|
| T-B01 | hermes-agent Dockerfile + docker-compose.yml (per-user 컨테이너, ~/.hermes/ 볼륨, default 프로필 자동 생성) | `server/Dockerfile`, `server/docker-compose.yml`, `server/docker-entrypoint.sh`, `server/.env.example` (신규) | DONE (2026-06-20) |
| T-B02 | Supabase 프로젝트 생성 + `users(id, email, plan, container_id)` 테이블 + Sign in with Apple OAuth 설정 | Supabase 대시보드 (코드 아님) | DOING — 프로젝트·테이블 완료. Apple OAuth: 유료 계정 결제 완료, Apple 심사 대기 중 (2026-06-20) |
| T-B03 | `server/cloud_gateway.py` 신규 — JWT 검증 미들웨어 + 사용자별 컨테이너 라우팅 프록시. 엔드포인트: `POST /auth/login`, `GET /status`, `GET /usage`, `DELETE /account`, `*` 프록시. `server/Dockerfile.cloud-gateway` + docker-compose.yml cloud-gateway 서비스 추가 | `server/cloud_gateway.py`, `server/Dockerfile.cloud-gateway` (신규), `server/docker-compose.yml`, `server/.env.example` | DONE (2026-06-20) |
| T-B04 | 클라우드 제공자 배포 — Fly.io 또는 Hetzner CCX13. nginx + Let's Encrypt SSL. Docker Compose로 다수 컨테이너 기동 | 인프라 (코드 아님) | TODO |
| T-B05 | 가격 플랜 (무료/Basic₩9,900/Pro₩29,900) — Supabase `users.plan` 컬럼 + `cloud_gateway.py` 제한 로직 (무료=월 200 메시지, Basic=3 프로필, Pro=10 프로필) | `server/cloud_gateway.py` | DONE (2026-06-20) |

## Phase C — 앱 SaaS 전환 (Phase B 완료 후)

> 자체 호스팅 모드는 그대로 유지. 클라우드 모드를 선택지로 추가.

| ID | 작업 | 파일 | 상태 |
|----|------|------|------|
| T-C01 | `AuthView.swift` 신규 — Sign in with Apple + Supabase Auth. JWT를 `KeychainHelper.swift`에 저장. 로그아웃/계정 삭제 | `Views/AuthView.swift` (신규, **pbxproj 등록**) | NEEDS-BUILD (2026-06-21) — 빌드 성공·실기기 설치 완료. Sign in with Apple 실제 흐름(Supabase id_token) 런타임 검증 대기. T-B02 Apple OAuth 심사 통과 후 가능. |
| T-C02 | 연결 모드 분기 — `AppSettings.connectionMode: .cloud \| .selfHosted`. `HermesAPIClient`에 모드별 baseURL 분기. `.cloud`는 클라우드 게이트웨이 URL 하드코딩 | `Services/AppDefaults.swift`, `Services/HermesAPIClient.swift` | NEEDS-BUILD (2026-06-21) — 빌드 성공·실기기 설치 완료. 클라우드 모드 엔드-투-엔드 런타임 검증 대기. |
| T-C03 | StoreKit 2 구독 — `SubscriptionService.swift` 신규 (Basic/Pro 제품 로드, 엔타이틀먼트 확인, 업그레이드 시트). `SettingsView.swift`에 "구독 관리" 섹션 추가 | `Services/SubscriptionService.swift` (신규, **pbxproj 등록**), `Views/SettingsView.swift` | NEEDS-BUILD (2026-06-21) — 빌드 성공·실기기 설치 완료. StoreKit 샌드박스 테스트는 App Store Connect 제품 등록 후 가능. |
| T-C04 | OnboardingView 클라우드 경로 활성화 — 현재 `isEnabled: false`인 클라우드 버튼 → AuthView 연결. 자체 호스팅 경로는 그대로 | `Views/OnboardingView.swift` | NEEDS-BUILD (2026-06-21) — 빌드 성공·실기기 설치 완료. |
| T-C05 | 사용량 표시 — SettingsView Cloud Account 섹션에 무료 플랜 잔여 메시지 수 표시. 클라우드 `GET /usage` 폴링 | `Views/SettingsView.swift` | NEEDS-BUILD (2026-06-21) — 빌드 성공·실기기 설치 완료. 클라우드 로그인 후 표시 확인 대기. |

## Phase D — 출시 및 운영

| ID | 작업 | 파일 | 상태 |
|----|------|------|------|
| T-D01 | App Store Connect 메타데이터 — 한국어/영어 앱 이름·설명·키워드·스크린샷(6.7인치 6장). 개인정보 처리방침 웹 페이지 (URL 채움) | App Store Connect (코드 아님) | TODO |
| T-D02 | TestFlight 베타 — 내부 테스터 → 외부 100명. 측정: 온보딩 완료율·연결 실패율·채팅 전환율 | TestFlight (코드 아님) | TODO |
| T-D03 | 앱 심사 제출 준비 — 리뷰어 계정·ATS 심사 노트·PrivacyInfo.xcprivacy 최종 확인. 심사 노트는 `docs/COMMERCIALIZATION.md` §App Store 심사 노트 템플릿 참조 | App Store Connect (코드 아님) | TODO |
| T-D04 | v1.1 출시 계획 — Phase 15 핸즈프리 음성(NEEDS-BUILD 완료 후) → TestFlight → 업데이트 심사 | 계획 | TODO |

## 빌드 검증 기록 (검증자가 갱신)

| 날짜 | 브랜치/커밋 | 결과 | 비고 |
|------|------------|------|------|
| 06-10 | claude/busy-meitner-lhc5os @ 9a9d64b | BUILD SUCCEEDED | Hermes 검증, 실기기 프로필 전환 확인 (T-001) |
| 06-10 | claude/busy-meitner-lhc5os @ 8438b64 | BUILD SUCCEEDED | Hermes(codex) 검증 (T-011/T-012/T-014), 실기기 SOUL.md 저장 확인 |
| 06-11 | claude/busy-meitner-lhc5os @ 66bdc93 | BUILD SUCCEEDED | Hermes 빌드검증, NEEDS-BUILD 전수 DONE(T-013/T-020~022/040~041/050~051/060~061/070~075) |
| 06-11 | main @ 879b47e (PR #1 병합) | BUILD SUCCEEDED | 사용자 Xcode 빌드 + 실기기 확인 — Deep think 토론(동시 발언·폴백 회수·결론) 정상 동작. T-090~115 DONE 전환 (음성·사진/파일 기능 확인은 별도 세션 예정) |
| 06-20 | claude/cool-maxwell-jbo5w1 @ 30328cb | BUILD SUCCEEDED | 사용자 빌드 확인 — T-A01~A05 DONE (PrivacyInfo, arm64, OnboardingView, 다국어 기반, 개인정보방침 링크) |
| 06-20 | claude/adoring-thompson-pdohly @ daafef5 | BUILD SUCCEEDED | 사용자 빌드 확인 — T-A06 DONE (Sign in with Apple 엔타이틀먼트) |
| 06-20 | claude/hopeful-edison-1p5q91 @ 8ec3575 | DOCKER BUILD SUCCEEDED | 사용자 확인 — T-B01 DONE (hermes-agent Dockerfile + docker-compose.yml) |
| 06-20 | claude/hopeful-edison-1p5q91 @ 6ce0852 | DOCKER BUILD SUCCEEDED | 사용자 확인 — T-B03 DONE (cloud_gateway.py Dockerfile + 컨테이너 프록시) |
| 06-20 | claude/hopeful-edison-1p5q91 @ 767df1e | DOCKER BUILD SUCCEEDED | 사용자 확인 — T-B05 DONE (플랜 제한 + Bridge 프록시 + 메시지 카운팅) |
| 06-21 | claude/sleepy-bardeen-x86kpk @ 98ad910 | BUILD SUCCEEDED + 실기기 설치 완료 | 사용자 Xcode 빌드 + iPhone17,4(iOS 26.5) 설치 확인 — T-C01~C05 Phase C 전체 (유료 Apple Developer C4LUZYK8L5, 빌드 오류 5개 수정 포함). 런타임 검증은 Sign in with Apple(T-C01)·StoreKit(T-C03) 대기. |
