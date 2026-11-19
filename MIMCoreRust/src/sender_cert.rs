//! 发送者证书的解析与验签（spike）。
//!
//! 与 `MIMCore` 的 `SenderCertificateView` / `SenderCertVerifier` 对齐：
//! - 规范化串字段顺序固定，改一个字节签名即失效；
//! - `keyId` 必须是服务端公钥 DER 的 SHA-256 十六进制；
//! - 时间窗口容忍 5 分钟时钟偏差；
//! - 任何解析或验签异常都按不可信处理。

use crate::sha256_hex;
use rsa::pkcs8::DecodePublicKey;
use rsa::{Pkcs1v15Sign, RsaPublicKey};
use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};

/// 与服务端一致的时钟偏差容忍。
pub const CLOCK_SKEW_MS: i64 = 5 * 60 * 1000;

#[derive(Serialize, Deserialize, Clone, Debug, PartialEq, Eq)]
pub struct SenderCertificate {
    pub username: String,
    pub device_id: String,
    pub identity_key: String,
    pub issued_at: i64,
    pub expires_at: i64,
    pub key_id: String,
    pub signature: String,
}

impl SenderCertificate {
    /// 规范化串：验签唯一允许的字节序列。
    pub fn canonical(&self) -> String {
        format!(
            "MIM-SENDER-CERT-1\n{}\n{}\n{}\n{}\n{}\n{}\n",
            self.username,
            self.device_id,
            self.identity_key,
            self.issued_at,
            self.expires_at,
            self.key_id
        )
    }

    /// 验签。`server_public_key_der` 是服务端证书公钥的 SPKI DER。
    pub fn verify(&self, server_public_key_der: &[u8], now_ms: i64) -> bool {
        if self.expires_at + CLOCK_SKEW_MS < now_ms || self.issued_at - CLOCK_SKEW_MS > now_ms {
            return false;
        }
        if self.key_id != sha256_hex(server_public_key_der) {
            return false;
        }
        let signature = match b64_decode(&self.signature) {
            Some(v) => v,
            None => return false,
        };
        let key = match RsaPublicKey::from_public_key_der(server_public_key_der) {
            Ok(v) => v,
            Err(_) => return false,
        };
        let digest = Sha256::digest(self.canonical().as_bytes());
        key.verify(Pkcs1v15Sign::new::<Sha256>(), &digest, &signature)
            .is_ok()
    }

    /// 证书里的身份公钥必须与 X3DH 握手参数一致，否则拒绝这条消息。
    pub fn matches_handshake(&self, handshake_identity_key: &str) -> bool {
        self.identity_key == handshake_identity_key
    }
}

fn b64_decode(value: &str) -> Option<Vec<u8>> {
    use base64::Engine;
    base64::engine::general_purpose::STANDARD.decode(value).ok()
}
