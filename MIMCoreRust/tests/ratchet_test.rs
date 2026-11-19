//! 双棘轮状态机的跨语言对拍。
//!
//! 向量由 `MIMCore/src/test/java/com/junesoon/mim/crypto/RatchetVectors.java`
//! 用真实 `RatchetService` 驱动一段确定性对话生成：A 发起、B 回信、
//! 之后 A 连发两条、B 乱序先解后一条再从缓存取前一条。
//!
//! 每一步都断言「明文 + 消息头 + 密文 + 完整状态」，状态里包含根密钥、
//! 收发链、棘轮密钥、计数与跳键缓存。任何一处不一致都会让 Rust 移植
//! 与现有客户端不兼容。

use base64::Engine;
use mim_core_rust::ratchet::{Header, RatchetState};
use serde::Deserialize;
use std::collections::BTreeMap;

#[derive(Deserialize)]
struct Vectors {
    a_initial: StateVec,
    b_initial: StateVec,
    steps: Vec<Step>,
}

#[derive(Deserialize, Clone)]
struct StateVec {
    root: String,
    send: Option<String>,
    recv: Option<String>,
    dh_self_private: String,
    dh_self_public: String,
    dh_remote_public: Option<String>,
    send_count: u32,
    recv_count: u32,
    prev_send_count: u32,
    #[serde(default)]
    skipped: Vec<SkippedVec>,
}

#[derive(Deserialize, Clone)]
struct SkippedVec {
    dh: String,
    n: u32,
    key: String,
}

#[derive(Deserialize)]
struct Step {
    actor: String,
    op: String,
    plaintext: String,
    iv: String,
    ciphertext: String,
    header: HeaderVec,
    #[serde(default)]
    next_dh_private: Option<String>,
    #[serde(default)]
    next_dh_public: Option<String>,
    state: StateVec,
}

#[derive(Deserialize)]
struct HeaderVec {
    dh: String,
    pn: u32,
    n: u32,
}

fn vectors() -> Vectors {
    serde_json::from_str(include_str!("fixtures/ratchet-vectors.json"))
        .expect("ratchet-vectors.json 解析失败")
}

fn from_vec(v: &StateVec) -> RatchetState {
    let mut state = RatchetState::new(
        hex::decode(&v.root).unwrap(),
        v.send.as_ref().map(|s| hex::decode(s).unwrap()),
        v.recv.as_ref().map(|s| hex::decode(s).unwrap()),
        hex::decode(&v.dh_self_private).unwrap(),
        hex::decode(&v.dh_self_public).unwrap(),
        v.dh_remote_public.as_ref().map(|s| hex::decode(s).unwrap()),
    );
    state.send_count = v.send_count;
    state.recv_count = v.recv_count;
    state.prev_send_count = v.prev_send_count;
    for entry in &v.skipped {
        state.restore_skipped(&entry.dh, entry.n, hex::decode(&entry.key).unwrap());
    }
    state
}

fn assert_state(actual: &RatchetState, expected: &StateVec, label: &str) {
    assert_eq!(hex::encode(&actual.root_key), expected.root, "{} root", label);
    assert_eq!(
        actual.send_chain_key.as_ref().map(hex::encode),
        expected.send,
        "{} send chain",
        label
    );
    assert_eq!(
        actual.recv_chain_key.as_ref().map(hex::encode),
        expected.recv,
        "{} recv chain",
        label
    );
    assert_eq!(
        hex::encode(&actual.dh_self_private),
        expected.dh_self_private,
        "{} dh self private",
        label
    );
    assert_eq!(
        hex::encode(&actual.dh_self_public),
        expected.dh_self_public,
        "{} dh self public",
        label
    );
    assert_eq!(
        actual.dh_remote_public.as_ref().map(hex::encode),
        expected.dh_remote_public,
        "{} dh remote",
        label
    );
    assert_eq!(actual.send_count, expected.send_count, "{} send_count", label);
    assert_eq!(actual.recv_count, expected.recv_count, "{} recv_count", label);
    assert_eq!(
        actual.prev_send_count, expected.prev_send_count,
        "{} prev_send_count",
        label
    );

    let mut actual_skipped: BTreeMap<(String, u32), String> = actual
        .skipped_entries()
        .into_iter()
        .map(|(dh, n, key)| ((dh, n), hex::encode(key)))
        .collect();
    let mut expected_skipped: BTreeMap<(String, u32), String> = expected
        .skipped
        .iter()
        .map(|e| ((e.dh.clone(), e.n), e.key.clone()))
        .collect();
    assert_eq!(actual_skipped, expected_skipped, "{} skipped keys", label);
    actual_skipped.clear();
    expected_skipped.clear();
}

