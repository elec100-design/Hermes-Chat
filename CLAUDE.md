# CLAUDE.md — HermesChat (iOS) 프로젝트 지침서

맥미니의 hermes-agent를 아이폰에서 쓰는 SwiftUI 앱.

> **작업 시작 전 반드시 읽기 (순서대로):**
> 1. `docs/PLAN.md` — 전체 설계와 Phase 계획 (hermes-agent 구조의 **검증된 사실** 포함)
> 2. `docs/TASKS.md` — 작업 상태 보드 (여기서 태스크를 골라 상태를 갱신하며 작업)
> 3. `docs/HANDOFF.md` — 에이전트 교대 프로토콜, 빌드 명령, pbxproj 파일 추가 절차
>
> 이 3개 문서가 단일 진실원본이다. CLAUDE.md와 충돌하면 위 문서를 따르고 CLAUDE.md를 고친다.

---

## 프로젝트 개요
- 프로젝트 이름: **HermesChat** (GitHub: `elec100-design/Hermes-Chat`)
- 주요 목표: 맥미니(M4)에 상주하는 hermes-agent를 아이폰에서 채팅·음성·칸반·프로필 관리로 쓰는 클라이언트
- 기술 스택: **SwiftUI** (iOS 앱) + **Python stdlib 단일 파일 Bridge**(`server/hermes_bridge.py`) + hermes-agent 게이트웨이 API
- 현재 상태: **개발 중** — Phase 14(Deep think)까지 main 병합·실기기 검증 완료. Phase 15~20은 NEEDS-BUILD(맥 빌드/실기기 검증 대기)
- 접속: Tailscale 사설망(`100.83.59.60`) — 게이트웨이 `:8642+`, Bridge `:8765`, 대시보드 `:8000`

## 코딩 스타일 및 규칙
- 언어: Swift 6.0+ / SwiftUI + async/await 우선
- 네이밍: Swift 표준 camelCase (타입은 PascalCase)
- 에러 처리: `throws`/`Result`, 네트워크 실패는 사용자에게 우아하게 강등(예: Bridge 404 → 읽기전용/placeholder, 알럿 남발 금지)
- **커밋 메시지: `T-0NN: <한 줄 설명> [DONE|NEEDS-BUILD|BLOCKED]`** (HANDOFF §2)
  - `DONE` = 맥 빌드+실기기 검증됨 / `NEEDS-BUILD` = 코드 작성됐으나 맥 빌드 미검증
- **비밀값(API Key, 토큰, PAT)은 저장소에 절대 커밋 금지.** 앱 설정 화면/Keychain에만 보관

## 아키텍처 및 디자인 패턴
- 패턴: **MVVM** (`Views/` + `ViewModels/` + `Services/` + `Models/`)
- 폴더 구조 요약 (`HermesChat/` 하위):
  - `Models/` — 데이터 모델 (`ProfileModels`, `KanbanModels`, `DiscussionModels`, `CronModels` 등)
  - `Views/` — 화면 + `Views/Components/` 재사용 뷰 (`MarkdownText`, `MessageView`, `ChatImageView` 등)
  - `ViewModels/` — `ChatViewModel`, `DiscussionViewModel`
  - `Services/` — `HermesAPIClient`(게이트웨이), `BridgeClient`(Bridge), `AppDefaults`(설정/영속화), `SpeechService`, `VoiceConversationController` 등
  - `Resources/` — `Info.plist`, `Assets.xcassets`
  - `server/hermes_bridge.py` — 게이트웨이가 못 하는 것(프로필 목록/생성/삭제·재시작·SOUL·업로드·칸반·크론·모델 카탈로그)을 담당하는 stdlib 단일 파일
- 핵심 원칙(PLAN §1): **세션/대화는 항상 프로필별 게이트웨이 API로 직접**, 게이트웨이가 못 하는 것만 Bridge로, 칸반은 hermes-agent **내장 칸반(kanban.db)이 단일 진실원본**

## 워크플로우 (Explore → Plan → Code → Review → Commit)
1. **Explore**: `docs/PLAN.md`·`TASKS.md`·`HANDOFF.md`를 먼저 읽고 관련 파일 파악
2. **Plan**: TASKS.md에서 번호가 가장 낮은 `TODO`/`NEEDS-BUILD`/`BLOCKED`를 집고 상태를 `DOING(에이전트명, 날짜)`으로 — **코드와 같은 커밋에 포함**
3. **Code**: 작은 단위로 변경. 새 Swift 파일은 **`project.pbxproj` 등록 필수**(HANDOFF §4)
4. **Review**: 보안·다크모드·접근성·스트리밍 깨짐 점검
5. **Commit**: `T-0NN: …` 형식으로 커밋 후 피처 브랜치로 푸시

