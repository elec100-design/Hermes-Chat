# HermesChat 프로젝트 총정리 (2026-07-03 스냅샷)

> **이 문서는 스냅샷 요약본입니다.** 진실원본은 여전히 `docs/PLAN.md`(아키텍처·검증된 사실·Phase 계획), `docs/TASKS.md`(실시간 태스크 상태보드), `docs/HANDOFF.md`(에이전트 운영 프로토콜)입니다. 최신 작업 상태는 TASKS.md를, 세부 아키텍처는 PLAN.md를 확인하세요. 이 문서는 6개 문서(PLAN/TASKS/HANDOFF/COMMERCIALIZATION/PRODUCT_REVIEW/BUILD_STATUS)에 흩어진 내용을 한눈에 보기 위해 만든 종합본입니다.

---

## 1. 개요

**HermesChat**은 맥미니(M4)에 상주하는 `hermes-agent`를 아이폰에서 채팅·음성·칸반·크론·프로필 관리로 쓰는 SwiftUI 네이티브 클라이언트다. GitHub: `elec100-design/Hermes-Chat`.

- **기술 스택**: SwiftUI(Swift 6.0+, async/await) iOS 앱 + Python stdlib 단일 파일 Bridge(`server/hermes_bridge.py`) + hermes-agent 게이트웨이 API. SPM 패키지/CocoaPods 의존성 없음(멀티 에이전트가 `project.pbxproj`를 수동 편집하는 구조라 파손 위험 때문에 금지).
- **아키텍처 패턴**: MVVM (`Views/` + `ViewModels/` + `Services/` + `Models/`).
- **접속**: Tailscale 사설망(`100.83.59.60`) — 게이트웨이 `:8642+`, Bridge `:8765`, 대시보드 `:8000`.
- **현재 전체 상태**: Phase 2~14(핵심 채팅/프로필/칸반/음성기본/Deep think)는 main 병합 + 실기기 검증 완료(`DONE`). Phase 15~23(핸즈프리 음성, 글라스 연동, Siri/위젯, 크론 통합관리, Live Activity, Gemini Live 탭)과 Phase A~C(App Store 컴플라이언스, Cloud SaaS, 앱 SaaS 전환)는 코드 작성 완료·`NEEDS-BUILD`(맥 빌드/실기기 검증 대기) 상태. 원래는 개인용 Tailscale 클라이언트였으나, 현재 App Store 출시 + SaaS 상업화로 방향을 전환한 상태.

---

## 2. 전체 아키텍처

### 2.1 시스템 구성도

```
iPhone (HermesChat 앱) — 설정: serverHost, apiKey, 프로필 목록
    ↓ Tailscale (100.83.59.60)
Mac mini:
  :8642  기본 프로필 게이트웨이 API
  :8643  프로필 A 게이트웨이 API   ← 세션/채팅/스킬
  :8644  프로필 B 게이트웨이 API
  :8765  Hermes Bridge (server/hermes_bridge.py) ← 프로필 목록/재시작/SOUL.md/업로드/칸반
  :8000  대시보드 (기존, WKWebView로 임베드)
  ~/.hermes/kanban.db (+ boards/<slug>/kanban.db) ← 내장 칸반, 디스패처/대시보드/앱 공유
```

상업화(Phase B) 이후에는 iPhone → HTTPS(Supabase JWT) → `server/cloud_gateway.py`(:8080, JWT 검증 + 플랜별 제한) → 사용자별 Docker 컨테이너(`hermes-user-{uid}`, 내부 8642/8765 포트) 라우팅이라는 레이어가 self-hosted 아키텍처 위에 추가되었다.

### 2.2 핵심 설계 원칙 (검증된 사실, PLAN.md §0)

