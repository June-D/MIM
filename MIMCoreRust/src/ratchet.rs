//! 双棘轮状态机（spike）。
//!
//! 对齐 `MIMCore` 的 `RatchetService` / `SessionState`：
//! - 发送链 / 接收链各自前进；
//! - 棘轮公钥变化时先跳过旧链剩余密钥，再做 DH 棘轮；
//! - 乱序消息从跳键缓存取；
//! - 解密失败不提交状态（调用方拿到的是副本）。

use crate::{aes_gcm_decrypt, aes_gcm_encrypt, dh, root_kdf, chain_step};
use base64::Engine;
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

fn b64(value: &[u8]) -> String {
    base64::engine::general_purpose::STANDARD.encode(value)
}

fn b64_decode(value: &str) -> Option<Vec<u8>> {
    base64::engine::general_purpose::STANDARD.decode(value).ok()
}
