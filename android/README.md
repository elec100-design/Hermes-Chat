# HermesChat Android

Jetpack Compose 클라이언트 (봇 중심). 맥미니 hermes-agent 게이트웨이 / Bridge / Hermes-bot 을 Tailscale 사설망(HTTP)으로 사용한다.

> ⚠️ **멀티플렉스 미대응 (T-216)**: 봇 탭(Hermes-bot `:8900` 경유)은 전 프로필 정상 동작하지만, 게이트웨이 직결(`:8642`)은 아직 포트 기준이라 현재 `default` 프로필만 동작한다.

## 주요 기능
- **6탭 구성** — 대화 · 작업(워크스페이스) · 칸반 · 크론 · 대시보드 · 더보기 (Galaxy Z Fold 커버/펼침 적응형 레이아웃)
- **대화 & Live** — DM/채널 채팅, GFM 마크다운 표, 첨부 사진 원본/뷰어, Meta Wearables DAT SDK 직접 촬영 기반 핸즈프리 Live (`T-187~195`)
- **워크스페이스 & Git** — 작업 폴더별 다중 작업 채팅, `GET /git/status` · `POST /git/<commit|pull|push|pr|merge|deploy>` 팝업
- **사용량 & 채널 토론** — `paseo usage` 기반 에이전트 계정별 사용량 팝업(`GET /usage`), 다중 봇 채널 토론 시트/진행 줄 (`Phase 28`)
- **알림** — WorkManager 15분 주기 + 포그라운드 30초 폴링 (봇 답장·승인 요청)

## 요구사항
- JDK 17+
- Android SDK (`compileSdk 36`, `targetSdk 36`, `minSdk 29` — Meta DAT SDK 요구). `android/local.properties` 에 `sdk.dir=/path/to/Android/sdk`
- (선택) Meta Wearables DAT 배포 시 `android/local.properties` 에 `mwdat_application_id`, `mwdat_client_token` 설정 (개발자 모드는 기본값 `0`)
- 비밀값(API Key, Bridge/Bot 토큰)은 앱 설정에서 입력 -> EncryptedSharedPreferences. 저장소에 커밋 금지.

## 빌드
```
cd android
./gradlew :app:testDebugUnitTest :app:assembleDebug
```
APK: `app/build/outputs/apk/debug/app-debug.apk`

(`gradlew` 래퍼가 없으면 Gradle 8.11.1 로 `gradle wrapper` 실행)

## 릴리스 빌드
1. 업로드 키 생성 (저장소 밖에 보관, 절대 커밋 금지):
```
keytool -genkeypair -v -keystore ~/hermes-upload.jks -alias upload \
  -keyalg RSA -keysize 2048 -validity 10000
```
2. `android/keystore.properties` 작성 (gitignore 됨). 또는 환경변수 `HERMES_KEYSTORE_FILE`, `HERMES_KEYSTORE_PASSWORD`, `HERMES_KEY_ALIAS`, `HERMES_KEY_PASSWORD` (환경변수가 우선):
```
storeFile=/Users/me/hermes-upload.jks
storePassword=...
keyAlias=upload
keyPassword=...
```
3. 빌드:
```
./gradlew :app:bundleRelease
```
AAB: `app/build/outputs/bundle/release/app-release.aab` (키 정보가 없으면 서명 없이 빌드됨 -> Play Console 업로드 불가)

4. Play Console 업로드. Play App Signing 사용 시 업로드 키만 내가 보관하고 앱 서명 키는 Google 이 관리한다. 업로드 키(keystore + 비밀번호) 분실 시 키 재설정을 Google 에 요청해야 하므로 반드시 백업할 것.
5. 업로드마다 `app/build.gradle.kts` 의 `versionCode` 를 올릴 것.