1. **프로필 = 독립 게이트웨이 프로세스**: 각 프로필은 `~/.hermes/profiles/<name>/`에 자신만의 `config.yaml`, `.env`, `SOUL.md`, 세션 DB를 가진 완전히 독립적인 게이트웨이 프로세스다(기본 프로필 = `~/.hermes` 자체). 각 프로필의 API 서버는 `.env`에서만 설정 가능: `API_SERVER_ENABLED`, `API_SERVER_PORT`, `API_SERVER_HOST=0.0.0.0`(Tailscale 접속용, 기본값 127.0.0.1에서 변경 필요), `API_SERVER_KEY`(Bearer 인증), `API_SERVER_MODEL_NAME`(앱의 자동탐색이 사용).
2. **세션/대화는 항상 프로필별 게이트웨이 API로 직접** — 가장 안정적인 공식 API. 게이트웨이 엔드포인트: `GET /health`, `GET /v1/models`, `GET /v1/skills`, `GET /v1/toolsets`, `GET/POST /api/sessions`, `GET/PATCH/DELETE /api/sessions/{id}`, `GET /api/sessions/{id}/messages`, `POST /api/sessions/{id}/chat/stream`(SSE), `POST /api/sessions/{id}/fork`, `POST /v1/runs` + `GET /v1/runs/{id}/events`(비동기 실행).
3. **게이트웨이가 못 하는 것만 Bridge로**: 프로필 목록/생성/삭제, 게이트웨이 재시작, SOUL.md 읽기/쓰기, 파일 업로드/첨부, 칸반 보드, 크론 조회/편집, 모델 카탈로그 — 전부 Bridge(`hermes_bridge.py`, 단일 stdlib 파일이라 유지보수 부담 최소)가 담당.
4. **칸반의 단일 진실원본은 hermes-agent 내장 칸반**(`kanban.db`, 2026-06-11부터). 디스패처/대시보드/앱/CLI가 모두 같은 DB를 본다. Bridge는 직접 sqlite로 읽고, 쓰기는 반드시 `hermes kanban` CLI 경유(직접 SQL 수정 금지 — 이벤트 로깅/의존성 재계산/디스패치 불변식이 깨짐).

---

## 3. 핵심 기능 인벤토리

### 3.1 채팅 / 세션
- `ChatView` + `ChatViewModel` — 입력창, 스트리밍 버블, 첨부파일. `HermesAPIClient`가 SSE로 실시간 스트리밍(`URLSession.bytes(for:)`).
- `MarkdownText`/`MarkdownLite` — SPM 없이 자체 파서(코드블록 분리 + `AttributedString` 인라인 마크다운).
- `MessageView`(`MessageBubble`, `ThinkingIndicator`), `ToolResultView`(`ToolCallsChip` — 도구 호출 접기/펼치기).
- `ChatImageView` — 맥 로컬 이미지 경로를 Bridge `/files/raw`로 해석해 렌더링.
- `SessionListView` — 세션 핀/이름변경(트레일링 스와이프), 포크(리딩 스와이프), 전체스와이프 삭제 비활성화, 소스/프로필 필터.

### 3.2 프로필 관리
- `ProfileBoardView` — 홈 화면, 프로필 카드 2열 그리드(온라인 상태 `/health`, 세션 수 표시).
- `ProfileDetailView` — 모델 피커(`/v1/models`), SOUL.md(성격) 편집기, 게이트웨이 재시작.
- 프로필 생성(`CreateProfileView`, `hermes profile create <n> --clone-from default` 경유), 삭제, 모델 카탈로그 선택.

### 3.3 칸반 / 크론
- `KanbanView` — 내장 칸반 보드(kanban.db 기반), 상태: `triage|todo|scheduled|ready|running|blocked|done`(+숨김 `archived`). 디스패처가 `ready` 태스크를 60초 내 자동 실행.
- `CronJobsView`/`CronManagerView`, `CronJobEditView` — 프로필 간 크론 통합 관리(필터, 일시정지/재개, 즉시실행, 편집, 삭제).

