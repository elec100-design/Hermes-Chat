# HANDOFF — 에이전트 교대 프로토콜

이 저장소는 **여러 코딩 에이전트가 번갈아 작업**한다:
Claude Code (Xcode 내장/웹) ↔ Hermes+Step-3.7-flash (맥미니 상주) ↔ Grok Build (웹).
사용자 개입 없이 매끄럽게 이어지도록, 모든 에이전트는 아래 규칙을 따른다.

## 0. 에이전트 역할 분담 (중요 — 2026-06-10 개정)

| 에이전트 | 허용 작업 | 금지 작업 |
|---|---|---|
| Claude Code | 코드 작성, pbxproj 수정, 충돌 해소, 문서 | — |
| Grok Build | 코드 작성 (NEEDS-BUILD로 기록) | 충돌 해소 |
| **Hermes(codex)** | **빌드 검증, TASKS.md 상태 갱신, 검증 기록 추가만** | **코드·pbxproj 수정, 충돌 해소, 요청 범위 밖 커밋** |

> 배경: codex(Step-3.7-flash)가 코드 작성 시 허위 보고(빈 구현을 "완료"로 보고),
> pbxproj 파손(다른 에이전트의 등록 삭제), 무단 충돌 해소·커밋을 일으킨 이력이 있다.
> codex에게는 빌드 검증만 맡긴다. 코드 작성 태스크는 Claude Code 또는 Grok Build가 집는다.

---

## 1. 세션 시작 시 (모든 에이전트 공통)

맥미니 저장소 경로 (공백 포함 — **반드시 따옴표로 감쌀 것**):
`REPO="/Users/macmini/Library/Mobile Documents/com~apple~CloudDocs/Coding/claude/busy-meitner-lhc5os"`

1. `cd "$REPO" && git status --porcelain` — **출력이 있으면(더러우면) pull 하지 말 것.**
   Hermes(codex)는 여기서 멈추고 상태를 보고한다. Claude Code만 정리 후 진행한다.
2. `git fetch origin claude/busy-meitner-lhc5os && git checkout claude/busy-meitner-lhc5os && git pull --ff-only`
   — `--ff-only`가 실패하면 머지하지 말고 §7을 따른다.
3. **읽기 순서**: `docs/PLAN.md` → `docs/TASKS.md` → 이 문서.
4. 작업 선택 (§0의 역할 범위 안에서만):
   - `BLOCKED`가 있으면 사유를 읽고 해소 가능하면 우선 처리.
   - `NEEDS-BUILD`가 있고 **자신이 맥에서 빌드 가능하면**(Hermes, Xcode의 Claude) 빌드 검증 최우선 (§3).
   - 아니면 TASKS.md에서 번호가 가장 낮은 `TODO`를 집는다 (의존: 같은 Phase 안에서는 위에서 아래 순서).
5. 집은 작업의 상태를 `DOING(에이전트명, 날짜)`으로 수정 — **코드와 같은 커밋에 포함**.

## 2. 작업 완료 시

1. TASKS.md 상태 갱신: 빌드 검증을 했으면 `DONE`, 못 했으면 `NEEDS-BUILD`.
2. 커밋 메시지 형식: `T-0NN: <한 줄 설명> [DONE|NEEDS-BUILD]`
3. `git push -u origin claude/busy-meitner-lhc5os` (실패 시 2s/4s/8s/16s 백오프 재시도).
4. **작업 도중 중단되더라도** 컴파일 가능한 단위로 자주 커밋·푸시할 것. 중단된 작업은 TASKS.md에 `DOING` + 마지막 상황 한 줄을 남기면 다음 에이전트가 이어받는다.
5. **`server/hermes_bridge.py`를 수정했다면 배포까지 한다** (맥에서 작업하는 에이전트만 가능):
   ```bash
   cp "$REPO/server/hermes_bridge.py" ~/.hermes/bridge/
   launchctl unload ~/Library/LaunchAgents/ai.hermes.bridge.plist
   launchctl load ~/Library/LaunchAgents/ai.hermes.bridge.plist
   curl -s http://127.0.0.1:8765/health   # {"status": "ok"} 확인
   ```
   맥이 아닌 에이전트(Grok 등)가 수정한 경우 TASKS.md에 `NEEDS-BUILD(브리지 재배포 필요)`로 남긴다.

## 3. 빌드 검증 (맥에서만 가능)

```bash
cd "/Users/macmini/Library/Mobile Documents/com~apple~CloudDocs/Coding/claude/busy-meitner-lhc5os"
xcodebuild -project HermesChat.xcodeproj -scheme HermesChat \
  -destination 'generic/platform=iOS Simulator' \
  CODE_SIGNING_ALLOWED=NO build 2>&1 | tail -30
```
- 성공 → 해당 작업들 `NEEDS-BUILD` → `DONE`, TASKS.md "빌드 검증 기록"에 **커밋 해시와 함께** 한 줄 추가, 커밋·푸시.
- 실패 →
  - **Hermes(codex): 코드를 수정하지 않는다.** 에러 전문을 `docs/BUILD_STATUS.md`에 저장하고
    해당 태스크를 `BLOCKED(빌드에러: <요약>)`로 기록 후 커밋·푸시. 수정은 Claude Code 담당.
  - Claude Code/Grok: 직접 수정 시도, 3회 실패 시 위와 동일하게 BLOCKED 처리.

