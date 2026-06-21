import StoreKit
import SwiftUI

// MARK: - Product IDs

extension SubscriptionService {
    static let productIDs: Set<String> = [
        "app.hermeschat.basic.monthly",
        "app.hermeschat.pro.monthly",
    ]
}

// MARK: - SubscriptionService (T-C03)

/// StoreKit 2 기반 구독 서비스. Basic / Pro 월정기 구독 제품 로드 및 엔타이틀먼트 확인.
@MainActor
final class SubscriptionService: ObservableObject {
    static let shared = SubscriptionService()

    @Published private(set) var products: [Product] = []
    @Published private(set) var purchasedProductIDs: Set<String> = []
    @Published private(set) var isLoading = false

    private var transactionListener: Task<Void, Never>?

    init() {
        transactionListener = listenForTransactions()
        Task { await loadProducts() }
    }

    deinit {
        transactionListener?.cancel()
    }

    var activeSubscription: Product? {
        products.first { purchasedProductIDs.contains($0.id) }
    }

    var planName: String {
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
