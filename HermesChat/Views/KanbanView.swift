import SwiftUI

// MARK: - ViewModel

/// 칸반 보드 상태 관리. 저장은 항상 GET-병합-PUT:
/// 최신 보드를 받아 우리 변경(업서트/삭제/이동)만 얹어서 전체 PUT 한다 (PLAN §4-5).
/// Hermes 에이전트가 같은 파일을 수정하므로 마지막 쓰기 승리 + 변경 단위 병합으로 충돌을 줄인다.
@MainActor
final class KanbanViewModel: ObservableObject {
    @Published var boardNames: [String] = []
    @Published var board: KanbanBoard?
    @Published var selectedBoardName: String?
    @Published var isLoading = false
    @Published var errorMessage: String?

    private let appSettings: AppSettings
    private static let iso = ISO8601DateFormatter()

    init(appSettings: AppSettings) {
        self.appSettings = appSettings
    }

    var bridgeConfigured: Bool { appSettings.bridgeClient != nil }

    func start() async {
        guard board == nil, bridgeConfigured else { return }
        await loadBoardNames()
        if selectedBoardName == nil, let first = boardNames.first {
            await select(first)
        }
    }

    func loadBoardNames() async {
        guard let bridge = appSettings.bridgeClient else { return }
        do {
            boardNames = try await bridge.fetchBoardNames()
            errorMessage = nil
        } catch {
            errorMessage = error.localizedDescription
        }
    }

    func select(_ name: String) async {
        guard let bridge = appSettings.bridgeClient else { return }
        isLoading = true
        defer { isLoading = false }
        do {
            let data = try await bridge.fetchBoardData(name: name)
            board = try JSONDecoder().decode(KanbanBoard.self, from: data)
            selectedBoardName = name
            errorMessage = nil
        } catch {
            errorMessage = error.localizedDescription
        }
    }

    func refresh() async {
        if let name = selectedBoardName {
            await select(name)
        }
        await loadBoardNames()
    }

    /// 파일명 규칙은 Bridge의 SAFE_NAME(영문/숫자/._-)과 동일해야 한다.
    static func isValidBoardName(_ name: String) -> Bool {
        name.range(of: "^[A-Za-z0-9._-]{1,80}$", options: .regularExpression) != nil
    }

    func createBoard(named name: String) async {
        guard let bridge = appSettings.bridgeClient else { return }
        let newBoard = KanbanBoard(name: name, updatedAt: Self.iso.string(from: .now), tasks: [])
        do {
            try await bridge.saveBoardData(name: name, data: JSONEncoder().encode(newBoard))
            await loadBoardNames()
            await select(name)
        } catch {
            errorMessage = error.localizedDescription
        }
    }

    func upsert(_ task: KanbanTask) async {
        var updated = task
        updated.updatedAt = Self.iso.string(from: .now)
        await mutate { tasks in
            if let index = tasks.firstIndex(where: { $0.id == updated.id }) {
                tasks[index] = updated
            } else {
                tasks.append(updated)
            }
        }
    }

    func delete(taskID: String) async {
        await mutate { tasks in
            tasks.removeAll { $0.id == taskID }
        }
    }

    func move(taskID: String, to status: KanbanStatus) async {
        await mutate { tasks in
            if let index = tasks.firstIndex(where: { $0.id == taskID }) {
                tasks[index].status = status
                tasks[index].updatedAt = Self.iso.string(from: .now)
            }
        }
    }

    private func mutate(_ change: (inout [KanbanTask]) -> Void) async {
        guard let bridge = appSettings.bridgeClient, let name = selectedBoardName else { return }
        do {
            let data = try await bridge.fetchBoardData(name: name)
            var latest = (try? JSONDecoder().decode(KanbanBoard.self, from: data))
                ?? KanbanBoard(name: name, updatedAt: nil, tasks: [])
            change(&latest.tasks)
            latest.updatedAt = Self.iso.string(from: .now)
            try await bridge.saveBoardData(name: name, data: JSONEncoder().encode(latest))
            board = latest
            errorMessage = nil
        } catch {
            errorMessage = error.localizedDescription
        }
    }
}

// MARK: - View

/// 칸반 화면: 상단 보드 선택, 컬럼(Triage→Done) 좌우 페이지 스와이프,
/// 카드 탭→편집 시트, ◀▶로 상태 이동.
struct KanbanView: View {
    @ObservedObject var appSettings: AppSettings
    @StateObject private var viewModel: KanbanViewModel

    @State private var currentStatus: KanbanStatus = .triage
    @State private var editingTask: KanbanTask?
    @State private var editorIsNew = false
    @State private var showNewBoardAlert = false
    @State private var newBoardName = ""

    init(appSettings: AppSettings) {
        self.appSettings = appSettings
        self._viewModel = StateObject(wrappedValue: KanbanViewModel(appSettings: appSettings))
    }

