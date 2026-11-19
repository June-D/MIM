//! 双棘轮状态机（spike）。
//!
//! 对齐 `MIMCore` 的 `RatchetService` / `SessionState`：
//! - 发送链 / 接收链各自前进；
//! - 棘轮公钥变化时先跳过旧链剩余密钥，再做 DH 棘轮；
//! - 乱序消息从跳键缓存取；
//! - 解密失败不提交状态（调用方拿到的是副本）。

use crate::{aes_gcm_decrypt, aes_gcm_encrypt, dh, root_kdf, chain_step};
use base64::Engine;
use p256::pkcs8::{EncodePrivateKey, EncodePublicKey};
use rand_core::OsRng;
use serde::{Deserialize, Serialize};
use std::collections::BTreeMap;

pub const MAX_SKIP: u32 = 200;

/// 消息头：棘轮公钥（Base64 SPKI）、上次发送链长度、当前链序号。
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Header {
    pub dh_public: String,
    pub previous_chain_length: u32,
    pub message_number: u32,
}

impl Header {
    pub fn aad(&self) -> String {
        format!(
            "{}|{}|{}",
            self.dh_public, self.previous_chain_length, self.message_number
        )
    }
}

/// 可序列化的状态快照，供 FFI 边界与跨进程交接使用。
///
/// 字节字段一律用十六进制，避免 JSON/文本协议里的编码差异。
/// 快照含私钥与链密钥：落盘前必须由平台密钥库加密。
#[derive(Serialize, Deserialize, Clone, Debug, PartialEq, Eq)]
pub struct StateSnapshot {
    pub root_key: String,
    pub send_chain_key: Option<String>,
    pub recv_chain_key: Option<String>,
    pub dh_self_private: String,
    pub dh_self_public: String,
    pub dh_remote_public: Option<String>,
    pub send_count: u32,
    pub recv_count: u32,
    pub prev_send_count: u32,
    pub skipped: Vec<SkippedKeySnapshot>,
}

#[derive(Serialize, Deserialize, Clone, Debug, PartialEq, Eq)]
pub struct SkippedKeySnapshot {
    pub dh_public: String,
    pub message_number: u32,
    pub message_key: String,
}

/// 线上消息格式：与既有客户端的字段一一对应（iv/密文为 Base64）。
#[derive(Serialize, Deserialize, Clone, Debug, PartialEq, Eq)]
pub struct WireMessage {
    pub dh: String,
    pub pn: u32,
    pub n: u32,
    pub iv: String,
    pub ciphertext: String,
}

impl WireMessage {
    pub fn header(&self) -> Header {
        Header {
            dh_public: self.dh.clone(),
            previous_chain_length: self.pn,
            message_number: self.n,
        }
    }

    pub fn iv_bytes(&self) -> Option<Vec<u8>> {
        b64_decode(&self.iv)
    }

    pub fn ciphertext_bytes(&self) -> Option<Vec<u8>> {
        b64_decode(&self.ciphertext)
    }
}

#[derive(Clone)]
pub struct RatchetState {
    pub root_key: Vec<u8>,
    pub send_chain_key: Option<Vec<u8>>,
    pub recv_chain_key: Option<Vec<u8>>,
    pub dh_self_private: Vec<u8>,
    pub dh_self_public: Vec<u8>,
    pub dh_remote_public: Option<Vec<u8>>,
    pub send_count: u32,
    pub recv_count: u32,
    pub prev_send_count: u32,
    skipped: BTreeMap<(String, u32), Vec<u8>>,
}

impl RatchetState {
    pub fn new(
        root_key: Vec<u8>,
        send_chain_key: Option<Vec<u8>>,
        recv_chain_key: Option<Vec<u8>>,
        dh_self_private: Vec<u8>,
        dh_self_public: Vec<u8>,
        dh_remote_public: Option<Vec<u8>>,
    ) -> Self {
        Self {
            root_key,
            send_chain_key,
            recv_chain_key,
            dh_self_private,
            dh_self_public,
            dh_remote_public,
            send_count: 0,
            recv_count: 0,
            prev_send_count: 0,
            skipped: BTreeMap::new(),
        }
    }

    /// 导出快照（含私钥，调用方负责加密存储）。
    pub fn snapshot(&self) -> StateSnapshot {
        StateSnapshot {
            root_key: hex::encode(&self.root_key),
            send_chain_key: self.send_chain_key.as_ref().map(hex::encode),
            recv_chain_key: self.recv_chain_key.as_ref().map(hex::encode),
            dh_self_private: hex::encode(&self.dh_self_private),
            dh_self_public: hex::encode(&self.dh_self_public),
            dh_remote_public: self.dh_remote_public.as_ref().map(hex::encode),
            send_count: self.send_count,
            recv_count: self.recv_count,
            prev_send_count: self.prev_send_count,
            skipped: self
                .skipped
                .iter()
                .map(|((dh, n), key)| SkippedKeySnapshot {
                    dh_public: dh.clone(),
                    message_number: *n,
                    message_key: hex::encode(key),
                })
                .collect(),
        }
    }

