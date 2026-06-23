import ActivityKit
import SwiftUI
import WidgetKit

/// 채팅 응답 생성 Live Activity의 잠금화면 + 다이내믹 아일랜드 레이아웃 (T-150).
///
/// ⚠️ 이 파일은 **위젯 익스텐션 타깃**에 속해야 빌드된다. `HermesWidgets` 익스텐션 타깃을
/// (HermesWidgets/SETUP.md 절차로) Xcode에서 만든 뒤 이 파일과 `HermesChatActivityAttributes.swift`를
/// 그 타깃 멤버십에 추가하고, `HermesWidgetBundle`에 `HermesChatLiveActivity()`를 더한다.
/// 그 전까지는 앱이 `LiveActivityManager`를 호출해도 표시할 UI가 없어 조용히 no-op 한다.
struct HermesChatLiveActivity: Widget {
    var body: some WidgetConfiguration {
        ActivityConfiguration(for: HermesChatActivityAttributes.self) { context in
            // 잠금화면 / 배너 표시
            lockScreen(context)
                .activityBackgroundTint(Color.black.opacity(0.4))
                .activitySystemActionForegroundColor(.white)
        } dynamicIsland: { context in
            DynamicIsland {
                DynamicIslandExpandedRegion(.leading) {
                    Label(context.attributes.profileName, systemImage: phaseIcon(context.state.phase))
                        .font(.caption.bold())
                        .lineLimit(1)
                }
                DynamicIslandExpandedRegion(.trailing) {
                    Text(timerInterval: context.attributes.startedAt...Date.now.addingTimeInterval(3600),
                         countsDown: false)
                        .font(.caption2.monospacedDigit())
                        .frame(maxWidth: 44)
                }
                DynamicIslandExpandedRegion(.bottom) {
                    Text(bottomText(context))
                        .font(.footnote)
                        .lineLimit(2)
                        .frame(maxWidth: .infinity, alignment: .leading)
                }
            } compactLeading: {
                Image(systemName: phaseIcon(context.state.phase))
            } compactTrailing: {
                Text(timerInterval: context.attributes.startedAt...Date.now.addingTimeInterval(3600),
                     countsDown: false)
                    .font(.caption2.monospacedDigit())
                    .frame(maxWidth: 36)
            } minimal: {
                Image(systemName: phaseIcon(context.state.phase))
            }
        }
    }

    @ViewBuilder
    private func lockScreen(_ context: ActivityViewContext<HermesChatActivityAttributes>) -> some View {
        VStack(alignment: .leading, spacing: 6) {
            HStack {
                Label(context.attributes.profileName, systemImage: phaseIcon(context.state.phase))
                    .font(.subheadline.bold())
                Spacer()
                Text(timerInterval: context.attributes.startedAt...Date.now.addingTimeInterval(3600),
                     countsDown: false)
                    .font(.caption.monospacedDigit())
                    .foregroundStyle(.secondary)
            }
            Text(bottomText(context))
                .font(.footnote)
                .foregroundStyle(.secondary)
                .lineLimit(3)
                .frame(maxWidth: .infinity, alignment: .leading)
        }
        .padding()
    }

    private func phaseIcon(_ phase: HermesChatActivityAttributes.ContentState.Phase) -> String {
        switch phase {
        case .thinking: return "brain"
        case .streaming: return "ellipsis.bubble"
        case .done: return "checkmark.circle.fill"
        }
    }

    private func bottomText(_ context: ActivityViewContext<HermesChatActivityAttributes>) -> String {
        let preview = context.state.preview.trimmingCharacters(in: .whitespacesAndNewlines)
        if !preview.isEmpty { return preview }
        switch context.state.phase {
        case .thinking: return "생각하는 중…"
        case .streaming: return "응답 생성 중…"
        case .done: return "응답 완료"
        }
    }
}
