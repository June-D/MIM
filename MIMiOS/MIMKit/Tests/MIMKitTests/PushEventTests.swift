import XCTest
@testable import MIMKit

/// 推送帧解析：类型编号错一个，界面上就是「消息到了但红点不消失」。
///
/// 这里钉住服务端 `CommonConstants` 里的真实编号——
/// 已读回执是 **100012**（我第一版写成 100011，代价是整条已读链路静默失效）。
final class PushEventTests: XCTestCase {

    private func frame(_ type: Int, data: String = "{}") -> String {
        "{\"type\":\(type),\"data\":\(data)}"
    }

    func testNewMessageAndRecallBothWakeUpSync() {
        XCTAssertEqual(MIMWebSocket.parse(frame(100010)), .newData)
        XCTAssertEqual(MIMWebSocket.parse(frame(100014)), .newData)
    }

    func testReadReceiptCarriesReaderPeer() {
        XCTAssertEqual(
            MIMWebSocket.parse(frame(100012, data: #"{"peer":"composebob","beforeId":null}"#)),
            .readReceipt(peer: "composebob")
        )
    }

    func testLegacyReadReceiptTypeStillParsed() {
        // 旧编号仍然认，避免历史服务端版本下静默丢事件。
        XCTAssertEqual(
            MIMWebSocket.parse(frame(100011, data: #"{"peer":"bob"}"#)),
            .readReceipt(peer: "bob")
        )
    }

    func testClearedAndReadSync() {
        XCTAssertEqual(
            MIMWebSocket.parse(frame(100019, data: #"{"peer":"bob"}"#)),
            .cleared(peer: "bob")
        )
        XCTAssertEqual(
            MIMWebSocket.parse(frame(100024, data: #"{"peer":"bob"}"#)),
            .readSync(peer: "bob")
        )
    }

    func testHistorySyncRequiresDeviceId() {
        XCTAssertEqual(
            MIMWebSocket.parse(frame(100022, data: #"{"deviceId":"device-b"}"#)),
            .syncRequested(deviceId: "device-b")
        )
        XCTAssertNil(MIMWebSocket.parse(frame(100022, data: "{}")), "没有设备 id 的请求无法处理")
    }

    func testUnknownAndMalformedFramesAreIgnored() {
        XCTAssertNil(MIMWebSocket.parse(frame(999999)))
        XCTAssertNil(MIMWebSocket.parse("{\"ping\":true}"))
        XCTAssertNil(MIMWebSocket.parse("not json"))
    }
}
