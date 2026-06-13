# HermesChat (iOS)

맥미니의 hermes-agent를 아이폰에서 쓰는 SwiftUI 앱.

**작업 시작 전 반드시 읽기 (순서대로):**
1. `docs/PLAN.md` — 전체 설계와 Phase 계획 (hermes-agent 구조의 검증된 사실 포함)
2. `docs/TASKS.md` — 작업 상태 보드 (여기서 태스크를 골라 상태를 갱신하며 작업)
3. `docs/HANDOFF.md` — 에이전트 교대 프로토콜, 빌드 명령, pbxproj 파일 추가 절차

핵심 규칙:
- 브랜치 전략 (2026-06-12 갱신): **`main`이 기준선.** 각 세션은 main에서 새 피처 브랜치
  `claude/<topic>`를 따서 개발·커밋·푸시하고, 완료되면 PR로 main에 병합한다.
  (구 `claude/busy-meitner-lhc5os`는 폐기 — PR #1 병합 후 낡은 상태로 남아 삭제 예정.)
- 커밋 메시지: `T-0NN: <설명> [DONE|NEEDS-BUILD]`
- 새 Swift 파일은 `project.pbxproj` 등록 필수 (HANDOFF.md §4)
- 빌드 검증: `xcodebuild -project HermesChat.xcodeproj -scheme HermesChat -destination 'generic/platform=iOS Simulator' CODE_SIGNING_ALLOWED=NO build`
- 비밀값(API Key, 토큰)은 저장소에 커밋 금지
