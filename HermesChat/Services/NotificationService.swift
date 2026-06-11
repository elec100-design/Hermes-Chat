import Foundation
import UserNotifications

/// 칸반 태스크 전이(done/blocked) 감지 → 로컬 알림 (T-093).
///
/// Bridge 무수정 설계(PLAN Phase 11): 기존 `GET /kanban`·`GET /kanban/<board>`만 폴링하고,
/// 직전 스냅샷(taskID→status)과 diff 해서 전이를 찾는다. 스냅샷은 UserDefaults(JSON) 보존.
/// 첫 폴링은 스냅샷만 기록하고 알리지 않는다 (앱 설치 직후 알림 폭주 방지).
@MainActor
final class NotificationService: ObservableObject {
    static let shared = NotificationService()

    /// [boardSlug: [taskID: status]]
    private static let snapshotKey = "kanbanStatusSnapshot"
    private static let pollIntervalSeconds: UInt64 = 60

    private var pollTask: Task<Void, Never>?
    private let delegate = ForegroundBannerDelegate()

    private init() {
        // 포그라운드에서도 배너가 보이도록 (기본은 무표시)
        UNUserNotificationCenter.current().delegate = delegate
    }

    // MARK: - 권한

    /// 미결정이면 요청, 거부면 false. 알림 기능 진입점에서 한 번 호출.
    @discardableResult
    func requestAuthorization() async -> Bool {
        let center = UNUserNotificationCenter.current()
        switch await center.notificationSettings().authorizationStatus {
        case .notDetermined:
            return (try? await center.requestAuthorization(options: [.alert, .sound, .badge])) ?? false
        case .denied:
            return false
        default:
            return true
        }
    }

    // MARK: - 포그라운드 폴링

    func startPolling(appSettings: AppSettings) {
        guard pollTask == nil else { return }
        pollTask = Task { [weak self, weak appSettings] in
            while !Task.isCancelled {
                await self?.checkKanbanTransitions(bridge: appSettings?.bridgeClient)
                try? await Task.sleep(nanoseconds: Self.pollIntervalSeconds * 1_000_000_000)
            }
        }
    }

    func stopPolling() {
        pollTask?.cancel()
        pollTask = nil
    }

    // MARK: - 칸반 전이 점검 (BGAppRefresh에서도 호출 — T-095)

    /// 보드 전체를 1회 읽어 직전 스냅샷과 비교한다. 네트워크 오류는 조용히 무시
    /// (백그라운드에서 Tailscale이 죽어 있을 수 있음 — 다음 기회에 재시도).
    func checkKanbanTransitions(bridge: BridgeClient?) async {
        guard let bridge else { return }
        guard let boards = try? await bridge.fetchKanbanBoards() else { return }

        let previous = loadSnapshot()
        var current: [String: [String: String]] = [:]

        for summary in boards {
            guard let board = try? await bridge.fetchBoard(slug: summary.board) else { continue }
            var statuses: [String: String] = [:]
            for task in board.tasks {
                statuses[task.id] = task.status.rawValue
                guard let prev = previous[summary.board]?[task.id],
                      prev != task.status.rawValue else { continue }
                switch task.status {
                case .done:
                    notify(
                        title: "칸반 작업 완료",
                        body: "[\(board.name)] \(task.title)",
                        id: "kanban-done-\(summary.board)-\(task.id)"
                    )
                case .blocked:
                    notify(
                        title: "칸반 작업 보류됨",
                        body: "[\(board.name)] \(task.title)",
                        id: "kanban-blocked-\(summary.board)-\(task.id)"
                    )
                default:
                    break
                }
            }
            current[summary.board] = statuses
        }

        // 일부 보드 읽기에 실패했으면 그 보드의 직전 스냅샷을 유지해 전이를 놓치지 않는다
        var merged = previous
        for (board, statuses) in current { merged[board] = statuses }
        saveSnapshot(merged)
    }

    // MARK: - 알림 발행

    /// 즉시 로컬 알림. T-094(채팅 응답 완료)도 이 메서드를 쓴다.
    func notify(title: String, body: String, id: String = UUID().uuidString) {
        let content = UNMutableNotificationContent()
        content.title = title
        content.body = body
        content.sound = .default
        let request = UNNotificationRequest(identifier: id, content: content, trigger: nil)
        UNUserNotificationCenter.current().add(request)
    }

    // MARK: - 스냅샷 영속화

    private func loadSnapshot() -> [String: [String: String]] {
        guard let data = UserDefaults.standard.data(forKey: Self.snapshotKey),
              let decoded = try? JSONDecoder().decode([String: [String: String]].self, from: data)
        else { return [:] }
        return decoded
    }

    private func saveSnapshot(_ snapshot: [String: [String: String]]) {
        if let data = try? JSONEncoder().encode(snapshot) {
            UserDefaults.standard.set(data, forKey: Self.snapshotKey)
        }
    }
}

/// 앱이 포그라운드일 때도 배너/사운드를 표시하는 델리게이트
private final class ForegroundBannerDelegate: NSObject, UNUserNotificationCenterDelegate {
    func userNotificationCenter(
        _ center: UNUserNotificationCenter,
        willPresent notification: UNNotification,
        withCompletionHandler completionHandler: @escaping (UNNotificationPresentationOptions) -> Void
    ) {
        completionHandler([.banner, .sound])
    }
}
