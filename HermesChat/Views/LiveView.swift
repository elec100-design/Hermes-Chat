import SwiftUI

/// 네비게이션 경로 — LiveSession은 ChatMessage(비 Hashable)를 품어 Hashable이 어려우므로 id로 라우팅.
private enum LiveRoute: Hashable {
    case new
    case existing(UUID)
}

/// Live 탭 루트 (T-155/156): 저장된 Gemini Live 대화 목록 + 검색 + 새 대화.
struct LiveView: View {
    @ObservedObject var appSettings: AppSettings
    @ObservedObject private var store = LiveSessionStore.shared
    @State private var search = ""
    @State private var path = NavigationPath()

    private var keyMissing: Bool {
        appSettings.geminiAPIKey.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
    }

    private var filtered: [LiveSession] {
        guard !search.isEmpty else { return store.sessions }
        return store.sessions.filter { s in
            s.title.localizedCaseInsensitiveContains(search)
                || s.messages.contains { $0.content.localizedCaseInsensitiveContains(search) }
        }
    }

    var body: some View {
        NavigationStack(path: $path) {
            Group {
                if keyMissing {
                    keyMissingView
                } else if store.sessions.isEmpty {
                    emptyView
                } else {
                    list
                }
            }
            .navigationTitle("Live")
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    Button { path.append(LiveRoute.new) } label: {
                        Image(systemName: "plus")
                    }
                    .disabled(keyMissing)
                    .accessibilityLabel("새 대화")
                }
            }
            .navigationDestination(for: LiveRoute.self) { route in
                switch route {
                case .new:
                    LiveConversationView(appSettings: appSettings, existingID: nil)
                case .existing(let id):
                    LiveConversationView(appSettings: appSettings, existingID: id)
                }
            }
        }
    }

    private var list: some View {
        List {
            ForEach(filtered) { session in
                Button { path.append(LiveRoute.existing(session.id)) } label: {
                    VStack(alignment: .leading, spacing: 4) {
                        Text(session.title).font(.headline).foregroundStyle(.primary)
                        HStack(spacing: 6) {
                            Image(systemName: "waveform")
                            Text(session.voice)
                            Text("· \(session.messages.count)개")
                            Spacer()
                            Text(session.updatedAt, style: .date)
                        }
                        .font(.caption)
                        .foregroundStyle(.secondary)
                    }
                }
            }
            .onDelete { offsets in store.delete(atOffsets: offsets, in: filtered) }
        }
        .listStyle(.insetGrouped)
        .searchable(text: $search, prompt: "대화 검색")
    }

    private var emptyView: some View {
        ContentUnavailableView {
            Label("Live 대화", systemImage: "waveform")
        } description: {
            Text("Gemini Live로 자연스러운 음성 대화를 시작하세요.\n대화는 기기에 저장되어 다시 불러올 수 있습니다.")
        } actions: {
            Button("새 대화 시작") { path.append(LiveRoute.new) }
                .buttonStyle(.borderedProminent)
        }
    }

    private var keyMissingView: some View {
        ContentUnavailableView {
            Label("Gemini 키가 필요합니다", systemImage: "key.slash")
        } description: {
            Text("설정 > Gemini Live에서 Google AI API 키를 입력하면 Live 음성 대화를 쓸 수 있습니다.\n오디오는 Google로 전송됩니다.")
        }
    }
}

/// 실제 Live 대화 화면 — 챗 버블 + 통화 제어 (T-155/156).
struct LiveConversationView: View {
    @ObservedObject var appSettings: AppSettings
    let existingID: UUID?
    @StateObject private var vm: LiveViewModel
    @State private var selectedVoice: GeminiVoice

    init(appSettings: AppSettings, existingID: UUID?) {
        self.appSettings = appSettings
        self.existingID = existingID
        _vm = StateObject(wrappedValue: LiveViewModel(appSettings: appSettings))
        _selectedVoice = State(initialValue: GeminiVoice(rawValue: appSettings.geminiLiveVoice) ?? .aoede)
    }

    var body: some View {
        VStack(spacing: 0) {
            messageList
            Divider()
            controls
        }
        .navigationTitle("Live 대화")
        .navigationBarTitleDisplayMode(.inline)
        .onAppear {
            if let id = existingID, let s = LiveSessionStore.shared.session(for: id) {
                vm.load(s)
                selectedVoice = GeminiVoice(rawValue: s.voice) ?? .aoede
            }
        }
        .onDisappear { vm.disconnect() }
    }

    private var messageList: some View {
        ScrollViewReader { proxy in
            ScrollView {
                LazyVStack(spacing: 10) {
                    if vm.messages.isEmpty {
                        Text("‘통화 시작’을 누르고 말씀하세요.")
                            .font(.callout)
                            .foregroundStyle(.secondary)
                            .padding(.top, 60)
                    }
                    ForEach(vm.messages) { message in
                        MessageBubble(message: message).id(message.id)
                    }
                    Color.clear.frame(height: 1).id("bottom")
                }
                .padding(.horizontal, 12)
                .padding(.vertical, 10)
            }
            .onChange(of: vm.messages.count) { _, _ in
                withAnimation { proxy.scrollTo("bottom", anchor: .bottom) }
            }
        }
    }

    @ViewBuilder
    private var controls: some View {
        VStack(spacing: 12) {
            if let banner = vm.errorBanner {
                Text(banner)
                    .font(.footnote)
                    .foregroundStyle(.red)
                    .multilineTextAlignment(.center)
                    .frame(maxWidth: .infinity)
            }

            if case .disconnected = vm.state {
                Picker("음성", selection: $selectedVoice) {
                    ForEach(GeminiVoice.allCases) { v in
                        Text("\(v.rawValue) · \(v.subtitle)").tag(v)
                    }
                }
                .pickerStyle(.menu)
            }

            HStack(spacing: 12) {
                statusOrb
                Text(statusText).font(.subheadline).foregroundStyle(.secondary)
                Spacer()
                callButton
            }
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 12)
        .background(.bar)
    }

    private var statusOrb: some View {
        Circle()
            .fill(orbColor)
            .frame(width: 14, height: 14)
            .scaleEffect(vm.state == .speaking ? 1.3 : 1.0)
            .animation(.easeInOut(duration: 0.4).repeatForever(autoreverses: true), value: vm.state)
    }

    private var orbColor: Color {
        switch vm.state {
        case .listening: return .green
        case .speaking:  return .blue
        case .connecting: return .orange
        case .error:     return .red
        case .disconnected: return .gray
        }
    }

    private var statusText: String {
        switch vm.state {
        case .disconnected: return "대기 중"
        case .connecting:   return "연결 중…"
        case .listening:    return "듣는 중 — 말씀하세요"
        case .speaking:     return "말하는 중…"
        case .error:        return "오류"
        }
    }

    @ViewBuilder
    private var callButton: some View {
        if vm.isConnected {
            Button(role: .destructive) { vm.disconnect() } label: {
                Label("종료", systemImage: "phone.down.fill")
            }
            .buttonStyle(.borderedProminent)
            .tint(.red)
        } else {
            Button {
                appSettings.geminiLiveVoice = selectedVoice.rawValue
                vm.setVoice(selectedVoice.rawValue)
                vm.connect()
            } label: {
                Label("통화 시작", systemImage: "phone.fill")
            }
            .buttonStyle(.borderedProminent)
        }
    }
}
