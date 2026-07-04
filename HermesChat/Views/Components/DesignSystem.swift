import SwiftUI

// MARK: - 디자인 토큰 (T-163)
// OpenClaw풍 카드 기반 다크/라이트 공용 디자인 시스템. 파랑 액센트는
// Assets.xcassets/AccentColor(라이트 #007AFF / 다크 #0A84FF)가 전역 적용한다.

enum HermesUI {
    /// 카드·버블 공용 코너 반경
    static let corner: CGFloat = 14
    /// 카드 내부 패딩
    static let cardPadding: CGFloat = 14
    /// 홈 허브 섹션 간 간격
    static let sectionSpacing: CGFloat = 20
    /// 플로팅 탭바가 가리는 하단 여백 (루트 스크롤 뷰에 safeAreaInset으로 부여)
    static let tabBarClearance: CGFloat = 72
}

extension Color {
    /// 카드 표면 — 시스템 시맨틱이라 라이트/다크 자동 대응
    static let hermesCardBG = Color(.secondarySystemBackground)
    /// 그룹 배경
    static let hermesGroupedBG = Color(.systemGroupedBackground)
    static let hermesPositive = Color.green
    static let hermesWarning = Color.orange
    static let hermesDanger = Color.red
}

// MARK: - 카드

struct HermesCard: ViewModifier {
    func body(content: Content) -> some View {
        content
            .padding(HermesUI.cardPadding)
            .background(Color.hermesCardBG)
            .clipShape(RoundedRectangle(cornerRadius: HermesUI.corner, style: .continuous))
    }
}

extension View {
    /// 카드 표면 래핑 — ProfileBoardView 카드 모티프의 표준화
    func hermesCard() -> some View { modifier(HermesCard()) }

    /// 루트 스크롤/리스트가 플로팅 탭바에 가리지 않도록 하단 여백 확보 (T-164)
    func floatingTabBarClearance() -> some View {
        safeAreaInset(edge: .bottom) {
            Color.clear.frame(height: HermesUI.tabBarClearance)
        }
    }
}

// MARK: - 상태 필 배지

/// OpenClaw풍 캡슐 상태 배지 — 도트 + 짧은 텍스트 (예: Online/Offline)
struct StatusPill: View {
    let text: String
    let color: Color

    var body: some View {
        HStack(spacing: 5) {
            Circle().fill(color).frame(width: 7, height: 7)
            Text(text)
                .font(.caption.weight(.semibold))
                .foregroundStyle(.primary)
        }
        .padding(.horizontal, 10)
        .padding(.vertical, 5)
        .background(Capsule().fill(Color(.tertiarySystemFill)))
        .accessibilityElement(children: .combine)
    }
}

// MARK: - 아이콘 행

/// 아이콘 + 타이틀 + 서브타이틀 행 — 홈 허브/설정 공용 (OpenClaw 리스트 행 모티프)
struct IconRow: View {
    let systemImage: String
    let tint: Color
    let title: String
    var subtitle: String? = nil
    var showsChevron = true

    var body: some View {
        HStack(spacing: 12) {
            Image(systemName: systemImage)
                .font(.system(size: 17, weight: .semibold))
                .foregroundStyle(tint)
                .frame(width: 34, height: 34)
                .background(
                    RoundedRectangle(cornerRadius: 9, style: .continuous)
                        .fill(tint.opacity(0.14))
                )
            VStack(alignment: .leading, spacing: 2) {
                Text(title)
                    .font(.body.weight(.semibold))
                    .foregroundStyle(.primary)
                if let subtitle {
                    Text(subtitle)
                        .font(.caption)
                        .foregroundStyle(.secondary)
                        .lineLimit(1)
                }
            }
            Spacer(minLength: 0)
            if showsChevron {
                Image(systemName: "chevron.right")
                    .font(.caption.weight(.semibold))
                    .foregroundStyle(.tertiary)
            }
        }
        .contentShape(Rectangle())
    }
}

// MARK: - 섹션 헤더

/// 카드 섹션 위에 얹는 좌측 정렬 소제목 (OpenClaw 'CHAT'/'CONTROL' 모티프)
struct SectionHeader: View {
    let title: String

    var body: some View {
        Text(title)
            .font(.caption.weight(.bold))
            .foregroundStyle(.secondary)
            .textCase(.uppercase)
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(.horizontal, 4)
    }
}
