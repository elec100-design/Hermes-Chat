import Foundation
import SwiftUI

/// Deep think 토론 화면의 ViewModel.
///
/// 설정/진행 상태를 @Published로 노출하고, 실제 토론 오케스트레이션은
/// `DiscussionOrchestrator`에 위임한다. 오케스트레이터가 방출하는
/// `DiscussionEvent`를 @Published 상태로 반영하는 것만 담당한다.
@MainActor
final class DiscussionViewModel: ObservableObject {
    // MARK: 설정 상태
    @Published var selectedProfileIDs: Set<UUID> = []
    @Published var topic: String = ""
    @Published var rounds: Int = 2
    /// nil이면 첫 참가자가 사회자
    @Published var moderatorID: UUID?
    /// 도구(웹 검색 등) 사용 허용 — 켜면 한 발언이 수 분까지 길어질 수 있다
    @Published var allowTools: Bool = false

    // MARK: 진행 상태
    @Published var phase: DiscussionPhase = .setup
    @Published var entries: [DiscussionEntry] = []
    /// 현재 발언(스트리밍/폴백 대기) 중인 참가자 이름들 — 라운드는 동시 진행된다
    @Published var speakingNames: [String] = []
    @Published var savedDiscussions: [SavedDiscussion] = DiscussionStore.load()

    let appSettings: AppSettings

    private var orchestrator: DiscussionOrchestrator?

    init(appSettings: AppSettings) {
        self.appSettings = appSettings
    }

    var canStart: Bool {
        !topic.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
            && selectedProfileIDs.count >= 2
    }

    /// 보드와 같은 정렬(port 순)로 선택된 참가 프로필
    var selectedProfiles: [HermesProfile] {
        appSettings.profiles.filter { selectedProfileIDs.contains($0.id) }
    }

    func toggleProfile(_ profile: HermesProfile) {
        if selectedProfileIDs.contains(profile.id) {
            selectedProfileIDs.remove(profile.id)
            if moderatorID == profile.id { moderatorID = nil }
        } else {
            selectedProfileIDs.insert(profile.id)
        }
    }

    func start() {
        guard canStart, !phase.isActive else { return }
        let orchestrator = DiscussionOrchestrator(
            profiles: selectedProfiles,
            topic: topic,
            rounds: rounds,
            moderatorID: moderatorID,
            allowTools: allowTools,
            defaultModel: appSettings.selectedModel,
            makeTransport: { [weak self] profile in
                self?.makeTransport(for: profile) ?? HermesAPIClient(
                    baseURL: URL(string: "http://localhost:8642")!,
                    apiKey: ""
                )
            }
        )
        orchestrator.onEvent = { [weak self] event in self?.apply(event) }
        self.orchestrator = orchestrator
        orchestrator.start()
    }

    func stop() {
        orchestrator?.stop()
    }

    func resetToSetup() {
        orchestrator?.resetToSetup()
        phase = .setup
    }

    func deleteSaved(id: UUID) {
        DiscussionStore.delete(id: id)
        savedDiscussions = DiscussionStore.load()
    }

    /// 결론 entry (있으면)
    var conclusionEntry: DiscussionEntry? {
        orchestrator?.conclusionEntry ?? entries.last { $0.kind == .conclusion }
    }

    /// 공유용 전체 기록 텍스트
    var shareText: String {
        Self.shareText(topic: topic, entries: entries)
    }

    static func shareText(topic: String, entries: [DiscussionEntry]) -> String {
        var lines = ["Deep think 토론", "주제: \(topic)", ""]
        for entry in entries {
            switch entry.kind {
            case .roundMarker:
                lines.append("=== \(entry.content) ===")
            case .system:
                lines.append("· \(entry.content)")
            case .statement:
                lines.append("[\(entry.speakerName)] \(MarkdownLite.plainText(from: entry.content))")
            case .conclusion:
                lines.append("")
                lines.append("=== 최종 결론 (사회자: \(entry.speakerName)) ===")
                lines.append(MarkdownLite.plainText(from: entry.content))
            }
            lines.append("")
        }
        return lines.joined(separator: "\n")
    }

    // MARK: - Event 반영

    private func apply(_ event: DiscussionEvent) {
        switch event {
        case .phaseChanged(let phase):
            self.phase = phase
            if case .finished = phase {
                savedDiscussions = DiscussionStore.load()
            }
        case .entryAppended(let entry):
            entries.append(entry)
        case .entryUpdated(let id, let content):
            guard let idx = entries.firstIndex(where: { $0.id == id }) else { return }
            entries[idx].content = content
        case .entryRemoved(let id):
            entries.removeAll { $0.id == id }
        case .speakingChanged(let names):
            speakingNames = names
        }
    }

    private func makeTransport(for profile: HermesProfile) -> DiscussionTransport {
        HermesAPIClient(
            baseURL: appSettings.baseURL(for: profile),
            apiKey: profile.apiKey.isEmpty ? appSettings.apiKey : profile.apiKey
        )
    }
}
