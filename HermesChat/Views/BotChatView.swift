import SwiftUI

/// 봇 DM / 채널 공용 대화 화면.
/// 말풍선은 기존 `MessageBubble` 을 그대로 재사용한다(ChatMessage 로 변환).
/// 봇의 진행 중 응답은 `BotChatViewModel.streaming` 에서, 확정본은 서버 메시지 본문에서 온다.
struct BotChatView: View {
    @ObservedObject var appSettings: AppSettings
    let client: BotClient
    let channelId: Int
    let title: String
    let isChannel: Bool
    let members: [BotMember]

    @StateObject private var viewModel: BotChatViewModel
    @State private var showResetConfirm = false
    @State private var showInviteSheet = false

    init(
        appSettings: AppSettings,
        client: BotClient,
        channelId: Int,
        title: String,
        isChannel: Bool,
        members: [BotMember] = [],
        initialGuestBotMode: String? = nil
    ) {
        _appSettings = ObservedObject(wrappedValue: appSettings)
        self.client = client
        self.channelId = channelId
        self.title = title
        self.isChannel = isChannel
        self.members = members
        _viewModel = StateObject(
            wrappedValue: BotChatViewModel(
                client: client,
                channelId: channelId,
                isChannel: isChannel,
                myUserId: appSettings.botUserId,
                isGuest: appSettings.isGuestMode,
                guestBotMode: initialGuestBotMode
            )
        )
    }

