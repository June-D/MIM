//! 设备密钥管理：身份密钥、签名预密钥、一次性预密钥。
//!
//! 与 `MIMCore` 的 `IdentityStore` / `PreKeyStore` 对齐的部分：
//! - 身份密钥与预密钥都是 P-256，公钥用 SPKI DER 的 Base64，私钥用 PKCS#8 DER；
//! - 签名预密钥的签名串是 `"{id}|{SPKI DER}"`，算法 `SHA256withECDSA`（DER 签名）；
//! - 一次性预密钥按递增 ID 批量生成，用掉即删。
//!
//! 与 Java 不同的部分：本模块不含文件格式与口令派生，状态序列化成 JSON 后
//! 由 [`crate::secure_store`] 加密，密钥来自平台 Keystore / Keychain。

use crate::secure_store;
use p256::ecdsa::signature::{Signer, Verifier};
use p256::ecdsa::{Signature, SigningKey, VerifyingKey};
use p256::pkcs8::{DecodePrivateKey, DecodePublicKey, EncodePrivateKey, EncodePublicKey};
use rand_core::OsRng;
use serde::{Deserialize, Serialize};
use std::time::{SystemTime, UNIX_EPOCH};

/// 默认一次性预密钥数量（与服务端配额保持同一量级）。
pub const DEFAULT_ONE_TIME_PRE_KEYS: u32 = 20;

/// 保留多少个历史签名预密钥，供尚未拉取新密钥的对端使用。
pub const SIGNED_PRE_KEY_KEEP: usize = 3;

#[derive(Serialize, Deserialize, Clone, Debug, PartialEq, Eq)]
pub struct IdentityKeyPair {
    pub version: u32,
    /// PKCS#8 DER 的十六进制。绝不出本机。
    pub private_key: String,
    /// SPKI DER 的十六进制。
    pub public_key: String,
}

#[derive(Serialize, Deserialize, Clone, Debug, PartialEq, Eq)]
pub struct SignedPreKey {
    pub id: u32,
    pub private_key: String,
    pub public_key: String,
    pub signature: String,
    pub created_at: i64,
}

#[derive(Serialize, Deserialize, Clone, Debug, PartialEq, Eq)]
pub struct OneTimePreKey {
    pub id: u32,
    pub private_key: String,
    pub public_key: String,
}

/// 一台设备的完整密钥状态。
#[derive(Serialize, Deserialize, Clone, Debug, PartialEq, Eq)]
pub struct KeyBundle {
    pub version: u32,
    pub identity: IdentityKeyPair,
    pub signed_pre_keys: Vec<SignedPreKey>,
    pub one_time_pre_keys: Vec<OneTimePreKey>,
    pub next_one_time_pre_key_id: u32,
}

fn now_ms() -> i64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|d| d.as_millis() as i64)
        .unwrap_or(0)
}

fn b64(value: &[u8]) -> String {
    use base64::Engine;
    base64::engine::general_purpose::STANDARD.encode(value)
}

fn generate_key_pair() -> Option<(Vec<u8>, Vec<u8>)> {
    let secret = p256::SecretKey::random(&mut OsRng);
    let private = secret.to_pkcs8_der().ok()?.as_bytes().to_vec();
    let public = secret.public_key().to_public_key_der().ok()?.as_bytes().to_vec();
    Some((private, public))
}

/// 生成身份密钥对。`version` 在「本地私钥丢失、服务端已有公钥」时递增。
pub fn generate_identity(version: u32) -> Option<IdentityKeyPair> {
    let (private, public) = generate_key_pair()?;
    Some(IdentityKeyPair {
        version,
        private_key: hex::encode(private),
        public_key: hex::encode(public),
    })
}

/// 签名串：`"{id}|{SPKI DER}"`。字段顺序与 Java 逐字节一致。
pub fn signed_pre_key_payload(pre_key_id: u32, public_key_der: &[u8]) -> Vec<u8> {
    let mut payload = pre_key_id.to_string().into_bytes();
    payload.push(b'|');
    payload.extend_from_slice(public_key_der);
    payload
}

fn signing_key(identity_private_hex: &str) -> Option<SigningKey> {
    let der = hex::decode(identity_private_hex).ok()?;
    SigningKey::from_pkcs8_der(&der).ok()
}

/// 生成签名预密钥。签名用身份私钥，算法与 Java 一致（ECDSA P-256 + SHA-256，DER 编码）。
pub fn sign_signed_pre_key(identity: &IdentityKeyPair, pre_key_id: u32) -> Option<SignedPreKey> {
    let (private, public) = generate_key_pair()?;
    let payload = signed_pre_key_payload(pre_key_id, &public);
    let key = signing_key(&identity.private_key)?;
    let signature: Signature = key.sign(&payload);
    Some(SignedPreKey {
        id: pre_key_id,
        private_key: hex::encode(private),
        public_key: hex::encode(&public),
        signature: b64(signature.to_der().as_bytes()),
        created_at: now_ms(),
    })
}

/// 用已固定的身份公钥验证预密钥签名。这是抗中间人的核心检查。
pub fn verify_signed_pre_key(
    identity_public_der: &[u8],
    pre_key_id: u32,
    signed_pre_key_public_der: &[u8],
    signature_base64: &str,
) -> bool {
    let signature = match b64_decode(signature_base64) {
        Some(v) => v,
        None => return false,
    };
    let signature = match Signature::from_der(&signature) {
        Ok(v) => v,
        Err(_) => return false,
    };
    let key = match VerifyingKey::from_public_key_der(identity_public_der) {
        Ok(v) => v,
        Err(_) => return false,
    };
    let payload = signed_pre_key_payload(pre_key_id, signed_pre_key_public_der);
    key.verify(&payload, &signature).is_ok()
}

