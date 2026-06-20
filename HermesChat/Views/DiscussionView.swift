import SwiftUI

/// Deep think 토론룸 — 프로필 보드의 "Deep think" 버튼으로 진입한다.
/// setup(참가자/주제/라운드) → running(발언 스트림) → finished(결론)를 한 화면에서 전환.
struct DiscussionView: View {
    @ObservedObject var appSettings: AppSettings
    @StateObject private var viewModel: DiscussionViewModel
    @Environment(\.dismiss) private var dismiss
    @State private var showStopConfirm = false

    init(appSettings: AppSettings) {
        self.appSettings = appSettings
        _viewModel = StateObject(wrappedValue: DiscussionViewModel(appSettings: appSettings))
    }

    var body: some View {
        NavigationStack {
            Group {
                switch viewModel.phase {
                case .setup:
                    DiscussionSetupView(viewModel: viewModel)
                case .running, .concluding, .finished, .failed:
                    DiscussionRoomView(viewModel: viewModel)
                }
            }
            .navigationTitle("Deep think")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarLeading) {
                    Button {
                        if viewModel.phase.isActive {
                            showStopConfirm = true
                        } else {
                            dismiss()
                        }
                    } label: {
                        Image(systemName: "xmark")
                    }
                }
            }
            .confirmationDialog(
                "discuss.stop.confirm",
                isPresented: $showStopConfirm,
                titleVisibility: .visible
            ) {
                Button("discuss.stop.action", role: .destructive) {
                    viewModel.stop()
                    dismiss()
                }
                Button("discuss.continue", role: .cancel) {}
            }
        }
        .environment(\.bridgeClient, appSettings.bridgeClient)
        .onChange(of: viewModel.phase.isActive) { _, active in
            // 토론 중 화면이 꺼지면 앱이 suspend되어 토론도 멈추므로 잠금 방지
            UIApplication.shared.isIdleTimerDisabled = active
        }
        .onDisappear {
            UIApplication.shared.isIdleTimerDisabled = false
        }
        .interactiveDismissDisabled(viewModel.phase.isActive)
    }
}

// MARK: - Setup

private struct DiscussionSetupView: View {
    @ObservedObject var viewModel: DiscussionViewModel

    private let chipColumns = [GridItem(.adaptive(minimum: 110), spacing: 8)]

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 20) {
                // 주제
                VStack(alignment: .leading, spacing: 6) {
                    sectionTitle(String(localized: "discuss.setup.topic"))
                    TextField("discuss.setup.topic.placeholder", text: $viewModel.topic, axis: .vertical)
                        .lineLimit(2...4)
                        .textFieldStyle(.roundedBorder)
                }

                // 참가자
                VStack(alignment: .leading, spacing: 6) {
                    sectionTitle(String(format: String(localized: "discuss.setup.participants %lld"), Int64(viewModel.selectedProfileIDs.count)))
                    Text("discuss.setup.participants.hint")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                    LazyVGrid(columns: chipColumns, alignment: .leading, spacing: 8) {
                        ForEach(viewModel.appSettings.profiles) { profile in
                            participantChip(profile)
                        }
                    }
                }

                // 진행 옵션
                VStack(alignment: .leading, spacing: 10) {
                    sectionTitle(String(localized: "discuss.setup.mode"))
                    Stepper(String(format: String(localized: "discuss.setup.rounds %lld"), Int64(viewModel.rounds)), value: $viewModel.rounds, in: 1...5)
                    moderatorPicker
                    Toggle("discuss.setup.tools", isOn: $viewModel.allowTools)
                    if viewModel.allowTools {
                        Text("discuss.setup.tools.hint")
                            .font(.caption)
                            .foregroundStyle(.orange)
                    }
                }

                // 시작
                VStack(spacing: 6) {
                    Button {
                        viewModel.start()
                    } label: {
                        Label("discuss.start", systemImage: "bubble.left.and.bubble.right.fill")
                            .frame(maxWidth: .infinity)
                    }
                    .buttonStyle(.borderedProminent)
                    .disabled(!viewModel.canStart)
                    if !viewModel.canStart {
                        Text("discuss.start.hint")
                            .font(.caption)
                            .foregroundStyle(.secondary)
                    }
                }

                // 지난 토론
                if !viewModel.savedDiscussions.isEmpty {
                    VStack(alignment: .leading, spacing: 6) {
                        sectionTitle(String(localized: "discuss.past"))
                        ForEach(viewModel.savedDiscussions) { saved in
                            savedRow(saved)
                        }
                    }
                }
            }
            .padding()
        }
        .scrollDismissesKeyboard(.interactively)
    }

    private func sectionTitle(_ text: String) -> some View {
        Text(text)
            .font(.headline)
    }

    private func participantChip(_ profile: HermesProfile) -> some View {
        let selected = viewModel.selectedProfileIDs.contains(profile.id)
        // 칩 색은 선택된 참가자 내 순서 — 토론방 발언 색과 일치
        let colorIndex = viewModel.selectedProfiles.firstIndex(of: profile) ?? 0
        let color = DiscussionPalette.color(at: colorIndex)
        return Button {
            viewModel.toggleProfile(profile)
        } label: {
            HStack(spacing: 5) {
                Image(systemName: selected ? "checkmark.circle.fill" : "circle")
                    .font(.caption)
                Text(profile.name)
                    .font(.subheadline.weight(selected ? .semibold : .regular))
                    .lineLimit(1)
            }
            .padding(.horizontal, 12)
            .padding(.vertical, 8)
            .frame(maxWidth: .infinity)
            .background(selected ? color.opacity(0.2) : Color(.secondarySystemBackground))
            .foregroundStyle(selected ? color : .secondary)
            .clipShape(Capsule())
            .overlay(
                Capsule().strokeBorder(selected ? color : .clear, lineWidth: 1)
            )
        }
        .buttonStyle(.plain)
    }

    private var moderatorPicker: some View {
        Picker("discuss.moderator", selection: $viewModel.moderatorID) {
            Text("discuss.moderator.first").tag(UUID?.none)
            ForEach(viewModel.selectedProfiles) { profile in
                Text(profile.name).tag(UUID?.some(profile.id))
            }
        }
    }

    private func savedRow(_ saved: SavedDiscussion) -> some View {
        NavigationLink {
            SavedDiscussionDetailView(discussion: saved)
        } label: {
            HStack {
                VStack(alignment: .leading, spacing: 3) {
                    Text(saved.topic)
                        .font(.subheadline.weight(.medium))
                        .lineLimit(1)
                        .foregroundStyle(.primary)
                    Text("\(saved.participantNames.joined(separator: ", ")) · \(saved.date.formatted(date: .abbreviated, time: .shortened))")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                        .lineLimit(1)
                }
                Spacer()
                Image(systemName: "chevron.right")
                    .font(.caption)
                    .foregroundStyle(.tertiary)
            }
            .padding(10)
            .background(Color(.secondarySystemBackground))
            .clipShape(RoundedRectangle(cornerRadius: 10))
        }
        .buttonStyle(.plain)
        .contextMenu {
            Button(role: .destructive) {
                viewModel.deleteSaved(id: saved.id)
            } label: {
                Label("common.delete", systemImage: "trash")
            }
        }
    }
}

