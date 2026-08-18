import XCTest
@testable import HermesChat

/// CloudAuthStore 단위 테스트. Keychain/UserDefaults 실물을 쓰므로 키를 정리해 격리하고,
/// /usage 응답은 MockURLProtocol로 가로챈다.
@MainActor
final class CloudAuthStoreTests: XCTestCase {
    private let authKeys = ["supabase_jwt", "supabase_refresh", "supabase_user_id", "supabase_email"]

    override func setUp() {
        super.setUp()
        for key in authKeys {
            KeychainHelper.delete(key)
            UserDefaults.standard.removeObject(forKey: key)
        }
        URLProtocol.registerClass(MockURLProtocol.self)
    }

    override func tearDown() {
        URLProtocol.unregisterClass(MockURLProtocol.self)
        for key in authKeys {
            KeychainHelper.delete(key)
            UserDefaults.standard.removeObject(forKey: key)
        }
        super.tearDown()
    }

    private func makeUsageJSON(messages: Int, limit: Int?) -> Data {
        let limits: [String: Any] = limit.map { ["monthly_messages": $0] } ?? [:]
        let body: [String: Any] = [
            "limits": limits,
            "this_month": ["messages": messages],
        ]
        return (try? JSONSerialization.data(withJSONObject: body)) ?? Data()
    }

    func testInitLoadsFromKeychain() {
        KeychainHelper.set("jwt-keychain", for: "supabase_jwt")
        KeychainHelper.set("uid-keychain", for: "supabase_user_id")

        let store = CloudAuthStore()
        XCTAssertEqual(store.supabaseJWT, "jwt-keychain")
        XCTAssertEqual(store.supabaseUserID, "uid-keychain")
        XCTAssertTrue(store.isCloudAuthenticated)
    }

    func testLegacyUserDefaultsMigratesToKeychain() {
        UserDefaults.standard.set("legacy-jwt", forKey: "supabase_jwt")
        UserDefaults.standard.set("legacy-refresh", forKey: "supabase_refresh")

        let store = CloudAuthStore()
        XCTAssertEqual(store.supabaseJWT, "legacy-jwt")
        XCTAssertEqual(store.supabaseRefresh, "legacy-refresh")
        // Keychain에 이관되고 UserDefaults에서는 제거된다
        XCTAssertEqual(KeychainHelper.get("supabase_jwt"), "legacy-jwt")
        XCTAssertNil(UserDefaults.standard.string(forKey: "supabase_jwt"))
    }

    func testFetchUsageParsesResponse() async {
        let store = CloudAuthStore()
        store.supabaseJWT = "jwt"
        store.supabaseUserID = "uid"

        MockURLProtocol.requestHandler = { request in
            let response = HTTPURLResponse(url: request.url!, statusCode: 200, httpVersion: nil, headerFields: nil)!
            return (response, self.makeUsageJSON(messages: 12, limit: 100))
        }

        await store.fetchUsage(gatewayURL: "http://mock.test", connectionMode: .cloud)
        XCTAssertEqual(store.usageCount, 12)
        XCTAssertEqual(store.usageLimit, 100)
    }

    func testFetchUsageSkipsWhenNotAuthenticated() async {
        let store = CloudAuthStore()
        MockURLProtocol.requestHandler = { request in
            XCTFail("요청이 발생하면 안 됩니다")
            let response = HTTPURLResponse(url: request.url!, statusCode: 200, httpVersion: nil, headerFields: nil)!
            return (response, Data())
        }

        await store.fetchUsage(gatewayURL: "http://mock.test", connectionMode: .cloud)
        XCTAssertEqual(store.usageCount, 0)
    }

    func testFetchUsageSkipsWhenNotCloudMode() async {
        let store = CloudAuthStore()
        store.supabaseJWT = "jwt"
        store.supabaseUserID = "uid"
        MockURLProtocol.requestHandler = { request in
            XCTFail("요청이 발생하면 안 됩니다")
            let response = HTTPURLResponse(url: request.url!, statusCode: 200, httpVersion: nil, headerFields: nil)!
            return (response, Data())
        }

        await store.fetchUsage(gatewayURL: "http://mock.test", connectionMode: .selfHosted)
        XCTAssertEqual(store.usageCount, 0)
    }

    func testFetchUsageIgnoresNon200() async {
        let store = CloudAuthStore()
        store.supabaseJWT = "jwt"
        store.supabaseUserID = "uid"
        MockURLProtocol.requestHandler = { request in
            let response = HTTPURLResponse(url: request.url!, statusCode: 500, httpVersion: nil, headerFields: nil)!
            return (response, Data())
        }

        await store.fetchUsage(gatewayURL: "http://mock.test", connectionMode: .cloud)
        XCTAssertEqual(store.usageCount, 0)
        XCTAssertNil(store.usageLimit)
    }

    func testSignOutCloudClearsAllState() {
        let store = CloudAuthStore()
        store.supabaseJWT = "jwt"
        store.supabaseRefresh = "refresh"
        store.supabaseUserID = "uid"
        store.supabaseEmail = "a@b.c"
        store.cloudPlan = "pro"
        store.usageCount = 42
        store.usageLimit = 500

        store.signOutCloud()

        XCTAssertEqual(store.supabaseJWT, "")
        XCTAssertEqual(store.supabaseRefresh, "")
        XCTAssertEqual(store.supabaseUserID, "")
        XCTAssertEqual(store.supabaseEmail, "")
        XCTAssertEqual(store.cloudPlan, "")
        XCTAssertEqual(store.usageCount, 0)
        XCTAssertNil(store.usageLimit)
        XCTAssertFalse(store.isCloudAuthenticated)
        // Keychain에서도 제거된다
        XCTAssertNil(KeychainHelper.get("supabase_jwt"))
    }
}