    var body: some View {
        VStack(spacing: 0) {
            messageList
            statusLines
            if isChannel && !members.isEmpty && !viewModel.shouldHideMemberChips && !viewModel.isRevoked {
                memberChips
            }
            if isChannel && !viewModel.isRevoked {
                mentionPopup
            }
            inputBar
        }
        .navigationTitle(title)
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            if !appSettings.isGuestMode {
                ToolbarItemGroup(placement: .topBarTrailing) {
                    if isChannel {
                        Button {
                            showInviteSheet = true
                        } label: {
                            Image(systemName: "person.badge.plus")
                        }
                        .accessibilityLabel(LocalizedStringKey("channel.invite.manage"))
                    }
                    Button {
                        showResetConfirm = true
                    } label: {
                        Image(systemName: "arrow.counterclockwise")
                    }
                    .disabled(viewModel.isResettingContext || viewModel.isSending)
                    .accessibilityLabel(LocalizedStringKey("bot.chat.reset_context.title"))
                }
            }
        }
        .sheet(isPresented: $showInviteSheet) {
            ChannelInviteSheet(
                client: client,
                channelId: channelId,
                channelTitle: title,
                initialGuestBotMode: viewModel.guestBotMode ?? "read_only"
            ) { newMode in
                viewModel.guestBotMode = newMode
            }
        }
        .confirmationDialog(
            LocalizedStringKey("bot.chat.reset_context.title"),
            isPresented: $showResetConfirm,
            titleVisibility: .visible
        ) {
            Button("bot.chat.reset_context.confirm", role: .destructive) {
                Task { await viewModel.resetContext() }
            }
            Button("common.cancel", role: .cancel) {}
        } message: {
            Text("bot.chat.reset_context.message")
        }
        .task { await viewModel.load() }
        // 화면을 떠나면 수신만 끊는다 — 돌아오면 .task 의 load() 가 진행 중 잡을 다시 구독한다
        .onDisappear { viewModel.stopFollowing() }
    }

    // MARK: - 메시지 목록

    private var messageList: some View {
        ScrollViewReader { proxy in
            List {
                ForEach(viewModel.messages) { message in
                    row(message)
                        .listRowSeparator(.hidden)
                        .listRowInsets(.init(top: 6, leading: 0, bottom: 6, trailing: 0))
                        .listRowBackground(Color.clear)
                }
            }
            .listStyle(.plain)
            .refreshable { await viewModel.load() }
            // 말풍선 썸네일(ChatImageView)이 Bridge 로 이미지를 받도록 주입 (ChatView 와 동일)
            .environment(\.bridgeClient, appSettings.bridgeClient)
            .onChange(of: viewModel.messages.count) { scrollToBottom(proxy) }
            .onChange(of: viewModel.streaming) { scrollToBottom(proxy) }
            .overlay {
                if viewModel.messages.isEmpty && !viewModel.isLoading && viewModel.errorMessage == nil {
                    Text(LocalizedStringKey(isChannel ? "bot.chat.empty.channel" : "bot.chat.empty.dm"))
                        .font(.subheadline)
                        .foregroundStyle(.secondary)
                        .multilineTextAlignment(.center)
                        .padding(32)
                }
            }
        }
    }

    private func scrollToBottom(_ proxy: ScrollViewProxy) {
        guard let last = viewModel.messages.last?.id else { return }
        withAnimation(.easeInOut) { proxy.scrollTo(last, anchor: .bottom) }
    }

    @ViewBuilder
    private func row(_ message: BotMessage) -> some View {
        if message.isSystem {
            Text(message.body)
                .font(.caption)
                .foregroundStyle(.secondary)
                .multilineTextAlignment(.center)
                .frame(maxWidth: .infinity)
                .padding(.horizontal, 24)
        } else {
            let mine = viewModel.isMessageFromMe(message)
            VStack(alignment: mine ? .trailing : .leading, spacing: 2) {
                // 채널에서는 타인/봇 발화자 표시
                if isChannel && !mine {
                    if message.isUser {
                        Text(verbatim: "🧑 \(senderName(message))")
                            .font(.caption)
                            .foregroundStyle(.secondary)
                            .padding(.horizontal, 30)
                    } else {
                        Text(verbatim: "\(senderAvatar(message)) \(senderName(message))")
                            .font(.caption)
                            .foregroundStyle(.secondary)
                            .padding(.horizontal, 30)
                    }
                }
                MessageBubble(message: chatMessage(for: message, isMine: mine))
                if let atts = Self.parseAttachments(message.attachments), !atts.isEmpty {
                    BotChannelAttachmentStrip(client: client, channelId: channelId, items: atts, alignRight: mine)
                }
            }
        }
    }

    private func senderAvatar(_ message: BotMessage) -> String {
        if let avatar = message.avatar, !avatar.isEmpty { return avatar }
        return "🤖"
    }

    private func senderName(_ message: BotMessage) -> String {
        if let name = message.displayName, !name.isEmpty { return name }
        if let author = message.author, !author.isEmpty { return author }
        return message.isUser ? NSLocalizedString("channel.member.guest", comment: "") : "bot"
    }

    /// 표시 텍스트: 진행 중이면 스트림 버퍼, 실패면 경고 + 오류, 그 외 확정 본문
    private func chatMessage(for message: BotMessage, isMine: Bool) -> ChatMessage {
        let text: String
        if message.isUser {
            text = message.body
        } else if message.isInFlight {
            text = viewModel.streaming[message.id] ?? ""
        } else if message.status == "failed" {
            text = "⚠ " + (message.error ?? "")
        } else {
            text = message.body
        }
        return ChatMessage(
            id: message.uuid,
            role: isMine ? .user : .assistant,
            content: text
        )
    }

    // MARK: - 상태 줄 (오류/안내)

    @ViewBuilder
    private var statusLines: some View {
        if let error = viewModel.errorMessage {
            HStack(spacing: 8) {
                Image(systemName: "exclamationmark.triangle.fill").foregroundStyle(.orange)
                Text(verbatim: error).lineLimit(3)
                Spacer(minLength: 0)
                Button("common.retry") { Task { await viewModel.load() } }
            }
            .font(.footnote)
            .padding(.horizontal, 16)
            .padding(.vertical, 6)
        }
        if let notice = viewModel.notice {
            Text(verbatim: notice)
                .font(.footnote)
                .foregroundStyle(.secondary)
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(.horizontal, 16)
                .padding(.vertical, 6)
        }
    }

    // MARK: - 입력

    /// 채널 멤버 @slug 칩 — 탭하면 입력창에 `@slug ` 를 넣는다 (멘션이 있어야 봇이 답한다)
    private var memberChips: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 8) {
                ForEach(members) { member in
                    Button {
                        insertMention(member.slug)
                    } label: {
                        Text(verbatim: "@\(member.slug)")
                            .font(.footnote)
                            .padding(.horizontal, 10)
                            .padding(.vertical, 5)
                            .background(Color.accentColor.opacity(0.15))
                            .clipShape(Capsule())
                    }
                    .buttonStyle(.plain)
                }
            }
            .padding(.horizontal, 16)
        }
        .padding(.top, 6)
    }

    private func insertMention(_ slug: String) {
        if !viewModel.input.isEmpty && !viewModel.input.hasSuffix(" ") {
            viewModel.input += " "
        }
        viewModel.input += "@\(slug) "
    }

    @ViewBuilder
    private var mentionPopup: some View {
        if let query = MentionAutocomplete.extractQuery(from: viewModel.input) {
            let candidates = mentionCandidates(matching: query.prefix)
            if !candidates.isEmpty {
                VStack(alignment: .leading, spacing: 0) {
                    Text("channel.mention.popup_title")
                        .font(.caption2)
                        .foregroundStyle(.secondary)
                        .padding(.horizontal, 12)
                        .padding(.top, 8)
                        .padding(.bottom, 4)
                    ScrollView {
                        LazyVStack(alignment: .leading, spacing: 0) {
                            ForEach(candidates) { cand in
                                Button {
                                    viewModel.input = MentionAutocomplete.replaceQuery(
                                        query, with: cand.handle, in: viewModel.input
                                    )
                                } label: {
                                    HStack(spacing: 10) {
                                        Text(cand.avatar)
                                        Text("@\(cand.handle)")
                                            .font(.subheadline.weight(.semibold))
                                        Text(cand.subtitle)
                                            .font(.caption)
                                            .foregroundStyle(.secondary)
                                        Spacer()
                                    }
                                    .padding(.horizontal, 12)
                                    .padding(.vertical, 8)
                                    .contentShape(Rectangle())
                                }
                                .buttonStyle(.plain)
                                Divider()
                            }
                        }
                    }
                    .frame(maxHeight: 170)
                }
                .background(Color(.secondarySystemBackground))
                .clipShape(RoundedRectangle(cornerRadius: 12))
                .overlay(
                    RoundedRectangle(cornerRadius: 12)
                        .stroke(Color(.separator), lineWidth: 0.5)
                )
                .shadow(color: .black.opacity(0.08), radius: 6, y: 2)
                .padding(.horizontal, 12)
                .padding(.top, 6)
            }
        }
    }

    private struct MentionCandidateItem: Identifiable {
        let id: String
        let handle: String
        let subtitle: String
        let avatar: String
    }

    private func mentionCandidates(matching prefix: String) -> [MentionCandidateItem] {
        var items: [MentionCandidateItem] = []
        if !viewModel.shouldHideMemberChips {
            for m in members {
                items.append(MentionCandidateItem(
                    id: "bot-\(m.id)",
                    handle: m.slug,
                    subtitle: m.displayName,
                    avatar: m.avatarText
                ))
            }
        }
        let myId = viewModel.currentUserId
        for u in viewModel.humanMembers where myId == 0 || u.id != myId {
            items.append(MentionCandidateItem(
                id: "user-\(u.id)",
                handle: u.displayName,
                subtitle: u.role == "owner"
                    ? NSLocalizedString("channel.member.host", comment: "")
                    : NSLocalizedString("channel.member.guest", comment: ""),
                avatar: "🧑"
            ))
        }
        let q = prefix.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
        guard !q.isEmpty else { return items }
        return items.filter {
            $0.handle.lowercased().contains(q) || $0.subtitle.lowercased().contains(q)
        }
    }

    private var inputBar: some View {
        HStack(alignment: .bottom, spacing: 12) {
            TextField("chat.message.placeholder", text: $viewModel.input, axis: .vertical)
                .textFieldStyle(.roundedBorder)
                .lineLimit(1...5)
                .disabled(viewModel.isRevoked)
            Button {
                Task { await viewModel.send() }
            } label: {
                Image(systemName: "paperplane.fill")
                    .font(.system(size: 18, weight: .semibold))
            }
            .disabled(
                viewModel.isRevoked
                    || viewModel.isSending
                    || viewModel.input.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
            )
            .accessibilityLabel(LocalizedStringKey("Send"))
        }
        .padding(.horizontal)
        .padding(.vertical, 8)
        .background(.thinMaterial)
    }

    private static func parseAttachments(_ raw: String?) -> [BotChannelAttachmentItem]? {
        guard let raw, !raw.isEmpty, let data = raw.data(using: .utf8) else { return nil }
        return try? JSONDecoder().decode([BotChannelAttachmentItem].self, from: data)
    }
}

