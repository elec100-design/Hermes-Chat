import SwiftUI

/// 봇 탭 — 메신저형 홈. 봇(1:1 대화)과 채널(여러 봇이 모인 방)을 한 목록에서 고른다.
/// 주기 폴링은 없다: 화면이 보일 때와 당겨서 새로고침할 때만 서버를 읽는다.
struct BotsView: View {
    @ObservedObject var appSettings: AppSettings
    @Binding var selectedTab: AppTab

    /// 채팅 화면으로 가는 목적지 (봇 DM 또는 채널)
    private struct ChatTarget: Hashable {
        let channelId: Int
        let title: String
        let isChannel: Bool
        let members: [BotMember]
    }

    @State private var bots: [Bot] = []
    @State private var channels: [BotChannel] = []
    @State private var pendingApprovals = 0
    @State private var isLoading = false
    @State private var loaded = false
    @State private var errorMessage: String?
    @State private var channelsError: String?
    @State private var showNewChannel = false

    var body: some View {
        NavigationStack {
            Group {
                if let client = appSettings.botClient {
                    content(client: client)
                } else {
                    placeholder
                }
            }
            .navigationTitle("tab.bots")
            .toolbar {
                if let client = appSettings.botClient {
                    ToolbarItemGroup(placement: .topBarTrailing) {
                        NavigationLink {
                            BotApprovalsView(client: client) { pendingApprovals = $0 }
                        } label: {
                            Image(systemName: "checkmark.seal")
                                .overlay(alignment: .topTrailing) { approvalBadge }
                        }
                        .accessibilityLabel("bots.approvals")
                        Button {
                            showNewChannel = true
                        } label: {
                            Image(systemName: "plus")
                        }
                        .accessibilityLabel("bots.newchannel.title")
                    }
                }
            }
            .navigationDestination(for: ChatTarget.self) { target in
                if let client = appSettings.botClient {
                    BotChatView(
                        appSettings: appSettings,
                        client: client,
                        channelId: target.channelId,
                        title: target.title,
                        isChannel: target.isChannel,
                        members: target.members
                    )
                }
            }
            .sheet(isPresented: $showNewChannel) {
                if let client = appSettings.botClient {
                    NewChannelSheet(client: client, bots: bots) {
                        Task { await load() }
                    }
                }
            }
            // 주소가 바뀌면(설정에서 입력 후 돌아온 경우 포함) 다시 읽는다
            .task(id: appSettings.botHost) { await load() }
        }
    }

    // MARK: - 미설정 안내

    private var placeholder: some View {
        VStack(spacing: 14) {
            Image(systemName: "bubble.left.and.text.bubble.right")
                .font(.system(size: 44))
                .foregroundStyle(.secondary)
            Text("bots.placeholder.title").font(.headline)
            Text("bots.placeholder.desc")
                .font(.subheadline)
                .foregroundStyle(.secondary)
                .multilineTextAlignment(.center)
            Button("bots.open_settings") { selectedTab = .settings }
                .buttonStyle(.borderedProminent)
        }
        .padding(32)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
    }

    // MARK: - 목록

    private func content(client: BotClient) -> some View {
        List {
            if let errorMessage {
                Section {
                    HStack(spacing: 8) {
                        Image(systemName: "exclamationmark.triangle.fill").foregroundStyle(.orange)
                        Text(verbatim: errorMessage).font(.footnote)
                        Spacer(minLength: 0)
                        Button("common.retry") { Task { await load() } }
                            .buttonStyle(.borderless)
                    }
                }
            }

            Section("bots.section.bots") {
                if bots.isEmpty && loaded && errorMessage == nil {
                    Text("bots.empty.bots").foregroundStyle(.secondary)
                }
                ForEach(bots) { bot in
                    if let dm = bot.dmChannelId {
                        NavigationLink(value: ChatTarget(
                            channelId: dm, title: bot.displayName, isChannel: false, members: []
                        )) { botRow(bot) }
                    } else {
                        botRow(bot)  // DM 채널이 아직 없는 봇은 열 수 없다
                    }
                }
            }

            Section("bots.section.channels") {
                if let channelsError {
                    Text(verbatim: channelsError).font(.footnote).foregroundStyle(.orange)
                } else if channels.isEmpty && loaded && errorMessage == nil {
                    Text("bots.empty.channels").foregroundStyle(.secondary)
                }
                ForEach(channels) { channel in
                    NavigationLink(value: ChatTarget(
                        channelId: channel.id, title: channel.title, isChannel: true, members: channel.members
                    )) { channelRow(channel) }
                }
            }
        }
        .refreshable { await load() }
    }

