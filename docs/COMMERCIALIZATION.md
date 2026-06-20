# HermesChat 상업화 계획 (SaaS 전환 로드맵)

> **최초 작성**: 2026-06-20  
> **상태**: Phase 0 컴플라이언스 진행 중 (T-A01~A06 DONE, T-A07~A08 진행 중)

---

## Context

HermesChat은 현재 사용자 개인 Mac mini + Tailscale 전용 클라이언트다. 목표는 **일반 유저도 사용 가능한 SaaS 서비스**로 전환하고 App Store에 출시하는 것. Phase 14까지 검증된 기능(채팅, 보이스, 칸반, 대시보드)으로 v1.0 출시, 이후 업데이트로 Phase 15+ 추가.

---

## 전체 로드맵

```
Phase 0 (앱 코드만, 즉시 가능)   → App Store 컴플라이언스 충족
Phase 0-L (Phase 0과 병행)        → 다국어 지원 (한국어·영어·중국어 간체)
Phase 1 (백엔드)                   → 클라우드 hermes-agent SaaS 인프라
Phase 2 (앱 SaaS 전환)             → 로그인·구독·클라우드 연결
Phase 3 (출시 및 운영)             → TestFlight → App Store → 성장
```

---

## Phase 0: App Store 컴플라이언스 (앱 코드만)

### 현황 (2026-06-20)

| 태스크 | 내용 | 상태 |
|--------|------|------|
| T-A01 | `PrivacyInfo.xcprivacy` 생성 + pbxproj 등록 | **DONE** |
| T-A02 | `Info.plist` arm64 수정 | **DONE** |
| T-A03 | `OnboardingView.swift` 신설 (3단계) + `HermesChatApp.swift` 연결 | **DONE** |
| T-A04 | 다국어 기반 설정 (ko/en/zh-Hans lproj, 탭 레이블 LocalizedStringKey) | **DONE** |
| T-A05 | `SettingsView.swift`에 개인정보 처리방침 링크 추가 | **DONE** |
| T-A06 | Sign in with Apple 엔타이틀먼트 추가 (Phase 2 준비) | **DONE** |
| T-A07 | 전체 Views 하드코딩 한국어 → LocalizedStringKey 전환 | **NEEDS-BUILD** |
| T-A08 | ATS 정리 (NSAllowsArbitraryLoads 유지·App Store 심사 사유 정리) | **NEEDS-BUILD** |

### 0-1. PrivacyInfo.xcprivacy ✅
- 파일: `HermesChat/Resources/PrivacyInfo.xcprivacy`
- `NSPrivacyTracking: false`, UserDefaults/FileTimestamp/SystemBootTime API 선언

### 0-2. ATS 수정
- `NSAllowsArbitraryLoads: true` **유지** — 자체 호스팅(Tailscale HTTP)이 필수
- App Store 심사 노트: "The app connects to user-configured self-hosted AI agent servers over local network (Tailscale VPN). HTTP is required as TLS certificates are not standard for self-hosted setups. No tracking or third-party data collection is performed."
- localhost 중복 예외는 제거 (NSAllowsArbitraryLoads로 이미 커버)

### 0-3. 온보딩 뷰 ✅
- `HermesChat/Views/OnboardingView.swift` — 3단계: 환영 → 서버연결 → 완료
- `AppSettings.isFirstLaunchComplete: Bool` → false면 `.fullScreenCover`
- 클라우드 선택 버튼은 `isEnabled: false` (Phase 2에서 활성화)

### 0-4. Sign in with Apple ✅
- `HermesChat.entitlements`에 `com.apple.developer.applesignin: ["Default"]`
- Phase 2 계정 시스템 추가 시 코드 연결

---

## Phase 0-L: 다국어 지원 (Phase 0과 병행)

### 목표 언어

| 언어 | 코드 | 상태 |
|------|------|------|
| 한국어 | `ko` | **기준 언어, 기반 DONE** |
| 영어 | `en` | **기반 DONE** |
| 중국어 간체 | `zh-Hans` | **기반 DONE** |
| 중국어 번체 | `zh-Hant` | v1.1에서 추가 예정 |

### 디렉토리 구조
```
HermesChat/Resources/
  ko.lproj/Localizable.strings    ← 기준 문자열
  ko.lproj/InfoPlist.strings
  en.lproj/Localizable.strings
  en.lproj/InfoPlist.strings
  zh-Hans.lproj/Localizable.strings
  zh-Hans.lproj/InfoPlist.strings
```

