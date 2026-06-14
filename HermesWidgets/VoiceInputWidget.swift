import AppIntents
import SwiftUI
import WidgetKit

/// 홈 화면 + 잠금화면 음성 입력 위젯 (T-135).
/// 버튼을 누르면 `StartVoiceInputIntent`가 실행되어(openAppWhenRun) 앱이 포그라운드로 뜨고
/// 음성 대기 모드로 진입한다. 인터랙티브 위젯 Button(intent:)은 iOS 17+.
struct VoiceInputWidget: Widget {
    let kind = "VoiceInputWidget"

    var body: some WidgetConfiguration {
        StaticConfiguration(kind: kind, provider: VoiceInputProvider()) { _ in
            VoiceInputWidgetView()
        }
        .configurationDisplayName("Hermes 음성 입력")
        .description("탭하면 Hermes Chat을 열고 음성 입력 대기 모드로 들어갑니다.")
        .supportedFamilies([.systemSmall, .accessoryCircular, .accessoryRectangular])
    }
}

struct VoiceInputEntry: TimelineEntry {
    let date: Date
}

struct VoiceInputProvider: TimelineProvider {
    func placeholder(in context: Context) -> VoiceInputEntry { VoiceInputEntry(date: .now) }

    func getSnapshot(in context: Context, completion: @escaping (VoiceInputEntry) -> Void) {
        completion(VoiceInputEntry(date: .now))
    }

    func getTimeline(in context: Context, completion: @escaping (Timeline<VoiceInputEntry>) -> Void) {
        // 정적 위젯 — 갱신 불필요. 단일 엔트리에 .never 정책.
        completion(Timeline(entries: [VoiceInputEntry(date: .now)], policy: .never))
    }
}

struct VoiceInputWidgetView: View {
    @Environment(\.widgetFamily) private var family

    var body: some View {
        switch family {
        case .accessoryCircular:
            Button(intent: StartVoiceInputIntent()) {
                Image(systemName: "waveform")
                    .font(.title2)
            }
            .buttonStyle(.plain)
            .containerBackground(.clear, for: .widget)
        case .accessoryRectangular:
            Button(intent: StartVoiceInputIntent()) {
                Label("Hermes 음성 입력", systemImage: "waveform")
                    .font(.headline)
            }
            .buttonStyle(.plain)
            .containerBackground(.clear, for: .widget)
        default:
            Button(intent: StartVoiceInputIntent()) {
                VStack(spacing: 8) {
                    Image(systemName: "waveform")
                        .font(.system(size: 34, weight: .semibold))
                    Text("음성 입력")
                        .font(.subheadline.bold())
                }
                .frame(maxWidth: .infinity, maxHeight: .infinity)
                .foregroundStyle(.white)
            }
            .buttonStyle(.plain)
            .containerBackground(for: .widget) {
                LinearGradient(
                    colors: [.accentColor, .accentColor.opacity(0.7)],
                    startPoint: .topLeading,
                    endPoint: .bottomTrailing
                )
            }
        }
    }
}