    /// 从快照恢复。密文格式不合法时返回 None，调用方应视为状态损坏。
    pub fn from_snapshot(snap: &StateSnapshot) -> Option<Self> {
        let mut state = RatchetState::new(
            hex::decode(&snap.root_key).ok()?,
            decode_optional(&snap.send_chain_key)?,
            decode_optional(&snap.recv_chain_key)?,
            hex::decode(&snap.dh_self_private).ok()?,
            hex::decode(&snap.dh_self_public).ok()?,
            decode_optional(&snap.dh_remote_public)?,
        );
        state.send_count = snap.send_count;
        state.recv_count = snap.recv_count;
        state.prev_send_count = snap.prev_send_count;
        for entry in &snap.skipped {
            state.restore_skipped(
                &entry.dh_public,
                entry.message_number,
                hex::decode(&entry.message_key).ok()?,
            );
        }
        Some(state)
    }

    /// 加密并直接产出线上消息格式。
    pub fn encrypt_wire(&mut self, iv: &[u8], plaintext: &str) -> Option<WireMessage> {
        let (header, ciphertext) = self.encrypt_with_iv(iv, plaintext)?;
        Some(WireMessage {
            dh: header.dh_public,
            pn: header.previous_chain_length,
            n: header.message_number,
            iv: b64(iv),
            ciphertext: b64(&ciphertext),
        })
    }

    /// 解密线上消息格式，换步密钥由调用方提供（对拍与确定性测试用）。
    pub fn decrypt_wire_with_keys(
        &mut self,
        message: &WireMessage,
        next_dh_private: Option<&[u8]>,
        next_dh_public: Option<&[u8]>,
    ) -> Option<String> {
        let iv = message.iv_bytes()?;
        let ciphertext = message.ciphertext_bytes()?;
        self.decrypt_with_ratchet_key(&message.header(), &iv, &ciphertext, next_dh_private, next_dh_public)
    }

    /// 解密线上消息格式，必要时自行生成新的棘轮密钥对。
    ///
    /// 这是生产/FFI 路径：调用方不需要管密钥生成。
    pub fn decrypt_wire(&mut self, message: &WireMessage) -> Option<String> {
        let iv = message.iv_bytes()?;
        let ciphertext = message.ciphertext_bytes()?;
        let header = message.header();
        let needs_ratchet = match (self.dh_remote_public.as_deref(), b64_decode(&header.dh_public)) {
            (Some(remote), Some(incoming)) => remote != incoming.as_slice(),
            _ => true,
        };
        if needs_ratchet {
            let (private, public) = new_ratchet_keypair()?;
            self.decrypt_with_ratchet_key(&header, &iv, &ciphertext, Some(&private), Some(&public))
        } else {
            self.decrypt_with_ratchet_key(&header, &iv, &ciphertext, None, None)
        }
    }

    pub fn skipped_len(&self) -> usize {
        self.skipped.len()
    }

    /// 恢复一条跳键（对拍从向量重建状态时使用）。
    pub fn restore_skipped(&mut self, dh_public: &str, message_number: u32, key: Vec<u8>) {
        self.skipped
            .insert((dh_public.to_string(), message_number), key);
    }

    /// 跳键缓存快照，供跨语言对拍比较。
    pub fn skipped_entries(&self) -> Vec<(String, u32, Vec<u8>)> {
        self.skipped
            .iter()
            .map(|((dh, n), key)| (dh.clone(), *n, key.clone()))
            .collect()
    }

    pub fn take_skipped(&mut self, dh_public: &str, message_number: u32) -> Option<Vec<u8>> {
        self.skipped
            .remove(&(dh_public.to_string(), message_number))
    }

    fn cache_skipped(&mut self, dh_public: &str, message_number: u32, key: Vec<u8>) {
        if self.skipped.len() as u32 >= MAX_SKIP {
            if let Some(first) = self.skipped.keys().next().cloned() {
                self.skipped.remove(&first);
            }
        }
        self.skipped
            .insert((dh_public.to_string(), message_number), key);
    }

    /// 加密一条消息。`iv` 由调用方提供，便于跨语言对拍；生产应使用随机 IV。
    pub fn encrypt_with_iv(&mut self, iv: &[u8], plaintext: &str) -> Option<(Header, Vec<u8>)> {
        let chain = self.send_chain_key.clone()?;
        let (message_key, next_chain) = chain_step(&chain);
        let header = Header {
            dh_public: b64(&self.dh_self_public),
            previous_chain_length: self.prev_send_count,
            message_number: self.send_count,
        };
        let ciphertext = aes_gcm_encrypt(
            &message_key,
            iv,
            header.aad().as_bytes(),
            plaintext.as_bytes(),
        );
        self.send_chain_key = Some(next_chain);
        self.send_count += 1;
        Some((header, ciphertext))
    }