/// 用身份私钥对任意载荷签名（ECDSA P-256 / SHA-256，DER 后 Base64）。
///
/// 设备间历史同步用它给「原消息归属 + 内容摘要」签名：服务端不参与验签，
/// 但接收设备会用已固定的身份公钥验，服务端伪造不了归属。
pub fn sign_payload(identity_private_hex: &str, payload: &[u8]) -> Option<String> {
    let key = signing_key(identity_private_hex)?;
    let signature: Signature = key.sign(payload);
    Some(b64(signature.to_der().as_bytes()))
}

/// 用身份公钥验证 [sign_payload] 的签名。任何解析失败都返回 false，不猜。
pub fn verify_payload(
    identity_public_der: &[u8],
    payload: &[u8],
    signature_base64: &str,
) -> bool {
    let signature = match b64_decode(signature_base64) {
        Some(v) => v,
        None => return false,
    };
    let signature = match Signature::from_der(&signature) {
        Ok(v) => v,
        Err(_) => return false,
    };
    let key = match VerifyingKey::from_public_key_der(identity_public_der) {
        Ok(v) => v,
        Err(_) => return false,
    };
    key.verify(payload, &signature).is_ok()
}

/// 批量生成一次性预密钥，ID 从 `start_id` 递增。
pub fn generate_one_time_pre_keys(start_id: u32, count: u32) -> Vec<OneTimePreKey> {
    let mut keys = Vec::with_capacity(count as usize);
    for offset in 0..count {
        let Some((private, public)) = generate_key_pair() else {
            break;
        };
        keys.push(OneTimePreKey {
            id: start_id.saturating_add(offset),
            private_key: hex::encode(private),
            public_key: hex::encode(public),
        });
    }
    keys
}

impl KeyBundle {
    /// 新建一台设备的密钥状态：身份密钥 + 1 个签名预密钥 + 一批一次性预密钥。
    pub fn generate(version: u32, one_time_count: u32) -> Option<Self> {
        let identity = generate_identity(version)?;
        let signed = sign_signed_pre_key(&identity, 1)?;
        let one_time = generate_one_time_pre_keys(1, one_time_count);
        let next_id = one_time.last().map(|k| k.id + 1).unwrap_or(1);
        Some(Self {
            version,
            identity,
            signed_pre_keys: vec![signed],
            one_time_pre_keys: one_time,
            next_one_time_pre_key_id: next_id,
        })
    }

    /// 轮换签名预密钥：ID 用当前最大值 +1，只保留最近 `SIGNED_PRE_KEY_KEEP` 个。
    pub fn rotate_signed_pre_key(&mut self) -> Option<SignedPreKey> {
        let next_id = self
            .signed_pre_keys
            .iter()
            .map(|k| k.id)
            .max()
            .unwrap_or(0)
            .saturating_add(1);
        let key = sign_signed_pre_key(&self.identity, next_id)?;
        self.signed_pre_keys.push(key.clone());
        self.signed_pre_keys.sort_by_key(|k| k.id);
        while self.signed_pre_keys.len() > SIGNED_PRE_KEY_KEEP {
            self.signed_pre_keys.remove(0);
        }
        Some(key)
    }

    /// 补充一次性预密钥。
    pub fn replenish_one_time_pre_keys(&mut self, count: u32) -> Vec<OneTimePreKey> {
        let keys = generate_one_time_pre_keys(self.next_one_time_pre_key_id, count);
        if let Some(last) = keys.last() {
            self.next_one_time_pre_key_id = last.id + 1;
        }
        self.one_time_pre_keys.extend(keys.iter().cloned());
        keys
    }

    /// 取用一个一次性预密钥；取到即从本地删除（一次性语义）。
    pub fn take_one_time_pre_key(&mut self, id: u32) -> Option<OneTimePreKey> {
        let index = self.one_time_pre_keys.iter().position(|k| k.id == id)?;
        Some(self.one_time_pre_keys.remove(index))
    }

    pub fn needs_replenish(&self, threshold: usize) -> bool {
        self.one_time_pre_keys.len() < threshold
    }

    /// 最近的签名预密钥（上传/发放给对端用）。
    pub fn current_signed_pre_key(&self) -> Option<&SignedPreKey> {
        self.signed_pre_keys.iter().max_by_key(|k| k.id)
    }

    pub fn to_json(&self) -> Option<String> {
        serde_json::to_string(self).ok()
    }

    pub fn from_json(json: &str) -> Option<Self> {
        serde_json::from_str(json).ok()
    }

    /// 加密落盘：JSON → AES-256-GCM（密钥来自平台密钥库）。
    pub fn save_encrypted(&self, key: &[u8], nonce: &[u8]) -> Option<String> {
        let json = self.to_json()?;
        secure_store::encrypt_state(json.as_bytes(), key, nonce)
    }

    pub fn load_encrypted(blob: &str, key: &[u8]) -> Option<Self> {
        let plaintext = secure_store::decrypt_state(blob, key)?;
        let json = std::str::from_utf8(&plaintext).ok()?;
        Self::from_json(json)
    }
}

fn b64_decode(value: &str) -> Option<Vec<u8>> {
    use base64::Engine;
    base64::engine::general_purpose::STANDARD.decode(value).ok()
}
