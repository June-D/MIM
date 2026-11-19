//! 首条消息组装与拆解（spike）。
//!
//! 把 X3DH 握手、发送者证书信封、会话标签与双棘轮加密组合成服务端可直接
//! 接收的字段。加密仍由 [`crate::ratchet`] 与 [`crate::session`] 完成，
//! 本模块只负责「按既有协议拼装」。
//!
//! 输入输出都是 JSON：JNI / C ABI 边界无需按字段展开，新增字段不破坏 ABI。

use crate::envelope::{sender_cert_envelope_pack, tag_envelope_pack};
use crate::ratchet::{RatchetState, StateSnapshot, WireMessage};
use crate::secure_store::fill_random;
use crate::sender_cert::SenderCertificate;
use crate::session::initiator_session;
use serde::{Deserialize, Serialize};

/// 与 `RatchetService.ALGORITHM` 一致。
pub const ALGORITHM: &str = "X3DH-P256-DOUBLE-RATCHET-AES-256-GCM";

#[derive(Deserialize, Clone, Debug)]
pub struct ComposeRequest {
    /// 本设备身份私钥（PKCS#8 DER 十六进制）。
    pub identity_private: String,
    /// 本设备身份公钥（SPKI DER 十六进制）。
    pub identity_public: String,
    /// 对端身份公钥（SPKI DER 十六进制）。
    pub peer_identity_public: String,
    pub peer_signed_pre_key_id: u32,
    /// 对端签名预密钥公钥（SPKI DER 十六进制）。
    pub peer_signed_pre_key_public: String,
    pub peer_one_time_pre_key_id: Option<u32>,
    /// 对端一次性预密钥公钥（SPKI DER 十六进制）。
    pub peer_one_time_pre_key_public: Option<String>,
    pub plaintext: String,
    /// 会话标签；为空表示这是首次联系、还没有标签。
    pub conversation_tag: Option<String>,
    pub session_id: String,
    /// 服务端签发的发送者证书。
    pub certificate: SenderCertificate,
    /// 12 字节 IV（十六进制）；缺省则随机生成。
    pub iv: Option<String>,
}

#[derive(Serialize, Clone, Debug)]
pub struct ComposeResponse {
    /// 双棘轮线格式：dh/pn/n/iv/ciphertext。
    pub wire: WireMessage,
    /// 发送后的会话状态（含私钥，调用方必须加密落盘）。
    pub state: StateSnapshot,
    pub alg: String,
    pub conversation_tag: String,
    pub session_id: String,
    /// 首条消息必须携带的握手参数（Base64）。
    pub sender_identity_key: String,
    pub sender_ephemeral_key: String,
    pub used_one_time_pre_key: bool,
    pub consumed_one_time_pre_key_id: Option<u32>,
    pub recipient_signed_pre_key_id: u32,
}

/// 组装首条消息：X3DH + 证书信封（含标签）+ 双棘轮加密。
pub fn compose_first_message(request: &ComposeRequest) -> Option<ComposeResponse> {
    let identity_private = hex::decode(&request.identity_private).ok()?;
    let identity_public = hex::decode(&request.identity_public).ok()?;
    let peer_identity = hex::decode(&request.peer_identity_public).ok()?;
    let peer_spk = hex::decode(&request.peer_signed_pre_key_public).ok()?;
    let peer_opk = crate::ratchet::decode_optional(&request.peer_one_time_pre_key_public)?;

    // 临时密钥对：每次首条消息都必须新生成。
    let (ephemeral_private, ephemeral_public) = crate::ratchet::new_ratchet_keypair()?;

    let initiator = initiator_session(
        &identity_private,
        &identity_public,
        &peer_identity,
        &peer_spk,
        peer_opk.as_deref(),
        &ephemeral_private,
        &ephemeral_public,
    )?;

    let tag = request
        .conversation_tag
        .clone()
        .filter(|value| !value.is_empty())
        .unwrap_or_else(new_conversation_tag);

    // 首条消息把证书与标签一起放进密文：服务端无需记录发送者。
    let payload = sender_cert_envelope_pack(&request.certificate, Some(&tag), &request.plaintext);

    let iv = match request.iv.as_deref() {
        Some(value) => hex::decode(value).ok()?,
        None => {
            let mut iv = [0u8; 12];
            fill_random(&mut iv);
            iv.to_vec()
        }
    };

    let mut state = initiator.state;
    let wire = state.encrypt_wire(&iv, &payload)?;

    Some(ComposeResponse {
        wire,
        state: state.snapshot(),
        alg: ALGORITHM.to_string(),
        conversation_tag: tag,
        session_id: request.session_id.clone(),
        sender_identity_key: b64(&initiator.sender_identity_key),
        sender_ephemeral_key: b64(&initiator.sender_ephemeral_key),
        used_one_time_pre_key: initiator.used_one_time_pre_key,
        consumed_one_time_pre_key_id: request.peer_one_time_pre_key_id,
        recipient_signed_pre_key_id: request.peer_signed_pre_key_id,
    })
}

