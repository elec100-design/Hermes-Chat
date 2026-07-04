import SwiftUI

// MARK: - 플로팅 탭바 가시성 (T-164)

/// 몰입형 화면(채팅·Live 통화·대시보드 웹)이 떠 있는 동안 플로팅 탭바를 숨긴다.
/// 카운터 방식 — 중첩 push에도 안전하고, pop 취소(엣지 스와이프 복귀)는
/// onDisappear가 불리지 않으므로 자연히 어긋나지 않는다.
@MainActor
final class TabBarVisibility: ObservableObject {
    @Published private var depth = 0
    var isVisible: Bool { depth == 0 }
    func push() { depth += 1 }
    func pop() { depth = max(0, depth - 1) }
}

private struct HidesFloatingTabBar: ViewModifier {
    @EnvironmentObject private var visibility: TabBarVisibility

    func body(content: Content) -> some View {
        content
            .onAppear { visibility.push() }
            .onDisappear { visibility.pop() }
    }
}

extension View {
    /// 이 화면이 보이는 동안 플로팅 탭바를 숨긴다 — 몰입형 상세 화면용 (T-164)
    func hidesFloatingTabBar() -> some View { modifier(HidesFloatingTabBar()) }
}

// MARK: - 플로팅 탭바 (T-164)

/// OpenClaw풍 캡슐 플로팅 탭바 — 시스템 TabView 크롬 대체. 5탭 고정.
struct FloatingTabBar: View {
    @Binding var selection: AppTab

    private struct Item: Identifiable {
        let tab: AppTab
        let icon: String
        let label: String
        var id: AppTab { tab }
    }

    private var items: [Item] {
        [
            Item(tab: .home, icon: "square.grid.2x2.fill", label: String(localized: "tab.home")),
            Item(tab: .chat, icon: "bubble.left.and.bubble.right.fill", label: String(localized: "tab.chat")),
            Item(tab: .live, icon: "waveform", label: "Live"),
            Item(tab: .kanban, icon: "rectangle.split.3x1.fill", label: String(localized: "tab.kanban")),
            Item(tab: .settings, icon: "gearshape.fill", label: String(localized: "tab.settings")),
        ]
    }

    var body: some View {
        HStack(spacing: 0) {
            ForEach(items) { item in
                tabButton(item)
            }
        }
        .padding(.horizontal, 10)
        .padding(.vertical, 8)
        .background(.ultraThinMaterial, in: Capsule(style: .continuous))
        .overlay(
            Capsule(style: .continuous)
                .strokeBorder(Color.primary.opacity(0.06), lineWidth: 0.5)
        )
        .shadow(color: .black.opacity(0.18), radius: 14, y: 5)
        .padding(.horizontal, 16)
        .padding(.bottom, 4)
    }

    private func tabButton(_ item: Item) -> some View {
        let isSelected = selection == item.tab
        return Button {
            selection = item.tab
        } label: {
            VStack(spacing: 3) {
                Image(systemName: item.icon)
                    .font(.system(size: 19, weight: .semibold))
                    .symbolRenderingMode(.hierarchical)
                Text(item.label)
                    .font(.system(size: 10, weight: .semibold))
                    .lineLimit(1)
            }
            .foregroundStyle(isSelected ? Color.accentColor : Color.secondary)
            .frame(maxWidth: .infinity)
            .padding(.vertical, 6)
            .background(
                Capsule(style: .continuous)
                    .fill(isSelected ? Color.accentColor.opacity(0.14) : .clear)
            )
        }
        .buttonStyle(.plain)
        .accessibilityLabel(item.label)
        .accessibilityAddTraits(isSelected ? [.isSelected] : [])
    }
}
