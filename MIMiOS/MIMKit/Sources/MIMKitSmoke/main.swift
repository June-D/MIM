import Foundation
import MIMKit

// 不依赖 XCTest 的冒烟程序：Command Line Tools 里没有 XCTest，
// 但 `swift run MIMKitSmoke` 现在就能跑——装 Xcode 之前也能验证 MIMKit 是否可用。
//
// 断言失败直接退出并打印原因，方便一眼看出是哪一层坏了。

func check(_ condition: Bool, _ message: String) {
    guard condition else {
        FileHandle.standardError.write(Data("✗ \(message)\n".utf8))
        exit(1)
    }
    print("· \(message)")
}

func hex(_ value: String) -> Data {
    var bytes = [UInt8]()
    var index = value.startIndex
    while index < value.endIndex {
        let next = value.index(index, offsetBy: 2)
        bytes.append(UInt8(value[index..<next], radix: 16) ?? 0)
        index = next
    }
    return Data(bytes)
}

let tag = "gTq0Y2v5Qm9m8Zt0k3Wc2Xr7Lp4Nb1Hs6Jd9Ff2Ka5E"

/// `--login <url> <user> <password>`：对真服务端跑一遍 M1 的 HTTP 路径。
/// 这是 iOS 侧与后端的第一条真实链路，比看文档可靠。
func liveSmoke(_ arguments: [String]) async {
    // arguments[0] 是可执行文件，[1] 是 --login，所以参数从 [2] 开始。
    guard arguments.count >= 5, let url = URL(string: arguments[2]) else {
        FileHandle.standardError.write(Data("用法：MIMKitSmoke --login <url> <user> <password>\n".utf8))
        exit(2)
    }
    let user = arguments[3]
    let password = arguments[4]
    let deviceId = "swift-smoke-\(Int(Date().timeIntervalSince1970))"

    do {
        let client = MIMClient(baseURL: url, deviceId: deviceId)
        _ = try await client.login(username: user, password: password)
        check(true, "登录成功（\(user) / \(deviceId)）")

        let bundle = try MimCore.makeKeyBundle(oneTimePreKeys: 5)
        try await client.publishKeys(password: password, deviceName: "Swift Smoke", bundle: bundle)
        check(true, "密钥包已发布（身份 + 签名预密钥 + 5 个一次性预密钥）")

        let devices = try await client.devices()
        check(devices.contains { $0.deviceId == deviceId }, "设备列表包含本机（共 \(devices.count) 台）")

        // 冒烟自己注册的设备要撤掉，否则每跑一次就多一台「幽灵设备」。
        try await client.revokeDevice(deviceId)
        check(true, "已注销本次冒烟注册的设备")
        print("LIVE_SMOKE_OK")
    } catch {
        FileHandle.standardError.write(Data("✗ 真实链路失败：\(error)\n".utf8))
        exit(1)
    }
}

/// `--chat <url>`：注册两个临时账号，跑一遍「A 发 → B 收 → B 回 → A 收」。
///
/// 这是 M2 的核心验证：X3DH 首条消息 + 双棘轮 + 服务端多设备副本 + 解密，
/// 全部走 Swift 与真服务端，不依赖界面。
func chatSmoke(_ arguments: [String]) async {
    guard arguments.count >= 3, let url = URL(string: arguments[2]) else {
        FileHandle.standardError.write(Data("用法：MIMKitSmoke --chat <url>\n".utf8))
        exit(2)
    }
    let suffix = String(Int(Date().timeIntervalSince1970) % 100000)
    let userA = "swiftchat-a-\(suffix)"
    let userB = "swiftchat-b-\(suffix)"
    let password = "SwiftChat123"

    do {
        // 1) 注册 + 登录（各自独立设备 id）
        let clientA = MIMClient(baseURL: url, deviceId: "swift-a-\(suffix)")
        let clientB = MIMClient(baseURL: url, deviceId: "swift-b-\(suffix)")
        try await clientA.register(username: userA, password: password)
        try await clientB.register(username: userB, password: password)
        _ = try await clientA.login(username: userA, password: password)
        _ = try await clientB.login(username: userB, password: password)
        check(true, "两个账号已注册并登录（\(userA) / \(userB)）")

        // 2) 发布密钥（不发布的话对方查不到预密钥）
        let bundleA = try MimCore.makeKeyBundle(oneTimePreKeys: 5)
        let bundleB = try MimCore.makeKeyBundle(oneTimePreKeys: 5)
        try await clientA.publishKeys(password: password, deviceName: "Swift A", bundle: bundleA)
        try await clientB.publishKeys(password: password, deviceName: "Swift B", bundle: bundleB)
        check(true, "双方密钥已发布")

        // 3) A → B：首条消息（X3DH + 证书信封）
        let sessionA = MessagingSession(client: clientA, bundle: bundleA)
        let hello = "你好，来自 Swift \(suffix)"
        let delivered = try await sessionA.sendText(to: userB, text: hello)
        check(delivered >= 1, "A 已投递到 \(delivered) 台设备")

        // 4) B 收到并解密
        let sessionB = MessagingSession(client: clientB, bundle: bundleB)
        let inboxB = try await sessionB.receive()
        guard let firstInB = inboxB.first else {
            check(false, "B 没有收到消息")
            exit(1)
        }
        check(firstInB.text == hello, "B 解出：\(firstInB.text)")
        check(firstInB.peer == userA, "B 识别出是从 \(firstInB.peer) 发来的")

        // 5) B 回复：走已建立的会话（双棘轮）
        let reply = "收到，这是回信 \(suffix)"
        _ = try await sessionB.sendText(to: userA, text: reply)
        let inboxA = try await sessionA.receive()
        guard let firstInA = inboxA.first else {
            check(false, "A 没有收到回信")
            exit(1)
        }
        check(firstInA.text == reply, "A 解出回信：\(firstInA.text)")

        // 6) 会话存储与索引都要能落盘（界面侧会加密存起来）
        check(!sessionA.sessions.isEmpty, "会话索引非空（\(sessionA.sessions.count) 条）")
        print("CHAT_SMOKE_OK")
    } catch {
        FileHandle.standardError.write(Data("✗ 聊天链路失败：\(error)\n".utf8))
        exit(1)
    }
}

