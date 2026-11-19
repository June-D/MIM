//! 设备密钥管理的生成、轮换、一次性语义与加密落盘。

use mim_core_rust::keys::{
    generate_identity, generate_one_time_pre_keys, sign_signed_pre_key, verify_signed_pre_key,
    KeyBundle, SIGNED_PRE_KEY_KEEP,
};

fn hex_to_vec(value: &str) -> Vec<u8> {
    hex::decode(value).expect("hex")
}

#[test]
fn generated_bundle_is_self_consistent() {
    let bundle = KeyBundle::generate(1, 5).expect("生成密钥包");
    assert_eq!(bundle.version, 1);
    assert_eq!(bundle.signed_pre_keys.len(), 1);
    assert_eq!(bundle.one_time_pre_keys.len(), 5);

    let signed = bundle.current_signed_pre_key().expect("签名预密钥");
    assert!(verify_signed_pre_key(
        &hex_to_vec(&bundle.identity.public_key),
        signed.id,
        &hex_to_vec(&signed.public_key),
        &signed.signature,
    ));

    // 一次性预密钥 ID 连续，且互不重复
    let ids: Vec<u32> = bundle.one_time_pre_keys.iter().map(|k| k.id).collect();
    assert_eq!(ids, vec![1, 2, 3, 4, 5]);
    assert_eq!(bundle.next_one_time_pre_key_id, 6);
}

#[test]
fn rotation_keeps_recent_signed_pre_keys() {
    let mut bundle = KeyBundle::generate(1, 0).expect("生成密钥包");
    for _ in 0..5 {
        bundle.rotate_signed_pre_key().expect("轮换");
    }
    assert_eq!(bundle.signed_pre_keys.len(), SIGNED_PRE_KEY_KEEP);
    let ids: Vec<u32> = bundle.signed_pre_keys.iter().map(|k| k.id).collect();
    assert_eq!(ids, vec![4, 5, 6]);

    // 最旧的已被裁剪，其签名仍然有效但不再对外发放
    let current = bundle.current_signed_pre_key().unwrap();
    assert_eq!(current.id, 6);
}

#[test]
fn one_time_pre_keys_are_consumed_once() {
    let mut bundle = KeyBundle::generate(1, 3).expect("生成密钥包");
    let taken = bundle.take_one_time_pre_key(2).expect("取用");
    assert_eq!(taken.id, 2);
    assert!(bundle.take_one_time_pre_key(2).is_none(), "同一条不能取两次");
    assert_eq!(bundle.one_time_pre_keys.len(), 2);

    let more = bundle.replenish_one_time_pre_keys(4);
    let ids: Vec<u32> = more.iter().map(|k| k.id).collect();
    assert_eq!(ids, vec![4, 5, 6, 7]);
    assert_eq!(bundle.next_one_time_pre_key_id, 8);
}

#[test]
fn replenish_threshold_uses_remaining_count() {
    let mut bundle = KeyBundle::generate(1, 10).expect("生成密钥包");
    assert!(!bundle.needs_replenish(5));
    for id in 1..=7 {
        bundle.take_one_time_pre_key(id);
    }
    assert!(bundle.needs_replenish(5));
}

#[test]
fn encrypted_bundle_round_trip() {
    let bundle = KeyBundle::generate(3, 4).expect("生成密钥包");
    let key = [7u8; 32];
    let nonce = [9u8; 12];
    let blob = bundle.save_encrypted(&key, &nonce).expect("加密");
    let loaded = KeyBundle::load_encrypted(&blob, &key).expect("解密");
    assert_eq!(loaded, bundle);

    let mut wrong = key;
    wrong[0] ^= 0xff;
    assert!(KeyBundle::load_encrypted(&blob, &wrong).is_none());
}

#[test]
fn rust_signed_pre_key_is_verifiable() {
    let identity = generate_identity(1).expect("身份密钥");
    let signed = sign_signed_pre_key(&identity, 42).expect("签名");
    assert!(verify_signed_pre_key(
        &hex_to_vec(&identity.public_key),
        42,
        &hex_to_vec(&signed.public_key),
        &signed.signature,
    ));
    assert!(!verify_signed_pre_key(
        &hex_to_vec(&identity.public_key),
        43,
        &hex_to_vec(&signed.public_key),
        &signed.signature,
    ));
}

#[test]
fn one_time_batch_matches_requested_count() {
    let keys = generate_one_time_pre_keys(10, 3);
    assert_eq!(keys.len(), 3);
    let ids: Vec<u32> = keys.iter().map(|k| k.id).collect();
    assert_eq!(ids, vec![10, 11, 12]);
    for key in keys {
        let der = hex_to_vec(&key.public_key);
        assert_eq!(&der[..1], &[0x30], "必须是 SPKI DER");
    }
}