## 4. 새 Swift 파일 추가 절차 (중요!)

`project.pbxproj`는 objectVersion 77, **명시적 파일 참조** 방식이다 (폴더 자동 동기화 아님).
새 `.swift` 파일은 pbxproj에 등록해야 빌드에 포함된다.

- **Xcode에서 작업 중이면**: Xcode로 파일 생성 (자동 등록됨). 끝.
- **CLI/웹 에이전트면** 4곳에 추가 (기존 `SessionModels.swift` 항목을 검색해 패턴 복사):
  1. `PBXBuildFile` 섹션: `<ID1> /* Foo.swift in Sources */ = {isa = PBXBuildFile; fileRef = <ID2> /* Foo.swift */; };`
  2. `PBXFileReference` 섹션: `<ID2> /* Foo.swift */ = {isa = PBXFileReference; lastKnownFileType = sourcecode.swift; path = Foo.swift; sourceTree = "<group>"; };`
  3. 해당 그룹(`Models`/`Views`/`Services` 등)의 `children`에 `<ID2>` 추가
  4. `PBXSourcesBuildPhase`의 `files`에 `<ID1>` 추가
  - ID는 24자리 16진수 아무 값(중복 금지). 예: `AAAA0001AAAA0001AAAA0001`.
- 등록 후 반드시 빌드 검증(§3)으로 확인.

## 5. 에이전트별 시작 프롬프트 (복사해서 사용)

### 5-A. Hermes(Step-3.7-flash, 맥미니)에게 — **빌드 검증 전용** (2026-06-10 개정)
텔레그램/슬랙으로 아래를 보내면 된다:

```
앞으로 "HermesChat 빌드 검증해줘"라고 하면 아래 절차만 정확히 수행해. 코드 수정 금지.
1. cd "/Users/macmini/Library/Mobile Documents/com~apple~CloudDocs/Coding/claude/busy-meitner-lhc5os"
2. git status --porcelain 출력이 비어 있지 않으면 → 아무것도 하지 말고 출력을 그대로 보고 후 종료.
3. git fetch origin && git checkout claude/busy-meitner-lhc5os && git pull --ff-only
   → 실패하면 머지/리베이스 하지 말고 에러를 그대로 보고 후 종료.
4. xcodebuild -project HermesChat.xcodeproj -scheme HermesChat -destination 'generic/platform=iOS Simulator' CODE_SIGNING_ALLOWED=NO build 2>&1 | tail -5
5. 성공: docs/TASKS.md의 NEEDS-BUILD를 DONE으로 바꾸고 빌드 검증 기록에
   "날짜 | 브랜치 @ 커밋해시 | BUILD SUCCEEDED" 한 줄 추가 → 이 두 파일만 커밋·푸시.
6. 실패: 코드를 고치지 말고 에러 전문을 docs/BUILD_STATUS.md에 추가, 해당 태스크를
   BLOCKED(빌드에러)로 바꿔 커밋·푸시.
7. 결과를 한 줄로 보고: 커밋해시, BUILD SUCCEEDED/FAILED, 갱신한 태스크 ID.
절대 금지: Swift/pbxproj 파일 수정, merge 충돌 해소, 시키지 않은 커밋.
지금 한 번 실행해줘.
```

### 5-B. Hermes 자동 빌드 감시 (cron — 사용자 개입 제거의 핵심)
Hermes에게 1회 지시 (기존 cron이 있으면 교체):

```
기존 HermesChat cron 작업을 삭제하고 새로 등록해줘: 매 시간마다
1. cd "/Users/macmini/Library/Mobile Documents/com~apple~CloudDocs/Coding/claude/busy-meitner-lhc5os"
2. git status --porcelain 출력이 비어 있지 않으면 조용히 종료 (절대 커밋/정리하지 말 것).
3. git fetch origin && git pull --ff-only — 실패하면 조용히 종료 (머지 금지).
4. docs/TASKS.md에 NEEDS-BUILD가 있을 때만 HANDOFF.md §3의 xcodebuild 명령으로 빌드.
5. 성공: 해당 태스크를 DONE으로, 빌드 검증 기록에 커밋해시와 함께 한 줄 추가.
   실패: 코드를 수정하지 말고 에러를 docs/BUILD_STATUS.md에 저장, 태스크를 BLOCKED로.
6. docs/ 아래 파일만 커밋("T-0NN: 빌드 검증 [DONE]" 형식)하고 푸시.
7. 그 외에는 아무것도 하지 말고 조용히 종료. Swift/pbxproj 수정 절대 금지.
```

