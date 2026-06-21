# HermesChat iOS — 전체 개발 계획 (Master Plan)

> 최종 수정: 2026-06-21 (Claude Code — Phase C 코드 완성, 브랜치 `claude/sleepy-bardeen-x86kpk` PR #14)
> 진행 상태는 `docs/TASKS.md`, 에이전트 교대 규칙은 `docs/HANDOFF.md` 참조.
> **어떤 에이전트든 이 3개 문서만 읽으면 즉시 작업을 이어갈 수 있어야 한다.**
> 다음 작업: PR #14 main 병합 → T-C01 Sign in with Apple 런타임 검증 → T-C03 StoreKit 샌드박스 테스트 (App Store Connect 제품 등록 필요).

---

## 0. 검증된 사실 (hermes-agent 구조 — 추측 아님, 공식 문서/소스 확인됨)

이 절은 NousResearch hermes-agent의 공식 문서와 `gateway/platforms/api_server.py` 소스에서 확인한 내용이다. 이후 모든 설계가 여기에 근거한다.

### 0.1 프로필 = 독립 게이트웨이 프로세스
- 각 프로필은 `~/.hermes/profiles/<name>/`에 `config.yaml`, `.env`, `SOUL.md`, 세션 DB를 따로 가진 **완전히 독립된 게이트웨이 프로세스**다. default 프로필은 `~/.hermes` 자체.
- **프로필마다 자기만의 API 서버가 있고, 포트가 다르다.** 설정은 각 프로필 `.env`에서:
  - `API_SERVER_ENABLED=true` (기본 false)
  - `API_SERVER_PORT=<고유 포트>` (기본 8642)
  - `API_SERVER_HOST=0.0.0.0` (기본 127.0.0.1 — Tailscale 접근하려면 반드시 변경)
  - `API_SERVER_KEY=<키>` (Bearer 인증)
  - `API_SERVER_MODEL_NAME=<이름>` (기본값 = 프로필 이름. `/v1/models`가 이걸 돌려줌 → 앱의 프로필 자동검색이 이용)
- `config.yaml`로는 API 서버 설정 불가 (문서 명시: env만 지원).
- 게이트웨이 재시작: `hermes gateway restart` (default) / `hermes --profile <name> gateway restart`.

### 0.2 게이트웨이 API 서버 엔드포인트 (포트 8642+, 프로필별)
| 메서드/경로 | 용도 |
|---|---|
| `GET /health` | 헬스체크 (인증 불필요) |
| `GET /v1/models` | 모델/프로필 이름 조회 |
| `GET /v1/skills` | 설치된 스킬 목록 |
| `GET /v1/toolsets` | 툴셋 목록 + 활성화 상태 |
| `GET /api/sessions?limit=&offset=&source=` | 세션 목록 (페이지네이션, `has_more`) |
| `POST /api/sessions` | 세션 생성 (`title`, `system_prompt`, `model` 선택) |
| `GET /api/sessions/{id}` | 세션 단건 |
| `PATCH /api/sessions/{id}` | **제목 변경** (`title`) |
| `DELETE /api/sessions/{id}` | 세션 삭제 |
| `GET /api/sessions/{id}/messages` | 메시지 히스토리 |
| `POST /api/sessions/{id}/chat/stream` | SSE 스트리밍 대화 |
| `POST /api/sessions/{id}/fork` | 세션 분기 |
| `POST /v1/runs`, `GET /v1/runs/{id}/events` | 비동기 실행 + 이벤트 SSE |

### 0.3 게이트웨이 API에 **없는** 것 (→ Hermes Bridge가 담당)
- 프로필 목록 조회 ❌ (각 게이트웨이는 자기 자신만 앎)
- 게이트웨이 재시작 ❌
- SOUL.md 읽기/쓰기 ❌
- 파일 업로드(첨부) ❌
- 칸반 보드 ❌
- 크론잡 조회/편집 ❌ (`<profile>/cron/jobs.json`) — Bridge `GET/PUT /profiles/<n>/cron[/<id>]` (Phase 18)
- 프로필 생성 ❌ — Bridge `POST /profiles`(`hermes profile create … --clone-from default`로 config.yaml/.env/SOUL.md/skills 복제) (Phase 19)
- 프로필 삭제 ❌ — Bridge `DELETE /profiles/<n>`(`hermes profile delete <n> -y`, default 거부) (Phase 19)
- 모델 카탈로그/현재 모델 ❌ (`<profile>/cache/model_catalog.json`, `config.yaml`의 `model.default`)
  — Bridge `GET/PUT /profiles/<n>/model` (Phase 19~20). `/v1/models`는 설정된 1개만 줌(카탈로그 아님).

---

## 1. 전체 아키텍처

```
┌─ iPhone (HermesChat 앱) ─────────────────────────────┐
│  설정: serverHost(스킴+호스트), apiKey, 프로필목록   │
└──────────────┬───────────────────────┬───────────────┘
        Tailscale (100.83.59.60)       │
┌──────────────▼───────────────────────▼───────────────┐
│ 맥미니                                                │
│  :8642  default 프로필 게이트웨이 API                 │
│  :8643  프로필A 게이트웨이 API   ← 세션/채팅/스킬     │
│  :8644  프로필B 게이트웨이 API                        │
│  :8765  Hermes Bridge (server/hermes_bridge.py)       │
│         ← 프로필목록·재시작·SOUL.md·업로드·칸반       │
│  :8000  대시보드 (기존, 추후 WKWebView 임베드용)      │
│  ~/.hermes/kanban.db (+boards/<slug>/kanban.db)       │
│         ← 내장 칸반 — 디스패처·대시보드·앱 공유       │
└───────────────────────────────────────────────────────┘
```

원칙:
1. **세션/대화는 항상 프로필별 게이트웨이 API로 직접** (가장 안정적, 공식 API).
2. 게이트웨이가 못 하는 것만 Bridge로. Bridge는 stdlib 단일 파일이라 유지보수 부담 최소.
3. 칸반은 hermes-agent **내장 칸반(kanban.db)이 단일 진실원본** (2026-06-11 전환, TASKS Phase 9) —
   게이트웨이 디스패처가 ready 태스크를 자동 실행하고, 대시보드 `:8000/kanban`·앱(Bridge 경유)·
   `hermes kanban` CLI가 같은 DB를 본다. 읽기는 Bridge가 sqlite로 직접, 쓰기는 CLI 경유.

---

## 2. Phase 2-2 근본 원인과 해결 (✅ 이번 커밋에서 구현됨)

**증상**: 프로필 드롭다운을 만들어도 맥미니의 기존 프로필이 안 올라오고 전환이 안 됨.

**근본 원인**:
1. 기존 `Profile` 모델이 로컬 전용(UUID/이름)이라 맥미니의 실제 프로필과 아무 연결이 없었음.
2. 구조적으로 **한 게이트웨이(8642)에서 다른 프로필의 세션을 가져오는 것 자체가 불가능** — 프로필마다 별도 프로세스/별도 포트이므로, "프로필 전환 = 다른 포트로 전환"이어야 한다.
3. 부차 버그: `loadSessions()`의 `guard !isLoadingSessions` 때문에 로딩 중 프로필을 바꾸면 재조회가 무시되고, 늦게 도착한 이전 프로필 응답이 목록을 덮어씀.

**구현된 해결** (빌드 검증 필요 → TASKS.md T-001):
- `HermesProfile` 모델: `name` + `port` (+ 프로필별 apiKey 옵션). 기본값 `default`/8642.
- `AppSettings`: 프로필 배열 영속화(UserDefaults JSON), `selectProfile()` 시 세션 초기화+재조회, **세대 카운터(loadGeneration)** 로 늦은 응답 폐기.
- `baseURL(for:)`: serverHost의 스킴/호스트 + 프로필 포트 결합.
- **프로필 자동 검색**: 호스트의 8642–8651 포트를 동시 프로브 → `/v1/models`의 모델 id(= 프로필 이름)로 자동 등록. 수동 추가(이름+포트)도 지원.
- `SessionListView` 좌측 상단: 프로필 드롭다운(+ 소스 필터 통합), `.searchable` 세션 검색(돋보기).
  행 스와이프: trailing = 삭제·이름변경·고정(Pin), leading = 분기. 풀 스와이프 자동 삭제는 끔
  (`allowsFullSwipe: false`). Pin은 서버 미지원이라 로컬(UserDefaults) 보관. (Phase 20)
- 첫 메시지 후 `PATCH /api/sessions/{id}` 자동 제목 (Phase 2 잔여분).

**맥미니 1회 설정 (필수!)** — 이게 없으면 앱을 아무리 고쳐도 전환 안 됨:
```bash
cd "/Users/macmini/projects/HermesChat"
git pull
bash scripts/setup_profiles_api.sh <API_KEY>
```
✅ 2026-06-10 완료 — default+6프로필(8642~8648) 헬스체크 통과.
스크립트가 하는 일: default=8642 유지, 나머지 프로필에 8643부터 포트 배정, `API_SERVER_HOST=0.0.0.0`, `API_SERVER_MODEL_NAME=<프로필명>` 설정, 게이트웨이 전체 재시작, 포트 응답 확인. 이후 아이폰 앱 설정에서 **"프로필 자동 검색"** 버튼 한 번.

---

## 3. Phase별 계획

표기: ✅ 구현됨(빌드검증 대기) / ⬜ 미착수. 세부 상태는 `docs/TASKS.md`.

### Phase 2-2/2-3 — 프로필 전환 + 자동 제목 + 세션 검색 ✅
위 2절 참조. 남은 일: 맥미니에서 `setup_profiles_api.sh` 실행, Xcode 빌드, 실기기 확인.

### Phase 3 — 설정 확장 + Bridge 도입
**목표**: 설정 창 완성 — 원격 접속, 프로필 설정(모델/SOUL.md), Skills & Tools, Gateway restart 버튼.
1. **Bridge 배포** (T-010): `server/hermes_bridge.py`를 맥미니 LaunchAgent로 상시 기동 (`server/README.md` 절차). 앱 설정에 Bridge URL(`http://100.83.59.60:8765`)·토큰 필드 추가.
2. **BridgeClient.swift** (T-011, 신규 파일 — pbxproj 등록 필요!): `fetchProfiles()`, `restartGateway(profile:)`, `fetchSoul/saveSoul`, `upload(data:filename:profile:)`, `fetchBoards/fetchBoard/saveBoard`.
3. **프로필 관리 화면** (T-012): 설정→프로필 행 탭→상세 화면. 모델 선택(`GET /v1/models`), SOUL.md 편집(TextEditor, Bridge GET/PUT), Gateway restart 버튼(확인 다이얼로그 필수).
4. **Skills & Tools 화면** (T-013): 게이트웨이 `GET /v1/skills`, `/v1/toolsets` 읽기 전용 표시(이름/설명/활성). 토글은 config.yaml 수정이 필요하므로 후순위(T-031).
5. Bridge 프로필 목록을 자동 검색과 병행: Bridge가 있으면 `GET /profiles`(포트 포함)가 더 정확 — 포트 스캔은 폴백.

**수용 기준**: 설정에서 SOUL.md 수정→저장→맥미니 파일 변경 확인. restart 버튼→해당 프로필만 재시작. 스킬 목록 표시.

### Phase 4 — 채팅 첨부 (사진/파일/구글드라이브)
**목표**: 입력창 왼쪽 `+` 버튼 → 사진/파일 첨부.
1. **업로드 흐름** (T-020): iOS에서 Bridge `POST /upload/<profile>` (raw body + `X-Filename`) → 응답의 절대경로를 메시지에 `[첨부: /Users/macmini/.hermes/profiles/<p>/uploads/xxx.jpg]` 형태로 prepend → Hermes가 자기 파일 도구로 읽음. (게이트웨이 chat API는 텍스트만 받으므로 이 방식이 정석.)
2. **UI** (T-021): `ChatView` inputBar에 `+` Menu — `PhotosPicker`(사진), `.fileImporter`(파일·**구글드라이브는 iOS Files 앱에 Drive가 Provider로 떠서 자동 지원** — 별도 Drive API 불필요), 업로드 진행 표시, 전송 전 첨부 칩 표시/삭제.
3. Info.plist: `NSPhotoLibraryUsageDescription` 추가 (T-022).

**수용 기준**: 사진 선택→전송→Hermes가 이미지 내용을 설명하는 답변.

### Phase 5 — 프로필 보드 (6분할 홈)
**목표**: 드롭다운 대신 2×3 그리드 보드에서 프로필 선택 → 해당 프로필 세션 목록으로 전환.
1. **ProfileBoardView** (T-040, 신규 파일): `LazyVGrid(columns: 2)` 카드 — 프로필명, 포트, 온라인 상태(`/health` 프로브), 최근 세션 수. 탭 → `SessionListView`(해당 프로필로 `selectProfile` 후 push).
2. 루트 구조 변경 (T-041): `HermesChatApp` → `TabView` { 프로필보드(홈) / 세션 / 칸반 / 설정 }. 기존 드롭다운은 세션 탭에 유지(빠른 전환용).

**수용 기준**: 보드에서 프로필 탭→그 프로필의 새 세션/지난 세션 목록 표시.

### Phase 6 — KANBAN 보드 (⚠ 2026-06-11 내장 칸반으로 전면 전환 — TASKS Phase 9)

> 초기 구현(JSON 파일 + 보드 전체 PUT)은 hermes-agent **내장 칸반과 별개의 데이터**라서
> 대시보드에 안 보이고 디스패처가 실행하지도 않았다. T-080~082에서 내장 칸반으로 전환.

**현행 구조**:
- 데이터: `~/.hermes/kanban.db`(default) + `~/.hermes/kanban/boards/<slug>/kanban.db`
- 상태 7단계: `triage|todo|scheduled|ready|running|blocked|done` (+archived는 숨김)
- **ready 태스크는 게이트웨이 디스패처가 60초 내 워커 프로필로 자동 실행** (running → done/blocked)
- Bridge API: `GET /kanban`(보드 목록+카운트), `GET /kanban/<board>`(태스크, sqlite 직접 읽기),
  `POST /kanban/<board>/tasks`(생성 — status: ready|triage|blocked),
  `POST /kanban/<board>/tasks/<id>/action`(promote/block/unblock/complete/archive/comment — `hermes kanban` CLI 경유)
- 앱: 카드 상태는 액션 메뉴로만 전이(running은 디스패처 소유라 수동 이동 불가).
  새 작업 시트에서 담당 프로필 + 시작 방식(바로 실행/구체화 후 실행/보류) 선택.
- Hermes 스킬: `~/.hermes/skills/kanban/SKILL.md` v2 — `hermes kanban` CLI 사용 (HANDOFF 부록 B).

**수용 기준**: 앱에서 "바로 실행" 태스크 생성 → 대시보드 `:8000/kanban`에 표시 → 1분 내 디스패처가
워커 실행(running) → done 전이가 앱·대시보드 양쪽에 반영.

### Phase 7 — 터미널 / 파일 탐색기
1. **빠른 길** (T-060): 기존 대시보드(`http://100.83.59.60:8000`)를 `WKWebView` 탭으로 임베드(세션 토큰 입력 재활용). 공수 거의 0.
2. **네이티브** (T-061, 후순위): Bridge에 읽기 전용 확장 — `GET /files?path=`(HERMES_HOME 하위로 제한), `GET /profiles/<n>/logs?tail=200`. 임의 명령 실행(exec)은 보안상 넣지 않는다 — 명령 실행이 필요하면 Hermes 채팅으로 시키는 것이 Hermes의 설계 철학과 일치.

### Phase 8 — 마감 품질
- API Key/토큰 Keychain 이전 (현재 UserDefaults) (T-070)
- 스트리밍 개선: 현재 `dataTask` 완료 후 일괄 파싱 → `URLSession.bytes(for:)` 라인 단위 실시간 SSE (T-071, `HermesAPIClient.streamChat` + `ApiClient.swift` 정리/삭제)
- 세션 페이지네이션(`has_more`/`offset`) (T-072), iPad 레이아웃, 다크모드 점검 (T-073)

### Phase 10 — 채팅 UX 고도화 (2026-06-11 계획 수립)
**목표**: 매일 쓰는 채팅 화면의 체감 품질 — 마크다운 렌더링, 복사/공유, 세션 분기.
1. **마크다운/코드블록** (T-090): 코드펜스(```)만 자체 분리하고 텍스트 구간은
   `AttributedString(markdown:, interpretedSyntax: .inlineOnlyPreservingWhitespace)`로 인라인 파싱.
   `.full` 해석은 SwiftUI Text가 블록 인텐트를 렌더링하지 못해 코드블록이 뭉개지므로 쓰지 않는다.
   코드블록은 모노스페이스+배경+복사 버튼. 미닫힌 펜스(스트리밍 중)는 코드로 취급. **SPM 의존성 금지**
   (멀티 에이전트가 pbxproj를 수동 편집하는 구조라 패키지 참조 추가는 파손 위험).
2. **메시지 컨텍스트 메뉴** (T-091): 복사(UIPasteboard)·공유(ShareLink).
3. **세션 fork** (T-092): 게이트웨이 `POST /api/sessions/{id}/fork` (§0.2에 이미 있음).
   createSession의 단계적 응답 해석을 공용 메서드로 추출해 재사용 (fork 응답 스키마도 미확인).

4. **사고과정 숨김 + 도구 칩** (T-103~104, 2026-06-11 사용자 요청): `<think>` 블록은 완전
   숨김(미닫힌 태그·토큰 경계 부분 태그까지 스트리밍 안전), tool/system role 메시지는 비렌더,
   도구 실행은 "도구 N회 실행" 접힌 칩(탭 펼침). 사고 중에는 작업 바에 "생각 중..." 표시.
5. **이미지 썸네일** (T-105~108): 사용자가 보낸 `[첨부: 경로]`와 에이전트의 `![alt](src)`·첨부
   경로를 썸네일로. 맥 로컬 경로는 Bridge 신규 `GET /files/raw`(바이너리, 20MB 상한)로 받고,
   절대경로의 `.hermes/` 마커 뒤를 상대경로로 변환. Bridge 미배포/경로 밖이면 placeholder 강등.
   전송 전 입력 칩에도 썸네일.

**수용 기준**: 코드블록이 모노스페이스+배경+복사 버튼으로 표시, 볼드/링크/인라인코드 렌더링,
스트리밍 중 깨짐 없음. 길게 눌러 복사/공유. fork → 새 세션에 히스토리 보존 + 이어서 대화.
다크모드에서 코드블록 가독 확인(버블 tertiary vs 코드 secondary 배경).
스트리밍 중 `<think>` 토큰이 한 글자도 노출되지 않고 도구 칩 펼침 동작. 사진 첨부 시 입력 칩·
사용자 버블·에이전트 회신 경로 모두 썸네일, Bridge 미설정 시 placeholder(에러 알럿 없음).

### Phase 11 — 알림 (로컬 알림 + 폴링, APNs 없음)
**목표**: 칸반 태스크 done/blocked 전이·긴 채팅 응답 완료를 로컬 알림으로.
**원칙**: Bridge 무수정 경로(기존 `GET /kanban*` 폴링 + diff)가 본선 — Bridge 재배포는 맥 에이전트만
가능하므로(HANDOFF §2.5) 차단 요소에서 제외. Bridge events 엔드포인트(T-096)는 선택적 최적화.
1. **NotificationService** (T-093): 칸반 스냅샷(taskID→status) diff → done/blocked 전이 시 로컬 알림.
2. **응답 완료 알림** (T-094): 스트림 완료 시 앱이 비활성이고 10초 이상 경과면 알림.
3. **BGAppRefreshTask** (T-095): 백그라운드 주기 폴링 — iOS가 실행 시점을 보장하지 않음을 수용.
4. **(선택) Bridge events API** (T-096): kanban.db events 테이블 — **착수 전 맥 에이전트가
   `sqlite3 ~/.hermes/kanban.db ".schema events"` 결과를 TASKS.md에 기록**할 것.

**수용 기준**: 앱 포그라운드에서 맥미니 `hermes kanban complete <id>` 후 60초 내 로컬 알림.
백그라운드 진입 후 응답 완료 알림. BGAppRefresh는 수 시간 내 1회 실행이면 합격(즉시성 미보장).

### Phase 12 — 설정 심화
1. **Bridge config API** (T-097): `GET /profiles/<n>/config`(key/token/secret 줄 마스킹) +
   `PATCH /profiles/<n>/config`(`toolsets` 키 화이트리스트만, 라인 단위 블록 치환, `.bak` 백업,
   비정형 입력은 400 거부 — stdlib만이라 yaml 파서 없음). **착수 전 실제 config.yaml의
   toolsets 블록 형태를 맥 에이전트가 확인해 기록**.
2. **툴셋 토글** (T-098, =T-031 본편): SkillsView 토글 → "적용" → Bridge PATCH → 재시작 안내.
   Bridge 404 시 기존 읽기전용으로 우아하게 강등.
3. **프로필별 apiKey Keychain 이관** (T-099): Keychain 키 `profileApiKey.<name>`,
   UserDefaults JSON에는 빈 문자열 직렬화, 로드 시 구버전 평문 1회 이관.

**수용 기준**: 툴셋 토글 적용 → config.yaml에 해당 블록만 변경+`.bak` 생성 → 재시작 후
`/v1/toolsets` 반영. 구버전 기기 업데이트 후 정상 인증 + UserDefaults에 평문 키 부재.

### Phase 13 — 음성 입출력 (내장 프레임워크만)
1. **음성 입력** (T-100): SpeechService(SFSpeechRecognizer+AVAudioEngine, ko-KR) + 입력창 마이크 버튼.
   Info.plist `NSSpeechRecognitionUsageDescription`·`NSMicrophoneUsageDescription`.
2. **읽어주기** (T-101): AVSpeechSynthesizer, 메시지 컨텍스트 메뉴 "읽어주기/중지".
   입력은 `MarkdownLite.plainText(from:)`(T-090 파서 재사용). AVAudioSession은 SpeechService 단일 소유.

**수용 기준**: 한국어 받아쓰기 → 입력창 삽입 → 전송. "읽어주기"가 마크다운 기호 없이 낭독, 재탭 중지.

### Phase 14 — Deep think 멀티 에이전트 토론룸 ✅ (2026-06-11 구현·실기기 검증 완료, main 병합)
**목표**: 프로필 보드 툴바 "Deep think" 버튼으로 진입하는 토론룸 — 여러 프로필(모델/페르소나)이
한 주제로 다라운드 토론 후 사회자가 최종 결론(합의점/이견/결론)을 작성. 서로 다른 모델의
상호 검증으로 환각을 줄이고 더 나은 아이디어를 얻는 것이 목적.

**설계**: 클라이언트 오케스트레이션 — 참가 프로필마다 해당 게이트웨이에 전용 세션 생성
(제목 `[Deep think] <주제>`, system_prompt로 토론 규칙 주입) 후 앱이 발언을 중계.
**라운드는 동시 진행** (T-115, 2026-06-11 실기기 피드백): 라운드 시작 시 직전 발언을
스냅샷해 참가자별 메시지를 사전 조립하고 withTaskGroup으로 전원 병렬 스트리밍 —
순차 대기 제거. 라운드 k>1은 타인의 직전 라운드 발언만 전달(자기 발언은 자기 세션
히스토리에 이미 있음 — 토큰 절약). 발언은 `strippingThink` 후 5~10문장 유도.
**발언 미수신 폴백** (T-114): 게이트웨이가 응답을 세션에는 기록하지만 SSE 스트림으로는
안 보내는 경우(실기기 확인)가 있어, 스트림이 빈 채 끝나면 세션 메시지를 2초 간격
폴링(최대 300초)해 "마지막 user 메시지 뒤의 visible assistant 답변"을 회수한다.
도구 사용은 설정 토글(기본 OFF — 켜면 한 턴이 수 분 가능 경고). 라운드 수 1~5 선택
(기본 2), 사회자 지정 가능(기본 첫 참가자, 탈락 시 승계). 게이트웨이 오류·폴백
타임아웃 참가자는 탈락 처리 후 계속, 활성 2명 미만이면 중단. 완료 토론은 UserDefaults
JSON 보관(최대 20건, 지난 토론 재열람). 진행 중 `isIdleTimerDisabled`로 화면 꺼짐
(앱 suspend → 토론 중단) 방지. 진입은 fullScreenCover — push는 엣지 스와이프 실수로
장시간 토론이 소실될 수 있어 배제.

**수용 기준**: 프로필 2~3개 선택 + 주제 입력 + 라운드 2 → 발언 실시간 스트리밍, 라운드
구분선, 토론 중 한 프로필 게이트웨이를 내려도 제외 알림 후 계속 진행, 사회자 결론 카드 +
복사/공유, 중지 버튼 즉시 중단, 앱 재시작 후 지난 토론 열람. 실기기 확인 항목:
system_prompt가 SOUL.md 페르소나와 병합되어 발언에 성향이 유지되는지(대체된다면
프롬프트 보강 후속 태스크).

### Phase 16 — 메타 글라스 사진 자동 전송 + 음성 후속 질의 🏗️ (2026-06-13 구현, NEEDS-BUILD)
**목표**: 음성 대화 중 글라스로 사진을 찍으면 자동 전송하고, 그 사진에 대해 음성으로 이어 묻는다.

**조사 결론(Meta Wearables DAT)**: 물리 '촬영' 버튼·안경다리 더블탭은 3rd-party 앱이 가로챌 수
없다(더블탭은 표준 BT AVRCP 미디어키 — 이미 T-119가 받는 신호일 뿐 사진을 주지 않음). DAT 카메라
스트림(`capturePhoto`, ~0.9MP)은 가능하나 Meta 개발자 등록·개발자 프리뷰라 도입하지 않기로 함(사용자
결정). **대신 사진 보관함 감시 방식** — 글라스 사진이 Meta AI 앱을 통해 카메라 롤에 동기화되는 것을
`PHPhotoLibrary` 변화로 감지해 기존 첨부·음성 파이프라인으로 넘긴다(SDK 의존성 없음, App Store 안전).

**설계**: `PhotoImportWatcher`(전체 사진 접근 필요, 시작 시각 이후·이미지·비스크린샷만)가 새 사진을
감지 → `ChatViewModel.handleCapturedPhoto`가 기존 `addAttachment`로 대기 첨부 →
`VoiceConversationController.announcePhotoArrival`가 **"사진이 도착했습니다"**를 음성으로 알린 뒤(동기화
지연 수초~수십초를 사용자에게 알림) 핸즈프리 청취로 진입 → 사용자의 음성 질문을 기존
`finishListening`→`send()`가 대기 첨부 사진과 함께 전송 → 응답 자동 낭독·재청취 루프. 질문이 없으면
무발화 타임아웃에 기본 프롬프트로 사진 전송(폴백). 전송은 컨트롤러 단일 경로로 일원화해 이중 전송 방지.

**수용 기준**: 전체 사진 접근 허용 + 글라스 모드 ON에서 글라스로 촬영 → 도착 음성 알림 → 음성 질문 →
사진+질문 전송·응답 낭독·재청취. 제한 접근이면 안내 후 비활성. 스크린샷/기존 사진은 제외.

### Phase 18 — 프로필별 크론잡 조회·편집 (2026-06-15, TASKS T-138)
대시보드(:8000)의 Cron 화면을 네이티브로 재현. 프로필 보드 카드의 시계 버튼 → 잡 목록(시트) →
잡 편집(프롬프트·스케줄·전달대상·스킬·활성화). 데이터는 `<profile>/cron/jobs.json`(단일 JSON).
Bridge `GET /profiles/<n>/cron`, `PUT /profiles/<n>/cron/<id>`(편집 필드만 read-modify-write,
나머지 보존, .bak+원자적). 후속: 생성/삭제/즉시실행/실행리포트 뷰어.

### Phase 19 — 프로필 추가('+' 카드) + 모델 카탈로그 선택 + 삭제 (2026-06-15~16, TASKS T-139~145)
프로필 보드 '+' 카드 → 새 프로필 **백엔드 완전 생성**(Bridge `POST /profiles`). 모델 선택은 **카탈로그 기반**:
Bridge `GET /profiles/<n>/model`(현재값=`config.yaml`의 `model.default` + 카탈로그=
`<profile>/cache/model_catalog.json`의 `providers.<p>.models[].id`), `PUT /profiles/<n>/model`
(`model.default` 값만 교체, 주변 블록 보존, restart 옵션). ProfileDetailView 모델 섹션을 카탈로그
드롭다운+저장(재시작)로 교체. 한계: provider/base_url은 미변경(모델 id만) — provider 라우팅 변경은 후속.

**생성 방식 확정(T-143)**: 처음엔 수동 mkdir+.env만 작성해 config.yaml이 안 생기고 모델 저장이 깨졌다.
→ **`hermes profile create <n> --clone-from default`**로 교체(default의 config.yaml/.env/SOUL.md/skills
복제). 클론된 .env 위에 API 서버 키만 머지(`set_env_values`)로 덮어쓰고, 포트는 생성 전 `next_free_port`.

**진단/보정(T-144)**: 재배포 후에도 앱 생성분에 config.yaml이 안 생기던 문제 — launchd로 뜬 브리지가
사용자 셸 env를 못 받아 `--clone-from default`가 default를 못 찾을 수 있음. ⓐcreate/start 서브프로세스에
`hermes_env()`로 HOME·HERMES_HOME 명시 주입, ⓑcreate 후 config.yaml 존재를 검증해 없으면 500 +
hermes stdout/stderr를 `detail`로 반환(조용한 실패 가시화).

**카탈로그 폴백(T-141)**: 새 프로필은 자기 `cache/model_catalog.json`이 없어 드롭다운이 비던 것 →
프로필 결과가 비면 default 프로필 카탈로그로 폴백(모든 프로필 동일 목록 공유).

**삭제(T-145)**: Bridge `DELETE /profiles/<n>` → `hermes profile delete <n> -y`(default/미존재 거부).
ProfileDetailView에 파괴적 "프로필 삭제" 버튼(확인 다이얼로그) → 성공 시 앱 목록에서 제거 + 화면 pop.

> **운영 주의**: `server/hermes_bridge.py`를 고치면 맥미니 기동본(`~/.hermes/bridge/`) 교체 +
> LaunchAgent 재기동까지 해야 앱에 반영된다(안 하면 신규 엔드포인트가 "브리지 HTTP 404"). HANDOFF §2.5.

### Phase 20 — 세션 핀·이름변경 (2026-06-16, TASKS T-147)
세션 목록 trailing 스와이프를 강화. 삭제 버튼 왼편에 **이름변경**(pencil)·**고정/고정해제**(pin)
아이콘 버튼 추가. **이름변경**은 기존 `updateSessionTitle` API + `updateSession` 로컬 갱신을
alert(TextField)로 연결(SettingsView 프로필 rename 패턴 재사용). **고정(Pin)**은 서버에 핀 저장이
없어 `pinnedSessionIDs`(UserDefaults)에 로컬 보관 — `filteredSessions`가 고정 세션을 맨 위로 안정
정렬하고 행에 `pin.fill` 표시, 앱 재실행 후에도 유지. 그리고 trailing 스와이프의 **풀 스와이프
자동 삭제를 끔**(`allowsFullSwipe: false`) — 버튼을 확인하려 길게 민 것만으로 세션이 삭제되던
사고를 막고, 삭제 버튼을 눌러야만 삭제되게 한다. 기존 파일만 수정(pbxproj 무수정).

### Phase A — App Store 컴플라이언스 (2026-06-20, T-A01~A08)
App Store 제출을 위한 필수 요건 정비. T-A01~A06 DONE, T-A07/A08 NEEDS-BUILD (PR #12 대기).

| 태스크 | 내용 | 상태 |
|---|---|---|
| T-A01 | PrivacyInfo.xcprivacy 신설 (API 사용 선언) | DONE |
| T-A02 | arm64 전용 빌드 + Excluded Arch 정리 | DONE |
| T-A03 | OnboardingView 3단계 (환영→서버연결→완료) | DONE |
| T-A04 | 다국어 기반 ko/en/zh-Hans Localizable.strings | DONE |
| T-A05 | SettingsView 개인정보 처리방침 링크 | DONE |
| T-A06 | Sign in with Apple 엔타이틀먼트 (Personal Team에서 임시 제거) | DONE |
| T-A07 | 전체 Views 한국어 하드코딩 → LocalizedStringKey | NEEDS-BUILD |
| T-A08 | ATS 정리 (NSAllowsArbitraryLoads 유지, localhost 중복 제거) | NEEDS-BUILD |

### Phase B — 클라우드 SaaS 인프라 (2026-06-20, T-B01~B05)
per-user Docker 컨테이너 + Supabase Auth + cloud_gateway.py.

**아키텍처**:
```
iPhone (HermesChat, cloud 모드)
  ↓ HTTPS (Supabase JWT Bearer)
cloud_gateway.py (:8080) ← T-B03
  ├── JWT 검증 (HS256, stdlib)
  ├── 플랜 제한 (T-B05): free=200msg/월·1프로필, basic=3프로필, pro=10프로필
  ├── 컨테이너 라우팅: hermes-user-{uid} Docker 컨테이너
  │     ├── :8642  hermes-agent 게이트웨이 API  (프록시)
  │     └── :8765  Hermes Bridge               (/bridge/* 프록시)
  └── SQLite /data/gateway_usage.db — 월별 메시지 카운트
```

**핵심 파일**:
- `server/Dockerfile` + `server/docker-entrypoint.sh` — per-user hermes-agent 이미지 (T-B01)
- `server/docker-compose.yml` — hermes-agent + cloud-gateway 서비스 (T-B01/T-B03)
- `server/cloud_gateway.py` — JWT 검증·컨테이너 프로비저닝·플랜 제한·Bridge 프록시 (T-B03/T-B05)
- `server/Dockerfile.cloud-gateway` — cloud_gateway 경량 이미지 (T-B03)
- `server/.env.example` — 환경변수 템플릿

**플랜 가격**: free (무료), basic ₩9,900/월 (3프로필), pro ₩29,900/월 (10프로필)

**남은 작업**: T-B02 Supabase 대시보드 설정(코드 아님), T-B04 클라우드 배포(코드 아님).

### Phase C — iOS SaaS 전환 (Phase B 완료 후, T-C01~C05)
자체 호스팅 모드는 그대로 유지, 클라우드 모드를 선택지로 추가.

| 태스크 | 내용 | 의존 |
|---|---|---|
| T-C01 | `AuthView.swift` — Sign in with Apple + Supabase Auth, JWT Keychain 저장 | T-B02 |
| T-C02 | `AppSettings.connectionMode: .cloud|.selfHosted` + HermesAPIClient baseURL 분기 | T-C01 |
| T-C03 | StoreKit 2 구독 — `SubscriptionService.swift` Basic/Pro 제품 로드·엔타이틀먼트 | T-C01 |
| T-C04 | OnboardingView 클라우드 경로 활성화 (AuthView 연결) | T-C01 |
| T-C05 | 사용량 표시 — `GET /usage` 폴링, 잔여 메시지 수 SettingsView 표시 | T-C02 |

---

## 4. 위험 요소 / 주의
1. **새 Swift 파일은 반드시 `project.pbxproj`에 등록** (objectVersion 77, 명시적 파일 참조 — 자동 동기화 폴더 아님). 절차는 HANDOFF.md §4. *이번 커밋은 기존 파일만 수정해서 pbxproj 변경 없음.*
2. `API_SERVER_HOST=0.0.0.0`은 Tailscale 사설망 전제. 공유기 포트포워딩으로 공인망 노출 금지.
3. 프로필 자동 검색은 default 프로필이 `API_SERVER_MODEL_NAME` 미설정이면 이름이 "hermes-agent" 등으로 잡힐 수 있음 → setup 스크립트가 모든 프로필에 MODEL_NAME을 프로필명으로 설정해 해결.
4. 여러 게이트웨이 상시 기동 = 프로필 수만큼 상주 프로세스. 맥미니 메모리 확인. `hermes-gateways status`로 관리.
5. 칸반 쓰기는 반드시 `hermes kanban` CLI(또는 에이전트 kanban_* 도구) 경유 — kanban.db를 SQL로 직접 수정하면 이벤트 기록·의존성 재계산·디스패치 불변식이 깨진다. Bridge도 읽기만 sqlite 직접, 쓰기는 CLI subprocess.
6. 스트리밍 중 마크다운 재파싱(T-090): 토큰마다 메시지 전체를 재파싱한다 — 일반 응답에선 무시
   가능하나 초장문에서 프레임 드랍 가능. 문제가 보이면 "스트리밍 중 평문, 완료 시 마크다운" 폴백을
   후속 태스크로.
