//! 会话存储与 sealed 消息：回信、标签学习、持久化与失败不改状态。

use mim_core_rust::keys::KeyBundle;
use mim_core_rust::messaging::{compose_first_message, open_first_message, ComposeRequest, OpenFirstRequest};
use mim_core_rust::ratchet::WireMessage;
use mim_core_rust::sender_cert::SenderCertificate;
use mim_core_rust::session_store::SessionStore;

struct Pair {
    alice: SessionStore,
    bob: SessionStore,
    session_id: String,
    tag: String,
}

fn certificate(identity_public_hex: &str) -> SenderCertificate {
    SenderCertificate {
        username: "alice".to_string(),
        device_id: "alice-desktop".to_string(),
        identity_key: base64(identity_public_hex),
        issued_at: 1_795_056_000_000,
        expires_at: 1_795_142_400_000,
        key_id: "00".repeat(32),
        signature: "c2lnbmF0dXJl".to_string(),
    }
}

fn base64(hex: &str) -> String {
    use base64::Engine;
    base64::engine::general_purpose::STANDARD.encode(hex::decode(hex).unwrap())
}

/// 用首条消息建立双方的会话存储。
fn establish() -> Pair {
    let alice = KeyBundle::generate(1, 1).expect("A 密钥包");
    let bob = KeyBundle::generate(1, 1).expect("B 密钥包");
    let bob_spk = bob.current_signed_pre_key().expect("B 签名预密钥");
    let bob_opk = bob.one_time_pre_keys.first().expect("B 一次性预密钥");
    let session_id = "session-store-1";

    let composed = compose_first_message(&ComposeRequest {
        identity_private: alice.identity.private_key.clone(),
        identity_public: alice.identity.public_key.clone(),
        peer_identity_public: bob.identity.public_key.clone(),
        peer_signed_pre_key_id: bob_spk.id,
        peer_signed_pre_key_public: bob_spk.public_key.clone(),
        peer_one_time_pre_key_id: Some(bob_opk.id),
        peer_one_time_pre_key_public: Some(bob_opk.public_key.clone()),
        plaintext: "首条".to_string(),
        conversation_tag: None,
        session_id: session_id.to_string(),
        certificate: certificate(&alice.identity.public_key),
        iv: Some("11".repeat(12)),
    })
    .expect("组装首条");

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
    .expect("拆首条");

    let mut alice_store = SessionStore::default();
    alice_store.insert(session_id, "bob", "bob-phone", composed.state);
    alice_store.learn_tag(session_id, &composed.conversation_tag);

    let mut bob_store = SessionStore::default();
    bob_store.insert(session_id, "alice", "alice-desktop", opened.state);
    if let Some(tag) = opened.conversation_tag {
        bob_store.learn_tag(session_id, &tag);
    }

    Pair {
        alice: alice_store,
        bob: bob_store,
        session_id: session_id.to_string(),
        tag: composed.conversation_tag,
    }
}

#[test]
fn sealed_reply_round_trip_learns_tag() {
    let mut pair = establish();
    assert_eq!(pair.bob.get(&pair.session_id).unwrap().tags, vec![pair.tag.clone()]);

    let reply = pair
        .bob
        .compose_sealed(&pair.session_id, "回信", &[0x22u8; 12])
        .expect("组装回信");
    assert_eq!(pair.alice.open_sealed(&pair.session_id, &reply).as_deref(), Some("回信"));

    let again = pair
        .alice
        .compose_sealed(&pair.session_id, "再回", &[0x33u8; 12])
        .expect("再回");
    assert_eq!(pair.bob.open_sealed(&pair.session_id, &again).as_deref(), Some("再回"));

    // 双方标签收敛到同一个
    assert_eq!(pair.alice.get(&pair.session_id).unwrap().tags, vec![pair.tag.clone()]);
    assert_eq!(pair.bob.get(&pair.session_id).unwrap().tags, vec![pair.tag.clone()]);
}

#[test]
fn unknown_session_or_missing_tag_is_rejected() {
    let mut pair = establish();
    assert!(pair.alice.compose_sealed("missing", "x", &[0u8; 12]).is_none());
    assert!(pair.alice.open_sealed("missing", &dummy_wire()).is_none());

    let mut empty = SessionStore::default();
    empty.insert("s", "bob", "dev", pair.alice.get(&pair.session_id).unwrap().state.clone());
    assert!(empty.compose_sealed("s", "x", &[0u8; 12]).is_none(), "没有标签不能发 sealed");
}

#[test]
fn failed_decrypt_does_not_advance_state() {
    let mut pair = establish();
    let reply = pair
        .bob
        .compose_sealed(&pair.session_id, "正常", &[0x44u8; 12])
        .expect("组装");

    let before = pair.alice.to_json().expect("快照");
    let mut tampered = reply.clone();
    let mut ciphertext = tampered.ciphertext.clone();
    ciphertext.pop();
    ciphertext.push(if ciphertext.ends_with('A') { 'B' } else { 'A' });
    tampered.ciphertext = ciphertext;

    assert!(pair.alice.open_sealed(&pair.session_id, &tampered).is_none());
    assert_eq!(pair.alice.to_json().expect("快照"), before, "失败不得改状态");

    // 原消息仍可正常解密，说明棘轮没有失步
    assert_eq!(pair.alice.open_sealed(&pair.session_id, &reply).as_deref(), Some("正常"));
}

#[test]
fn store_survives_encrypted_restart() {
    let mut pair = establish();
    let key = [5u8; 32];
    let nonce = [6u8; 12];
    let blob = pair.bob.save_encrypted(&key, &nonce).expect("加密存储");
    let mut reloaded = SessionStore::load_encrypted(&blob, &key).expect("恢复");

    let reply = pair
        .alice
        .compose_sealed(&pair.session_id, "重启后仍能解", &[0x55u8; 12])
        .expect("组装");
    assert_eq!(
        reloaded.open_sealed(&pair.session_id, &reply).as_deref(),
        Some("重启后仍能解")
    );

    let mut wrong = key;
    wrong[0] ^= 0xff;
    assert!(SessionStore::load_encrypted(&blob, &wrong).is_none());
}

fn dummy_wire() -> WireMessage {
    WireMessage {
        dh: "AAAA".to_string(),
        pn: 0,
        n: 0,
        iv: "AAAAAAAAAAAAAAAA".to_string(),
        ciphertext: "AAAA".to_string(),
    }
}