### 5-C. Grok Build에게 (빌드 불가 환경 → 코드 작성 전담)
```
GitHub 저장소 elec100-design/Hermes-Chat 의 브랜치 claude/busy-meitner-lhc5os 에서 작업해.
먼저 docs/PLAN.md, docs/TASKS.md, docs/HANDOFF.md를 읽고, TASKS.md에서 가장 낮은 번호의
TODO 태스크 하나를 골라 HANDOFF.md 프로토콜대로 구현해. 빌드는 못 하니 상태는
NEEDS-BUILD로 기록하고 커밋·푸시해. 새 Swift 파일을 만들면 반드시 HANDOFF.md §4대로
project.pbxproj에 등록해. 완료하면 태스크 ID와 변경 파일을 보고해.
```

### 5-D. Claude Code(Xcode/웹)에게
```
docs/HANDOFF.md 프로토콜대로 HermesChat 작업을 이어서 진행해줘.
```

## 6. 푸시 인증 (헤드리스 에이전트 필수 설정)

Hermes 같은 백그라운드 에이전트는 macOS 키체인/대화형 프롬프트에 접근할 수 없어
HTTPS 푸시가 `could not read Username ... Device not configured` / `-25308`
(errSecInteractionNotAllowed)으로 실패한다. **1회 설정** (사용자가 로컬 터미널에서):

```bash
# 1) GitHub에서 Fine-grained PAT 발급:
#    Settings → Developer settings → Fine-grained tokens →
#    Repository access: elec100-design/Hermes-Chat 만, Permissions: Contents = Read and write
# 2) 토큰을 평문 파일 저장소에 1회 기록 (키체인을 거치지 않아 헤드리스에서도 동작):
cd "/Users/macmini/Library/Mobile Documents/com~apple~CloudDocs/Coding/claude/busy-meitner-lhc5os"
git config credential.helper "store --file ~/.hermes/.git-credentials"
printf 'https://elec100-design:%s@github.com\n' '<발급한 토큰>' > ~/.hermes/.git-credentials
chmod 600 ~/.hermes/.git-credentials
git push -u origin claude/busy-meitner-lhc5os   # 동작 확인
```
토큰은 저장소 밖(~/.hermes/)에 있으므로 커밋될 일이 없다. 토큰을 텔레그램 등
채팅으로 에이전트에게 보내지 말 것 — 위 명령은 사람이 직접 실행한다.

## 7. 막혔을 때 / 충돌 시

- 같은 태스크에 두 에이전트가 붙는 것을 막기 위해 `DOING` 표시가 있으면 그 태스크는 건드리지 않는다 (7일 이상 방치된 `DOING`은 회수 가능).
- push 충돌 시: `git pull --rebase origin claude/busy-meitner-lhc5os` 후 재푸시. TASKS.md 충돌은 양쪽 상태 변경을 모두 보존하는 방향으로 병합.
- **머지/리베이스 충돌이 나면 Hermes(codex)는 해소를 시도하지 않는다**: 즉시
  `git merge --abort`(또는 `git rebase --abort`)로 되돌리고 충돌 파일 목록을 보고 후 종료.
  충돌 해소는 Claude Code만 한다.
- 설계 판단이 필요한 모호함 → 임의로 결정하지 말고 TASKS.md에 `BLOCKED(질문: ...)`로 남긴다. 사용자가 모아서 답한다.

---

## 부록 A. 접속 정보 (코드/문서에 비밀값은 절대 커밋 금지)

| 항목 | 값 |
|---|---|
| 게이트웨이 (default) | `http://100.83.59.60:8642` |
| 프로필 게이트웨이 | `:8643` 부터 (`scripts/setup_profiles_api.sh`가 배정) |
| Hermes Bridge | `http://100.83.59.60:8765` (T-010에서 배포) |
| 대시보드 | `http://100.83.59.60:8000` |
| API Key / 토큰 | 앱 설정 화면에서 입력 (저장소에 쓰지 않는다) |

## 부록 B. Hermes 칸반 스킬 (T-052에서 맥미니에 등록할 SKILL.md 초안)

```markdown
# kanban
복잡한 작업을 하위 작업으로 분배·추적할 때 사용한다.
보드 파일: ~/.hermes/kanban/<보드이름>.json
스키마: {"name", "updated_at", "tasks":[{"id","title","detail","status",
"assignee","session_id","created_at","updated_at"}]}
status는 triage|todo|ready|in_progress|blocked|done 만 허용.

규칙:
- 작업을 새로 분배하면 tasks에 추가(status=todo 또는 ready).
- 하위 에이전트 작업 시작 시 in_progress, 막히면 blocked(detail에 사유), 끝나면 done.
- 파일 수정 시 updated_at을 현재 UTC로 갱신. JSON 유효성을 깨뜨리지 말 것.
- 사용자가 "칸반 보여줘/정리해줘"라고 하면 이 파일을 읽고 요약한다.
```