// MARK: - 채널 첨부 이미지/파일 스트립 (게스트 Bridge 없음 대응)

private struct BotChannelAttachmentItem: Decodable, Identifiable {
    let name: String
    let path: String
    let mime: String?

    var id: String { path }
    var isImage: Bool {
        if let mime, mime.hasPrefix("image/") { return true }
        let lower = (name + path).lowercased()
        return lower.hasSuffix(".png") || lower.hasSuffix(".jpg") || lower.hasSuffix(".jpeg")
            || lower.hasSuffix(".webp") || lower.hasSuffix(".gif")
    }
}

private struct BotChannelAttachmentStrip: View {
    let client: BotClient
    let channelId: Int
    let items: [BotChannelAttachmentItem]
    let alignRight: Bool

    var body: some View {
        HStack {
            if alignRight { Spacer(minLength: 40) }
            VStack(alignment: alignRight ? .trailing : .leading, spacing: 6) {
                ForEach(items) { item in
                    if item.isImage {
                        BotChannelAttachmentImage(client: client, channelId: channelId, item: item)
                    } else {
                        Label(item.name, systemImage: "doc")
                            .font(.caption)
                            .padding(.horizontal, 10)
                            .padding(.vertical, 6)
                            .background(Color(.tertiarySystemBackground))
                            .clipShape(RoundedRectangle(cornerRadius: 8))
                    }
                }
            }
            if !alignRight { Spacer(minLength: 40) }
        }
        .padding(.horizontal, 20)
    }
}