/// `--send <url> <user> <pass> <peer> <text>`：用某个账号给对端发一条消息。
///
/// 会为这次发送注册一个**新设备**（独立的设备 id），因此不会覆盖该账号
/// 已有设备的预密钥——用来在命令行驱动真机/模拟器做收发验证。
func sendOnce(_ arguments: [String]) async {
    guard arguments.count >= 7, let url = URL(string: arguments[2]) else {
        FileHandle.standardError.write(
            Data("用法：MIMKitSmoke --send <url> <user> <pass> <peer> <text>\n".utf8)
        )
        exit(2)
    }
    let user = arguments[3], password = arguments[4]
    let peer = arguments[5]
    let text = arguments[6...].joined(separator: " ")

    do {
        let client = MIMClient(
            baseURL: url,
            deviceId: "cli-\(user)-\(Int(Date().timeIntervalSince1970))"
        )
        _ = try await client.login(username: user, password: password)
        let bundle = try MimCore.makeKeyBundle(oneTimePreKeys: 5)
        try await client.publishKeys(password: password, deviceName: "CLI \(user)", bundle: bundle)
        let session = MessagingSession(client: client, bundle: bundle)
        let delivered = try await session.sendText(to: peer, text: text)
        check(true, "\(user) → \(peer) 已投递到 \(delivered) 台设备：\(text)")
        print("SEND_OK")
    } catch {
        FileHandle.standardError.write(Data("✗ 发送失败：\(error)\n".utf8))
        exit(1)
    }
}

/// `--receipt <url> <user> <pass> <peer>`：等对端发来一条消息，然后标记已读。
///
/// 用来验证「已读回执」这条链路：本进程作为**新设备**注册并发布预密钥，
/// 对端随后发来的消息会扇出一份到本设备，解出来后调用 markRead，
/// 服务端就会推 100011 给对方。
func receiptSmoke(_ arguments: [String]) async {
    guard arguments.count >= 6, let url = URL(string: arguments[2]) else {
        FileHandle.standardError.write(
            Data("用法：MIMKitSmoke --receipt <url> <user> <pass> <peer>\n".utf8)
        )
        exit(2)
    }
    let user = arguments[3], password = arguments[4], peer = arguments[5]
    do {
        let client = MIMClient(
            baseURL: url,
            deviceId: "cli-read-\(Int(Date().timeIntervalSince1970))"
        )
        _ = try await client.login(username: user, password: password)
        let bundle = try MimCore.makeKeyBundle(oneTimePreKeys: 5)
        try await client.publishKeys(password: password, deviceName: "CLI Reader", bundle: bundle)
        check(true, "已注册为 \(user) 的新设备，等待来自 \(peer) 的消息")

        let session = MessagingSession(client: client, bundle: bundle)
        for _ in 0..<25 {
            let received = try await session.receive()
            if let first = received.first {
                let ids = received.map(\.clientMsgId).filter { !$0.isEmpty }
                try await client.markRead(peer: peer, clientMsgIds: ids)
                check(true, "收到「\(first.text)」并已标记已读")
                print("RECEIPT_OK")
                return
            }
            try await Task.sleep(nanoseconds: 2_000_000_000)
        }
        FileHandle.standardError.write(Data("✗ 40 秒内没有收到消息\n".utf8))
        exit(1)
    } catch {
        FileHandle.standardError.write(Data("✗ 已读回执链路失败：\(error)\n".utf8))
        exit(1)
    }
}