#[test]
fn double_ratchet_matches_java() {
    let v = vectors();
    let mut a = from_vec(&v.a_initial);
    let mut b = from_vec(&v.b_initial);
    assert_state(&a, &v.a_initial, "a_initial");
    assert_state(&b, &v.b_initial, "b_initial");

    for (index, step) in v.steps.iter().enumerate() {
        let label = format!("#{} {} {}", index + 1, step.actor, step.op);
        let state = if step.actor == "A" { &mut a } else { &mut b };
        let iv = hex::decode(&step.iv).unwrap();
        let ciphertext = hex::decode(&step.ciphertext).unwrap();
        let header = Header {
            dh_public: step.header.dh.clone(),
            previous_chain_length: step.header.pn,
            message_number: step.header.n,
        };

        if step.op == "encrypt" {
            let (actual_header, actual_ciphertext) = state
                .encrypt_with_iv(&iv, &str::from_utf8(&hex::decode(&step.plaintext).unwrap()).unwrap())
                .expect("encrypt");
            assert_eq!(actual_header, header, "{} header", label);
            assert_eq!(hex::encode(&actual_ciphertext), step.ciphertext, "{} ciphertext", label);
        } else {
            let next_priv = step
                .next_dh_private
                .as_ref()
                .map(|s| hex::decode(s).unwrap());
            let next_pub = step
                .next_dh_public
                .as_ref()
                .map(|s| hex::decode(s).unwrap());
            let plaintext = state
                .decrypt_with_ratchet_key(&header, &iv, &ciphertext, next_priv.as_deref(), next_pub.as_deref())
                .expect("decrypt");
            assert_eq!(
                hex::encode(plaintext.as_bytes()),
                step.plaintext,
                "{} plaintext",
                label
            );
        }

        assert_state(state, &step.state, &label);
    }
}

/// 同一密文重复投递必须失败：跳键一次性消耗。
#[test]
fn replayed_skipped_message_is_rejected() {
    let v = vectors();
    let mut a = from_vec(&v.a_initial);
    let mut b = from_vec(&v.b_initial);

    // 只重放真实会话里的「乱序解密」那一步：先解 m3，再用缓存解 m2。
    for step in &v.steps {
        let iv = hex::decode(&step.iv).unwrap();
        let ciphertext = hex::decode(&step.ciphertext).unwrap();
        let header = Header {
            dh_public: step.header.dh.clone(),
            previous_chain_length: step.header.pn,
            message_number: step.header.n,
        };
        if step.op == "encrypt" {
            let state = if step.actor == "A" { &mut a } else { &mut b };
            state
                .encrypt_with_iv(&iv, &str::from_utf8(&hex::decode(&step.plaintext).unwrap()).unwrap())
                .expect("encrypt");
            continue;
        }
        if step.actor != "B" {
            continue;
        }
        let next_priv = step.next_dh_private.as_ref().map(|s| hex::decode(s).unwrap());
        let next_pub = step.next_dh_public.as_ref().map(|s| hex::decode(s).unwrap());
        let first = b.decrypt_with_ratchet_key(&header, &iv, &ciphertext, next_priv.as_deref(), next_pub.as_deref());
        assert!(first.is_some(), "首次解密应成功");
        let replay = b.decrypt_with_ratchet_key(&header, &iv, &ciphertext, next_priv.as_deref(), next_pub.as_deref());
        assert!(replay.is_none(), "重复投递必须被拒绝");
    }
}

#[test]
fn base64_header_matches_state() {
    // 消息头里的 dh 必须是当前棘轮公钥的 Base64(SPKI)，否则对端无法换步。
    let v = vectors();
    let a = from_vec(&v.a_initial);
    let expected = base64::engine::general_purpose::STANDARD.encode(&a.dh_self_public);
    let first = v.steps.iter().find(|s| s.actor == "A" && s.op == "encrypt").unwrap();
    assert_eq!(first.header.dh, expected);
}
