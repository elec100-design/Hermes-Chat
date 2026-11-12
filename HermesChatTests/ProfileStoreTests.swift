import XCTest
@testable import HermesChat

/// ProfileStore 단위 테스트. 실제 Keychain/UserDefaults를 사용하므로
/// 테스트 전후에 관련 키를 정리해 격리한다.
@MainActor
final class ProfileStoreTests: XCTestCase {
    override func setUp() {
        super.setUp()
        UserDefaults.standard.removeObject(forKey: "hermesProfiles")
        UserDefaults.standard.removeObject(forKey: "selectedProfileName")
        for key in ["profileApiKey.default", "profileApiKey.alpha", "profileApiKey.beta", "profileApiKey.work"] {
            KeychainHelper.delete(key)
        }
    }

    override func tearDown() {
        UserDefaults.standard.removeObject(forKey: "hermesProfiles")
        UserDefaults.standard.removeObject(forKey: "selectedProfileName")
        super.tearDown()
    }

    func testEmptyStorageFallsBackToDefaultProfile() {
        let store = ProfileStore()
        XCTAssertEqual(store.profiles.map(\.name), ["default"])
        XCTAssertNotNil(store.selectedProfileID)
        XCTAssertEqual(store.selectedProfile.name, "default")
    }

    func testAddProfileSortsByPortAndPersists() {
        let store = ProfileStore()
        store.addProfile(name: "zeta", port: 9000)
        store.addProfile(name: "alpha", port: 8500)

        XCTAssertEqual(store.profiles.map(\.name), ["alpha", "default", "zeta"])
        XCTAssertEqual(store.profiles.map(\.port), [8500, 8642, 9000])

        // 새 인스턴스가 같은 목록을 읽어온다 (UserDefaults 영속성)
        let reloaded = ProfileStore()
        XCTAssertEqual(reloaded.profiles.map(\.name), ["alpha", "default", "zeta"])
    }

    func testAddProfileIgnoresDuplicatePortAndEmptyName() {
        let store = ProfileStore()
        store.addProfile(name: "work", port: 8643)
        store.addProfile(name: "dup", port: 8643)
        store.addProfile(name: "   ", port: 8650)

        XCTAssertEqual(store.profiles.filter { $0.name != "default" }.count, 1)
        XCTAssertEqual(store.profiles.map(\.name).sorted(), ["default", "work"])
    }

    func testSelectProfilePersistsAndInvokesCallback() {
        let store = ProfileStore()
        store.addProfile(name: "work", port: 8643)
        let work = store.profiles.first { $0.name == "work" }!

        var selectedOnCallback: HermesProfile?
        store.onProfileSelectionChanged = { selectedOnCallback = store.selectedProfile }

        store.selectProfile(work)
        XCTAssertEqual(store.selectedProfileID, work.id)
        XCTAssertEqual(store.selectedProfile.name, "work")
        XCTAssertEqual(UserDefaults.standard.string(forKey: "selectedProfileName"), "work")
        XCTAssertEqual(selectedOnCallback?.name, "work")
    }

    func testRemoveProfilesRemovesKeychainApiKey() {
        let store = ProfileStore()
        store.addProfile(name: "alpha", port: 9100)
        KeychainHelper.set("secret", for: "profileApiKey.alpha")

        let idx = store.profiles.firstIndex { $0.name == "alpha" }!
        store.removeProfiles(at: IndexSet(integer: idx))

        XCTAssertFalse(store.profiles.contains { $0.name == "alpha" })
        XCTAssertNil(KeychainHelper.get("profileApiKey.alpha"))
    }

    func testUpdateProfileRenameMigratesKeychainKey() {
        let store = ProfileStore()
        store.addProfile(name: "alpha", port: 9100)
        var renamed = store.profiles.first { $0.name == "alpha" }!
        renamed.name = "beta"
        renamed.apiKey = "secret"
        store.updateProfile(renamed)

        XCTAssertEqual(store.profiles.first { $0.id == renamed.id }?.name, "beta")
        XCTAssertNil(KeychainHelper.get("profileApiKey.alpha"))
        XCTAssertEqual(KeychainHelper.get("profileApiKey.beta"), "secret")
    }

    func testBaseURLCombinesHostSchemeAndProfilePort() {
        let store = ProfileStore()
        store.hostProvider = { "http://my-mac.local:9999/some/path" }

        let url = store.baseURL(for: HermesProfile(name: "x", port: 8650))
        XCTAssertEqual(url.absoluteString, "http://my-mac.local:8650")
    }

    func testBaseURLDefaultsToLocalhost() {
        let store = ProfileStore()
        store.hostProvider = { "" }

        let url = store.baseURL(for: HermesProfile(name: "x", port: 8642))
        XCTAssertEqual(url.absoluteString, "http://localhost:8642")
    }

    func testDiscoverProfilesOverClosedPortsReturnsZero() async {
        let store = ProfileStore()
        let changed = await store.discoverProfiles(ports: [1, 2, 3])
        XCTAssertEqual(changed, 0)
        XCTAssertFalse(store.isDiscoveringProfiles)
        XCTAssertEqual(store.profiles.count, 1) // 기본 프로필만
    }
}