// MARK: - 토론방 (running / finished)

private struct DiscussionRoomView: View {
    @ObservedObject var viewModel: DiscussionViewModel

    var body: some View {
        VStack(spacing: 0) {
            ScrollViewReader { proxy in
                ScrollView {
                    LazyVStack(alignment: .leading, spacing: 12) {
                        topicHeader
                        ForEach(viewModel.entries) { entry in
                            DiscussionEntryRow(entry: entry)
                                .id(entry.id)
                        }
                    }
                    .padding()
                }
                // 동시 라운드에서는 중간 카드도 자라므로 하단 고정 앵커로 따라간다
                // (사용자가 위로 스크롤하면 자동 해제). 새 entry 추가 시에는 명시적으로 하단 이동.
                .defaultScrollAnchor(.bottom)
                .onChange(of: viewModel.entries.count) { _, _ in
                    if let lastID = viewModel.entries.last?.id {
                        proxy.scrollTo(lastID, anchor: .bottom)
                    }
                }
            }
            Divider()
            bottomBar
        }
    }

    private var topicHeader: some View {
        VStack(alignment: .leading, spacing: 4) {
            Text("discuss.room.topic")
                .font(.caption)
                .foregroundStyle(.secondary)
            Text(viewModel.topic)
                .font(.subheadline.weight(.semibold))
        }
        .padding(10)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(Color(.secondarySystemBackground))
        .clipShape(RoundedRectangle(cornerRadius: 10))
    }

    @ViewBuilder
    private var bottomBar: some View {
        switch viewModel.phase {
        case .running, .concluding:
            HStack(spacing: 10) {
                ProgressView()
                Text(statusText)
                    .font(.footnote)
                    .foregroundStyle(.secondary)
                    .lineLimit(1)
                Spacer()
                Button(role: .destructive) {
                    viewModel.stop()
                } label: {
                    Label("discuss.room.stop", systemImage: "stop.fill")
                }
                .buttonStyle(.bordered)
            }
            .padding()
        case .finished:
            HStack(spacing: 12) {
                if let conclusion = viewModel.conclusionEntry {
                    Button {
                        UIPasteboard.general.string = MarkdownLite.plainText(from: conclusion.content)
                    } label: {
                        Label("discuss.room.copy.conclusion", systemImage: "doc.on.doc")
                    }
                    .buttonStyle(.bordered)
                }
                ShareLink(item: viewModel.shareText) {
                    Label("discuss.room.share", systemImage: "square.and.arrow.up")
                }
                .buttonStyle(.bordered)
                Spacer()
                Button {
                    viewModel.resetToSetup()
                } label: {
                    Label("discuss.room.new", systemImage: "plus.bubble")
                }
                .buttonStyle(.borderedProminent)
            }
            .padding()
        case .failed(let message):
            VStack(spacing: 10) {
                Label(message, systemImage: "exclamationmark.triangle.fill")
                    .font(.footnote)
                    .foregroundStyle(.orange)
                    .frame(maxWidth: .infinity, alignment: .leading)
                Button {
                    viewModel.resetToSetup()
                } label: {
                    Label("discuss.room.back.setup", systemImage: "arrow.uturn.backward")
                        .frame(maxWidth: .infinity)
                }
                .buttonStyle(.bordered)
            }
            .padding()
        case .setup:
            EmptyView()
        }
    }

