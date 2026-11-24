import CryptoKit
import Foundation
import Security

/// 证书锁定（开发期用自签证书时使用）。
///
/// 为什么不用「把自签证书设为信任锚点」：
/// `SecTrustSetAnchorCertificates` 要求锚点能构成合法链，而 keytool 生成的自签证书
/// 默认不带 `CA:TRUE`，于是校验失败——在 URLSession 里表现为
/// `transport("cancelled")`（委托取消了认证挑战），极难从错误信息看出是证书问题。
///
/// 这里改成**指纹锁定**：只比对服务端证书 DER 的 SHA-256 是否等于随包分发的那一张。
/// 这是业界常用的 certificate pinning，语义比「信任某个 CA」更严格——
/// 只认这一张证书，换证书必须发新版本。
public final class PinnedSession: NSObject, URLSessionDelegate, @unchecked Sendable {

    /// 最近一次握手失败的原因，供上层给出可读提示。
    public private(set) var lastFailure: String?

    private let expectedFingerprint: String
    private let queue = DispatchQueue(label: "ai.mim.pinning")

    /// - Parameter certificateDER: 自签证书的 DER 字节（app 内随包分发）。
    public init?(certificateDER: Data) {
        guard !certificateDER.isEmpty else { return nil }
        self.expectedFingerprint = Self.fingerprint(of: certificateDER)
        super.init()
    }

    public func makeSession(configuration: URLSessionConfiguration) -> URLSession {
        URLSession(configuration: configuration, delegate: self, delegateQueue: nil)
    }

    public func urlSession(
        _ session: URLSession,
        didReceive challenge: URLAuthenticationChallenge,
        completionHandler: @escaping (URLSession.AuthChallengeDisposition, URLCredential?) -> Void
    ) {
        guard challenge.protectionSpace.authenticationMethod == NSURLAuthenticationMethodServerTrust,
              let trust = challenge.protectionSpace.serverTrust,
              let chain = SecTrustCopyCertificateChain(trust) as? [SecCertificate],
              let leaf = chain.first else {
            completionHandler(.performDefaultHandling, nil)
            return
        }

        let actual = Self.fingerprint(of: SecCertificateCopyData(leaf) as Data)
        if actual == expectedFingerprint {
            // 指纹一致即放行：不再让系统去「构建链」，那样会在自签证书上卡住。
            completionHandler(.useCredential, URLCredential(trust: trust))
        } else {
            queue.sync {
                lastFailure = "服务器证书与 App 内置的不一致（期望 \(expectedFingerprint.prefix(16))…，"
                    + "实际 \(actual.prefix(16))…）"
            }
            completionHandler(.cancelAuthenticationChallenge, nil)
        }
    }

    private static func fingerprint(of data: Data) -> String {
        SHA256.hash(data: data).map { String(format: "%02x", $0) }.joined()
    }
}
