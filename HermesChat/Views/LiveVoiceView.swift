import SwiftUI
import UIKit

/// Live 전체화면 (T-152/T-153) — 핸즈프리 음성 대화의 상태·자막·주 버튼을 크게 보여준다.
/// 음성 상태 머신은 `VoiceConversationController.shared`가 그대로 소유한다 — 여기는 표시와 입력만.
/// 최소화(chevron)는 dismiss만 하고 음성은 유지, 종료(xmark)는 음성을 끝낸 뒤 dismiss.
struct LiveVoiceView: View {
    @ObservedObject var viewModel: ChatViewModel
    @ObservedObject private var voice = VoiceConversationController.shared
    let title: String

    @Environment(\.dismiss) private var dismiss
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var pulse = false

    var body: some View {
        VStack(spacing: 0) {
            topBar
            Spacer(minLength: 8)
            statusCircle
            Text(statusText)
                .font(.title3.weight(.semibold))
                .padding(.top, 20)
                .accessibilityAddTraits(.isHeader)
            Spacer(minLength: 8)
            captions
            primaryButton
                .padding(.vertical, 24)
        }
        .padding(.horizontal, 20)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(Color(.systemBackground))
        .onAppear {
            // 화면이 켜져 있어야 Live 대화가 끊기지 않는다 (백그라운드/잠금은 오디오 세션이 유지)
            UIApplication.shared.isIdleTimerDisabled = true
            pulse = true
        }
        .onDisappear {
            UIApplication.shared.isIdleTimerDisabled = false
        }
    }

    // MARK: - 상단

    private var topBar: some View {
        HStack {
            Button {
                dismiss()  // 음성은 유지 — 최소화
            } label: {
                Image(systemName: "chevron.down")
                    .font(.title3.weight(.semibold))
                    .frame(width: 44, height: 44)
            }
            .accessibilityLabel("chat.live.minimize")

            Spacer()
            Text(title)
                .font(.headline)
                .lineLimit(1)
            Spacer()

            Button {
                voice.stop()
                dismiss()
            } label: {
                Image(systemName: "xmark.circle.fill")
                    .font(.title2)
                    .foregroundStyle(.secondary)
                    .frame(width: 44, height: 44)
            }
            .accessibilityLabel("chat.voice.stop")
        }
        .padding(.top, 8)
    }

    // MARK: - 상태 원

    private var statusCircle: some View {
        ZStack {
            Circle()
                .fill(circleColor.opacity(0.15))
                .frame(width: 180, height: 180)
                .scaleEffect(isPulsing ? 1.15 : 1)
                .animation(
                    isPulsing ? .easeInOut(duration: 0.9).repeatForever(autoreverses: true) : .easeOut(duration: 0.2),
                    value: isPulsing
                )
            Circle()
                .fill(circleColor.opacity(0.3))
                .frame(width: 130, height: 130)
            circleContent
        }
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(statusText)
    }

    @ViewBuilder
    private var circleContent: some View {
        switch voice.state {
        case .waitingResponse:
            ProgressView()
                .controlSize(.large)
        case .speaking:
            Image(systemName: "waveform")
                .font(.system(size: 48, weight: .semibold))
                .foregroundStyle(circleColor)
                .symbolEffect(.variableColor.iterative, isActive: !reduceMotion)
        case .listening:
            Image(systemName: "mic.fill")
                .font(.system(size: 44, weight: .semibold))
                .foregroundStyle(circleColor)
        case .idle:
            Image(systemName: "waveform")
                .font(.system(size: 44, weight: .semibold))
                .foregroundStyle(circleColor)
        }
    }

    private var circleColor: Color {
        voice.state == .listening ? .red : .accentColor
    }

    /// 듣는 중에만 맥동 — 나머지 상태에선 고정 (동작 줄이기 설정 존중)
    private var isPulsing: Bool {
        voice.state == .listening && pulse && !reduceMotion
    }

    private var statusText: String {
        switch voice.state {
        case .listening: return String(localized: "chat.voice.listening")
        case .waitingResponse: return String(localized: "chat.voice.thinking")
        case .speaking: return String(localized: "chat.voice.speaking")
        case .idle: return ""
        }
    }

    // MARK: - 자막

    private var captions: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text(userCaption)
                .font(.callout)
                .foregroundStyle(.secondary)
                .lineLimit(3)
                .truncationMode(.head)
                .frame(maxWidth: .infinity, alignment: .leading)

            ScrollViewReader { proxy in
                ScrollView {
                    VStack(alignment: .leading, spacing: 0) {
                        Text(assistantCaption)
                            .font(.body)
                            .frame(maxWidth: .infinity, alignment: .leading)
                        Color.clear.frame(height: 1).id("live-bottom")
                    }
                }
                .onChange(of: assistantCaption) { _, _ in
                    proxy.scrollTo("live-bottom", anchor: .bottom)
                }
            }
            .frame(maxHeight: 220)
        }
    }

    /// 사용자 자막: 청취 중이면 실시간 받아쓰기, 아니면 마지막 user 메시지
    private var userCaption: String {
        if voice.state == .listening { return voice.liveTranscript }
        return viewModel.messages.last(where: { $0.role == .user })?.content ?? ""
    }

    /// 응답 자막: 마지막 assistant 메시지의 평문 (think 제거)
    private var assistantCaption: String {
        guard let last = viewModel.messages.last(where: { $0.role == .assistant }) else { return "" }
        let visible = MarkdownLite.strippingThink(last.content)
        return MarkdownLite.plainText(from: visible)
            .trimmingCharacters(in: .whitespacesAndNewlines)
    }

    // MARK: - 주 버튼

    @ViewBuilder
    private var primaryButton: some View {
        switch voice.state {
        case .listening:
            actionButton("chat.live.send_now", systemImage: "paperplane.fill", tint: .red) {
                voice.primaryAction()
            }
        case .speaking:
            actionButton("chat.live.interrupt", systemImage: "hand.raised.fill", tint: .accentColor) {
                voice.interrupt()
            }
        case .waitingResponse:
            actionButton("chat.stop", systemImage: "stop.circle.fill", tint: .accentColor) {
                voice.interrupt()
                viewModel.cancelSend()
            }
        case .idle:
            EmptyView()
        }
    }

    private func actionButton(
        _ key: LocalizedStringKey,
        systemImage: String,
        tint: Color,
        action: @escaping () -> Void
    ) -> some View {
        Button(action: action) {
            Label(key, systemImage: systemImage)
                .font(.headline)
                .frame(maxWidth: .infinity, minHeight: 28)
        }
        .buttonStyle(.borderedProminent)
        .tint(tint)
        .controlSize(.large)
    }
}
