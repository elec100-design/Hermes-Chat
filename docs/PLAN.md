# HermesChat iOS — 전체 개발 계획 (Master Plan)

> 최종 수정: 2026-11-17 (default — 멀티플렉스 게이트웨이 반영: §0.1a 신설, §1 구성도·§2·§4 정정, Phase 30(T-216) 추가)
> 진행 상태는 `docs/TASKS.md`, 에이전트 교대 규칙은 `docs/HANDOFF.md` 참조.
> **어떤 에이전트든 이 3개 문서만 읽으면 즉시 작업을 이어갈 수 있어야 한다.**
> 다음 작업: T-216 앱 게이트웨이 직결 멀티플렉스 대응(지금은 default 외 프로필 직결 불가) · Phase 28 실기기 검증 · T-210 마무리 → T-211 Bridge MCP API.

---

## 0. 검증된 사실 (hermes-agent 구조 — 추측 아님, 공식 문서/소스 확인됨)

이 절은 NousResearch hermes-agent의 공식 문서와 `gateway/platforms/api_server.py` 소스에서 확인한 내용이다. 이후 모든 설계가 여기에 근거한다.

### 0.1 프로필 = 독립 게이트웨이 프로세스 (⚠️ 옛 구성 — 2026-10-22부터 §0.1a 멀티플렉스로 대체)
- 각 프로필은 `~/.hermes/profiles/<name>/`에 `config.yaml`, `.env`, `SOUL.md`, 세션 DB를 따로 가진 **완전히 독립된 게이트웨이 프로세스**다. default 프로필은 `~/.hermes` 자체.
- **프로필마다 자기만의 API 서버가 있고, 포트가 다르다.** 설정은 각 프로필 `.env`에서:
  - `API_SERVER_ENABLED=true` (기본 false)
  - `API_SERVER_PORT=<고유 포트>` (기본 8642)
  - `API_SERVER_HOST=0.0.0.0` (기본 127.0.0.1 — Tailscale 접근하려면 반드시 변경)
  - `API_SERVER_KEY=<키>` (Bearer 인증)
  - `API_SERVER_MODEL_NAME=<이름>` (기본값 = 프로필 이름. `/v1/models`가 이걸 돌려줌 → 앱의 프로필 자동검색이 이용)
- `config.yaml`로는 API 서버 설정 불가 (문서 명시: env만 지원).
- 게이트웨이 재시작: `hermes gateway restart` (default) / `hermes --profile <name> gateway restart`.

### 0.1a 멀티플렉스 게이트웨이 (현재 구성 — 2026-10-22 Hermes-bot 실기 확인, 2026-11-17 맥미니 v0.23.1 재확인)
- `~/.hermes/config.yaml` `gateway.multiplex_profiles: true`. 게이트웨이 **하나(:8642)**가 모든 프로필을 서비스한다. 프로필별 게이트웨이 실행은 종료 코드 75로 끝난다 → **8643~ 포트는 더 이상 열리지 않는다.**
- default는 접두사 없이, 나머지는 `:8642/p/<profile>/…` (예: `/p/wiki/health` → 200, `/p/wiki/v1/models` → `wiki`). 접두사 없는 `/v1/models`는 `default`만 준다. 모르는 접두사는 `{"error": "Unknown or unconfigured profile"}`(fail closed). `/health`는 인증 불필요.
- 인증: `/p/<profile>/`는 **그 프로필 `.env`의 `API_SERVER_KEY`만** 받는다. default 키로는 `gateway_auth_failed`(401) — owner 키를 상속하지 않는다. 소스 `gateway/platforms/api_server.py` `_expected_api_key`·`_check_auth`.
- 바인딩: default `.env` `API_SERVER_HOST=100.83.59.60`(Tailscale IP) — `127.0.0.1:8642`로는 닿지 않는다. 같은 프로세스가 `:8644`에 webhook 플랫폼도 띄운다(게이트웨이 API 아님).
- Bridge `GET /profiles`의 `port`·`api_enabled`는 프로필 `.env`의 옛 값이라 **멀티플렉스에서는 의미가 없다**(11-17 실측: wiki 8646·invest 8647·safety 8648로 나오지만 그 포트는 닫혀 있음, grok·opus·gemini는 api_enabled=false·port 8642).
- Hermes-bot은 P28(`8143a66`)부터 `HB_GATEWAY_MULTIPLEX=auto`로 `/p/<profile>/`를 붙여 전 프로필에 닿는다. **HermesChat 앱(iOS·Android)의 게이트웨이 직결은 아직 "프로필 = 포트" → default만 동작** (Phase 30 / T-216).
- 프로필별 재시작(`hermes --profile <name> gateway restart`, Bridge `POST /profiles/<n>/restart`)이 멀티플렉스에서 어떻게 동작하는지는 **확인 필요**. 공유 게이트웨이 재시작은 `hermes gateway restart`.

