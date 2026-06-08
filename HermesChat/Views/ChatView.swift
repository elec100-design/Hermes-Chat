import SwiftUI

struct ChatView: View {
    @ObservedObject var appSettings: AppSettings
    let sessionId: String
    @StateObject private var viewModel: ChatViewModel
    @FocusState private var isInputFocused: Bool

    init(sessionId: String, appSettings: AppSettings) {
        self.sessionId = sessionId
        self.appSettings = appSettings
        self._viewModel = StateObject(wrappedValue: .init(sessionId: sessionId, appSettings: appSettings))
    }

    var body: some View {
        NavigationStack {
            VStack(spacing: 0) {
                messageList

                if viewModel.isWorking {
                    Divider()
                    HStack {
                        Spacer()
                        ProgressView()
                            .padding(.trailing, 4)
                        Text(viewModel.messages.last?.content.isEmpty == true ? "응답 생성 중..." : "응답 중")
                        Spacer()
                    }
                    .padding(.vertical, 6)
                    .background(.thinMaterial)
                }

                Divider()
                inputBar
                    .padding()
                    .background(.background)
            }
            .navigationTitle("Hermes Chat")
            .toolbar {
                ToolbarItem(placement: .keyboard) {
                    Button("완료") { isInputFocused = false }
                }
            }
        }
    }

    private var messageList: some View {
        ScrollViewReader { proxy in
            List {
                ForEach(viewModel.messages) { message in
                    MessageBubble(
                        message: message
                    )
                    .listRowSeparator(.hidden)
                    .listRowInsets(.init(top: 6, leading: 0, bottom: 6, trailing: 0))
                    .listRowBackground(Color.clear)
                }
            }
            .listStyle(.plain)
            .onChange(of: viewModel.messages.count) { _ in scrollToBottom(proxy: proxy) }
            .onChange(of: viewModel.messages.last?.content) { _ in scrollToBottom(proxy: proxy) }
        }
    }

    private func scrollToBottom(proxy: ScrollViewProxy) {
        guard let last = viewModel.messages.last?.id else { return }
        withAnimation(.easeInOut) {
            proxy.scrollTo(last, anchor: .bottom)
        }
    }

    private var inputBar: some View {
        HStack(alignment: .bottom, spacing: 12) {
            TextField("메시지를 입력하세요", text: $viewModel.inputText, axis: .vertical)
                .textFieldStyle(.roundedBorder)
                .focused($isInputFocused)

            Button {
                Task { await viewModel.send() }
                isInputFocused = false
            } label: {
                Image(systemName: viewModel.isWorking ? "ellipsis" : "paperplane.fill")
                    .font(.system(size: 18, weight: .semibold))
            }
            .disabled(viewModel.inputText.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty || viewModel.isWorking)
            .accessibilityLabel("Send")
        }
    }
}