### 3.4 음성
- `SpeechService` — 받아쓰기(`SFSpeechRecognizer`+`AVAudioEngine`, ko-KR), 읽어주기(`AVSpeechSynthesizer`). `AVAudioSession` 단독 소유.
- `VoiceConversationController` — 받아쓰기 응답 자동 읽어주기 + 핸즈프리 듣기→응답→재청취 루프(1.8초 무음 자동전송). 오디오 라우팅: 받아쓰기는 HFP(`.allowBluetooth`), TTS는 A2DP 고음질.
- `GeminiLiveService` — Gemini `BidiGenerateContent` 양방향 WebSocket 실시간 음성대화(마이크→16kHz PCM16→base64 송신, 24kHz PCM 수신 재생). `LiveView`/`LiveViewModel`이 Live 탭 UI를 구성하고 대화 기록은 `LiveSessionStore`로 기기 로컬 저장(게이트웨이/클라우드로 전송 안 함).

### 3.5 Deep think 멀티에이전트 토론
- `DiscussionView`/`DiscussionViewModel` — 여러 프로필이 주제를 놓고 여러 라운드에 걸쳐 토론하고 모더레이터가 결론을 작성. 클라이언트 사이드 오케스트레이션(참여 프로필마다 전용 세션 생성, `withTaskGroup`으로 라운드 동시 실행), 실기기에서 발견된 SSE 미전송 버그에 대한 폴링 폴백 포함.

### 3.6 Ray-Ban Meta 글라스 연동
- `PhotoImportWatcher` — `PHPhotoLibrary` 변화 감지로 Meta AI 앱을 통해 동기화된 사진을 자동 첨부/전송 + 음성 안내.
- `VoiceEntryCoordinator` — Siri App Intent/URL 스킴(`hermes://`)/위젯을 통한 음성입력 진입점, AirPods/글라스 탭은 표준 BT AVRCP 미디어 커맨드(`MPRemoteCommandCenter`)로만 수신.
- **명시적 미채택 결정**: Meta Wearables DAT의 물리 캡처 버튼/템플 더블탭은 3rd-party 앱이 가로챌 수 없고(표준 BT 미디어 키만 가능), DAT 카메라 스트림은 개발자 프리뷰 전용이라 의도적으로 도입하지 않음.

### 3.7 대시보드 / 파일 / 알림
- `DashboardWebView` — 맥미니 웹 대시보드(:8000)를 WKWebView로 임베드, 세션 토큰 쿠키 로그인 유지, 핀치줌+데스크톱모드 토글(Phase 21).
- `FileBrowserView` — Bridge `/files`를 통한 `~/.hermes` 읽기전용 파일 브라우저(`.env` 등 숨김파일 차단). **의도적으로 임의 명령 실행 미지원**("명령이 필요하면 채팅으로 Hermes에게 요청" 설계 철학).
- `NotificationService` — `/kanban` 폴링 + 상태 스냅샷 비교로 완료/차단 로컬 알림. `LiveActivityManager` — 채팅 응답 Live Activity. `PushService` — APNs 등록/토큰 업로드.

### 3.8 설정 / 보안 / 접근성
- `KeychainHelper` — API 키/Bridge 토큰 Keychain 저장.
- `AppDefaults` — 연결 모드(self-hosted/cloud) 등 앱 전역 설정.
- 다크모드/iPad 레이아웃 대응, 다국어(한국어/영어/중국어 간체) 로컬라이제이션, Sign in with Apple(`AuthView`) + Supabase Auth, `SubscriptionService`(StoreKit 2).

### 3.9 Bridge (`server/hermes_bridge.py`, 포트 8765, Bearer 토큰 인증)

