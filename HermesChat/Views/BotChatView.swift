import SwiftUI

/// 봇 DM / 채널 공용 대화 화면.
/// 말풍선은 기존 `MessageBubble` 을 그대로 재사용한다(ChatMessage 로 변환).
/// 봇의 진행 중 응답은 `BotChatViewModel.streaming` 에서, 확정본은 서버 메시지 본문에서 온다.
struct BotChatView: View {
    @ObservedObject var appSettings: AppSettings
    let title: String
    let isChannel: Bool
    let members: [BotMember]

    @StateObject private var viewModel: BotChatViewModel

    init(
        appSettings: AppSettings,
        client: BotClient,
        channelId: Int,
        title: String,
        isChannel: Bool,
        members: [BotMember] = []
    ) {
        _appSettings = ObservedObject(wrappedValue: appSettings)
        self.title = title
        self.isChannel = isChannel
        self.members = members
        _viewModel = StateObject(
            wrappedValue: BotChatViewModel(client: client, channelId: channelId, isChannel: isChannel)
        )
    }

    var body: some View {
        VStack(spacing: 0) {
            messageList
            statusLines
            if isChannel && !members.isEmpty { memberChips }
            inputBar
        }
        .navigationTitle(title)
        .navigationBarTitleDisplayMode(.inline)
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
            VStack(alignment: .leading, spacing: 0) {
                // 채널에서는 누가 말하는지 봇 버블 위에 표시
                if isChannel && !message.isUser {
                    Text(verbatim: "\(senderAvatar(message)) \(senderName(message))")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                        .padding(.horizontal, 30)
                }
                MessageBubble(message: chatMessage(for: message))
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
        return "bot"
    }

    /// 표시 텍스트: 진행 중이면 스트림 버퍼, 실패면 경고 + 오류, 그 외 확정 본문
    private func chatMessage(for message: BotMessage) -> ChatMessage {
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
            role: message.isUser ? .user : .assistant,
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

    private var inputBar: some View {
        HStack(alignment: .bottom, spacing: 12) {
            TextField("chat.message.placeholder", text: $viewModel.input, axis: .vertical)
                .textFieldStyle(.roundedBorder)
                .lineLimit(1...5)
            Button {
                Task { await viewModel.send() }
            } label: {
                Image(systemName: "paperplane.fill")
                    .font(.system(size: 18, weight: .semibold))
            }
            .disabled(
                viewModel.isSending
                    || viewModel.input.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
            )
            .accessibilityLabel(LocalizedStringKey("Send"))
        }
        .padding(.horizontal)
        .padding(.vertical, 8)
        .background(.thinMaterial)
    }
}
