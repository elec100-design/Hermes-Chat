import SwiftUI

// MARK: - ViewModel

/// hermes-agent 내장 칸반 화면 상태 관리.
/// 데이터 원본은 맥미니의 kanban.db — 대시보드(:8000/kanban)·게이트웨이 디스패처와 동일하다.
/// 쓰기는 전부 Bridge가 `hermes kanban` CLI를 호출하므로(생성/promote/block/...)
/// 앱은 보드 전체를 덮어쓰지 않고 태스크 단위 액션만 보낸다.
@MainActor
final class KanbanViewModel: ObservableObject {
    @Published var boards: [KanbanBoardSummary] = []
    @Published var board: KanbanBoard?
    @Published var selectedSlug: String?
    @Published var isLoading = false
    @Published var isMutating = false
    @Published var errorMessage: String?

    private let appSettings: AppSettings

    init(appSettings: AppSettings) {
        self.appSettings = appSettings
    }

    var bridgeConfigured: Bool { appSettings.bridgeClient != nil }

    var selectedBoardTitle: String {
        boards.first { $0.board == selectedSlug }?.name
            ?? board?.name
            ?? "칸반"
    }

    func start() async {
        guard board == nil, bridgeConfigured else { return }
        await loadBoards()
        if selectedSlug == nil, let first = boards.first {
            await select(first.board)
        }
    }

    func loadBoards() async {
        guard let bridge = appSettings.bridgeClient else { return }
        do {
            boards = try await bridge.fetchKanbanBoards()
            errorMessage = nil
        } catch {
            errorMessage = error.localizedDescription
        }
    }

    func select(_ slug: String) async {
        guard let bridge = appSettings.bridgeClient else { return }
        isLoading = true
        defer { isLoading = false }
        do {
            board = try await bridge.fetchBoard(slug: slug)
            selectedSlug = slug
            errorMessage = nil
        } catch {
            errorMessage = error.localizedDescription
        }
    }

    func refresh() async {
        await loadBoards()
        if let slug = selectedSlug {
            await select(slug)
        }
    }

    func createTask(
        title: String,
        detail: String?,
        assignee: String?,
        mode: KanbanCreateMode
    ) async {
        guard let bridge = appSettings.bridgeClient, let slug = selectedSlug else { return }
        isMutating = true
        defer { isMutating = false }
        do {
            try await bridge.createKanbanTask(
                board: slug, title: title, detail: detail, assignee: assignee, mode: mode
            )
            errorMessage = nil
            await select(slug)
        } catch {
            errorMessage = error.localizedDescription
        }
    }

    func perform(_ action: KanbanAction, on task: KanbanTask, reason: String? = nil) async {
        guard let bridge = appSettings.bridgeClient, let slug = selectedSlug else { return }
        isMutating = true
        defer { isMutating = false }
        do {
            try await bridge.kanbanAction(
                board: slug, taskID: task.id, action: action, reason: reason
            )
            errorMessage = nil
            await select(slug)
        } catch {
            errorMessage = error.localizedDescription
        }
    }
}

// MARK: - View

/// 칸반 화면: 상단 보드 선택, 컬럼(Triage→Done) 좌우 페이지 스와이프,
/// 카드의 상태 전이는 액션 메뉴(실행/보류/완료/아카이브)로 수행.
struct KanbanView: View {
    @ObservedObject var appSettings: AppSettings
    @StateObject private var viewModel: KanbanViewModel

    @State private var currentStatus: KanbanStatus = .ready
    @State private var showComposer = false
    @State private var inspectingTask: KanbanTask?

    init(appSettings: AppSettings) {
        self.appSettings = appSettings
        self._viewModel = StateObject(wrappedValue: KanbanViewModel(appSettings: appSettings))
    }

    var body: some View {
        NavigationStack {
            content
                .navigationTitle(viewModel.selectedBoardTitle)
                .navigationBarTitleDisplayMode(.inline)
                .toolbar {
                    ToolbarItem(placement: .topBarLeading) { boardMenu }
                    ToolbarItem(placement: .topBarTrailing) {
                        Button {
                            showComposer = true
                        } label: {
                            Image(systemName: "plus")
                        }
                        .disabled(viewModel.board == nil || viewModel.isMutating)
                    }
                }
                .sheet(isPresented: $showComposer) {
                    KanbanTaskComposer(
                        profiles: appSettings.profiles.map(\.name),
                        onCreate: { title, detail, assignee, mode in
                            Task {
                                await viewModel.createTask(
                                    title: title, detail: detail, assignee: assignee, mode: mode
                                )
                            }
                        }
                    )
                }
                .sheet(item: $inspectingTask) { task in
                    KanbanTaskDetail(task: task) { action in
                        Task { await viewModel.perform(action, on: task) }
                    }
                }
                .task { await viewModel.start() }
        }
    }