### 0.2 게이트웨이 API 서버 엔드포인트 (멀티플렉스: default는 `:8642/…`, 그 외 `:8642/p/<profile>/…`)
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

#### 스트리밍 와이어 포맷 (2026-11-12 정정 — Hermes-bot docs/PLAN.md §0.2.1 에서 게이트웨이 소스로 확인된 내용)

`POST /api/sessions/{id}/chat/stream` 응답은 OpenAI 청크가 아니라 **이름 있는 SSE 이벤트**다:
`run.started` → `message.started` → `assistant.delta`(`{"delta":"…"}`, 반복) → (`tool.progress` / `tool.started` / `tool.completed` / `tool.failed`, payload 형식 미확인) → `assistant.completed`(`{"content":"<전체>"}`) → `run.completed` → `done`.
`event: error`의 data는 `{"message":…}`.

T-149 이전 앱은 델타를 전부 버리고 폴링 폴백(T-116/T-121)으로만 답을 받았다.

멀티플렉스 게이트웨이(§0.1a)는 앱이 아직 대응하지 않는다 — default 외 프로필 직결은 **동작하지 않는다**(11-17 확인: 8643~ 닫힘). 대응은 Phase 30(T-216). 봇 탭(Hermes-bot 경유)은 전 프로필에 닿는다.

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