/// `--listen <url> <user> <pass> [秒]`：只连 WebSocket 并打印收到的事件。
///
/// 单独把长连接拿出来验证：不涉及模拟器、不涉及界面，收到什么就是什么。
func listenSmoke(_ arguments: [String]) async {
    guard arguments.count >= 5, let url = URL(string: arguments[2]) else {
        FileHandle.standardError.write(
            Data("用法：MIMKitSmoke --listen <url> <user> <pass> [秒]\n".utf8)
        )
        exit(2)
    }
    let user = arguments[3], password = arguments[4]
    let seconds = Int(arguments.count > 5 ? arguments[5] : "30") ?? 30
    do {
        let client = MIMClient(
            baseURL: url,
            deviceId: "cli-listen-\(Int(Date().timeIntervalSince1970))"
        )
        _ = try await client.login(username: user, password: password)
        let bundle = try MimCore.makeKeyBundle(oneTimePreKeys: 5)
        try await client.publishKeys(password: password, deviceName: "CLI Listen", bundle: bundle)
        check(true, "已登录并发布密钥，开始监听 \(seconds) 秒…")

        let socket = MIMWebSocket(baseURL: url, token: client.currentToken ?? "", deviceId: client.deviceId)
        let deadline = Date().addingTimeInterval(Double(seconds))
        for await event in socket.events() {
            // 直接写 stderr：重定向到文件时 stdout 是块缓冲的，看不到实时输出。
            FileHandle.standardError.write(Data("EVENT \(event)\n".utf8))
            if Date() > deadline { break }
        }
        FileHandle.standardError.write(Data("LISTEN_DONE\n".utf8))
    } catch {
        FileHandle.standardError.write(Data("✗ 监听失败：\(error)\n".utf8))
        exit(1)
    }
}

if CommandLine.arguments.count > 1, CommandLine.arguments[1] == "--listen" {
    await listenSmoke(CommandLine.arguments)
    exit(0)
}

if CommandLine.arguments.count > 1, CommandLine.arguments[1] == "--receipt" {
    await receiptSmoke(CommandLine.arguments)
    exit(0)
}

if CommandLine.arguments.count > 1, CommandLine.arguments[1] == "--send" {
    await sendOnce(CommandLine.arguments)
    exit(0)
}

if CommandLine.arguments.count > 1, CommandLine.arguments[1] == "--chat" {
    await chatSmoke(CommandLine.arguments)
    exit(0)
}

if CommandLine.arguments.count > 1, CommandLine.arguments[1] == "--login" {
    await liveSmoke(CommandLine.arguments)
    exit(0)
}

do {
    print("MIMKit 冒烟（core \(MimCore.version)）")

    let alice = try MimCore.makeKeyBundle(oneTimePreKeys: 1)
    let bob = try MimCore.makeKeyBundle(oneTimePreKeys: 1)
    check(!alice.identity.privateKey.isEmpty, "生成 alice 密钥包")
    guard let bobSigned = bob.signedPreKeys.first,
          let bobOneTime = bob.oneTimePreKeys.first else {
        check(false, "bob 密钥包缺少预密钥")
        exit(1)
    }

    let composed = try MimCore.composeFirstMessage(ComposeFirstRequest(
        identityPrivate: alice.identity.privateKey,
        identityPublic: alice.identity.publicKey,
        peerIdentityPublic: bob.identity.publicKey,
        peerSignedPreKeyId: bobSigned.id,
        peerSignedPreKeyPublic: bobSigned.publicKey,
        peerOneTimePreKeyId: bobOneTime.id,
        peerOneTimePreKeyPublic: bobOneTime.publicKey,
        plaintext: "iOS 第一条消息",
        conversationTag: tag,
        sessionId: "session-ios",
        certificate: SenderCertificate(
            username: "alice",
            deviceId: "alice-iphone",
            identityKey: hex(alice.identity.publicKey).base64EncodedString(),
            issuedAt: 1_795_056_000_000,
            expiresAt: 1_795_142_400_000,
            keyId: String(repeating: "00", count: 32),
            signature: "c2lnbmF0dXJl"
        )
    ))
    check(composed.conversationTag == tag, "组装首条消息（tag 一致）")

    let opened = try MimCore.openFirstMessage(OpenFirstRequest(
        identityPrivate: bob.identity.privateKey,
        identityPublic: bob.identity.publicKey,
        signedPreKeyPrivate: bobSigned.privateKey,
        signedPreKeyPublic: bobSigned.publicKey,
        oneTimePreKeyPrivate: bobOneTime.privateKey,
        peerIdentityPublic: composed.senderIdentityKey,
        peerEphemeralPublic: composed.senderEphemeralKey,
        usedOneTimePreKey: true,
        wire: composed.wire
    ))
    check(opened.plaintext == "iOS 第一条消息", "对端解出明文：\(opened.plaintext)")
    check(opened.certificate.deviceId == "alice-iphone", "证书归属还原为 alice-iphone")

    let payload = Data("msg-1|\(tag)|alice|bob|device-9".utf8)
    let signature = try MimCore.sign(identityPrivateHex: alice.identity.privateKey, payload: payload)
    check(MimCore.verify(identityPublic: hex(alice.identity.publicKey),
                         payload: payload, signatureBase64: signature), "身份签名往返")
    check(!MimCore.verify(identityPublic: hex(alice.identity.publicKey),
                          payload: Data("tampered".utf8), signatureBase64: signature), "篡改后验签失败")

    print("MIMKIT_SMOKE_OK")
} catch {
    FileHandle.standardError.write(Data("✗ 抛错：\(error)\n".utf8))
    exit(1)
}