### 문자열 키 네임스페이스
- `tab.*` — 탭바
- `onboarding.*` — 온보딩
- `settings.*` — 설정
- `session.*` — 세션 목록
- `chat.*` — 채팅
- `kanban.*` — 칸반
- `profile.*` — 프로필 (board/detail/create)
- `cron.*` — 크론잡
- `skills.*` — Skills & Tools
- `common.*` — 공통 버튼/상태

### 완료 여부
- Tab, Onboarding, Settings, Session, Chat, Kanban: **DONE**
- ProfileBoardView, ProfileDetailView, CronJobsView, CronJobEditView, SkillsView, FileBrowserView, CreateProfileView, DashboardWebView: **T-A07 완료** (NEEDS-BUILD)

---

## Phase 1: 클라우드 SaaS 인프라 (백엔드)

> **선행 조건**: Phase 0 App Store 제출 후 병행 개발

### 아키텍처 — per-user Docker 컨테이너

hermes-agent는 단일 사용자용 설계 → 멀티테넌트 불가 → **사용자당 1개 컨테이너** 전략.

```
[iPhone App]
     │ HTTPS
     ▼
[Load Balancer + nginx]  ← Let's Encrypt SSL
     │
[Auth Service: Supabase]  ← Sign in with Apple, JWT
     │
[Container Orchestrator (Docker Compose / Fly.io)]
  ├── user-001-hermes-agent  :8642 (private)
  ├── user-002-hermes-agent  :8642 (private)
  └── user-NNN-hermes-agent  :8642 (private)
     │
[Bridge API Gateway]  ← 각 컨테이너 라우팅
```

### T-B01: hermes-agent 컨테이너화
- Dockerfile (hermes-agent Python 환경 + 의존성)
- `~/.hermes/` 볼륨 마운트 (프로필별 영속 스토리지)
- 컨테이너 시작 시 default 프로필 자동 생성

### T-B02: Supabase 인증
- 이유: PostgreSQL + Auth + Storage 통합, Sign in with Apple 기본 지원, 오픈소스
- 사용자 테이블: `users(id, email, plan, container_id, created_at)`
- Row Level Security로 데이터 격리
- Sign in with Apple OAuth 설정

### T-B03: API Gateway (`server/cloud_gateway.py`)
- 기존 `hermes_bridge.py` 위에 얹는 인증 미들웨어
- JWT 검증 → 해당 사용자 컨테이너로 프록시
- 엔드포인트: `POST /auth/login`, `GET /status`, `GET /usage`, `*` → 컨테이너 프록시

### T-B04: 클라우드 제공자 배포
- **1순위**: Fly.io (글로벌 분산, 간단한 배포)
- **대안**: Hetzner Cloud CCX13 (2vCPU, 8GB RAM) + Docker Compose (가격 대비 성능)
- 성장 시: Kubernetes (k3s) 전환 검토

### T-B05: 가격 플랜 설정

| 플랜 | 가격 | 제한 |
|------|------|------|
| 무료 | ₩0 | 1 프로필, 월 200 메시지 |
| Basic | ₩9,900/월 | 3 프로필, 무제한 메시지 |
| Pro | ₩29,900/월 | 10 프로필, 우선 응답, 파일 첨부 |
| Self-hosted | 무료 | 직접 서버 사용 (기존 파워유저) |

- Supabase `users.plan` 컬럼 + 제한 로직 (`cloud_gateway.py`)

---

## Phase 2: 앱 SaaS 전환

### T-C01: 로그인/회원가입 뷰 (`AuthView.swift` 신규)
- Sign in with Apple (Apple 정책: 계정 생성 앱은 필수)
- Supabase Auth 연동
- JWT → Keychain 저장 (기존 `KeychainHelper.swift` 재사용)
- pbxproj 등록 필요

### T-C02: 연결 모드 분기
- `AppSettings`에 `connectionMode: .cloud | .selfHosted` 추가
- `.cloud`: JWT로 클라우드 게이트웨이 연결 (하드코딩 또는 `AppSettings.cloudEndpoint`)
- `.selfHosted`: 기존 IP 직접 입력 방식 유지 (파워유저, 온보딩에서 선택)
- `HermesAPIClient.swift`에 모드별 baseURL 분기 추가

### T-C03: StoreKit 2 구독 (`SubscriptionService.swift` 신규)
- `Product.products(for:)` → Basic/Pro 제품 로드
- `Transaction.currentEntitlements` → 플랜 상태 확인
- 구독 게이트: 무료 플랜 메시지 초과 시 업그레이드 시트 표시
- `SettingsView.swift`에 "구독 관리" 섹션 추가
- pbxproj 등록 필요