private struct BotChannelAttachmentImage: View {
    let client: BotClient
    let channelId: Int
    let item: BotChannelAttachmentItem

    @State private var imageData: Data?

    var body: some View {
        Group {
            if let imageData, let uiImage = UIImage(data: imageData) {
                Image(uiImage: uiImage)
                    .resizable()
                    .scaledToFit()
                    .frame(maxWidth: 220, maxHeight: 220)
                    .clipShape(RoundedRectangle(cornerRadius: 10))
            } else {
                Label(item.name, systemImage: "photo")
                    .font(.caption)
                    .padding(.horizontal, 10)
                    .padding(.vertical, 6)
                    .background(Color(.tertiarySystemBackground))
                    .clipShape(RoundedRectangle(cornerRadius: 8))
            }
        }
        .task(id: item.path) {
            if let data = try? await client.fetchChannelAttachment(channelId: channelId, path: item.path) {
                imageData = data
            }
        }
    }
}

// MARK: - 오너용 채널 초대 · 멤버 관리 시트 (P34 / T-227)

private struct ChannelInviteSheet: View {
    let client: BotClient
    let channelId: Int
    let channelTitle: String
    let initialGuestBotMode: String
    let onGuestBotModeChanged: (String) -> Void

    @Environment(\.dismiss) private var dismiss
    @State private var guestBotMode: String = "read_only"
    @State private var invites: [ChannelInvite] = []
    @State private var members: [ChannelHumanMember] = []
    @State private var labelInput = ""
    @State private var expiresHours: Int = 168
    @State private var maxUsesChoice: Int = 0
    @State private var isLoading = false
    @State private var isCreating = false
    @State private var statusBanner: String?
    @State private var errorMessage: String?

