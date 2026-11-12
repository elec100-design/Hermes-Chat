# HermesChat Android (MVP)

Jetpack Compose 클라이언트. 맥미니 hermes-agent 게이트웨이 / Bridge / Hermes-bot 을 Tailscale 사설망(HTTP)으로 사용한다.

## 요구사항
- JDK 17+
- Android SDK (platform 36, build-tools). `android/local.properties` 에 `sdk.dir=/path/to/Android/sdk`
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