    /// 解密一条消息。棘轮步需要注入新密钥对（对拍用）；生产应由调用方生成随机密钥。
    pub fn decrypt_with_ratchet_key(
        &mut self,
        header: &Header,
        iv: &[u8],
        ciphertext: &[u8],
        next_dh_private: Option<&[u8]>,
        next_dh_public: Option<&[u8]>,
    ) -> Option<String> {
        let mut trial = self.clone();
        if let Some(message_key) = trial.take_skipped(&header.dh_public, header.message_number) {
            let plaintext =
                aes_gcm_decrypt(&message_key, iv, header.aad().as_bytes(), ciphertext)?;
            // 命中也必须提交状态：该跳键已消耗，不能重放。
            *self = trial;
            return Some(String::from_utf8_lossy(&plaintext).into_owned());
        }

        // remote 存的是 SPKI DER，header 是 Base64，比较前先解码。
        let remote_der = b64_decode(&header.dh_public)?;
        if trial.dh_remote_public.as_deref() != Some(remote_der.as_slice()) {
            trial.skip_message_keys(header.previous_chain_length)?;
            trial.dh_ratchet(&remote_der, next_dh_private?, next_dh_public?)?;
        }
        trial.skip_message_keys(header.message_number)?;
        let chain = trial.recv_chain_key.clone()?;
        let (message_key, next_chain) = chain_step(&chain);
        trial.recv_chain_key = Some(next_chain);
        trial.recv_count += 1;

        let plaintext = aes_gcm_decrypt(&message_key, iv, header.aad().as_bytes(), ciphertext)?;
        *self = trial;
        Some(String::from_utf8_lossy(&plaintext).into_owned())
    }

    fn skip_message_keys(&mut self, until: u32) -> Option<()> {
        // 尚未建立接收链时无需跳过，与 Java 的 skipMessageKeys 一致。
        let mut chain = match self.recv_chain_key.clone() {
            Some(chain) => chain,
            None => return Some(()),
        };
        if until.saturating_sub(self.recv_count) > MAX_SKIP {
            return None;
        }
        while self.recv_count < until {
            let (message_key, next_chain) = chain_step(&chain);
            let remote = b64(&self.dh_remote_public.clone()?);
            self.cache_skipped(&remote, self.recv_count, message_key);
            chain = next_chain;
            self.recv_count += 1;
        }
        self.recv_chain_key = Some(chain);
        Some(())
    }

    fn dh_ratchet(
        &mut self,
        remote_public_der: &[u8],
        new_private_der: &[u8],
        new_public_der: &[u8],
    ) -> Option<()> {
        self.prev_send_count = self.send_count;
        self.send_count = 0;
        self.recv_count = 0;
        self.dh_remote_public = Some(remote_public_der.to_vec());

        let dh_recv = dh(&self.dh_self_private, remote_public_der)?;
        let (new_root, recv_chain) = root_kdf(&self.root_key, &dh_recv);
        self.root_key = new_root;
        self.recv_chain_key = Some(recv_chain);

        let dh_send = dh(new_private_der, remote_public_der)?;
        let (new_root, send_chain) = root_kdf(&self.root_key, &dh_send);
        self.root_key = new_root;
        self.send_chain_key = Some(send_chain);
        self.dh_self_private = new_private_der.to_vec();
        self.dh_self_public = new_public_der.to_vec();
        Some(())
    }
}

/// 生成一对 P-256 棘轮密钥，返回 PKCS#8 私钥与 SPKI 公钥的 DER。
pub fn new_ratchet_keypair() -> Option<(Vec<u8>, Vec<u8>)> {
    let secret = p256::SecretKey::random(&mut OsRng);
    let private = secret.to_pkcs8_der().ok()?.as_bytes().to_vec();
    let public = secret.public_key().to_public_key_der().ok()?.as_bytes().to_vec();
    Some((private, public))
}

/// None 表示字段缺省，Some 但解码失败表示快照损坏。
pub(crate) fn decode_optional(value: &Option<String>) -> Option<Option<Vec<u8>>> {
    match value {
        None => Some(None),
        Some(text) => hex::decode(text).ok().map(Some),
    }
}

fn b64(value: &[u8]) -> String {
    base64::engine::general_purpose::STANDARD.encode(value)
}

fn b64_decode(value: &str) -> Option<Vec<u8>> {
    base64::engine::general_purpose::STANDARD.decode(value).ok()
}
