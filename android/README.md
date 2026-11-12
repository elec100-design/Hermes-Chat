# HermesChat Android (MVP)

Jetpack Compose 클라이언트. 맥미니 hermes-agent 게이트웨이 / Bridge / Hermes-bot 을 Tailscale 사설망(HTTP)으로 사용한다.

## 요구사항
- JDK 17+
- Android SDK (platform 35, build-tools). `android/local.properties` 에 `sdk.dir=/path/to/Android/sdk`
- 비밀값(API Key, Bridge/Bot 토큰)은 앱 설정에서 입력 -> EncryptedSharedPreferences. 저장소에 커밋 금지.

## 빌드
```
cd android
./gradlew :app:testDebugUnitTest :app:assembleDebug
```
APK: `app/build/outputs/apk/debug/app-debug.apk`

(`gradlew` 래퍼가 없으면 Gradle 8.11.1 로 `gradle wrapper` 실행)
