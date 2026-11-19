//! X3DH 会话建立（spike）。
//!
//! 对齐 `MIMCore` 的 `RatchetService.initiatorSession` / `responderSession`：
//! 发起方用身份私钥与对端预密钥算出 DH1..DH4，派生初始根密钥并立即做一次
//! DH 棘轮建立发送链；接收方用同一组 DH 得到相同根密钥，留空对端棘轮公钥，
//! 强制首条消息触发换步。
//!
//! 临时密钥与换步密钥由调用方传入，便于跨语言对拍；生产中应使用安全随机。

use crate::{dh, hkdf_derive, ratchet::RatchetState, root_kdf};

pub const INFO_INITIAL: &str = "MIM/v2/initial";

/// 发起方建会话的结果，含首条消息需要带给对端的公开参数。
pub struct InitiatorResult {
    pub state: RatchetState,
    pub sender_identity_key: Vec<u8>,
    pub sender_ephemeral_key: Vec<u8>,
    pub used_one_time_pre_key: bool,
}

fn concat_dh(parts: &[Option<Vec<u8>>]) -> Vec<u8> {
    let mut out = Vec::new();
    for part in parts.iter().flatten() {
        out.extend_from_slice(part);
    }
    out
}

/// 发起方：`DH1 = DH(IK_A, SPK_B)`、`DH2 = DH(EK_A, IK_B)`、
/// `DH3 = DH(EK_A, SPK_B)`、`DH4 = DH(EK_A, OPK_B)`（可选）。
pub fn initiator_session(
    self_identity_priv: &[u8],
    self_identity_pub: &[u8],
    peer_identity_pub: &[u8],
    peer_signed_pre_key_pub: &[u8],
    peer_one_time_pre_key_pub: Option<&[u8]>,
    ephemeral_priv: &[u8],
    ephemeral_pub: &[u8],
) -> Option<InitiatorResult> {
    let dh1 = dh(self_identity_priv, peer_signed_pre_key_pub)?;
    let dh2 = dh(ephemeral_priv, peer_identity_pub)?;
    let dh3 = dh(ephemeral_priv, peer_signed_pre_key_pub)?;
    let dh4 = match peer_one_time_pre_key_pub {
        Some(key) => Some(dh(ephemeral_priv, key)?),
        None => None,
    };

    let shared = concat_dh(&[Some(dh1), Some(dh2), Some(dh3), dh4]);
    let root = hkdf_derive(None, &shared, INFO_INITIAL, 32);

    // 发起方立即换步：自己的临时私钥 × 对端签名预密钥，建立发送链。
    let dh_out = dh(ephemeral_priv, peer_signed_pre_key_pub)?;
    let (new_root, send_chain) = root_kdf(&root, &dh_out);

    let state = RatchetState::new(
        new_root,
        Some(send_chain),
        None,
        ephemeral_priv.to_vec(),
        ephemeral_pub.to_vec(),
        Some(peer_signed_pre_key_pub.to_vec()),
    );

    Some(InitiatorResult {
        state,
        sender_identity_key: self_identity_pub.to_vec(),
        sender_ephemeral_key: ephemeral_pub.to_vec(),
        used_one_time_pre_key: peer_one_time_pre_key_pub.is_some(),
    })
}

/// 接收方：用首条消息带来的对方身份公钥与临时公钥完成同一组 DH。
pub fn responder_session(
    self_identity_priv: &[u8],
    self_identity_pub: &[u8],
    self_signed_pre_key_priv: &[u8],
    self_signed_pre_key_pub: &[u8],
    self_one_time_pre_key_priv: Option<&[u8]>,
    peer_identity_pub: &[u8],
    peer_ephemeral_pub: &[u8],
    used_one_time_pre_key: bool,
) -> Option<RatchetState> {
    let dh1 = dh(self_signed_pre_key_priv, peer_identity_pub)?;
    let dh2 = dh(self_identity_priv, peer_ephemeral_pub)?;
    let dh3 = dh(self_signed_pre_key_priv, peer_ephemeral_pub)?;
    let dh4 = if used_one_time_pre_key {
        Some(dh(self_one_time_pre_key_priv?, peer_ephemeral_pub)?)
    } else {
        None
    };

    let shared = concat_dh(&[Some(dh1), Some(dh2), Some(dh3), dh4]);
    let root = hkdf_derive(None, &shared, INFO_INITIAL, 32);

    // 对端棘轮公钥留空，首条消息必然触发 DH 换步（与 Java 一致）。
    let _ = self_identity_pub;
    Some(RatchetState::new(
        root,
        None,
        None,
        self_signed_pre_key_priv.to_vec(),
        self_signed_pre_key_pub.to_vec(),
        None,
    ))
}