| 메서드 | 경로 | 역할 |
|---|---|---|
| GET | `/health` | 헬스체크(무인증) |
| GET | `/files`, `/files/content`, `/files/raw` | `~/.hermes` 파일 목록/텍스트/바이너리 다운로드 |
| GET | `/profiles`, `/profiles/<n>/soul`, `/profiles/<n>/cron`, `/profiles/<n>/model`, `/profiles/<n>/logs` | 프로필 목록/SOUL.md/크론/모델/로그 조회 |
| GET | `/kanban`, `/kanban/<board>` | 보드 목록/태스크 조회 |
| POST | `/profiles`, `/profiles/<n>/restart`, `/profiles/<n>/cron` | 프로필 생성(디렉토리+.env+SOUL.md+게이트웨이 설치/재시작)/재시작/크론 생성 |
| POST | `/upload/<profile>`, `/push/register` | 파일 업로드, APNs 토큰 등록 |
| POST | `/kanban/boards`, `/kanban/<board>/tasks`, `/kanban/<board>/tasks/<id>/action` | 보드/태스크 생성, 액션(`hermes kanban` CLI 경유) |
| PUT | `/profiles/<n>/soul`, `/profiles/<n>/cron/<id>`, `/profiles/<n>/model` | SOUL.md 쓰기, 크론 편집, 모델 변경 |
| DELETE | `/profiles/<n>`, `/profiles/<n>/cron/<id>` | 프로필/크론 삭제 |

APNs 발송(`send_apns`, JWT 프로바이더 인증)과 `kanban_push_watcher` 백그라운드 루프도 포함.

### 3.10 Cloud Gateway (`server/cloud_gateway.py`, SaaS 라우터)

| 메서드 | 경로 | 역할 |
|---|---|---|
| GET | `/health` | 헬스체크(무인증) |
| POST | `/auth/login` | Supabase JWT 검증 + 컨테이너 프로비저닝(≤90초, 블로킹) |
| GET | `/status`, `/usage` | 컨테이너 상태, 월별 사용량+플랜 정보 |
| DELETE | `/account` | 컨테이너+볼륨 영구 삭제 |
| ANY | `/bridge/*` | 사용자 컨테이너 Bridge(8765) 프록시, 플랜별 프로필 수 제한 |
| ANY | 그 외 전체 | 사용자 컨테이너 게이트웨이(8642) 프록시, `chat/stream` POST에 플랜별 메시지 제한 적용 |

---

## 4. 전체 개발 계획 (Phase 히스토리)