### 0.4 MCP (2026-11-16 — hermes-agent GitHub main 의 `website/docs/user-guide/features/mcp.md` 로 확인. **맥미니 v0.23.1 에서는 미검증** → TASKS T-210)
- MCP 서버는 `config.yaml` 의 `mcp_servers:` 에 이름별 블록으로 등록한다. stdio 는 `command`/`args`/`env`, 원격은 `url`/`headers`/`auth: oauth`. 공통 `enabled`, `tools.include|exclude`.
- CLI: `hermes mcp`(대화형 선택기) · `hermes mcp catalog`(카탈로그 평문 목록) · `hermes mcp install <name>` · `hermes mcp configure <name>` · `hermes mcp login <server> [--flow device]` · `hermes mcp test <name>` · `hermes mcp add <name> --preset codex`. **`list`/`remove` 서브커맨드는 문서에서 확인하지 못했다.**
- 카탈로그는 Nous 가 검수한 항목만(`optional-mcps/`), 기본 비활성. 카탈로그 자격증명은 프로필 `.env` 에 쓴다.
- 게이트웨이(`hermes gateway run`)가 `config.yaml` 을 감시해 약 1분 안에 반영(항목 제거·`enabled: false` → 내려감, 새 항목 → 연결). 진행 중 세션의 도구 목록은 새 세션·`/reload-mcp` 전까지 고정.
- 도구는 `mcp_<서버>_<도구>` 로 등록되고, 도구가 하나라도 등록된 서버는 런타임 툴셋 `mcp-<서버>` 를 만든다 → 설치만 하면 봇이 쓰고, 앱의 실행 단계에 그대로 보인다.
- 프로필별 설정인지는 문서에 명시가 없다(프로필 `.env`·`value_from: profile` 언급으로 추정) — 멀티플렉스 게이트웨이에서의 동작 포함 실기 확인 대상.
- **맥미니 v0.23.1 실측 (2026-11-17, T-210)**
  - ① `hermes mcp --help` 서브커맨드: add, remove(rm), list(ls), test, configure(config), login, reauth, picker, catalog, install. **list·remove 있음.**
  - ② `hermes mcp catalog`: 평문 표 `Name / Status / Description`. Status 는 `available` · `installed (disabled)` 등.
  - ③ `hermes mcp install <identifier>`(카탈로그 이름 또는 `official/<name>`): 쓰기라 실행하지 않음 — 입력 요구 여부 **확인 필요**.
  - ④ `mcp_servers` 블록 실제 키: stdio=`command`/`args`/`enabled`(+`env`, `timeout`), 원격=`url`/`enabled`(+`headers`). default 프로필에만 있고 wiki·grok·opus·gemini·invest·safety `config.yaml` 에는 블록 없음.
  - ⑤ 프로필별: `hermes -p wiki mcp list` → "No MCP servers configured"(default 와 별개). 게이트웨이 `gateway/run.py` `_discover_gateway_mcp_tools` 가 멀티플렉스에서 프로필마다 자기 `config.yaml` 의 `mcp_servers` 로 따로 discovery 한다(#95518). 실제 세션에서 프로필별 도구가 다르게 보이는지는 **확인 필요**.

---

## 1. 전체 아키텍처

```
┌─ iPhone (HermesChat 앱) ─────────────────────────────┐
│  설정: serverHost(스킴+호스트), apiKey, 프로필목록   │
└──────────────┬───────────────────────┬───────────────┘
        Tailscale (100.83.59.60)       │
┌──────────────▼───────────────────────▼───────────────┐
│ 맥미니                                                │
│  :8642  멀티플렉스 게이트웨이 API  ← 세션/채팅/스킬   │
│         default = /…   그 외 = /p/<profile>/… (§0.1a) │
│  :8765  Hermes Bridge (server/hermes_bridge.py)       │
│         ← 프로필목록·재시작·SOUL.md·업로드·칸반       │
│  :8900  Hermes-bot 서버 ← 봇·채널·승인·토론          │
│  :8000  대시보드 (기존, 추후 WKWebView 임베드용)      │
│  ~/.hermes/kanban.db (+boards/<slug>/kanban.db)       │
│         ← 내장 칸반 — 디스패처·대시보드·앱 공유       │
└───────────────────────────────────────────────────────┘
```

원칙:
1. **세션/대화는 항상 프로필별 게이트웨이 API로 직접** (가장 안정적, 공식 API). 멀티플렉스에서 "프로필별"은 포트가 아니라 경로 접두사 `/p/<profile>/` + 그 프로필 키다(§0.1a).
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

> ⚠️ **2026-10-22 이후 무효.** 게이트웨이가 멀티플렉스(§0.1a)로 바뀌어 프로필별 포트가 없다. 이 스크립트는 다시 실행하지 않는다. "프로필 전환 = 다른 포트로 전환"은 이제 "프로필 전환 = 다른 `/p/<profile>/` 접두사 + 그 프로필 키"여야 하며, 앱 쪽 대응은 Phase 30(T-216).

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

### Phase 22 — 채팅 코어 (스트리밍·스트림 회수·중단·빠른 전환, 2026-11-12, TASKS T-149~151)
`SSEParser` 기반 네이티브 SSE 이벤트(`assistant.delta`, `tool.*`, `run.completed`, `done`) + 레거시 OpenAI 청크 병존, 전송 단절 시 저장 응답 자동 회수(`ChatRecoveryTests`/`SSEParserTests`), 응답 중단 버튼(부분 응답 보존), 채팅 툴바 프로필 빠른 전환.

### Phase 23 — Live(핸즈프리 음성) 전체화면 (2026-11-12, TASKS T-152~153)
`LiveVoiceView`(상태 원·실시간/응답 자막·바로 보내기·끼어들기·응답 중단). waveform/Siri 진입 시 cover 자동 표시, 최소화하면 배너로 복귀. cover 표시 중 음성 정리는 `cleanupVoice()` 가드.

### Phase 24-B — Hermes Bot 연동 (2026-11-12, TASKS T-154~157, T-167~168, T-160)
Hermes-bot 서버(:8900)를 백엔드로 하는 봇 탭 신설: `BotClient`(REST + job SSE), 봇/채널 목록, DM·채널 채팅(@멘션 칩), 승인 목록(T-167). 탭 재구성(T-168: 봇/보드/세션/칸반/설정, 대시보드는 설정에서 연다). 봇 서버 미설정 시 첫 탭은 보드. 봇 첨부 업로드·음성(T-160)은 TODO. 봇 탭은 Hermes-bot 경유라 전 프로필에 닿는다(§0.2 멀티플렉스 참조).

### Phase 25 — Android MVP (`android/`, Kotlin + Jetpack Compose, 2026-11-12, TASKS T-161~164)
단일 `:app` 모듈(`com.hermes.chatandroid`). 스캐폴드·데이터/네트워크 계층·JVM 단위 테스트, 온보딩·프로필 보드·세션·설정, 스트리밍 채팅(think 은닉·첨부·중단), 봇 탭. 빌드 절차는 HANDOFF §3 "Android 빌드". NEEDS-BUILD.

### Phase 26 — Android 봇 중심 재구성 (2026-11-13, TASKS T-170~174)
사용자 결정(11-13): 세션보다 봇을 주로 쓰므로 Android 앱을 **봇 중심**으로 재구성한다. Hermes-bot 서버(:8900)가 1차 백엔드, 홈 = 봇 목록(봇 1개 = DM 대화 1개, 최근 활동순) + 채널. 하단 탭은 대화 / 승인 / 더보기. 게이트웨이 직결 세션(프로필 보드·세션 목록)은 홈에서 빼고 **봇 프로필 화면의 "지난 세션"**으로 흡수(봇 = 프로필이므로 그 봇의 게이트웨이로 직결 — 현재는 포트 기준이라 멀티플렉스에서 default 외 불가, T-216). 칸반·크론·대시보드 등은 2차 부가 기능. iOS 는 Android 구조 확정 후 같은 구조로 따라간다. 1차 범위: 봇 홈·DM·채널·승인, 봇 채팅 첨부·실행 단계(steps), 봇 프로필 편집·헬스·발견, 채널 관리, 워크스페이스. 서버 API 는 Hermes-bot `app/main.py`(P31 이후 라우트 포함)를 따른다. 멀티플렉스 게이트웨이(/p/<profile>/) 미대응 유지 → Phase 30 에서 대응.

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

### Phase 27 — Android 글라스 Live (Meta DAT + 핸즈프리, 2026-11-15, TASKS T-187/T-188)
**목표**: 글라스를 쓴 채 폰을 꺼내지 않고 Hermes 봇과 음성으로 대화하고, 지금 보는 것을 사진으로 함께 보낸다.
**결정(사용자 11-15)**: iOS Phase 16 의 갤러리 감시 대신 **Meta Wearables DAT SDK 직접 촬영** 도입(개인 사이드로드 APK 라 개발자 프리뷰 제약 무관).
실시간 영상 이해 모델(Gemini Live 등)은 쓰지 않고 **A안 = 말하는 순간의 시야 사진 + 질문**을 Hermes 봇 채널로 보낸다(사설망 원칙 유지, 기록이 채널에 남음).
iOS 실사용 오류 3건(사진 늦음·묻기 전 사진 설명·글 판독 불량)의 원인: Meta AI 앱 동기화 대기, 무발화 시 기본 프롬프트로 자동 전송(`glassesPhotoPrompt`), 저해상도 원본.
**설계**: `GlassesCamera`(DAT 세션·카메라 스트림은 촬영용·in-stream `capturePhoto`) + `LiveController`(듣기→촬영 병렬→업로드→`POST /api/channels/<id>/messages`→잡 SSE delta 문장 낭독→재청취) +
`LiveAudio`(통화 모드 BT SCO 고정, SpeechRecognizer ko-KR, TTS 큐) + `LiveService`(포그라운드·MediaSession 탭). 사진은 사용자 발화와 함께만 전송.
**제약(DAT 1.0.1)**: 스트림 최대 720×1280, 프레임 YUV 레이아웃 미문서화(그래서 프레임 대신 사진), Inputs·Speech·standalone Photo 는 experimental(Developer Center 승인 필요할 수 있음), 글라스 단일 탭이 스트림을 PAUSED 로 만들 수 있음.
**후속 후보**: in-stream 사진 해상도가 글 판독에 부족하면 standalone `camera.photo`(FULL/HIGH, 스트림 정지 필요), Gemini 실시간 모드(B안), Hermes 텔레그램 동시 게시.
**11-16 개편(TASKS T-191~T-195)**: Live 는 별도 화면이 아니라 **봇 채팅 입력 카드의 Live 버튼 + Live 바**로 동작하고, 글라스 설정은 설정 화면으로 옮겼다.
검증된 사실 — hermes-agent `vision_analyze` 는 긴 변 1568px 또는 base64 256KB(`vision.embed_target_bytes` 기본값)를 넘는 이미지를 **반으로** 줄인다. 그래서 앱이 1568px·190KB 이하로 맞춰 올린다(그 이상 화질은 봇에게 전달되지 않음 — 더 작은 글씨는 가운데 크롭/타일 전송이 후속 후보).
채팅의 첨부 사진은 Bridge `GET /files/raw` 로 받아 표시한다(Hermes-bot 에는 첨부 다운로드 경로 없음).

**11-16 사용량·워크스페이스 탭·git 팝업(TASKS T-197~T-199)**: 검증된 사실 — 맥미니의 `paseo usage --json` 이 에이전트 계정별 한도(Claude·Codex·Grok·Copilot·Cursor)를 준다. Antigravity 는 paseo 프로바이더이지만 사용량 항목이 없다. Bridge 가 이를 `GET /usage` 로 중계한다.
작업 채팅은 새 백엔드가 아니라 **작업 폴더가 지정된 Hermes-bot 채널**이다(워크스페이스 = Hermes-bot 등록 작업 위치) — Paseo 데몬에는 붙지 않는다. 채널은 멘션이 없으면 아무도 답하지 않으므로, 봇이 하나뿐인 채널은 앱이 `@slug` 를 붙인다.
풀·푸시·PR·병합·배포는 Hermes-bot 에 없어 Bridge `/git/*` 가 맡는다(Hermes-bot 의 푸시는 승인 항목을 만드는 별도 경로 — 워크스페이스 상세 시트의 "푸시 요청"은 그대로 그 경로). Bridge 는 앱이 보낸 명령 문자열을 실행하지 않는다: 정해진 git/gh 동작과 리포 안의 `scripts/deploy.sh`·`deploy.sh` 뿐.

### Phase 28 — 채널·워크스페이스 토론 (2026-11-16, TASKS T-200~T-203)
**목표**: 채널이나 작업 채팅에서 주제를 던지면 여러 봇이 의견을 조율해 결론을 낸다. Phase 14 Deep think 의 후속이며, T-186 에서 Android 이식을 접었던 것을 **서버 기능**으로 되살렸다.
**결정(사용자 11-16)**: 발언 한도는 봇마다 3~4회, 토론을 이끄는 봇(진행자)은 그 2배. 오케스트레이션은 앱이 아니라 **Hermes-bot 서버**(P32, `app/discussions.py`)가 한다 — 앱을 꺼도 이어지고 기록이 채널에 남으며 웹·iOS·Android 가 같은 것을 본다.
**흐름**: 참가자 전원이 서로의 답을 보지 않고 동시에 첫 의견 → 진행자가 합의점·쟁점을 정리하며 `@슬러그` 로 지목 → 지목된 봇만 발언 → … → 진행자가 `## 최종 결론`. Deep think 의 고정 라운드와 달리 진행자가 누구에게 무엇을 물을지 정한다. 응답 실패한 참가자는 제외하고 계속, 진행자가 실패하면 중단.
**서버 API**(Hermes-bot `docs/PLAN.md` §5): `POST /api/channels/{id}/discussions`, `GET /api/discussions/{id}`, `POST /api/discussions/{id}/stop`. 메시지 행에 `discussion_id`·`disc_kind`(`topic|statement|moderation|conclusion`). 채널에서 `/토론 <주제>` 로도 열린다(iOS·웹은 당분간 이 방법).
**Android**: 봇이 둘 이상인 채널의 입력 카드에 **토론 버튼** → 시트(주제·진행자·참가자·발언 횟수 2/3/4·도구 허용) → 진행 중에는 입력 카드 위 **토론 줄**(봇별 사용/한도, 중지). 진행자 발언에 "진행", 결론에 "최종 결론" 꼬리표. 발언 추적은 기존 `BotJobFollower`(잡 종료 → 목록 재조회 → 새 잡 구독) 그대로.
**한계**: 말하는 중인 발언은 중지해도 끝까지 기록된다(게이트웨이 스트림 취소 API 없음). 도구 허용 여부는 지시일 뿐 강제가 아니다. 모델이 지목·결론 규약을 지키는지는 실기 확인 대상(Hermes-bot T-326).

### Phase 29 — MCP 서버 관리 (계획, 2026-11-16, TASKS T-210~T-214)
**목표(사용자 11-16)**: 앱에서 프로필별로 **어떤 MCP 가 등록돼 있는지 보고, 필요한 것을 추가하고 불필요한 것을 제거**하는 선택창. 지금은 맥미니 터미널에서만 바꿀 수 있다.
**근거**: §0.4. 게이트웨이가 config.yaml 을 감시하므로 재시작 없이 반영되고, 등록된 서버의 도구는 봇이 그대로 쓴다.
**설계**: 게이트웨이가 못 하는 일이므로 Bridge 가 맡는다(원칙 2). 쓰기는 크론·칸반과 같이 **`hermes mcp` CLI 경유**, CLI 에 없는 동작(목록·제거)만 config.yaml 의 `mcp_servers` 블록을 라인 단위로 읽고 고친다(`.bak` 백업, 비정형이면 400 — T-097 과 같은 방식).
- Bridge: `GET /mcp/catalog` · `GET /profiles/<n>/mcp`(이름·종류·enabled, env/headers 값은 마스킹) · `POST /profiles/<n>/mcp`(카탈로그 이름 또는 URL) · `PATCH /profiles/<n>/mcp/<server>`(enabled) · `DELETE /profiles/<n>/mcp/<server>` · `POST /profiles/<n>/mcp/<server>/test`.
- Android: 더보기 > **MCP 서버** — 프로필 선택, "등록됨"(켜기/끄기·연결 테스트·제거), "추가"(카탈로그 체크 목록 + URL 직접 입력).
- **범위 제한(권장안, 사용자 확정 대기)**: 앱에서는 카탈로그 설치와 URL 형 원격 서버만. 임의 명령(stdio) 서버 추가는 터미널에서만 — Bridge 가 앱이 보낸 명령을 실행하면 맥미니에 원격 코드 실행 통로가 생긴다(Bridge 는 지금도 앱이 보낸 명령 문자열을 실행하지 않는다).
- 후속: API 키 입력(프로필 `.env`), OAuth 기기 코드 로그인 중계, iOS·Hermes-bot 웹.
**선행 조건**: T-210 맥미니 확인. 이 결과 없이 Bridge 를 짜면 §0.4 의 "문서 main 기준" 사실 위에 추측을 쌓게 된다.

### Phase 30 — 게이트웨이 직결 멀티플렉스 대응 (계획, 2026-11-17, TASKS T-216~T-218)
**배경**: 맥미니 게이트웨이가 2026-10-22부터 멀티플렉스(§0.1a)다. 앱의 게이트웨이 직결 코드는 여전히 "프로필 = 포트"라서 default 외 프로필의 세션 목록·채팅·Deep think·봇 프로필 "지난 세션"이 닿지 않는다. 봇 탭·채널·토론은 Hermes-bot 경유라 영향 없음.
**현재 코드 위치(11-17 grep)**: iOS `HermesProfile(name, port)`·`AppSettings.baseURL(for:)`·`discoverProfiles(ports: 8642...8651)`(`Services/AppDefaults.swift`), `CreateProfileView.suggestedPort`. Android `ProfileEntry(name, port)`·`ChatViewModel(port)`·`AppNav` `chat/{sessionId}?port=`·`BotProfileScreen` `bot.gatewayPort`.
**설계(Hermes-bot P28 과 같은 방식)**:
- 프로필 base URL = `host:8642` + (default 가 아니면) `/p/<name>`. 키는 프로필별 키(`profileApiKey.<name>`, 이미 iOS Keychain 에 있음).
- 발견: Bridge `/profiles` 이름 목록 → 각 `/p/<name>/health` 프로브. 응답하면 멀티플렉스로 등록, 안 되면 옛 포트 방식 폴백(구 구성 호환). 포트 스캔은 폴백으로만.
- Bridge `/profiles` 의 `port`·`api_enabled` 는 멀티플렉스에서 쓰지 않는다(§0.1a). 필요하면 Bridge 가 `multiplex: true` 를 함께 돌려준다(T-218, 선택).
- 프로필 생성(T-139)의 "포트 자동 할당·게이트웨이 기동"은 멀티플렉스에서는 "프로필 `.env` 에 `API_SERVER_KEY` 생성"으로 바뀌어야 한다 — 생성 직후 `/p/<name>/health` 확인. **실기 확인 필요.**
**완료 기준**: 앱에서 wiki·grok 프로필 세션 목록이 뜨고 채팅 스트리밍이 된다(실기기), default 동작 회귀 없음, 단위 테스트에 URL 조립(멀티플렉스/옛 포트) 케이스.

## 4. 위험 요소 / 주의
1. **새 Swift 파일은 반드시 `project.pbxproj`에 등록** (objectVersion 77, 명시적 파일 참조 — 자동 동기화 폴더 아님). 절차는 HANDOFF.md §4. *이번 커밋은 기존 파일만 수정해서 pbxproj 변경 없음.*
2. `API_SERVER_HOST`는 Tailscale 사설망 전제(현재 `100.83.59.60` 바인딩). 공유기 포트포워딩으로 공인망 노출 금지.
3. (옛 구성) 프로필 자동 검색은 default 프로필이 `API_SERVER_MODEL_NAME` 미설정이면 이름이 "hermes-agent" 등으로 잡힐 수 있음 → setup 스크립트가 모든 프로필에 MODEL_NAME을 프로필명으로 설정해 해결. 멀티플렉스에서는 `/p/<name>/v1/models` 가 프로필 이름을 준다.
4. 멀티플렉스 전환으로 게이트웨이 상주 프로세스는 1개다(예전: 프로필 수만큼). 대신 이 프로세스가 죽으면 전 프로필이 함께 내려간다.
5. 칸반 쓰기는 반드시 `hermes kanban` CLI(또는 에이전트 kanban_* 도구) 경유 — kanban.db를 SQL로 직접 수정하면 이벤트 기록·의존성 재계산·디스패치 불변식이 깨진다. Bridge도 읽기만 sqlite 직접, 쓰기는 CLI subprocess.
6. 스트리밍 중 마크다운 재파싱(T-090): 토큰마다 메시지 전체를 재파싱한다 — 일반 응답에선 무시
   가능하나 초장문에서 프레임 드랍 가능. 문제가 보이면 "스트리밍 중 평문, 완료 시 마크다운" 폴백을
   후속 태스크로.
