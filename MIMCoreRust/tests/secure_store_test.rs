//! 加密状态存储的格式与拒绝路径。

use mim_core_rust::secure_store::{
    decrypt_state, encrypt_state, new_nonce, KEY_BYTES, NONCE_BYTES, STATE_MAGIC,
};

fn fixed_key() -> Vec<u8> {
    (0..KEY_BYTES as u8).collect()
}

fn fixed_nonce() -> Vec<u8> {
    (0..NONCE_BYTES as u8).collect()
}

#[test]
fn round_trip_and_format() {
    let key = fixed_key();
    let nonce = fixed_nonce();
    let plaintext = br#"{"root_key":"deadbeef","send_count":7}"#;

    let blob = encrypt_state(plaintext, &key, &nonce).expect("加密");
    let lines: Vec<&str> = blob.split('\n').collect();
    assert_eq!(lines.len(), 3);
    assert_eq!(lines[0], STATE_MAGIC);

    let back = decrypt_state(&blob, &key).expect("解密");
    assert_eq!(back, plaintext);

    // 同一 key/nonce 必须得到同一份密文：格式是确定的。
    let again = encrypt_state(plaintext, &key, &nonce).expect("再加密");
    assert_eq!(blob, again);
}

#[test]
fn golden_blob_is_stable() {
    // 这条值一旦变化，说明格式或 AAD 改了，旧状态将无法解密。
    let blob = encrypt_state(b"mim", &fixed_key(), &fixed_nonce()).expect("加密");
    assert_eq!(blob, "MIM-STATE-1\nAAECAwQFBgcICQoL\nKmu7S4Uk95w9Y4FJrMNnzE5U6w==");
}

#[test]
fn rejects_wrong_key() {
    let blob = encrypt_state(b"secret", &fixed_key(), &fixed_nonce()).expect("加密");
    let mut key = fixed_key();
    key[0] ^= 0xff;
    assert!(decrypt_state(&blob, &key).is_none());
}

#[test]
fn rejects_tampered_ciphertext_and_nonce() {
    let blob = encrypt_state(b"secret", &fixed_key(), &fixed_nonce()).expect("加密");
    let lines: Vec<&str> = blob.split('\n').collect();

    // 改密文最后一个字符（属于 Base64 尾部），必须失败。
    let mut ciphertext = lines[2].to_string();
    let last = ciphertext.pop().unwrap();
    ciphertext.push(if last == 'A' { 'B' } else { 'A' });
    let tampered = format!("{}\n{}\n{}", lines[0], lines[1], ciphertext);
    assert!(decrypt_state(&tampered, &fixed_key()).is_none());

    // 换一个 nonce，必须失败。
    let other_blob = encrypt_state(b"secret", &fixed_key(), &[9u8; NONCE_BYTES]).expect("加密");
    let other_nonce = other_blob.split('\n').nth(1).unwrap();
    let swapped = format!("{}\n{}\n{}", lines[0], other_nonce, lines[2]);
    assert!(decrypt_state(&swapped, &fixed_key()).is_none());
}

#[test]
fn rejects_malformed_blobs() {
    let key = fixed_key();
    let blob = encrypt_state(b"secret", &key, &fixed_nonce()).expect("加密");
    let lines: Vec<&str> = blob.split('\n').collect();

    // 版本串被改：AAD 不匹配
    let wrong_magic = format!("MIM-STATE-2\n{}\n{}", lines[1], lines[2]);
    assert!(decrypt_state(&wrong_magic, &key).is_none());

    // 多一行
    let extra_line = format!("{}\nextra", blob);
    assert!(decrypt_state(&extra_line, &key).is_none());

    // 缺字段
    assert!(decrypt_state(STATE_MAGIC, &key).is_none());

    // 密钥长度不对
    assert!(decrypt_state(&blob, &key[..16]).is_none());
    assert!(encrypt_state(b"x", &key[..16], &fixed_nonce()).is_none());
    assert!(encrypt_state(b"x", &key, &[0u8; 8]).is_none());
}

#[test]
fn random_nonce_is_not_repeated() {
    let first = new_nonce();
    let second = new_nonce();
    assert_ne!(first, second);
    assert_eq!(first.len(), NONCE_BYTES);
}