| Phase | 핵심 내용 | 대표 Task ID | 상태 |
|---|---|---|---|
| 2-2/2-3 | 프로필 전환 버그 근본원인 수정, 세션 검색/자동제목 | T-002~006 | ✅ DONE |
| 3 | Bridge 배포 + BridgeClient + 프로필 상세화면 + Skills 뷰 | T-010~014 | ✅ DONE |
| 4 | 채팅 첨부파일(사진/파일) | T-020~022 | ✅ DONE |
| 5 | 프로필 보드(2×3 홈 그리드) | T-040~041 | ✅ DONE |
| 6/9 | 칸반 보드 → 내장 칸반으로 전면 전환(2026-06-11) | T-050~052, T-080~082 | ✅ DONE |
| 7 | 대시보드 WKWebView 임베드 + 파일 브라우저 | T-060~061 | ✅ DONE |
| 8 | Keychain, SSE 실시간 스트리밍, 페이지네이션, iPad/다크모드 | T-070~075 | ✅ DONE |
| 10 | 마크다운/코드블록, 컨텍스트메뉴, 포크, 씽킹블록 숨김, 도구 칩, 이미지 썸네일 | T-090~092, T-103~108 | ✅ DONE (main 병합) |
| 11 | 알림(로컬+폴링): 칸반 완료/차단 알림, 응답완료 알림, BGAppRefresh | T-093~095 | ✅ DONE (T-096 Bridge 이벤트 API는 TODO) |
| 12 | 설정 심화: 프로필별 apiKey Keychain 이관 | T-099 | ✅ DONE (T-097/098 Bridge config·툴셋 토글은 TODO) |
| 13 | 음성 입출력(받아쓰기/읽어주기) | T-100~102 | ✅ DONE |
| 14 | **Deep think 멀티에이전트 토론방** | T-110~115 | ✅ DONE (실기기 검증, main 병합) |
| 15 | 핸즈프리 음성대화(오디오세션 통합, AirPods/글라스 탭, 백그라운드 오디오) | T-117~124 | 🔶 NEEDS-BUILD |
| 16 | Meta 글라스 사진 자동전송 + 음성 후속응대 | T-125~130 | 🔶 NEEDS-BUILD |
| 17 | Siri/URL스킴/위젯 음성입력 진입점, 글라스 더블탭 매핑 | T-131~137 | 🔶 NEEDS-BUILD |
| 18 | 크론 조회/편집 → 통합 관리 화면(CronManagerView) | T-138, T-146 | 🔶 NEEDS-BUILD |
| 19 | 프로필 생성('+' 카드)/모델 카탈로그/삭제 | T-139~145 | 🔶 NEEDS-BUILD |
| 20 | 세션 핀/이름변경 | T-147 | 🔶 NEEDS-BUILD |
| 21 | 대시보드 핀치줌 + 데스크톱모드 토글 | T-148 | 🔶 NEEDS-BUILD |
| 22 | 실시간 채팅 재조정, Live Activity, APNs 푸시 인프라 | T-149~152 | 🔶 NEEDS-BUILD (일부 빌드 성공, 실기기/위젯타겟/시크릿 대기) |
| 23 | 세션 TTS 로컬 최적화 + Gemini Live 탭(음성-음성 실시간대화) | T-153~157 | 🔶 NEEDS-BUILD |
| A | App Store 컴플라이언스(PrivacyInfo, arm64, 온보딩, 다국어, Sign in with Apple) | T-A01~A08 | 🔶 T-A01~A06 DONE, T-A07/08 NEEDS-BUILD |
| B | Cloud SaaS 인프라(Docker per-user 컨테이너, cloud_gateway.py) | T-B01~B05 | 🔶 T-B01/03/05 DONE, T-B02 DOING(Apple OAuth 심사대기), T-B04 TODO |
| C | iOS SaaS 전환(Sign in with Apple, StoreKit 구독, 클라우드 온보딩) | T-C01~C05 | 🔶 NEEDS-BUILD (맥 빌드+실기기 설치 성공, 런타임 검증 대기) |
| D | App Store Connect 메타데이터, TestFlight, 심사제출, v1.1 계획 | T-D01~D04 | ⬜ TODO |

---

## 5. 상업화 계획

원래 개인용 Tailscale 클라이언트였던 HermesChat을 **App Store에 출시 가능한 범용 SaaS 제품**으로 전환하는 계획(`docs/COMMERCIALIZATION.md`, 2026-06-20 작성).

### 5.1 로드맵
`Phase 0`(App Store 컴플라이언스, 코드만) → `Phase 0-L`(다국어: ko/en/zh-Hans, zh-Hant는 추후) → `Phase 1`(백엔드: 사용자별 Docker 컨테이너 SaaS 인프라) → `Phase 2`(앱 SaaS 전환: 로그인/구독/클라우드) → `Phase 3`(출시/운영).

### 5.2 요금제

| 플랜 | 가격 | 제한 |
|---|---|---|
| Free | ₩0 | 프로필 1개, 월 200메시지 |
| Basic | ₩9,900/월 | 프로필 3개 |
| Pro | ₩29,900/월 | 프로필 10개 |
| 자가호스팅(Self-hosted) | 무료 | 맥미니 직접 운영, 제한 없음 |

### 5.3 아키텍처
Load Balancer/nginx → Supabase Auth → Docker Compose/Fly.io 오케스트레이터 → Bridge API Gateway. 사용자별 컨테이너(`hermes-user-{uid}`)가 게이트웨이(8642)+Bridge(8765)를 내부 포트로 노출하고, `cloud_gateway.py`가 JWT 검증 + 플랜별 제한 + 프록시를 담당. 결제는 StoreKit 2.

