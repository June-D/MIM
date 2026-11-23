import XCTest
@testable import MIMKit

/// Keychain 用例默认跳过：某些 CI / 无签名环境访问钥匙串会弹授权框或直接失败。
/// 需要时用 `MIM_KEYCHAIN_TESTS=1 swift test` 打开。
final class KeychainTests: XCTestCase {

    private var keychain: Keychain!

    override func setUpWithError() throws {
        try XCTSkipUnless(
            ProcessInfo.processInfo.environment["MIM_KEYCHAIN_TESTS"] == "1",
            "设置 MIM_KEYCHAIN_TESTS=1 才跑钥匙串用例"
        )
        keychain = Keychain(service: "ai.mim.app.tests")
        keychain.deleteAll()
    }

    override func tearDownWithError() throws {
        keychain?.deleteAll()
    }

    func testRoundTripAndOverwrite() throws {
        let account = "identity"
        try keychain.set(Data("first".utf8), account: account)
        XCTAssertEqual(keychain.get(account: account), Data("first".utf8))

        // 覆盖写：先删后加，不能出现两条或旧值残留。
        try keychain.set(Data("second".utf8), account: account)
        XCTAssertEqual(keychain.get(account: account), Data("second".utf8))

        XCTAssertTrue(keychain.delete(account: account))
        XCTAssertNil(keychain.get(account: account))
    }

    func testDeleteAllClearsService() throws {
        try keychain.set(Data("a".utf8), account: "one")
        try keychain.set(Data("b".utf8), account: "two")

        XCTAssertTrue(keychain.deleteAll())
        XCTAssertNil(keychain.get(account: "one"))
        XCTAssertNil(keychain.get(account: "two"))
    }
}