    var body: some View {
        NavigationStack {
            content
                .navigationTitle(viewModel.selectedBoardName ?? "칸반")
                .navigationBarTitleDisplayMode(.inline)
                .toolbar {
                    ToolbarItem(placement: .topBarLeading) { boardMenu }
                    ToolbarItem(placement: .topBarTrailing) {
                        Button {
                            editorIsNew = true
                            editingTask = .new(status: currentStatus)
                        } label: {
                            Image(systemName: "plus")
                        }
                        .disabled(viewModel.board == nil)
                    }
                }
                .sheet(item: $editingTask) { task in
                    KanbanTaskEditor(
                        task: task,
                        isNew: editorIsNew,
                        onSave: { saved in Task { await viewModel.upsert(saved) } },
                        onDelete: { id in Task { await viewModel.delete(taskID: id) } }
                    )
                }
                .alert("새 보드", isPresented: $showNewBoardAlert) {
                    TextField("이름 (영문/숫자/._-)", text: $newBoardName)
                    Button("만들기") {
                        let name = newBoardName.trimmingCharacters(in: .whitespaces)
                        newBoardName = ""
                        guard KanbanViewModel.isValidBoardName(name) else {
                            viewModel.errorMessage = "보드 이름은 영문/숫자/._- 만 쓸 수 있습니다."
                            return
                        }
                        Task { await viewModel.createBoard(named: name) }
                    }
                    Button("취소", role: .cancel) {}
                } message: {
                    Text("맥미니 ~/.hermes/kanban/<이름>.json 으로 저장됩니다.")
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
                columnPager(board)
            }
        } else if viewModel.isLoading {
            ProgressView("보드 불러오는 중...")
        } else {
            ContentUnavailableView {
                Label("보드 없음", systemImage: "rectangle.split.3x1")
            } description: {
                Text(viewModel.errorMessage ?? "새 보드를 만들거나, Hermes에게 칸반 작업을 시키면 보드가 생깁니다.")
            } actions: {
                Button("새 보드 만들기") { showNewBoardAlert = true }
                    .buttonStyle(.borderedProminent)
            }
        }
    }

    private var boardMenu: some View {
        Menu {
            ForEach(viewModel.boardNames, id: \.self) { name in
                Button {
                    Task { await viewModel.select(name) }
                } label: {
                    if name == viewModel.selectedBoardName {
                        Label(name, systemImage: "checkmark")
                    } else {
                        Text(name)
                    }
                }
            }
            Divider()
            Button {
                showNewBoardAlert = true
            } label: {
                Label("새 보드...", systemImage: "plus")
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
                .lineLimit(2)
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
                Spacer()
                Button {
                    if let previous = task.status.previous {
                        Task { await viewModel.move(taskID: task.id, to: previous) }
                    }
                } label: {
                    Image(systemName: "chevron.left.circle")
                }
                .disabled(task.status.previous == nil)
                Button {
                    if let next = task.status.next {
                        Task { await viewModel.move(taskID: task.id, to: next) }
                    }
                } label: {
                    Image(systemName: "chevron.right.circle")
                }
                .disabled(task.status.next == nil)
            }
            .buttonStyle(.borderless)
        }
        .padding(10)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(Color.gray.opacity(0.1))
        .clipShape(RoundedRectangle(cornerRadius: 10))
        .contentShape(Rectangle())
        .onTapGesture {
            editorIsNew = false
            editingTask = task
        }
    }
}

// MARK: - Task Editor

private struct KanbanTaskEditor: View {
    @State var task: KanbanTask
    let isNew: Bool
    let onSave: (KanbanTask) -> Void
    let onDelete: (String) -> Void
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    TextField("제목", text: $task.title)
                    Picker("상태", selection: $task.status) {
                        ForEach(KanbanStatus.allCases) { status in
                            Text(status.displayName).tag(status)
                        }
                    }
                    TextField(
                        "담당 (프로필/서브에이전트)",
                        text: Binding(
                            get: { task.assignee ?? "" },
                            set: { task.assignee = $0.isEmpty ? nil : $0 }
                        )
                    )
                }
                Section("설명") {
                    TextEditor(
                        text: Binding(
                            get: { task.detail ?? "" },
                            set: { task.detail = $0.isEmpty ? nil : $0 }
                        )
                    )
                    .frame(minHeight: 120)
                }
                if !isNew {
                    Section {
                        Button("삭제", role: .destructive) {
                            onDelete(task.id)
                            dismiss()
                        }
                    }
                }
            }
            .navigationTitle(isNew ? "새 작업" : "작업 편집")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button("저장") {
                        onSave(task)
                        dismiss()
                    }
                    .disabled(task.title.trimmingCharacters(in: .whitespaces).isEmpty)
                }
                ToolbarItem(placement: .cancellationAction) {
                    Button("취소") { dismiss() }
                }
            }
        }
    }
}
