//! MiM 协议核心的 Rust 实现（spike）。
//!
//! 只覆盖与平台无关的密码学原语与线格式：HKDF、AES-256-GCM、
//! 棘轮 KDF、AAD 序列化、标签/同步信封、证书规范化串、SHA-256 十六进制。
//!
//! 目标不是完整客户端，而是验证「同一份协议能否在 Rust 中逐字节复现」。
//! 基准向量由 Java 核心（`MIMCore/tools/ExportVectors.java`）生成，
//! 见 `tests/vectors_test.rs`。

use aes_gcm::aead::{Aead, KeyInit, Payload};
use aes_gcm::{Aes256Gcm, Nonce};
use hkdf::Hkdf;
use p256::pkcs8::{DecodePrivateKey, DecodePublicKey};
use hmac::{Hmac, Mac};
use sha2::{Digest, Sha256};

pub mod ffi;
pub mod ratchet;
pub mod session;

type HmacSha256 = Hmac<Sha256>;

const INFO_ROOT: &str = "MIM/v2/root";
const INFO_CHAIN: &str = "MIM/v2/chain";
const CHAIN_MESSAGE: u8 = 0x01;
const CHAIN_NEXT: u8 = 0x03;

const TAG_MAGIC: &str = "\u{1}mimtag1\u{1}";
const SYNC_MAGIC: &str = "\u{1}mimsync1\u{1}";

/// P-256 ECDH：返回共享点的 X 坐标（32 字节），与 JDK ECDH 的输出一致。
pub fn dh(private_pkcs8: &[u8], public_spki: &[u8]) -> Option<Vec<u8>> {
    let secret = p256::SecretKey::from_pkcs8_der(private_pkcs8).ok()?;
    let public = p256::PublicKey::from_public_key_der(public_spki).ok()?;
    let shared = p256::ecdh::diffie_hellman(secret.to_nonzero_scalar(), public.as_affine());
    Some(shared.raw_secret_bytes().to_vec())
}

/// X3DH 初始共享密钥：DH1||DH2||DH3||DH4 → HKDF("MIM/v2/initial")。
pub fn x3dh_initial_key(dh_parts: &[&[u8]]) -> Vec<u8> {
    let shared: Vec<u8> = dh_parts.iter().flat_map(|p| p.iter().copied()).collect();
    hkdf_derive(None, &shared, "MIM/v2/initial", 32)
}

/// RFC 5869 HKDF-SHA256；salt 为空时按 32 字节全零处理。
pub fn hkdf_derive(salt: Option<&[u8]>, ikm: &[u8], info: &str, len: usize) -> Vec<u8> {
    let zeros = [0u8; 32];
    let salt = salt.filter(|s| !s.is_empty()).unwrap_or(&zeros);
    let hk = Hkdf::<Sha256>::new(Some(salt), ikm);
    let mut out = vec![0u8; len];
    hk.expand(info.as_bytes(), &mut out).expect("hkdf expand");
    out
}

/// AES-256-GCM，返回密文后附 16 字节 tag（与 JDK Cipher 的输出一致）。
pub fn aes_gcm_encrypt(key: &[u8], iv: &[u8], aad: &[u8], plaintext: &[u8]) -> Vec<u8> {
    let cipher = Aes256Gcm::new_from_slice(key).expect("aes key");
    let nonce = Nonce::from_slice(iv);
    cipher
        .encrypt(nonce, Payload { msg: plaintext, aad })
        .expect("aes-gcm encrypt")
}

pub fn aes_gcm_decrypt(key: &[u8], iv: &[u8], aad: &[u8], ciphertext: &[u8]) -> Option<Vec<u8>> {
    let cipher = Aes256Gcm::new_from_slice(key).ok()?;
    let nonce = Nonce::from_slice(iv);
    cipher
        .decrypt(nonce, Payload { msg: ciphertext, aad })
        .ok()
}

/// 棘轮根密钥派生：HKDF-Extract(root, dh) 后分别扩展出 root/chain。
pub fn root_kdf(root: &[u8], dh: &[u8]) -> (Vec<u8>, Vec<u8>) {
    let hk = Hkdf::<Sha256>::new(Some(root), dh);
    let mut new_root = [0u8; 32];
    let mut chain = [0u8; 32];
    hk.expand(INFO_ROOT.as_bytes(), &mut new_root).expect("root");
    hk.expand(INFO_CHAIN.as_bytes(), &mut chain).expect("chain");
    (new_root.to_vec(), chain.to_vec())
}

/// 链密钥推进：mk = HMAC(ck, 0x01)，next = HMAC(ck, 0x03)。
pub fn chain_step(chain: &[u8]) -> (Vec<u8>, Vec<u8>) {
    let mk = hmac(chain, CHAIN_MESSAGE);
    let next = hmac(chain, CHAIN_NEXT);
    (mk, next)
}

fn hmac(key: &[u8], marker: u8) -> Vec<u8> {
    let mut mac = <HmacSha256 as Mac>::new_from_slice(key).expect("hmac key");
    mac.update(&[marker]);
    mac.finalize().into_bytes().to_vec()
}

/// 消息头 AAD：`dh|pn|n`，字段顺序固定。
pub fn header_aad(dh: &str, previous_chain_length: u32, message_number: u32) -> String {
    format!("{}|{}|{}", dh, previous_chain_length, message_number)
}

/// 标签信封（已确认会话的每条消息都带）。
pub fn tag_envelope_pack(tag: &str, payload: &str) -> String {
    if tag.is_empty() {
        return payload.to_string();
    }
    format!("{}{}\n{}", TAG_MAGIC, tag, payload)
}

pub fn tag_envelope_unpack(text: &str) -> Option<(&str, &str)> {
    let rest = text.strip_prefix(TAG_MAGIC)?;
    let sep = rest.find('\n')?;
    let (tag, body) = rest.split_at(sep);
    if tag.is_empty() || tag.len() > 64 {
        return None;
    }
    Some((tag, &body[1..]))
}

/// 同步副本里的撤回凭证信封。
pub fn sync_envelope_pack(token: &str, payload: &str) -> String {
    if token.is_empty() {
        return payload.to_string();
    }
    format!("{}{}\n{}", SYNC_MAGIC, token, payload)
}

/// 发送者证书的规范化串；改一个字节都会让签名失效。
pub fn cert_canonical(
    username: &str,
    device_id: &str,
    identity_key: &str,
    issued_at: i64,
    expires_at: i64,
    key_id: &str,
) -> String {
    format!(
        "MIM-SENDER-CERT-1\n{}\n{}\n{}\n{}\n{}\n{}\n",
        username, device_id, identity_key, issued_at, expires_at, key_id
    )
}

pub fn sha256_hex(value: &[u8]) -> String {
    hex::encode(Sha256::digest(value))
}
