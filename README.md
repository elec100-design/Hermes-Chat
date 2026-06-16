# HermesChat

맥미니에서 실행 중인 [hermes-agent](https://github.com/NousResearch/hermes-agent)를 아이폰에서 사용하는 SwiftUI 네이티브 앱.

## 아키텍처

```
┌─ iPhone (HermesChat) ──────────────────────────────┐
│  SwiftUI · iOS 17+                                  │
└──────────────┬─────────────────────┬───────────────┘
         Tailscale VPN               │
┌──────────────▼─────────────────────▼───────────────┐
│ 맥미니                                              │
│  :8642  default 프로필 게이트웨이 API               │
│  :8643  프로필A 게이트웨이 API                      │
│  :8644  프로필B 게이트웨이 API  …                   │
│  :8765  Hermes Bridge (hermes_bridge.py)            │
│  :8000  hermes-agent 대시보드                       │
│  ~/.hermes/kanban.db  내장 칸반 DB                  │
└─────────────────────────────────────────────────────┘
```

- **게이트웨이 API** — 세션 생성·대화·SSE 스트리밍 등 공식 REST API
- **Hermes Bridge** — 게이트웨이가 지원하지 않는 기능(SOUL.md 편집, 파일 업로드, 칸반 쓰기 등)을 담당하는 단일 Python 파일 서버
- **Tailscale** — 공유기 설정 없이 외부에서 맥미니에 접근

---

## 구현된 기능

### 멀티 프로필 관리
- **프로필 보드** — 2×3 그리드로 등록된 hermes-agent 프로필을 한눈에 표시. 온라인 상태 실시간 프로브
- **프로필 자동 검색** — 8642~8651 포트를 병렬 스캔해 활성 게이트웨이를 자동 등록 (Bridge가 있으면 Bridge 목록 우선)
- **프로필 생성** — 보드의 `+` 카드 → 이름·SOUL 입력 → Bridge가 **백엔드에 완전 생성**(`hermes profile create <name> --clone-from default`로 default의 config.yaml/.env/SOUL.md/skills 복제, 포트 자동 할당, 게이트웨이 기동·헬스 폴링)
- **프로필 삭제** — 상세 화면의 "프로필 삭제"(확인 다이얼로그) → Bridge `hermes profile delete <name> -y`로 백엔드에서 제거하고 앱 목록에서도 정리 (default는 삭제 불가)
- **프로필 전환** — 탭 한 번으로 전환. 이전 프로필 응답이 늦게 도착해도 세대 카운터로 안전하게 폐기
- **프로필 상세 편집** — **모델 카탈로그 드롭다운**(`cache/model_catalog.json`, 없으면 default 카탈로그 폴백)에서 선택·저장(재시작), SOUL.md 편집기, 게이트웨이 재시작 버튼 (확인 다이얼로그 포함)
- **프로필 이름 편집** — 설정 화면에서 롱프레스로 프로필 이름 변경. Keychain 키도 자동 이전

### 크론잡 (스케줄 자동화)
- 보드 카드의 시계 버튼 → 프로필별 크론잡 목록·편집 (hermes-agent 대시보드 Cron 화면을 네이티브로 재현)
- 프롬프트 · 스케줄 · 전달 대상 · 스킬 · 활성화 토글 편집 — Bridge가 `<profile>/cron/jobs.json`을 read-modify-write(편집 필드만 갱신, 나머지 보존, `.bak` 백업 + 원자적 교체)

### 채팅
- **SSE 실시간 스트리밍** — `URLSession.bytes` 기반 라인 단위 파싱. 스트리밍 중 말풍선이 실시간으로 채워짐
- **마크다운 렌더링** — 코드 펜스(모노스페이스 + 배경 + 복사 버튼), 볼드/인라인 코드/링크 인라인 파싱. SPM 의존성 없음
- **사고과정 숨김** — `<think>` 블록은 스트리밍 도중에도 한 글자도 노출되지 않음. 사고 중 "생각 중…" 표시
- **도구 호출 칩** — "도구 N회 실행" 캡슐로 접혀 있다가 탭하면 상세 펼침. 스트리밍 중 카운트 라이브 갱신
- **이미지 썸네일** — 에이전트 응답의 `![alt](src)` 및 `[첨부: 경로]`를 Bridge `/files/raw`로 인라인 표시 (NSCache 64MB)
- **메시지 컨텍스트 메뉴** — 길게 눌러 복사 / 공유 / 평문 복사 (마크다운 기호 제거)
- **세션 fork** — 툴바 메뉴 또는 세션 목록 스와이프로 현재 히스토리를 보존한 채 새 세션 분기
- **자동 제목** — 첫 메시지 전송 후 `PATCH /api/sessions/{id}`로 자동 제목 설정
- **세션 검색·페이지네이션** — `.searchable` 검색 바, 50개 단위 자동 로드

### 응답 미수신 폴백
게이트웨이가 세션 DB에는 응답을 기록하지만 SSE 스트림으로 전송하지 않는 실기기 버그를 방어:
스트림이 보일 내용 없이 끝나면 세션 메시지를 2초 간격 폴링(빈 스트림 최대 300초, think-only 최대 6초)해 답변을 회수.

### 파일·사진 첨부
- 입력창 `+` 버튼 → **PhotosPicker**(사진) / **fileImporter**(파일 · Google Drive 포함, iOS Files 앱 Provider로 자동 지원)
- 첨부 시 Bridge `/upload`로 맥미니에 파일을 전송하고 절대경로를 메시지에 prepend → Hermes가 자기 파일 도구로 접근
- 전송 전 입력창에 **첨부 칩 + 이미지 썸네일** 미리보기

### 음성 입출력 (내장 프레임워크)
- **음성 입력** — SFSpeechRecognizer(ko-KR) + AVAudioEngine. 부분 인식 결과를 입력창에 실시간 반영
- **읽어주기** — AVSpeechSynthesizer(ko-KR). 메시지 컨텍스트 메뉴 "읽어주기/중지". 마크다운 기호 없이 낭독
- **블루투스 마이크** — AirPods·Meta Ray-Ban Glasses HFP 마이크 입력 지원

### 핸즈프리 음성 대화 (Phase 15)
에어팟·메타 글라스로 폰을 꺼내지 않고 대화하는 완전 핸즈프리 모드:
- **자동 낭독** — 받아쓰기로 전송한 경우 응답을 문장 단위 스트리밍 TTS로 자동 낭독 (키보드 전송은 낭독 안 함)
- **핸즈프리 루프** — waveform 버튼 → 1.8초 침묵 시 자동 전송 → 스트리밍 낭독 → 자동 재청취. 무발화 60초 자동 종료
- **탭 제어** — AirPods 스템 탭 / Meta 글라스 탭: 낭독 중=바지-인 재청취, 청취 중=즉시 전송(무발화면 종료)
- **백그라운드 지속** — 잠금화면·주머니 속에서도 음성 대화 유지 (AVAudioSession 상시 가동)
- **통합 오디오 세션** — `.playAndRecord/.voiceChat/HFP` 프로필 멱등 전환. 라우트 분리·전화 인터럽션 자동 처리

### Deep Think 멀티 에이전트 토론룸 (Phase 14)
여러 hermes-agent 프로필이 한 주제로 토론해 환각을 줄이고 더 나은 아이디어를 도출:
- 참가 프로필 선택 → 주제 입력 → 라운드 수(1~5) 설정 → 토론 시작
- 각 참가자는 자신의 게이트웨이 세션에서 독립 스트리밍. **라운드는 TaskGroup 병렬 진행** (순차 대기 없음)
- 사회자가 최종 결론 카드 작성 (탈락 시 승계). 완료 토론 최대 20건 로컬 보관, 재열람 가능
- 한 참가자 게이트웨이가 꺼져도 탈락 처리 후 나머지로 계속 진행

### 칸반 보드
- hermes-agent **내장 칸반**(kanban.db)과 연동 — 앱에서 만든 태스크가 대시보드·디스패처와 같은 DB를 공유
- 상태 7단계: `triage → todo → scheduled → ready → running → blocked → done`
- **ready 태스크는 게이트웨이 디스패처가 60초 내 자동 실행** (running → done/blocked)
- 카드 액션 메뉴: 실행 요청 / 보류 / 완료 / 아카이브 / 담당 프로필 지정

### 알림
- **칸반 알림** — 60초 폴링으로 done/blocked 전이 감지 시 로컬 알림 (APNs 없음, Bridge 무수정)
- **응답 완료 알림** — 스트림 완료 시 앱이 비활성이고 10초 이상 경과하면 로컬 알림 (본문 80자 미리보기)
- **백그라운드 폴링** — BGAppRefreshTask로 앱이 꺼진 동안에도 칸반 변경 감지

### Skills & Tools
- 게이트웨이 `/v1/skills` · `/v1/toolsets` 읽기전용 표시 (이름/설명/활성화 상태)

### 대시보드 & 파일 브라우저
- 맥미니 hermes-agent 대시보드(`:8000`)를 WKWebView 탭으로 임베드
- Bridge 기반 읽기전용 파일 브라우저 + 프로필 게이트웨이 로그 열람

### 보안
- API Key · Bridge 토큰 → **Keychain** 저장 (UserDefaults 평문 저장 없음)
- 프로필별 API Key도 Keychain 분리 보관 (`profileApiKey.<name>` 키)

---

## 맥미니 초기 설정

### 1. Hermes Bridge 실행
```bash
# LaunchAgent로 상시 기동
cp server/com.hermes.bridge.plist ~/Library/LaunchAgents/
launchctl load ~/Library/LaunchAgents/com.hermes.bridge.plist
```

> **⚠️ 브리지 코드(`server/hermes_bridge.py`)를 고쳤다면 맥미니의 기동본을 교체하고
> LaunchAgent를 재기동해야 반영된다.** 안 하면 앱이 새 엔드포인트를 호출할 때
> `브리지 HTTP 404`가 난다.
> ```bash
> cp ./server/hermes_bridge.py ~/.hermes/bridge/        # 배포 위치
> launchctl unload ~/Library/LaunchAgents/ai.hermes.bridge.plist
> launchctl load   ~/Library/LaunchAgents/ai.hermes.bridge.plist
> curl -s http://127.0.0.1:8765/health                  # {"status":"ok"} 확인
> ```
> (LaunchAgent 파일명은 등록 시 쓴 이름. 위 절차 전문은 `docs/HANDOFF.md` §2.5)

### 2. 프로필별 API 서버 활성화
```bash
bash scripts/setup_profiles_api.sh <API_KEY>
# default + 각 프로필에 8642~부터 포트 자동 배정,
# API_SERVER_HOST=0.0.0.0, API_SERVER_MODEL_NAME=<프로필명> 설정 후 재시작
```

### 3. 앱 설정
1. **서버 주소** — `http://<Tailscale IP>` 입력
2. **API Key** 입력
3. **프로필 자동 검색** 버튼 탭 → 활성 게이트웨이 자동 등록
4. (선택) **Bridge URL** — `http://<Tailscale IP>:8765` 입력

---

## 개발 문서

| 파일 | 내용 |
|------|------|
| `docs/PLAN.md` | 전체 아키텍처 설계 및 Phase별 계획 |
| `docs/TASKS.md` | 작업 상태 보드 (TODO/DOING/DONE) |
| `docs/HANDOFF.md` | 에이전트 교대 프로토콜, 빌드 명령, pbxproj 등록 절차 |
| `server/hermes_bridge.py` | Hermes Bridge 서버 (단일 파일, stdlib만 사용) |
| `scripts/setup_profiles_api.sh` | 맥미니 프로필 API 서버 초기화 스크립트 |

---

## 기술 스택

- **iOS 17+ / SwiftUI** — 전체 UI
- **Swift Concurrency** — async/await, TaskGroup, AsyncStream
- **URLSession** — SSE 스트리밍 (`bytes(for:)`)
- **AVFoundation** — TTS(AVSpeechSynthesizer), 오디오 세션 관리
- **Speech** — 음성 인식 (SFSpeechRecognizer)
- **MediaPlayer** — 탭 제어 (MPRemoteCommandCenter)
- **UserNotifications** — 로컬 알림 + BGAppRefreshTask
- **WebKit** — 대시보드 임베드 (WKWebView)
- **Python 3 stdlib** — Hermes Bridge 서버 (외부 패키지 없음)
