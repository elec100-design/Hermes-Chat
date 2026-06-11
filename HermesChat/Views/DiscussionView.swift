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
                "토론이 진행 중입니다. 중단하고 나갈까요?",
                isPresented: $showStopConfirm,
                titleVisibility: .visible
            ) {
                Button("중단하고 나가기", role: .destructive) {
                    viewModel.stop()
                    dismiss()
                }
                Button("계속 진행", role: .cancel) {}
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
                    sectionTitle("토론 주제")
                    TextField("토론 주제를 입력하세요", text: $viewModel.topic, axis: .vertical)
                        .lineLimit(2...4)
                        .textFieldStyle(.roundedBorder)
                }

                // 참가자
                VStack(alignment: .leading, spacing: 6) {
                    sectionTitle("참가자 (\(viewModel.selectedProfileIDs.count)명 선택)")
                    Text("서로 다른 모델·성향의 에이전트를 모을수록 상호 검증 효과가 커집니다.")
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
                    sectionTitle("진행 방식")
                    Stepper("라운드 수: \(viewModel.rounds)", value: $viewModel.rounds, in: 1...5)
                    moderatorPicker
                    Toggle("도구 사용 허용", isOn: $viewModel.allowTools)
                    if viewModel.allowTools {
                        Text("에이전트가 웹 검색 등으로 근거를 찾습니다. 한 발언이 수 분까지 길어질 수 있습니다.")
                            .font(.caption)
                            .foregroundStyle(.orange)
                    }
                }

                // 시작
                VStack(spacing: 6) {
                    Button {
                        viewModel.start()
                    } label: {
                        Label("토론 시작", systemImage: "bubble.left.and.bubble.right.fill")
                            .frame(maxWidth: .infinity)
                    }
                    .buttonStyle(.borderedProminent)
                    .disabled(!viewModel.canStart)
                    if !viewModel.canStart {
                        Text("주제와 2명 이상의 참가자가 필요합니다.")
                            .font(.caption)
                            .foregroundStyle(.secondary)
                    }
                }

                // 지난 토론
                if !viewModel.savedDiscussions.isEmpty {
                    VStack(alignment: .leading, spacing: 6) {
                        sectionTitle("지난 토론")
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
        Picker("사회자 (결론 작성)", selection: $viewModel.moderatorID) {
            Text("첫 참가자").tag(UUID?.none)
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
                Label("삭제", systemImage: "trash")
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
            Text("주제")
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
                    Label("중지", systemImage: "stop.fill")
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
                        Label("결론 복사", systemImage: "doc.on.doc")
                    }
                    .buttonStyle(.bordered)
                }
                ShareLink(item: viewModel.shareText) {
                    Label("공유", systemImage: "square.and.arrow.up")
                }
                .buttonStyle(.bordered)
                Spacer()
                Button {
                    viewModel.resetToSetup()
                } label: {
                    Label("새 토론", systemImage: "plus.bubble")
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
                    Label("설정으로 돌아가기", systemImage: "arrow.uturn.backward")
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
            return "사회자가 결론을 정리하는 중..."
        }
        if viewModel.speakingNames.count == 1, let name = viewModel.speakingNames.first {
            return "\(name) 발언 중..."
        }
        if viewModel.speakingNames.count > 1 {
            return "\(viewModel.speakingNames.count)명 발언 중..."
        }
        if case .running(let round, let total) = viewModel.phase {
            return "라운드 \(round)/\(total) 진행 중..."
        }
        return "진행 중..."
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
                Text(highlighted ? "최종 결론 — \(entry.speakerName)" : entry.speakerName)
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
                    Text("주제")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                    Text(discussion.topic)
                        .font(.subheadline.weight(.semibold))
                    Text("\(discussion.date.formatted(date: .abbreviated, time: .shortened)) · 라운드 \(discussion.rounds) · 사회자 \(discussion.moderatorName)")
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
        .navigationTitle("지난 토론")
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