### 5.4 핵심 결정사항
- 인증: Supabase (Apple OAuth 연동)
- 초기 오케스트레이션: Fly.io
- 결제: StoreKit 2
- **자가호스팅 모드는 계속 유지** — 상업화가 기존 개인용 사용성을 대체하지 않음
- v1.0 로컬라이제이션: 한국어+영어+중국어 간체 (1차 번역은 Claude API 활용)
- ATS `NSAllowsArbitraryLoads` 유지 — 리뷰 사유서로 대응(Tailscale 사설망 HTTP 접속 설명)

### 5.5 App Store 심사 대응
리뷰어가 사설 Tailscale 백엔드에 접근할 수 없다는 점을 설명하는 심사 노트 템플릿과, 리뷰용 테스트 계정 placeholder를 준비함.

---

## 6. 현재까지 진행 상황 총정리 (2026-07-03 기준)

### 6.1 완료 (DONE, 실기기 검증됨)
Phase 2-2 ~ 14 전체 (T-000~T-115, T-099) — 핵심 채팅/프로필/칸반/음성기본/Deep think 기능 세트. Phase A는 T-A01~A06까지 DONE.

### 6.2 NEEDS-BUILD (코드 작성 완료, 맥 빌드/실기기 검증 대기 — "미검증 부채")
- Phase 15(핸즈프리 음성), 16(글라스 사진연동), 17(Siri/위젯/글라스탭), 18(크론 통합관리), 19(프로필 생성/삭제/모델카탈로그), 20(세션 핀), 21(대시보드 줌), 22(Live Activity/APNs — 일부 빌드 성공), 23(Gemini Live 탭)
- Phase A: T-A07/A08
- Phase B: T-B01/B03/B05는 DONE(Docker 빌드 검증됨)
- Phase C: T-C01~C05 전체 — 맥 빌드 성공 + 실기기(iPhone17,4/iOS 26.5) 설치 확인됨. Sign in with Apple 실제 플로우(T-C01), StoreKit 샌드박스 테스트(T-C03)는 런타임 검증 대기.

### 6.3 TODO (미착수)
T-096(Bridge 칸반 이벤트 API), T-097/T-098(Bridge config + 툴셋 토글 쓰기), T-152(토론 Live Activity, T-150/151 검증에 종속), T-B04(클라우드 배포), Phase D 전체(App Store Connect 메타데이터/TestFlight/제출/v1.1 계획).

### 6.4 DOING
T-B02 — Supabase 프로젝트+테이블은 완료, Apple OAuth는 애플 심사 대기 중.

### 6.5 최근 빌드 검증 로그 하이라이트

