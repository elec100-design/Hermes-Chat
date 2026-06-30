import Foundation
import UIKit

/// APNs 원격 푸시 등록 및 Bridge 토큰 전달 (T-151, Phase B).
///
/// 흐름: 알림 권한 허용 → `UIApplication.registerForRemoteNotifications()` → AppDelegate가
/// 디바이스 토큰 수신 → 현재 프로필의 Bridge `POST /push/register`로 업로드. Bridge가
/// 칸반 변경을 감시해 앱이 꺼져 있어도 푸시를 보낸다. 페이로드는 최소만 — 본문은 앱이
/// 열릴 때 기존 경로로 fetch 하므로 사설망 프라이버시 철학과 충돌하지 않는다.
///
/// Bridge·토큰이 아직 없으면 조용히 보류했다가 둘 다 준비되면(또는 포그라운드 복귀 시)
/// 업로드한다 — 프로필 전환 후에도 새 프로필 Bridge에 토큰이 등록되도록.
@MainActor
final class PushService: NSObject, ObservableObject {
    static let shared = PushService()
    private override init() {}

    private(set) var deviceToken: String?
    private weak var appSettings: AppSettings?

    func configure(appSettings: AppSettings) {
        self.appSettings = appSettings
    }

    /// 알림 권한이 허용된 경우 APNs 등록을 시작한다.
    func start() {
        UIApplication.shared.registerForRemoteNotifications()
    }

    /// AppDelegate가 디바이스 토큰을 받았을 때 호출.
    func didRegister(tokenData: Data) {
        deviceToken = tokenData.map { String(format: "%02x", $0) }.joined()
        Task { await uploadToken() }
    }

    /// 현재 토큰을 현재 프로필 Bridge에 등록한다. 토큰/Bridge가 준비됐을 때만 동작하고
    /// 실패는 조용히 무시한다(다음 포그라운드 복귀에 재시도). 포그라운드 복귀 시에도
    /// 호출돼 프로필 전환 후 재등록을 보장한다.
    func uploadToken() async {
        guard let token = deviceToken,
              let appSettings,
              let bridge = appSettings.bridgeClient else { return }
        try? await bridge.registerPushToken(token, profile: appSettings.selectedProfile.name)
    }
}

/// APNs 콜백 수신용 앱 델리게이트 (SwiftUI App에 @UIApplicationDelegateAdaptor로 연결).
final class AppDelegate: NSObject, UIApplicationDelegate {
    func application(
        _ application: UIApplication,
        didRegisterForRemoteNotificationsWithDeviceToken deviceToken: Data
    ) {
        Task { @MainActor in PushService.shared.didRegister(tokenData: deviceToken) }
    }

    func application(
        _ application: UIApplication,
        didFailToRegisterForRemoteNotificationsWithError error: Error
    ) {
        // 시뮬레이터/미지원 환경 — 조용히 무시
    }
}