### T-C04: OnboardingView 클라우드 경로 활성화
- 현재 `isEnabled: false`인 클라우드 버튼 활성화
- 클라우드 선택 시: Sign in with Apple → 무료 시작 → AuthView로 연결
- 자체 호스팅 선택 시: 기존 IP 입력 플로우 유지

### T-C05: 사용량 표시
- `SettingsView.swift` 또는 별도 `UsageView.swift`
- 무료 플랜: 잔여 메시지 수 표시
- 클라우드 API `GET /usage` 폴링

---

## Phase 3: 출시 및 운영

### T-D01: App Store 메타데이터
- **앱 이름**: HermesChat (영어), 헤르메스챗 (한국어)
- **카테고리**: Productivity
- **스크린샷**: 6.7인치 iPhone 필수 (6장), iPad 권장
- **설명문** (영어 필수):
  > "HermesChat brings your AI agent to mobile. Connect to your self-hosted hermes-agent server via Tailscale, or use the HermesChat cloud service. Chat, voice, kanban, and dashboard — all from your iPhone."
- **키워드**: AI chat, agent, kanban, voice input, productivity, self-hosted

### T-D02: TestFlight 베타
- 내부 테스터 (개발팀) → 외부 베타 100명
- 측정 지표: 온보딩 완료율, 연결 실패율, 채팅 전환율
- 기간: 2주

### T-D03: 앱 심사 제출 준비
- 리뷰어 계정: 테스트용 클라우드 계정 제공 (서버 없이 리뷰 가능)
- ATS 심사 노트 준비 (Phase 0-2 참조)
- `PrivacyInfo.xcprivacy` 포함 확인 ✅
- 개인정보 처리방침 URL 준비 (웹 페이지 필요)

### T-D04: 출시 후 계획
- **v1.1**: Phase 15 핸즈프리 음성 (NEEDS-BUILD 완료 후)
- **v1.2**: Ray-Ban 글라스 연동 (Phase 16-17)
- **v1.3**: 팀 플랜 (다수 사용자 공유 hermes-agent)

---

## 핵심 결정 사항

| 결정 항목 | 채택 | 사유 |
|-----------|------|------|
| 인증 서비스 | Supabase | 오픈소스, 저렴, Sign in with Apple 기본 지원 |
| 컨테이너 오케스트레이션 | Fly.io (초기) | 배포 간단, 글로벌 분산 |
| 결제 | StoreKit 2 (앱 내) | Apple 정책 준수, 구현 단순 |
| 자체 호스팅 지원 | 유지 | 파워유저 확보, 개발 기반 |
| 다국어 v1.0 | 한국어 + 영어 + 중국어 간체 | 최대 시장 커버 |
| 번역 방법 | Claude API 초벌 + 검토 | 비용·속도·품질 균형 |
| ATS | NSAllowsArbitraryLoads 유지 + 심사 사유 | 자체 호스팅 HTTP 필수 |

---

## App Store 심사 노트 템플릿

```
REVIEWER NOTE — NETWORK ACCESS

HermesChat connects to user-configured, self-hosted AI agent servers 
(hermes-agent) over a private network (Tailscale VPN). The server address
is entered by the user during onboarding and can use HTTP because:

1. The connection is entirely within a private Tailscale network — no 
   public internet exposure.
2. Self-hosted server setups rarely include TLS certificates, making 
   HTTPS impractical for this user base.
3. No user data is sent to third-party servers. All AI processing happens
   on the user's own hardware.

NSAllowsArbitraryLoads is required to support these self-hosted 
configurations. The app does not collect, transmit, or share any 
user data externally.

TEST ACCOUNT (for cloud mode review in Phase 2):
Email: reviewer@hermeschat.test
Password: [to be provided with submission]
```

---

## 검증 체크리스트

### Phase 0 완료 확인
- [ ] `xcodebuild` 성공 (arm64 simulator)
- [ ] 온보딩 플로우 Simulator 테스트 (3단계 전환)
- [ ] Localizable.strings 모든 화면 커버 (grep으로 하드코딩 한국어 0개)
- [ ] PrivacyInfo.xcprivacy 빌드에 포함되는지 확인

### Phase 1 완료 확인
- [ ] Docker 컨테이너에 curl로 채팅 API 호출 성공
- [ ] Supabase Sign in with Apple 플로우 완료
- [ ] JWT 검증 → 컨테이너 라우팅 동작 확인

### Phase 2 완료 확인
- [ ] 새 계정 생성 → 클라우드 채팅 → 구독 업그레이드 E2E 테스트
- [ ] 자체 호스팅 모드 기존 기능 회귀 없음

### Phase 3 완료 확인
- [ ] TestFlight 외부 테스터 10명 → 피드백 반영
- [ ] App Store 심사 제출 → 승인
