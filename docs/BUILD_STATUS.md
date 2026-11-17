# BUILD_STATUS

> 맥에서 빌드/실기기 검증이 필요한 항목 추적. 상세는 `docs/TASKS.md`가 단일 진실원본.

## 현재 대기 (2026-11-17)

### 1. Android (`android/`) — 로컬 빌드·단위 테스트 114건 통과, 실기기 검증 대기
- **T-200~T-202 (Phase 28, 채널·워크스페이스 토론)**: 토론 버튼·시작 시트·진행 줄·꼬리표. 맥미니 Hermes-bot P32 배포 후 `TASKS.md` Phase 28 체크리스트 검증 필요.
- **T-205~T-209, T-215**: 멘션 수신 봇 선택(T-205), 칸반 첫 화면·빈 열 접기(T-206), Git 경로/상태 구분(T-207), 크론 카드 접기(T-208), `@이름` 칩 커서 위치(T-209), Live 버튼 글자·글라스 한국어 오류(T-215).
- **T-187~T-189, T-191~T-196**: Meta Wearables DAT SDK 직접 촬영 Live, 채팅 첨부 이미지 뷰어, Live 채팅 통합, 작업 폴더 선택, Claude 앱 풍 디자인 개편, 5→6탭 재구성 및 승인 헤더 버튼화.
- 최신 로컬 빌드: `11-17 | claude/t204-bridge-dotdirs (main 909ef69 + T-204) | ANDROID BUILD SUCCESSFUL` (단위 테스트 114건 통과, `app-debug.apk` sha256 `e084d565…5f0f`).

### 2. Hermes Bridge (`server/hermes_bridge.py`)
- **T-204**: `PROFILES_DIR.iterdir()`에서 `.`으로 시작하는 디렉터리(`.deleted`) 제외. Bridge 재배포 및 `/profiles` 응답 확인 완료 — 크론 칩 실기기 확인 대기.

### 3. iOS (`HermesChat/`) — Xcode 빌드 / 실기기 검증 대기
- **Phase 22~24-B (T-149~T-157, T-167~T-168)**: `11-13` iOS Simulator 빌드 및 `HermesChatTests` 34건 통과(`e0b8819`) — 실기기 체크리스트 확인 대기.
- **Phase 15~21**: 핸즈프리 음성(T-117~124), 메타 글라스 사진(T-125~130), Siri·위젯·글라스 탭(T-131~137, 위젯 타깃은 Xcode GUI 수동 생성 필요), 크론 중앙 관리(T-138, T-146), 프로필 생성/삭제/카탈로그(T-139~145), 세션 핀/이름변경(T-147), 대시보드 핀치 줌(T-148) — `NEEDS-BUILD`.
- **Phase A / C / Hotfix**: T-A07~A08, T-C01~C05(런타임 검증 대기), T-REV01(Demo Mode), T-158~T-159(Bridge 토큰/업로드 타임아웃) — `NEEDS-BUILD`.

> ⚠️ **주의 (T-216)**: 맥미니 게이트웨이는 2026-10-22부터 멀티플렉스(`:8642`, default 외 `/p/<profile>/`)로 전환되어 있어, iOS·Android의 게이트웨이 직결은 현재 `default` 프로필만 동작한다. 봇 탭(Hermes-bot `:8900` 경유)은 전 프로필 정상.

---

## 과거 기록

### 2026-06-16 (PR #5 / T-146~T-147)
- `claude/busy-ritchie-cx2ohx` (T-147 세션 핀·이름변경), `claude/youthful-archimedes-aqigr4` (T-146 크론 중앙 관리), `claude/profile-cronjob-config-ui-bhh2ip` (T-138~145) — 세부 내역은 `docs/TASKS.md` 참조.

### T-012 블로커 — 해소됨 (2026-06-10, Claude)
- 증상: xcodebuild가 `Services/ProfileDetailView.swift`를 빌드 입력으로 요구하나 실제 파일은 `Views/ProfileDetailView.swift`였음.
- 원인 1: pbxproj에서 fileRef가 Views가 아닌 Services 그룹 children에 있었음 → Views 그룹으로 이동.
- 원인 2: T-012 커밋이 T-011의 BridgeClient.swift pbxproj 등록 4곳을 덮어써 유실 → 재등록.
- 추가: 빈 껍데기였던 ProfileDetailView를 실기능(모델 선택/SOUL 편집/Gateway 재시작)으로 교체하고 설정 화면 프로필 행에 ⓘ 버튼 추가.
