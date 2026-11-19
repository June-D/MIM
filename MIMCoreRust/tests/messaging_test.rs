//! 首条消息组装与拆解：X3DH + 证书信封 + 标签 + 双棘轮。

use mim_core_rust::keys::KeyBundle;
use mim_core_rust::messaging::{
    compose_first_message, compose_sealed_message, open_first_message, ComposeRequest,
    OpenFirstRequest,
};
use mim_core_rust::ratchet::RatchetState;
use mim_core_rust::sender_cert::SenderCertificate;

const TAG: &str = "gTq0Y2v5Qm9m8Zt0k3Wc2Xr7Lp4Nb1Hs6Jd9Ff2Ka5E";

fn certificate(device: &str, identity_public_hex: &str) -> SenderCertificate {
    SenderCertificate {
        username: "alice".to_string(),
        device_id: device.to_string(),
        identity_key: hex_to_base64(identity_public_hex),
        issued_at: 1_795_056_000_000,
        expires_at: 1_795_142_400_000,
        key_id: "00".repeat(32),
        signature: "c2lnbmF0dXJl".to_string(),
    }
}

fn hex_to_base64(hex: &str) -> String {
    use base64::Engine;
    let bytes = hex::decode(hex).expect("hex");
    base64::engine::general_purpose::STANDARD.encode(bytes)
}

#[test]
fn first_message_round_trip() {
    let alice = KeyBundle::generate(1, 1).expect("A 密钥包");
    let bob = KeyBundle::generate(1, 1).expect("B 密钥包");
    let bob_spk = bob.current_signed_pre_key().expect("B 签名预密钥");
    let bob_opk = bob.one_time_pre_keys.first().expect("B 一次性预密钥");

    let iv = [0x11u8; 12];
    let composed = compose_first_message(&ComposeRequest {
        identity_private: alice.identity.private_key.clone(),
        identity_public: alice.identity.public_key.clone(),
        peer_identity_public: bob.identity.public_key.clone(),
        peer_signed_pre_key_id: bob_spk.id,
        peer_signed_pre_key_public: bob_spk.public_key.clone(),
        peer_one_time_pre_key_id: Some(bob_opk.id),
        peer_one_time_pre_key_public: Some(bob_opk.public_key.clone()),
        plaintext: "首条消息".to_string(),
        conversation_tag: Some(TAG.to_string()),
        session_id: "session-1".to_string(),
        certificate: certificate("alice-desktop", &alice.identity.public_key),
        iv: Some(hex::encode(iv)),
    })
    .expect("组装首条消息");

    assert_eq!(composed.conversation_tag, TAG);
    assert_eq!(composed.session_id, "session-1");
    assert!(composed.used_one_time_pre_key);
    assert_eq!(composed.consumed_one_time_pre_key_id, Some(bob_opk.id));
    assert_eq!(composed.wire.n, 0);

    let opened = open_first_message(&OpenFirstRequest {
        identity_private: bob.identity.private_key.clone(),
        identity_public: bob.identity.public_key.clone(),
        signed_pre_key_private: bob_spk.private_key.clone(),
        signed_pre_key_public: bob_spk.public_key.clone(),
        one_time_pre_key_private: Some(bob_opk.private_key.clone()),
        peer_identity_public: composed.sender_identity_key.clone(),
        peer_ephemeral_public: composed.sender_ephemeral_key.clone(),
        used_one_time_pre_key: true,
        wire: composed.wire.clone(),
        next_dh_private: None,
        next_dh_public: None,
    })
    .expect("拆首条消息");

    assert_eq!(opened.plaintext, "首条消息");
    assert_eq!(opened.certificate.username, "alice");
    assert_eq!(opened.certificate.device_id, "alice-desktop");
    assert_eq!(opened.conversation_tag.as_deref(), Some(TAG));

    // 接收方回一条 sealed 消息，发送方必须能解开并学到同一标签。
    let mut b_state = RatchetState::from_snapshot(&opened.state).expect("恢复 B 状态");
    let mut a_state = RatchetState::from_snapshot(&composed.state).expect("恢复 A 状态");

    let reply_wire = compose_sealed_message(&mut b_state, TAG, "回信", &[0x22u8; 12]).expect("回信");
    let reply = a_state
        .decrypt_wire(&reply_wire)
        .expect("A 解密回信");
    let parsed = mim_core_rust::envelope::tag_envelope_unpack(&reply).expect("标签信封");
    assert_eq!(parsed.tag, TAG);
    assert_eq!(parsed.text, "回信");
}

#[test]
fn unknown_tag_is_generated_on_first_contact() {
    let alice = KeyBundle::generate(1, 0).expect("A 密钥包");
    let bob = KeyBundle::generate(1, 0).expect("B 密钥包");
    let bob_spk = bob.current_signed_pre_key().expect("B 签名预密钥");

    let composed = compose_first_message(&ComposeRequest {
        identity_private: alice.identity.private_key.clone(),
        identity_public: alice.identity.public_key.clone(),
        peer_identity_public: bob.identity.public_key.clone(),
        peer_signed_pre_key_id: bob_spk.id,
        peer_signed_pre_key_public: bob_spk.public_key.clone(),
        peer_one_time_pre_key_id: None,
        peer_one_time_pre_key_public: None,
        plaintext: "hi".to_string(),
        conversation_tag: None,
        session_id: "session-2".to_string(),
        certificate: certificate("alice-desktop", &alice.identity.public_key),
        iv: None,
    })
    .expect("组装");

    assert!(!composed.used_one_time_pre_key);
    assert_eq!(composed.consumed_one_time_pre_key_id, None);
    assert_eq!(composed.conversation_tag.len(), 43, "32 字节 Base64URL 无填充");
}