## 절대 하지 말 것 (Forbidden)
- **Hallucination 금지**: 모르는 건 "모릅니다"로 명확히. hermes-agent 구조는 PLAN §0 "검증된 사실"만 신뢰
- 요청 범위를 넘는 대규모 리팩토링 금지 — 작은 변경부터
- 비밀값(API Key, Private Key, 토큰) 생성/노출/커밋 금지
- 불필요한 의존성 추가 금지 — **SPM 패키지 참조 추가 금지**(멀티 에이전트가 pbxproj를 수동 편집하는 구조라 파손 위험), CocoaPods 금지
- kanban.db를 SQL로 직접 수정 금지 — 쓰기는 반드시 `hermes kanban` CLI 경유
- `server/hermes_bridge.py` 수정 후 맥미니 재배포 누락 금지 — 안 하면 앱이 "브리지 HTTP 404"

## Git / 브랜치 전략 (2026-06-12 확정)
- **`main`이 기준선.** 각 세션은 main에서 새 피처 브랜치 `claude/<topic>`를 따서 개발·커밋·푸시하고, 완료되면 PR로 main에 병합
- 푸시: `git push -u origin <branch>` (네트워크 실패 시 2s/4s/8s/16s 백오프 재시도)
- **PR은 사용자가 명시적으로 요청할 때만 생성**
- 구 `claude/busy-meitner-lhc5os`는 폐기됨 — 사용 금지

## 빌드 / 검증
- 빌드 명령 (맥에서만 가능):
  ```bash
  xcodebuild -project HermesChat.xcodeproj -scheme HermesChat \
    -destination 'generic/platform=iOS Simulator' CODE_SIGNING_ALLOWED=NO build
  ```
- 빌드 성공 → TASKS.md의 `NEEDS-BUILD`를 `DONE`으로, "빌드 검증 기록"에 커밋 해시와 함께 한 줄 추가
- **새 Swift 파일은 `project.pbxproj`(objectVersion 77, 명시적 파일 참조) 4곳에 수기 등록** — 절차는 HANDOFF §4

## iOS / SwiftUI 특화 지침
- Swift 6.0+ / SwiftUI + Combine + async/await
- 의존성: **Swift Package Manager 추가 참조 금지**(위 Forbidden 참조). 내장 프레임워크만 사용
- 마크다운: SPM 없이 자체 파서(`MarkdownText`/`MarkdownLite`) — `AttributedString(markdown:, .inlineOnlyPreservingWhitespace)`
- 음성: `SFSpeechRecognizer`+`AVAudioEngine`(ko-KR), `AVSpeechSynthesizer`, `AVAudioSession`은 `SpeechService` 단일 소유
- 접근성·다크 모드·로컬라이제이션 고려. Apple HIG 준수
- 권한 키(`Info.plist`): 마이크/음성인식/사진 라이브러리 사용 설명 필수

## Ray-Ban Meta 글라스 연동 특화 (Phase 15~17)
- 핸즈프리 연속 대화(waveform 루프): 침묵 자동 전송 → 문장 단위 TTS → 자동 재청취
- 오디오 라우팅: 받아쓰기는 HFP(`.allowBluetooth`), TTS는 A2DP 고음질. "Hey Meta"와 비간섭
- 글라스 탭은 표준 BT AVRCP 미디어 커맨드(`MPRemoteCommandCenter`)로만 수신 — Meta SDK는 더블탭/카메라 제스처를 3P 앱에 안 줌(미도입 결정)
- 사진 자동 전송은 `PHPhotoLibrary` 변화 감지(전체 접근) 방식 — Meta DAT 카메라 스트림 미사용
- Privacy: 사용자 데이터는 Tailscale 사설망 안에서만. 공인망 노출/클라우드 저장 금지

## 자주 쓰는 Skills / MCP
- `/code-review`, `/security-review`, `/verify`, `/simplify`
- MCP: GitHub (PR/이슈/CI — `mcp__github__*`, 스코프 `elec100-design/Hermes-Chat`)

## Auto Memory 업데이트 정책
- 새 빌드 명령·디버깅 인사이트·선호 설정은 PLAN/TASKS/HANDOFF에 기록(이 3개가 단일 진실원본)
- 규칙이 바뀌면 이 CLAUDE.md도 함께 갱신

---

**마지막 업데이트**: 2026-06-17
**Claude 버전 선호**: 최신 Claude (Opus / Sonnet)