    private func botRow(_ bot: Bot) -> some View {
        HStack(spacing: 12) {
            Text(verbatim: bot.avatarText)
                .font(.title2)
                .frame(width: 42, height: 42)
                .background(Color.secondary.opacity(0.15))
                .clipShape(Circle())
            VStack(alignment: .leading, spacing: 2) {
                HStack(spacing: 6) {
                    Text(verbatim: bot.displayName).font(.headline)
                    Circle()
                        .fill(bot.isOnline ? Color.green : Color.gray)
                        .frame(width: 8, height: 8)
                        .accessibilityLabel(LocalizedStringKey(bot.isOnline ? "bots.online" : "bots.offline"))
                }
                Text(verbatim: bot.previewLine)
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
                    .lineLimit(1)
            }
        }
        .padding(.vertical, 2)
    }

    private func channelRow(_ channel: BotChannel) -> some View {
        VStack(alignment: .leading, spacing: 3) {
            Text(verbatim: channel.title).font(.headline)
            if !channel.members.isEmpty {
                Text(verbatim: channel.members.map { "@\($0.slug)" }.joined(separator: " "))
                    .font(.caption)
                    .foregroundStyle(.tint)
                    .lineLimit(1)
            }
            if let preview = channel.preview, !preview.isEmpty {
                Text(verbatim: preview)
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
                    .lineLimit(1)
            }
        }
        .padding(.vertical, 2)
    }

    @ViewBuilder
    private var approvalBadge: some View {
        if pendingApprovals > 0 {
            Text(verbatim: "\(pendingApprovals)")
                .font(.system(size: 10, weight: .bold))
                .foregroundStyle(.white)
                .padding(.horizontal, 5)
                .padding(.vertical, 1)
                .background(Color.red)
                .clipShape(Capsule())
                .offset(x: 10, y: -8)
        }
    }

    // MARK: - 로드

    /// 봇 목록이 실패하면 화면 오류로, 채널/승인은 각자 강등한다
    /// (구버전 서버엔 /api/channels 가 없을 수 있다 — 봇 대화는 계속 쓸 수 있게).
    private func load() async {
        guard let client = appSettings.botClient else { return }
        isLoading = true
        defer { isLoading = false }
        do {
            bots = try await client.fetchBots()
            errorMessage = nil
        } catch {
            errorMessage = error.localizedDescription
            loaded = true
            return
        }
        do {
            channels = try await client.fetchChannels()
            channelsError = nil
        } catch {
            channels = []
            channelsError = error.localizedDescription
        }
        if let pending = try? await client.fetchApprovals(status: "pending") {
            pendingApprovals = pending.count
        }
        loaded = true
    }
}

// MARK: - 새 채널

private struct NewChannelSheet: View {
    let client: BotClient
    let bots: [Bot]
    let onCreated: () -> Void

    @Environment(\.dismiss) private var dismiss
    @State private var title = ""
    @State private var selected: Set<Int> = []
    @State private var isCreating = false
    @State private var errorMessage: String?

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    TextField("bots.newchannel.name", text: $title)
                }
                Section("bots.newchannel.members") {
                    ForEach(bots) { bot in
                        Button {
                            if selected.contains(bot.id) { selected.remove(bot.id) } else { selected.insert(bot.id) }
                        } label: {
                            HStack {
                                Text(verbatim: "\(bot.avatarText) \(bot.displayName)")
                                    .foregroundStyle(.primary)
                                Spacer()
                                if selected.contains(bot.id) {
                                    Image(systemName: "checkmark").foregroundStyle(.tint)
                                }
                            }
                        }
                    }
                }
                if let errorMessage {
                    Section {
                        Text(verbatim: errorMessage).font(.footnote).foregroundStyle(.red)
                    }
                }
            }
            .navigationTitle("bots.newchannel.title")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("common.cancel") { dismiss() }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("bots.newchannel.create") { create() }
                        .disabled(isCreating || title.trimmingCharacters(in: .whitespaces).isEmpty)
                }
            }
        }
    }

    private func create() {
        isCreating = true
        errorMessage = nil
        let name = title.trimmingCharacters(in: .whitespaces)
        // 목록 순서를 유지해 멤버 id 를 보낸다
        let ids = bots.map(\.id).filter { selected.contains($0) }
        Task {
            do {
                try await client.createChannel(title: name, botIds: ids)
                onCreated()
                dismiss()
            } catch {
                errorMessage = error.localizedDescription
            }
            isCreating = false
        }
    }
}
