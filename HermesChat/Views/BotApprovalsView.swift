import SwiftUI

/// 봇이 사람의 허락을 구한 작업 목록(대기 중 승인) — 승인/거절(메모 선택).
/// 승인은 서버가 동작을 실행하므로 오래 걸릴 수 있어(최대 200초) 행마다 처리 중 표시를 둔다.
struct BotApprovalsView: View {
    let client: BotClient
    /// 목록을 읽을 때마다 대기 건수를 알린다 — 봇 목록 화면의 배지 갱신용
    var onPendingCountChange: ((Int) -> Void)? = nil

    @State private var approvals: [BotApproval] = []
    @State private var notes: [Int: String] = [:]
    @State private var processing: Set<Int> = []
    @State private var isLoading = false
    @State private var loaded = false
    @State private var errorMessage: String?

    var body: some View {
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
            ForEach(approvals) { approval in
                Section { card(approval) }
            }
        }
        .navigationTitle("bot.approvals.title")
        .navigationBarTitleDisplayMode(.inline)
        .refreshable { await load() }
        .task { await load() }
        .overlay {
            if loaded && approvals.isEmpty && errorMessage == nil {
                Text("bot.approvals.empty")
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
                    .padding(32)
            }
        }
    }

    private func card(_ approval: BotApproval) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack {
                Text(verbatim: approval.title).font(.headline)
                Spacer(minLength: 8)
                if let kind = approval.kind, !kind.isEmpty {
                    Text(verbatim: kindLabel(kind))
                        .font(.caption2)
                        .padding(.horizontal, 8)
                        .padding(.vertical, 3)
                        .background(Color.secondary.opacity(0.15))
                        .clipShape(Capsule())
                }
            }
            if let detail = approval.detail, !detail.isEmpty {
                Text(verbatim: detail).font(.subheadline)
            }
            if let action = approval.action, !action.isEmpty {
                labeled("bot.approvals.action", action, tint: .secondary)
            }
            if let risk = approval.risk, !risk.isEmpty {
                labeled("bot.approvals.risk", risk, tint: .orange)
            }
            TextField("bot.approvals.note", text: noteBinding(approval.id))
                .textFieldStyle(.roundedBorder)
                .font(.footnote)
                .disabled(processing.contains(approval.id))

            if processing.contains(approval.id) {
                HStack(spacing: 8) {
                    ProgressView()
                    Text("bot.approvals.processing").font(.footnote).foregroundStyle(.secondary)
                }
            } else {
                HStack {
                    Button(role: .destructive) {
                        Task { await decide(approval, approve: false) }
                    } label: {
                        Text("bot.approvals.reject").frame(maxWidth: .infinity)
                    }
                    .buttonStyle(.bordered)
                    Button {
                        Task { await decide(approval, approve: true) }
                    } label: {
                        Text("bot.approvals.approve").frame(maxWidth: .infinity)
                    }
                    .buttonStyle(.borderedProminent)
                }
            }
        }
        .padding(.vertical, 4)
    }

    private func labeled(_ key: LocalizedStringKey, _ text: String, tint: Color) -> some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(key).font(.caption).foregroundStyle(tint)
            Text(verbatim: text).font(.footnote)
        }
    }

    private func kindLabel(_ kind: String) -> String {
        switch kind {
        case "bot": return NSLocalizedString("bot.approvals.kind.bot", comment: "")
        case "git_push": return NSLocalizedString("bot.approvals.kind.git_push", comment: "")
        case "app_screen": return NSLocalizedString("bot.approvals.kind.app_screen", comment: "")
        default: return kind
        }
    }

    private func noteBinding(_ id: Int) -> Binding<String> {
        Binding(get: { notes[id] ?? "" }, set: { notes[id] = $0 })
    }

    private func load() async {
        isLoading = true
        defer { isLoading = false }
        do {
            approvals = try await client.fetchApprovals(status: "pending")
            errorMessage = nil
            onPendingCountChange?(approvals.count)
        } catch {
            errorMessage = error.localizedDescription
        }
        loaded = true
    }

    private func decide(_ approval: BotApproval, approve: Bool) async {
        processing.insert(approval.id)
        errorMessage = nil
        let note = (notes[approval.id] ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
        do {
            if approve {
                try await client.approve(id: approval.id, note: note)
            } else {
                try await client.reject(id: approval.id, note: note)
            }
            notes[approval.id] = nil
            processing.remove(approval.id)
            await load()  // 처리 결과 반영
        } catch {
            processing.remove(approval.id)
            errorMessage = error.localizedDescription
            await load()  // 이미 처리된 건이면 목록에서 사라지게(오류 문구는 load 성공 시 지워지므로 다시 설정)
            errorMessage = error.localizedDescription
        }
    }
}
