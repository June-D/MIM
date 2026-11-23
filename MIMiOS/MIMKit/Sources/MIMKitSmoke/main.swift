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