    init(
        client: BotClient,
        channelId: Int,
        channelTitle: String,
        initialGuestBotMode: String,
        onGuestBotModeChanged: @escaping (String) -> Void
    ) {
        self.client = client
        self.channelId = channelId
        self.channelTitle = channelTitle
        self.initialGuestBotMode = initialGuestBotMode
        self.onGuestBotModeChanged = onGuestBotModeChanged
        _guestBotMode = State(initialValue: initialGuestBotMode.isEmpty ? "read_only" : initialGuestBotMode)
    }

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    Picker("channel.guest_bot_mode", selection: $guestBotMode) {
                        Text("channel.guest_bot_mode.read_only").tag("read_only")
                        Text("channel.guest_bot_mode.mention_off").tag("mention_off")
                        Text("channel.guest_bot_mode.full").tag("full")
                    }
                    .onChange(of: guestBotMode) { _, newValue in
                        Task { await updateBotMode(newValue) }
                    }
                } header: {
                    Text("channel.guest_bot_mode")
                } footer: {
                    Text("channel.guest_bot_mode.desc")
                }

                Section("channel.invite.create") {
                    TextField("channel.invite.label.placeholder", text: $labelInput)
                    Picker("channel.invite.expires", selection: $expiresHours) {
                        Text("channel.invite.expires.24h").tag(24)
                        Text("channel.invite.expires.7d").tag(168)
                        Text("channel.invite.expires.never").tag(0)
                    }
                    Picker("channel.invite.max_uses", selection: $maxUsesChoice) {
                        Text("channel.invite.max_uses.unlimited").tag(0)
                        Text("channel.invite.max_uses.1").tag(1)
                        Text("channel.invite.max_uses.10").tag(10)
                    }
                    Button {
                        Task { await createInvite() }
                    } label: {
                        HStack {
                            Spacer()
                            if isCreating {
                                ProgressView()
                            } else {
                                Label("channel.invite.create_and_copy", systemImage: "link.badge.plus")
                                    .bold()
                            }
                            Spacer()
                        }
                    }
                    .disabled(isCreating)
                }

                if let statusBanner {
                    Section {
                        Label(statusBanner, systemImage: "checkmark.circle.fill")
                            .foregroundStyle(.green)
                            .font(.footnote)
                    }
                }
                if let errorMessage {
                    Section {
                        Label(errorMessage, systemImage: "exclamationmark.triangle.fill")
                            .foregroundStyle(.red)
                            .font(.footnote)
                    }
                }

                Section("channel.invite.list") {
                    if invites.isEmpty && !isLoading {
                        Text("channel.invite.empty").foregroundStyle(.secondary)
                    }
                    ForEach(invites) { inv in
                        VStack(alignment: .leading, spacing: 6) {
                            HStack {
                                Text(inv.label.isEmpty ? "#\(inv.id)" : inv.label)
                                    .font(.headline)
                                Spacer()
                                if inv.revoked {
                                    Text("channel.invite.revoked")
                                        .font(.caption)
                                        .foregroundStyle(.red)
                                } else if let max = inv.maxUses {
                                    Text("\(inv.usesCount)/\(max)")
                                        .font(.caption)
                                        .foregroundStyle(.secondary)
                                } else {
                                    Text("\(inv.usesCount)")
                                        .font(.caption)
                                        .foregroundStyle(.secondary)
                                }
                            }
                            if !inv.revoked {
                                HStack(spacing: 12) {
                                    if let url = inv.inviteUrl, !url.isEmpty {
                                        Button("channel.invite.copy_web") {
                                            UIPasteboard.general.string = url
                                            statusBanner = NSLocalizedString("channel.invite.copied", comment: "")
                                        }
                                        .font(.caption)
                                        .buttonStyle(.bordered)
                                    }
                                    if let deep = inv.deepLink, !deep.isEmpty {
                                        Button("channel.invite.copy_app") {
                                            UIPasteboard.general.string = deep
                                            statusBanner = NSLocalizedString("channel.invite.copied", comment: "")
                                        }
                                        .font(.caption)
                                        .buttonStyle(.bordered)
                                    }
                                    Spacer()
                                    Button("channel.invite.revoke", role: .destructive) {
                                        Task { await revokeInvite(inv.id) }
                                    }
                                    .font(.caption)
                                    .buttonStyle(.borderless)
                                }
                            }
                        }
                        .padding(.vertical, 2)
                    }
                }

                Section("channel.members.human") {
                    if members.isEmpty && !isLoading {
                        Text("channel.members.empty").foregroundStyle(.secondary)
                    }
                    ForEach(members) { member in
                        HStack {
                            VStack(alignment: .leading, spacing: 2) {
                                Text(member.displayName)
                                if let joined = member.joinedAt, !joined.isEmpty {
                                    Text(joined).font(.caption2).foregroundStyle(.secondary)
                                }
                            }
                            Spacer()
                            if member.role == "guest" {
                                Button("channel.members.remove", role: .destructive) {
                                    Task { await removeMember(member.id) }
                                }
                                .font(.caption)
                                .buttonStyle(.borderless)
                            }
                        }
                    }
                }
            }
            .navigationTitle(channelTitle)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button("common.done") { dismiss() }
                }
            }
            .task { await reload() }
            .refreshable { await reload() }
        }
    }

    private func reload() async {
        isLoading = true
        defer { isLoading = false }
        do {
            async let invs = client.fetchChannelInvites(channelId: channelId)
            async let usrs = client.fetchChannelUsers(channelId: channelId)
            invites = try await invs
            members = try await usrs
            errorMessage = nil
        } catch {
            errorMessage = error.localizedDescription
        }
    }

    private func updateBotMode(_ mode: String) async {
        do {
            _ = try await client.updateChannelGuestBotMode(channelId: channelId, mode: mode)
            onGuestBotModeChanged(mode)
            errorMessage = nil
        } catch {
            errorMessage = error.localizedDescription
        }
    }

    private func createInvite() async {
        isCreating = true
        defer { isCreating = false }
        do {
            let inv = try await client.createChannelInvite(
                channelId: channelId,
                label: labelInput,
                maxUses: maxUsesChoice > 0 ? maxUsesChoice : nil,
                expiresHours: expiresHours > 0 ? expiresHours : nil
            )
            invites.insert(inv, at: 0)
            labelInput = ""
            if let copyTarget = inv.inviteUrl ?? inv.deepLink {
                UIPasteboard.general.string = copyTarget
                statusBanner = NSLocalizedString("channel.invite.copied", comment: "")
            }
            errorMessage = nil
        } catch {
            errorMessage = error.localizedDescription
        }
    }

    private func revokeInvite(_ id: Int) async {
        do {
            try await client.revokeChannelInvite(channelId: channelId, inviteId: id)
            await reload()
        } catch {
            errorMessage = error.localizedDescription
        }
    }

    private func removeMember(_ userId: Int) async {
        do {
            try await client.removeChannelUser(channelId: channelId, userId: userId)
            members.removeAll { $0.id == userId }
        } catch {
            errorMessage = error.localizedDescription
        }
    }
}
