# HermesChat iOS — 전체 개발 계획 (Master Plan)

> 최종 수정: 2026-06-10 (Claude Code)
> 진행 상태는 `docs/TASKS.md`, 에이전트 교대 규칙은 `docs/HANDOFF.md` 참조.
> **어떤 에이전트든 이 3개 문서만 읽으면 즉시 작업을 이어갈 수 있어야 한다.**

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
│  ~/.hermes/kanban/*.json  ← 앱과 Hermes가 공유하는    │
│                              칸반 저장소               │
└───────────────────────────────────────────────────────┘
```

원칙:
1. **세션/대화는 항상 프로필별 게이트웨이 API로 직접** (가장 안정적, 공식 API).
2. 게이트웨이가 못 하는 것만 Bridge로. Bridge는 stdlib 단일 파일이라 유지보수 부담 최소.
3. 칸반은 파일(`~/.hermes/kanban/*.json`)이 단일 진실원본 — Hermes 에이전트는 자기 파일 도구로, 앱은 Bridge로 같은 파일을 읽고 쓴다.

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
- 첫 메시지 후 `PATCH /api/sessions/{id}` 자동 제목 (Phase 2 잔여분).

**맥미니 1회 설정 (필수!)** — 이게 없으면 앱을 아무리 고쳐도 전환 안 됨:
```bash
cd "/Users/macmini/Library/Mobile Documents/com~apple~CloudDocs/Coding/claude/busy-meitner-lhc5os"
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

### Phase 6 — KANBAN 보드
**목표**: Hermes가 하위 에이전트 작업을 분배·관리하는 칸반. Triage→To Do→Ready→In Progress→Blocked→Done 컬럼 좌우 스와이프, 상단에서 기존 보드/새 보드 선택.

**데이터 모델** (보드 = `~/.hermes/kanban/<board>.json`, Bridge가 GET/PUT):
```json
{
  "name": "프로젝트X",
  "updated_at": "2026-06-10T12:00:00Z",
  "tasks": [
    {
      "id": "uuid",
      "title": "작업 제목",
      "detail": "설명",
      "status": "triage|todo|ready|in_progress|blocked|done",
      "assignee": "프로필명 또는 subagent 이름",
      "session_id": "연결된 세션 (선택)",
      "created_at": "...", "updated_at": "..."
    }
  ]
}
```
1. **KanbanModels.swift** (T-050, 신규): `KanbanBoard`, `KanbanTask`, `KanbanStatus` enum(6단계, 색상/표시명).
2. **KanbanView** (T-051, 신규): 상단 보드 Picker(+새 보드), `TabView(.page)` 로 컬럼 좌우 스와이프, 카드에 상태 이동 메뉴(◀▶), 카드 탭→상세(편집/세션 점프). 저장은 보드 전체 PUT(낙관적 업데이트, 30초 주기+pull-to-refresh로 동기화).
3. **Hermes 스킬** (T-052): 맥미니 Hermes에 칸반 스킬 등록 — 에이전트가 작업을 분배/완료할 때 같은 JSON 파일을 갱신하도록 SKILL.md 작성 (내용 초안은 `docs/HANDOFF.md` 부록 B). 이로써 "Hermes가 관리하고 앱은 본다"가 자동으로 성립.

**수용 기준**: 앱에서 태스크 생성→맥미니 JSON 반영. Hermes에게 텔레그램으로 "칸반 X 작업 done 처리해" 지시→앱 새로고침 시 반영.

### Phase 7 — 터미널 / 파일 탐색기
1. **빠른 길** (T-060): 기존 대시보드(`http://100.83.59.60:8000`)를 `WKWebView` 탭으로 임베드(세션 토큰 입력 재활용). 공수 거의 0.
2. **네이티브** (T-061, 후순위): Bridge에 읽기 전용 확장 — `GET /files?path=`(HERMES_HOME 하위로 제한), `GET /profiles/<n>/logs?tail=200`. 임의 명령 실행(exec)은 보안상 넣지 않는다 — 명령 실행이 필요하면 Hermes 채팅으로 시키는 것이 Hermes의 설계 철학과 일치.

### Phase 8 — 마감 품질
- API Key/토큰 Keychain 이전 (현재 UserDefaults) (T-070)
- 스트리밍 개선: 현재 `dataTask` 완료 후 일괄 파싱 → `URLSession.bytes(for:)` 라인 단위 실시간 SSE (T-071, `HermesAPIClient.streamChat` + `ApiClient.swift` 정리/삭제)
- 세션 페이지네이션(`has_more`/`offset`) (T-072), iPad 레이아웃, 다크모드 점검 (T-073)

---

## 4. 위험 요소 / 주의
1. **새 Swift 파일은 반드시 `project.pbxproj`에 등록** (objectVersion 77, 명시적 파일 참조 — 자동 동기화 폴더 아님). 절차는 HANDOFF.md §4. *이번 커밋은 기존 파일만 수정해서 pbxproj 변경 없음.*
2. `API_SERVER_HOST=0.0.0.0`은 Tailscale 사설망 전제. 공유기 포트포워딩으로 공인망 노출 금지.
3. 프로필 자동 검색은 default 프로필이 `API_SERVER_MODEL_NAME` 미설정이면 이름이 "hermes-agent" 등으로 잡힐 수 있음 → setup 스크립트가 모든 프로필에 MODEL_NAME을 프로필명으로 설정해 해결.
4. 여러 게이트웨이 상시 기동 = 프로필 수만큼 상주 프로세스. 맥미니 메모리 확인. `hermes-gateways status`로 관리.
5. 칸반 동시 수정(앱 PUT vs Hermes 파일 수정)은 마지막 쓰기 승리 — 보드 전체 PUT 전에 GET으로 최신본을 받아 병합하는 규칙을 앱에 구현(T-051에 포함).