/// 接收方拆首条消息：X3DH 回应 + 解密 + 拆证书信封。
#[derive(Deserialize, Clone, Debug)]
pub struct OpenFirstRequest {
    pub identity_private: String,
    pub identity_public: String,
    pub signed_pre_key_private: String,
    pub signed_pre_key_public: String,
    pub one_time_pre_key_private: Option<String>,
    /// 对端身份公钥：线上字段是 Base64(SPKI)，这里保持一致。
    pub peer_identity_public: String,
    /// 对端临时公钥：同上。
    pub peer_ephemeral_public: String,
    pub used_one_time_pre_key: bool,
    /// 首条消息的线格式。
    pub wire: WireMessage,
    /// 接收方换步用的棘轮密钥对（十六进制）；缺省则随机生成。
    pub next_dh_private: Option<String>,
    pub next_dh_public: Option<String>,
}

#[derive(Serialize, Clone, Debug)]
pub struct OpenFirstResponse {
    /// 拆掉证书信封后的正文。
    pub plaintext: String,
    pub certificate: SenderCertificate,
    pub conversation_tag: Option<String>,
    /// 解密后的会话状态（含私钥，调用方必须加密落盘）。
    pub state: StateSnapshot,
}

pub fn open_first_message(request: &OpenFirstRequest) -> Option<OpenFirstResponse> {
    use crate::envelope::sender_cert_envelope_unpack;

    let identity_private = hex::decode(&request.identity_private).ok()?;
    let identity_public = hex::decode(&request.identity_public).ok()?;
    let spk_private = hex::decode(&request.signed_pre_key_private).ok()?;
    let spk_public = hex::decode(&request.signed_pre_key_public).ok()?;
    let opk_private = crate::ratchet::decode_optional(&request.one_time_pre_key_private)?;
    let peer_identity = b64_decode(&request.peer_identity_public)?;
    let peer_ephemeral = b64_decode(&request.peer_ephemeral_public)?;

    let mut state = crate::session::responder_session(
        &identity_private,
        &identity_public,
        &spk_private,
        &spk_public,
        opk_private.as_deref(),
        &peer_identity,
        &peer_ephemeral,
        request.used_one_time_pre_key,
    )?;

    let (next_private, next_public) = match (
        request.next_dh_private.as_deref(),
        request.next_dh_public.as_deref(),
    ) {
        (Some(private), Some(public)) => (hex::decode(private).ok()?, hex::decode(public).ok()?),
        _ => crate::ratchet::new_ratchet_keypair()?,
    };

    let payload = state.decrypt_wire_with_keys(
        &request.wire,
        Some(&next_private),
        Some(&next_public),
    )?;

    let envelope = sender_cert_envelope_unpack(&payload)?;
    Some(OpenFirstResponse {
        plaintext: envelope.payload,
        certificate: envelope.certificate,
        conversation_tag: envelope.conversation_tag,
        state: state.snapshot(),
    })
}

/// 已确认会话的后续消息：只带标签信封。
pub fn compose_sealed_message(
    state: &mut RatchetState,
    conversation_tag: &str,
    plaintext: &str,
    iv: &[u8],
) -> Option<WireMessage> {
    let payload = tag_envelope_pack(conversation_tag, plaintext);
    state.encrypt_wire(iv, &payload)
}

/// 32 字节随机会话标签的 Base64URL（无填充）。
fn new_conversation_tag() -> String {
    use base64::Engine;
    let mut raw = [0u8; 32];
    fill_random(&mut raw);
    base64::engine::general_purpose::URL_SAFE_NO_PAD.encode(raw)
}

fn b64_decode(value: &str) -> Option<Vec<u8>> {
    use base64::Engine;
    base64::engine::general_purpose::STANDARD.decode(value).ok()
}

fn b64(value: &[u8]) -> String {
    use base64::Engine;
    base64::engine::general_purpose::STANDARD.encode(value)
}
