import XCTest
@testable import MIMKit

/// 推送跳转的核心：`conversation_tag` → 对端用户名。
///
/// 服务端拿不到发送者（没有 from 列），只能把标签带进推送；
/// 反查必须只靠本机索引，因此这段逻辑值得单独钉住。
final class SessionIndexTests: XCTestCase {

    private func ref(_ tag: String) -> SessionRef {
        SessionRef(sessionId: "s-\(tag)", tag: tag)
    }

    func testResolvesPeerFromTag() {
        let sessions = [
            "composebob|device-a": ref("tagA"),
            "composebob|device-b": ref("tagB"),
            "zhangsan|device-c": ref("tagC"),
        ]
        XCTAssertEqual(SessionIndex.peer(forTag: "tagB", in: sessions), "composebob")
        XCTAssertEqual(SessionIndex.peer(forTag: "tagC", in: sessions), "zhangsan")
    }

    func testUnknownTagReturnsNil() {
        let sessions = ["composebob|device-a": ref("tagA")]
        XCTAssertNil(SessionIndex.peer(forTag: "nope", in: sessions))
        XCTAssertNil(SessionIndex.peer(forTag: "", in: sessions))
        XCTAssertNil(SessionIndex.peer(forTag: "tagA", in: [:]))
    }

    func testPeerWithDeviceSuffixIsSplitCorrectly() {
        // 键里含多个 "|" 时只取第一段（用户名），不能被设备 id 带偏。
        let sessions = ["a|b|c": ref("tagX")]
        XCTAssertEqual(SessionIndex.peer(forTag: "tagX", in: sessions), "a")
    }
}