    private var statusText: String {
        if case .concluding = viewModel.phase {
            return String(localized: "discuss.status.concluding")
        }
        if viewModel.speakingNames.count == 1, let name = viewModel.speakingNames.first {
            return String(format: String(localized: "discuss.status.speaking %@"), name)
        }
        if viewModel.speakingNames.count > 1 {
            return String(format: String(localized: "discuss.status.speaking.count %lld"), Int64(viewModel.speakingNames.count))
        }
        if case .running(let round, let total) = viewModel.phase {
            return String(format: String(localized: "discuss.status.round %lld %lld"), Int64(round), Int64(total))
        }
        return String(localized: "discuss.status.running")
    }
}

// MARK: - Entry 렌더링 (토론방과 지난 토론 상세가 공유)

private struct DiscussionEntryRow: View {
    let entry: DiscussionEntry

    var body: some View {
        switch entry.kind {
        case .roundMarker:
            Text(entry.content)
                .font(.caption.weight(.semibold))
                .foregroundStyle(.secondary)
                .padding(.horizontal, 12)
                .padding(.vertical, 5)
                .background(.ultraThinMaterial)
                .clipShape(Capsule())
                .frame(maxWidth: .infinity)
        case .system:
            Text(entry.content)
                .font(.caption)
                .foregroundStyle(.secondary)
                .frame(maxWidth: .infinity)
                .multilineTextAlignment(.center)
        case .statement:
            statementCard(highlighted: false)
        case .conclusion:
            statementCard(highlighted: true)
        }
    }

    private func statementCard(highlighted: Bool) -> some View {
        let color = DiscussionPalette.color(at: entry.colorIndex)
        return VStack(alignment: .leading, spacing: 6) {
            HStack(spacing: 6) {
                Circle()
                    .fill(color)
                    .frame(width: 8, height: 8)
                Text(highlighted ? String(format: String(localized: "discuss.conclusion.label %@"), entry.speakerName) : entry.speakerName)
                    .font(.subheadline.weight(.bold))
                    .foregroundStyle(color)
                if let round = entry.round {
                    Text("R\(round)")
                        .font(.caption2)
                        .foregroundStyle(.secondary)
                }
            }
            if entry.content.isEmpty {
                ProgressView()
                    .padding(.vertical, 4)
            } else {
                MarkdownText(content: entry.content)
                    .font(.subheadline)
            }
        }
        .padding(12)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(highlighted ? color.opacity(0.12) : Color(.secondarySystemBackground))
        .clipShape(RoundedRectangle(cornerRadius: 12))
        .overlay(
            RoundedRectangle(cornerRadius: 12)
                .strokeBorder(highlighted ? color.opacity(0.5) : .clear, lineWidth: 1)
        )
    }
}

// MARK: - 지난 토론 상세 (읽기 전용)

private struct SavedDiscussionDetailView: View {
    let discussion: SavedDiscussion

    var body: some View {
        ScrollView {
            LazyVStack(alignment: .leading, spacing: 12) {
                VStack(alignment: .leading, spacing: 4) {
                    Text("discuss.room.topic")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                    Text(discussion.topic)
                        .font(.subheadline.weight(.semibold))
                    Text("\(discussion.date.formatted(date: .abbreviated, time: .shortened)) · \(String(format: String(localized: "discuss.setup.rounds %lld"), Int64(discussion.rounds))) · \(discussion.moderatorName)")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
                .padding(10)
                .frame(maxWidth: .infinity, alignment: .leading)
                .background(Color(.secondarySystemBackground))
                .clipShape(RoundedRectangle(cornerRadius: 10))

                ForEach(discussion.entries) { entry in
                    DiscussionEntryRow(entry: entry)
                }
            }
            .padding()
        }
        .navigationTitle("discuss.past")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .topBarTrailing) {
                ShareLink(item: DiscussionViewModel.shareText(topic: discussion.topic, entries: discussion.entries)) {
                    Image(systemName: "square.and.arrow.up")
                }
            }
        }
    }
}