    @ViewBuilder
    private var content: some View {
        if !viewModel.bridgeConfigured {
            ContentUnavailableView(
                "Bridge 설정 필요",
                systemImage: "antenna.radiowaves.left.and.right.slash",
                description: Text("설정 화면의 Hermes Bridge 섹션에 URL과 토큰을 입력하면 칸반을 쓸 수 있습니다.")
            )
        } else if let board = viewModel.board {
            VStack(spacing: 0) {
                if let errorMessage = viewModel.errorMessage {
                    errorBanner(errorMessage)
                }
                if viewModel.isMutating {
                    ProgressView()
                        .padding(.vertical, 4)
                }
                columnPager(board)
            }
        } else if viewModel.isLoading {
            ProgressView("보드 불러오는 중...")
        } else {
            ContentUnavailableView {
                Label("보드 없음", systemImage: "rectangle.split.3x1")
            } description: {
                Text(viewModel.errorMessage
                     ?? "맥미니에서 `hermes kanban boards create <이름>` 으로 보드를 만들 수 있습니다.")
            } actions: {
                Button("다시 불러오기") {
                    Task { await viewModel.refresh() }
                }
                .buttonStyle(.borderedProminent)
            }
        }
    }

    private var boardMenu: some View {
        Menu {
            ForEach(viewModel.boards) { summary in
                Button {
                    Task { await viewModel.select(summary.board) }
                } label: {
                    if summary.board == viewModel.selectedSlug {
                        Label("\(summary.name) (\(summary.activeCount))", systemImage: "checkmark")
                    } else {
                        Text("\(summary.name) (\(summary.activeCount))")
                    }
                }
            }
        } label: {
            HStack(spacing: 3) {
                Image(systemName: "rectangle.split.3x1")
                Image(systemName: "chevron.down")
                    .font(.caption.weight(.semibold))
            }
        }
    }

    private func errorBanner(_ message: String) -> some View {
        HStack {
            Image(systemName: "exclamationmark.triangle.fill")
                .foregroundStyle(.orange)
            Text(message)
                .font(.footnote)
                .foregroundStyle(.secondary)
                .lineLimit(3)
            Spacer()
        }
        .padding(.horizontal)
        .padding(.vertical, 6)
        .background(Color.orange.opacity(0.08))
    }

    private func columnPager(_ board: KanbanBoard) -> some View {
        TabView(selection: $currentStatus) {
            ForEach(KanbanStatus.allCases) { status in
                column(status: status, tasks: board.tasks.filter { $0.status == status })
                    .tag(status)
            }
        }
        .tabViewStyle(.page(indexDisplayMode: .always))
        .indexViewStyle(.page(backgroundDisplayMode: .always))
    }

    private func column(status: KanbanStatus, tasks: [KanbanTask]) -> some View {
        VStack(spacing: 0) {
            HStack(spacing: 8) {
                Circle()
                    .fill(status.color)
                    .frame(width: 10, height: 10)
                Text(status.displayName)
                    .font(.headline)
                Text("\(tasks.count)")
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
                Spacer()
            }
            .padding(.horizontal)
            .padding(.vertical, 10)

            List {
                ForEach(tasks) { task in
                    taskCard(task)
                        .listRowSeparator(.hidden)
                        .listRowInsets(.init(top: 4, leading: 16, bottom: 4, trailing: 16))
                }
            }
            .listStyle(.plain)
            .refreshable { await viewModel.refresh() }
            .overlay {
                if tasks.isEmpty {
                    Text("비어 있음")
                        .foregroundStyle(.secondary)
                }
            }
        }
    }

