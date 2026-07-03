import StoreKit
import SwiftUI

// MARK: - Product IDs

extension SubscriptionService {
    /// 평생 이용권 — 비소모성(non-consumable), 한 번 결제로 유료 기능 영구 해제 (T-159)
    static let lifetimeProductID = "app.hermeschat.lifetime"
    static let productIDs: Set<String> = [
        "app.hermeschat.basic.monthly",
        "app.hermeschat.pro.monthly",
        lifetimeProductID,
    ]
}

// MARK: - SubscriptionService (T-C03, T-159)

/// StoreKit 2 기반 구독 서비스. Basic / Pro 월정기 구독 + 평생 이용권(비소모성) 로드 및 엔타이틀먼트 확인.
@MainActor
final class SubscriptionService: ObservableObject {
    static let shared = SubscriptionService()

    @Published private(set) var products: [Product] = []
    @Published private(set) var purchasedProductIDs: Set<String> = []
    @Published private(set) var isLoading = false

    private var transactionListener: Task<Void, Never>?

    init() {
        guard AppSettings.cloudFeaturesEnabled else { return }
        transactionListener = listenForTransactions()
        Task { await loadProducts() }
    }

    deinit {
        transactionListener?.cancel()
    }

    /// 활성 월정기 구독 (자동 갱신 상품만 — 평생 이용권은 hasLifetime으로 별도 판단)
    var activeSubscription: Product? {
        products.first { $0.type == .autoRenewable && purchasedProductIDs.contains($0.id) }
    }

    /// 평생 이용권 보유 여부 (T-159)
    var hasLifetime: Bool {
        purchasedProductIDs.contains(Self.lifetimeProductID)
    }

    var lifetimeProduct: Product? {
        products.first { $0.id == Self.lifetimeProductID }
    }

    /// 서버 플랜 매핑용 — 평생 이용권은 최고 플랜(pro)으로 취급.
    /// cloud_gateway 플랜 제한(Supabase users.plan)과의 영수증 동기화는 후속 과제(T-159 비고).
    var planName: String {
        if hasLifetime { return "pro" }
        guard let product = activeSubscription else { return "free" }
        return product.id.contains("pro") ? "pro" : "basic"
    }

    func loadProducts() async {
        isLoading = true
        defer { isLoading = false }
        do {
            products = try await Product.products(for: Self.productIDs)
                .sorted { $0.price < $1.price }
            await updatePurchasedProducts()
        } catch {
            // 제품 로드 실패 시 빈 배열 유지 — 구독 UI를 숨기지 않고 재시도 허용
        }
    }

    func purchase(_ product: Product) async throws -> Bool {
        let result = try await product.purchase()
        switch result {
        case .success(let verification):
            let transaction = try checkVerified(verification)
            await updatePurchasedProducts()
            await transaction.finish()
            return true
        case .userCancelled:
            return false
        case .pending:
            return false
        @unknown default:
            return false
        }
    }

    func restorePurchases() async {
        do {
            try await AppStore.sync()
            await updatePurchasedProducts()
        } catch {
            // 복원 실패는 조용히 처리
        }
    }

    private func updatePurchasedProducts() async {
        var ids: Set<String> = []
        for await result in Transaction.currentEntitlements {
            if let transaction = try? checkVerified(result),
               transaction.revocationDate == nil {
                ids.insert(transaction.productID)
            }
        }
        purchasedProductIDs = ids
    }

    private func checkVerified<T>(_ result: VerificationResult<T>) throws -> T {
        switch result {
        case .unverified:
            throw StoreError.failedVerification
        case .verified(let safe):
            return safe
        }
    }

    private func listenForTransactions() -> Task<Void, Never> {
        Task(priority: .background) {
            for await result in Transaction.updates {
                if let transaction = try? self.checkVerified(result) {
                    await self.updatePurchasedProducts()
                    await transaction.finish()
                }
            }
        }
    }
}

enum StoreError: LocalizedError {
    case failedVerification

    var errorDescription: String? {
        switch self {
        case .failedVerification:
            return String(localized: "subscription.error.verification_failed")
        }
    }
}
