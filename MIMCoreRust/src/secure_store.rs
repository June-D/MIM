//! 加密状态存储（spike）。
//!
//! 会话状态含私钥与链密钥，不能明文落盘。平台侧（Android Keystore /
//! iOS Keychain）负责保管 32 字节密钥，本模块只做加解密与格式校验。
//!
//! 格式固定为三行文本，便于跨语言与跨进程交换：
//! ```text
//! MIM-STATE-1
//! <Base64(nonce, 12 字节)>
//! <Base64(密文 + 16 字节 GCM tag)>
//! ```
//! 版本串同时作为 AAD：版本被改动时解密必然失败，不会静默降级。

use aes_gcm::aead::{Aead, KeyInit, Payload};
use aes_gcm::{Aes256Gcm, Nonce};
use base64::Engine;
use rand_core::{OsRng, RngCore};

pub const STATE_MAGIC: &str = "MIM-STATE-1";
pub const KEY_BYTES: usize = 32;
pub const NONCE_BYTES: usize = 12;

/// 生成 12 字节随机 nonce。每条状态写入都必须用新 nonce。
pub fn new_nonce() -> [u8; NONCE_BYTES] {
    let mut nonce = [0u8; NONCE_BYTES];
    OsRng.fill_bytes(&mut nonce);
    nonce
}

/// 用安全随机填充任意长度缓冲（C ABI / JNI 生成 nonce 或密钥时使用）。
pub fn fill_random(buffer: &mut [u8]) {
    OsRng.fill_bytes(buffer);
}

/// 加密状态。`key` 必须 32 字节，`nonce` 必须 12 字节。
pub fn encrypt_state(plaintext: &[u8], key: &[u8], nonce: &[u8]) -> Option<String> {
    if key.len() != KEY_BYTES || nonce.len() != NONCE_BYTES {
        return None;
    }
    let cipher = Aes256Gcm::new_from_slice(key).ok()?;
    let ciphertext = cipher
        .encrypt(
            Nonce::from_slice(nonce),
            Payload {
                msg: plaintext,
                aad: STATE_MAGIC.as_bytes(),
            },
        )
        .ok()?;
    let engine = base64::engine::general_purpose::STANDARD;
    Some(format!(
        "{}\n{}\n{}",
        STATE_MAGIC,
        engine.encode(nonce),
        engine.encode(ciphertext)
    ))
}

/// 解密状态。格式、密钥或密文任一不对都返回 None，调用方必须视为损坏。
pub fn decrypt_state(blob: &str, key: &[u8]) -> Option<Vec<u8>> {
    if key.len() != KEY_BYTES {
        return None;
    }
    let mut lines = blob.split('\n');
    if lines.next()? != STATE_MAGIC {
        return None;
    }
    let engine = base64::engine::general_purpose::STANDARD;
    let nonce = engine.decode(lines.next()?).ok()?;
    let ciphertext = engine.decode(lines.next()?).ok()?;
    if lines.next().is_some() || nonce.len() != NONCE_BYTES {
        return None;
    }
    let cipher = Aes256Gcm::new_from_slice(key).ok()?;
    cipher
        .decrypt(
            Nonce::from_slice(&nonce),
            Payload {
                msg: &ciphertext,
                aad: STATE_MAGIC.as_bytes(),
            },
        )
        .ok()
}