    private func taskCard(_ task: KanbanTask) -> some View {
        VStack(alignment: .leading, spacing: 6) {
            Text(task.title)
                .font(.subheadline.weight(.semibold))
            if let detail = task.detail, !detail.isEmpty {
                Text(detail)
                    .font(.caption)
                    .foregroundStyle(.secondary)
                    .lineLimit(2)
            }
            HStack {
                if let assignee = task.assignee, !assignee.isEmpty {
                    Text(assignee)
                        .font(.caption2)
                        .padding(.horizontal, 6)
                        .padding(.vertical, 2)
                        .background(Color.blue.opacity(0.12))
                        .clipShape(Capsule())
                }
                if task.status == .running {
                    ProgressView()
                        .controlSize(.mini)
                }
                Spacer()
                if !task.status.actions.isEmpty {
                    Menu {
                        actionButtons(for: task)
                    } label: {
                        Image(systemName: "ellipsis.circle")
                    }
                    .disabled(viewModel.isMutating)
                }
            }
            .buttonStyle(.borderless)
        }
        .padding(10)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(Color(.secondarySystemBackground))
        .clipShape(RoundedRectangle(cornerRadius: 10))
        .contentShape(Rectangle())
        .onTapGesture { inspectingTask = task }
    }

    @ViewBuilder
    private func actionButtons(for task: KanbanTask) -> some View {
        ForEach(task.status.actions) { action in
            Button(role: action.isDestructive ? .destructive : nil) {
                Task { await viewModel.perform(action, on: task) }
            } label: {
                Label(action.label, systemImage: action.systemImage)
            }
        }
    }
}

// MARK: - 새 태스크 작성 시트

private struct KanbanTaskComposer: View {
    let profiles: [String]
    let onCreate: (String, String?, String?, KanbanCreateMode) -> Void
    @Environment(\.dismiss) private var dismiss

    @State private var title = ""
    @State private var detail = ""
    @State private var assignee = ""
    @State private var mode: KanbanCreateMode = .ready

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    TextField("제목 (무엇을 할지)", text: $title)
                    Picker("담당 프로필", selection: $assignee) {
                        Text("자동").tag("")
                        ForEach(profiles, id: \.self) { name in
                            Text(name).tag(name)
                        }
                    }
                }
                Section("내용") {
                    TextEditor(text: $detail)
                        .frame(minHeight: 120)
                }
                Section {
                    Picker("시작 방식", selection: $mode) {
                        ForEach(KanbanCreateMode.allCases) { mode in
                            Text(mode.label).tag(mode)
                        }
                    }
                    .pickerStyle(.segmented)
                } footer: {
                    Text(mode.footnote)
                }
            }
            .navigationTitle("새 작업")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button("만들기") {
                        onCreate(
                            title.trimmingCharacters(in: .whitespaces),
                            detail.isEmpty ? nil : detail,
                            assignee.isEmpty ? nil : assignee,
                            mode
                        )
                        dismiss()
                    }
                    .disabled(title.trimmingCharacters(in: .whitespaces).isEmpty)
                }
                ToolbarItem(placement: .cancellationAction) {
                    Button("취소") { dismiss() }
                }
            }
        }
    }
}

// MARK: - 태스크 상세 시트

private struct KanbanTaskDetail: View {
    let task: KanbanTask
    let onAction: (KanbanAction) -> Void
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    LabeledContent("상태") {
                        HStack(spacing: 6) {
                            Circle()
                                .fill(task.status.color)
                                .frame(width: 8, height: 8)
                            Text(task.status.displayName)
                        }
                    }
                    if let assignee = task.assignee, !assignee.isEmpty {
                        LabeledContent("담당", value: assignee)
                    }
                    LabeledContent("ID", value: task.id)
                    if let created = task.createdAt {
                        LabeledContent("생성", value: created)
                    }
                    if let updated = task.updatedAt {
                        LabeledContent("갱신", value: updated)
                    }
                }
                if let detail = task.detail, !detail.isEmpty {
                    Section("내용") {
                        Text(detail)
                            .font(.callout)
                            .textSelection(.enabled)
                    }
                }
                if !task.status.actions.isEmpty {
                    Section {
                        ForEach(task.status.actions) { action in
                            Button(role: action.isDestructive ? .destructive : nil) {
                                onAction(action)
                                dismiss()
                            } label: {
                                Label(action.label, systemImage: action.systemImage)
                            }
                        }
                    }
                }
            }
            .navigationTitle(task.title)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("닫기") { dismiss() }
                }
            }
        }
    }
}