| 날짜 | 브랜치@커밋 | 결과 |
|---|---|---|
| 06-11 | `main`@879b47e (PR #1 병합) | BUILD SUCCEEDED, 실기기 Deep think 확인, T-090~115 → DONE |
| 06-20 | `claude/cool-maxwell-jbo5w1`@30328cb | BUILD SUCCEEDED, T-A01~A05 DONE |
| 06-20 | `claude/hopeful-edison-1p5q91`@8ec3575 등 | DOCKER BUILD SUCCEEDED, T-B01/B03/B05 DONE |
| 06-21 | `claude/sleepy-bardeen-x86kpk`@98ad910 | BUILD SUCCEEDED + 실기기 설치(iPhone17,4/iOS 26.5), Phase C 코드 완료, 빌드에러 5건 수정 |
| 06-24 | `claude/realtime-chat-liveactivity-apns`@72e23fb | BUILD SUCCEEDED, Phase 22 T-149/150/151 앱단 컴파일 성공, 실기기 체감/위젯타겟/.p8+Bridge재배포는 대기 |

### 6.6 다음 세션 계획 (PLAN.md 헤더 기준)
1. PR #14를 main에 병합
2. T-C01 Sign in with Apple 실기기 런타임 검증
3. App Store Connect 상품 등록 후 T-C03 StoreKit 샌드박스 테스트
4. T-B02 Supabase Apple OAuth 심사 통과 확인

---

## 7. 알려진 리스크 / 남은 이슈 (PRODUCT_REVIEW.md 기반, 2026-06-24 작성)

- **App Store 심사 리스크 1순위**: 리뷰어가 사설 Tailscale 백엔드에 접근할 수 없음 — 리뷰 거절 가능성 가장 높은 항목. 그 외 `UIBackgroundModes: audio`, 전체 사진라이브러리 접근 사유, 상시 마이크 인디케이터, NEEDS-BUILD 기능이 리뷰 빌드에서 "보이지만 작동 안 함"으로 노출될 위험.
- **미검증 부채 규모**: Phase 15-20 코드는 작성되었지만 맥 빌드+실기기 테스트를 거치지 않음.
- **실시간성 한계**: 진짜 push가 아닌 로컬 폴링 + BGAppRefresh 기반. 비동기 실행(`/v1/runs`)이 앱에 노출되지 않음.
- **PC 대비 기능 격차**: 네이티브 터미널 없음(의도적), 파일 브라우저 읽기전용, 라이브 로그 tail 없음, 툴셋 토글 읽기전용, 칸반 이벤트 타임라인 없음.
- **다음 추천 기능 1순위**: Live Activity/Dynamic Island(칸반 진행률, Deep think 토론 진행상황, 긴 생성 작업, 핸즈프리 음성 상태) — PRODUCT_REVIEW.md가 최우선으로 권고.
- **의도적으로 PC를 넘어서려 하지 않는 영역**: 터미널/명령실행, Meta DAT 카메라 스트림, 대량 파일 편집/IDE 작업.

---

## 8. 부록

### 8.1 빌드 명령 (맥에서만 가능)
```bash
xcodebuild -project HermesChat.xcodeproj -scheme HermesChat \
  -destination 'generic/platform=iOS Simulator' CODE_SIGNING_ALLOWED=NO build
```

### 8.2 접속 정보

| 서비스 | 주소 |
|---|---|
| 기본 게이트웨이 | `http://100.83.59.60:8642` |
| 프로필 게이트웨이 | `:8643`부터 순차 할당 |
| Hermes Bridge | `http://100.83.59.60:8765` |
| 대시보드 | `http://100.83.59.60:8000` |

### 8.3 Bridge 수정 시 재배포 (필수 — 안 하면 앱이 "브리지 HTTP 404")
```bash
cd "$REPO"
cp ./server/hermes_bridge.py ~/.hermes/bridge/
launchctl unload ~/Library/LaunchAgents/ai.hermes.bridge.plist
launchctl load ~/Library/LaunchAgents/ai.hermes.bridge.plist
curl -s http://127.0.0.1:8765/health
```

### 8.4 신규 Swift 파일 등록 (project.pbxproj, objectVersion 77)
기존 항목(예: `SessionModels.swift`)을 참고해 4곳에 수기 등록: `PBXBuildFile`, `PBXFileReference`, 해당 그룹(`Models`/`Views`/`Services`)의 `children`, `PBXSourcesBuildPhase`의 `files`. 등록 후 반드시 빌드 검증. Xcode GUI에서 파일을 만들면 자동 등록되어 수기 절차 불필요.

### 8.5 참고 문서 지도

| 문서 | 담당 내용 |
|---|---|
| `docs/PLAN.md` | 아키텍처, 검증된 사실, 전체 Phase 설계 |
| `docs/TASKS.md` | 실시간 태스크 상태보드(단일 진실원본) |
| `docs/HANDOFF.md` | 에이전트 교대 프로토콜, 빌드 명령, pbxproj 등록 절차 |
| `docs/COMMERCIALIZATION.md` | 상업화 로드맵, 요금제, SaaS 아키텍처, 심사 대응 |
| `docs/PRODUCT_REVIEW.md` | 기능 감사, 강점/약점 분석, 리스크, 차기 기능 제안 |
| `docs/BUILD_STATUS.md` | 빌드/실기기 검증 대기열 롤링 트래커 |
| `docs/PROJECT_SUMMARY.md` | (본 문서) 위 6개를 종합한 스냅샷 개요 |
