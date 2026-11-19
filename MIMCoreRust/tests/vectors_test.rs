//! 与 Java 协议核心的逐字节对拍。
//!
//! 向量文件由 `MIMCore/tools/ExportVectors.java` 生成；
//! 任何一处不一致都说明 Rust 移植会破坏与现有客户端的兼容性。

use mim_core_rust::*;
use mim_core_rust::envelope::{
    sender_cert_envelope_pack, sender_cert_envelope_unpack, sync_envelope_pack,
    sync_envelope_unpack, tag_envelope_pack, tag_envelope_unpack,
};
use mim_core_rust::sender_cert::SenderCertificate;
use serde::Deserialize;

#[derive(Deserialize)]
struct Vectors {
    hkdf: Vec<HkdfCase>,
    aesgcm: AesGcmCase,
    root_kdf: RootKdfCase,
    chain: ChainCase,
    aad: AadCase,
    tag_envelope: EnvelopeCase,
    sync_envelope: SyncEnvelopeCase,
    cert_canonical: CertCase,
    x3dh: X3dhCase,
    dh_ratchet: DhRatchetCase,
    cert_rsa: CertRsaCase,
    cert_envelope: CertEnvelopeCase,
    sha256_hex: HashCase,
}

#[derive(Deserialize)]
struct HkdfCase {
    salt: String,
    ikm: String,
    info: String,
    len: usize,
    out: String,
}

#[derive(Deserialize)]
struct AesGcmCase {
    key: String,
    iv: String,
    aad: String,
    plaintext: String,
    ciphertext: String,
}

#[derive(Deserialize)]
struct RootKdfCase {
    root: String,
    dh: String,
    new_root: String,
    chain: String,
}

#[derive(Deserialize)]
struct ChainCase {
    chain: String,
    message_key: String,
    next_chain: String,
}

#[derive(Deserialize)]
struct AadCase {
    dh: String,
    pn: u32,
    n: u32,
    aad: String,
}

#[derive(Deserialize)]
struct EnvelopeCase {
    tag: String,
    body: String,
    packed: String,
}

#[derive(Deserialize)]
struct SyncEnvelopeCase {
    token: String,
    body: String,
    packed: String,
}

#[derive(Deserialize)]
struct CertCase {
    username: String,
    device: String,
    identity: String,
    issued: i64,
    expires: i64,
    key_id: String,
    canonical: String,
}

#[derive(Deserialize)]
struct X3dhCase {
    aik_priv: String,
    aek_priv: String,
    bik_pub: String,
    bspk_pub: String,
    bopk_pub: String,
    dh1: String,
    dh2: String,
    dh3: String,
    dh4: String,
    initial_key: String,
}

#[derive(Deserialize)]
struct DhRatchetCase {
    aek2_priv: String,
    bik_pub: String,
    dh_out: String,
    root: String,
    new_root: String,
    chain: String,
}

#[derive(Deserialize)]
struct CertRsaCase {
    public_key: String,
    now: i64,
    canonical: String,
    cert: SenderCertificate,
}

#[derive(Deserialize)]
struct CertEnvelopeCase {
    body: String,
    tag: String,
    packed7: String,
    packed8: String,
}

#[derive(Deserialize)]
struct HashCase {
    input: String,
    hash: String,
}

fn vectors() -> Vectors {
    let raw = include_str!("fixtures/vectors.json");
    serde_json::from_str(raw).expect("vectors.json 解析失败")
}

#[test]
fn hkdf_matches_java() {
    let v = vectors();
    for case in v.hkdf {
        let salt = if case.salt.is_empty() {
            None
        } else {
            Some(hex::decode(&case.salt).unwrap())
        };
        let out = hkdf_derive(
            salt.as_deref(),
            &hex::decode(&case.ikm).unwrap(),
            &case.info,
            case.len,
        );
        assert_eq!(hex::encode(out), case.out, "info={}", case.info);
    }
}

#[test]
fn aes_gcm_matches_java() {
    let v = vectors();
    let case = v.aesgcm;
    let key = hex::decode(&case.key).unwrap();
    let iv = hex::decode(&case.iv).unwrap();
    let aad = hex::decode(&case.aad).unwrap();
    let plaintext = hex::decode(&case.plaintext).unwrap();
    let ct = aes_gcm_encrypt(&key, &iv, &aad, &plaintext);
    assert_eq!(hex::encode(&ct), case.ciphertext);
    let back = aes_gcm_decrypt(&key, &iv, &aad, &ct).expect("decrypt");
    assert_eq!(back, plaintext);
}

#[test]
fn ratchet_kdf_matches_java() {
    let v = vectors();
    let (new_root, chain) = root_kdf(
        &hex::decode(&v.root_kdf.root).unwrap(),
        &hex::decode(&v.root_kdf.dh).unwrap(),
    );
    assert_eq!(hex::encode(new_root), v.root_kdf.new_root);
    assert_eq!(hex::encode(chain), v.root_kdf.chain);

    let (mk, next) = chain_step(&hex::decode(&v.chain.chain).unwrap());
    assert_eq!(hex::encode(mk), v.chain.message_key);
    assert_eq!(hex::encode(next), v.chain.next_chain);
}

#[test]
fn aad_and_envelopes_match_java() {
    let v = vectors();
    let aad = header_aad(&v.aad.dh, v.aad.pn, v.aad.n);
    assert_eq!(hex::encode(aad.as_bytes()), v.aad.aad);

    let body = String::from_utf8(hex::decode(&v.tag_envelope.body).unwrap()).unwrap();
    assert_eq!(
        hex::encode(tag_envelope_pack(&v.tag_envelope.tag, &body).as_bytes()),
        v.tag_envelope.packed
    );
    let packed = String::from_utf8(hex::decode(&v.tag_envelope.packed).unwrap()).unwrap();
    let parsed = tag_envelope_unpack(&packed).expect("unpack");
    assert_eq!(parsed.tag, v.tag_envelope.tag);
    assert_eq!(parsed.text, body);

    let sync_body = String::from_utf8(hex::decode(&v.sync_envelope.body).unwrap()).unwrap();
    assert_eq!(
        hex::encode(sync_envelope_pack(&v.sync_envelope.token, &sync_body).as_bytes()),
        v.sync_envelope.packed
    );
}

#[test]
fn p256_ecdh_and_x3dh_match_java() {
    let v = vectors();
    let x = v.x3dh;
    let a_ik = hex::decode(&x.aik_priv).unwrap();
    let a_ek = hex::decode(&x.aek_priv).unwrap();
    let b_ik = hex::decode(&x.bik_pub).unwrap();
    let b_spk = hex::decode(&x.bspk_pub).unwrap();
    let b_opk = hex::decode(&x.bopk_pub).unwrap();

    let dh1 = dh(&a_ik, &b_spk).expect("dh1");
    let dh2 = dh(&a_ek, &b_ik).expect("dh2");
    let dh3 = dh(&a_ek, &b_spk).expect("dh3");
    let dh4 = dh(&a_ek, &b_opk).expect("dh4");
    assert_eq!(hex::encode(&dh1), x.dh1, "DH1");
    assert_eq!(hex::encode(&dh2), x.dh2, "DH2");
    assert_eq!(hex::encode(&dh3), x.dh3, "DH3");
    assert_eq!(hex::encode(&dh4), x.dh4, "DH4");

    let initial = x3dh_initial_key(&[&dh1, &dh2, &dh3, &dh4]);
    assert_eq!(hex::encode(initial), x.initial_key, "X3DH 初始密钥");

    let r = v.dh_ratchet;
    let dh_out = dh(&hex::decode(&r.aek2_priv).unwrap(), &hex::decode(&r.bik_pub).unwrap())
        .expect("dh_ratchet");
    assert_eq!(hex::encode(&dh_out), r.dh_out);
    let (new_root, chain) = root_kdf(&hex::decode(&r.root).unwrap(), &dh_out);
    assert_eq!(hex::encode(new_root), r.new_root);
    assert_eq!(hex::encode(chain), r.chain);
}

#[test]
fn certificate_canonical_and_hash_match_java() {
    let v = vectors();
    let c = v.cert_canonical;
    let canonical = cert_canonical(
        &c.username, &c.device, &c.identity, c.issued, c.expires, &c.key_id,
    );
    assert_eq!(hex::encode(canonical.as_bytes()), c.canonical);

    let input = hex::decode(&v.sha256_hex.input).unwrap();
    assert_eq!(sha256_hex(&input), v.sha256_hex.hash);
}

#[test]
fn envelopes_unpack_like_java() {
    let v = vectors();

    // 标签信封
    let tag_packed = String::from_utf8(hex::decode(&v.tag_envelope.packed).unwrap()).unwrap();
    let tag_body = String::from_utf8(hex::decode(&v.tag_envelope.body).unwrap()).unwrap();
    let parsed = tag_envelope_unpack(&tag_packed).expect("标签信封");
    assert_eq!(parsed.tag, v.tag_envelope.tag);
    assert_eq!(parsed.text, tag_body);
    assert_eq!(
        tag_envelope_pack(&v.tag_envelope.tag, &tag_body),
        tag_packed
    );
    assert!(tag_envelope_unpack("普通正文").is_none());

    // 同步副本信封
    let sync_packed = String::from_utf8(hex::decode(&v.sync_envelope.packed).unwrap()).unwrap();
    let sync_body = String::from_utf8(hex::decode(&v.sync_envelope.body).unwrap()).unwrap();
    let parsed = sync_envelope_unpack(&sync_packed).expect("同步信封");
    assert_eq!(parsed.recall_token, v.sync_envelope.token);
    assert_eq!(parsed.text, sync_body);
    assert_eq!(sync_envelope_pack(&v.sync_envelope.token, &sync_body), sync_packed);
    // 凭证过短必须拒绝
    assert!(sync_envelope_unpack(&format!("\u{1}mimsync1\u{1}short\nbody")).is_none());

    // 证书信封
    let body = String::from_utf8(hex::decode(&v.cert_envelope.body).unwrap()).unwrap();
    let packed7 = String::from_utf8(hex::decode(&v.cert_envelope.packed7).unwrap()).unwrap();
    let parsed7 = sender_cert_envelope_unpack(&packed7).expect("7 段证书信封");
    assert_eq!(parsed7.certificate, v.cert_rsa.cert);
    assert_eq!(parsed7.payload, body);
    assert!(parsed7.conversation_tag.is_none());

    let packed8 = String::from_utf8(hex::decode(&v.cert_envelope.packed8).unwrap()).unwrap();
    let parsed8 = sender_cert_envelope_unpack(&packed8).expect("8 段证书信封");
    assert_eq!(parsed8.certificate, v.cert_rsa.cert);
    assert_eq!(parsed8.conversation_tag.as_deref(), Some(v.cert_envelope.tag.as_str()));
    assert_eq!(parsed8.payload, body);
    assert_eq!(
        sender_cert_envelope_pack(
            &v.cert_rsa.cert,
            Some(&v.cert_envelope.tag),
            &body
        ),
        packed8
    );
    // 缺正文分隔符必须拒绝
    assert!(sender_cert_envelope_unpack(&format!("\u{1}mimcert1\u{1}no-body")).is_none());
}

#[test]
fn sender_certificate_verifies_with_rsa() {
    let v = vectors();
    let c = &v.cert_rsa;
    let public_key = hex::decode(&c.public_key).unwrap();

    assert_eq!(
        hex::encode(c.cert.canonical().as_bytes()),
        c.canonical,
        "规范化串"
    );
    assert!(c.cert.verify(&public_key, c.now), "有效证书应验签通过");
    assert!(!c.cert.verify(&public_key, c.cert.expires_at + 10 * 60 * 1000), "过期必须拒绝");
    assert!(!c.cert.verify(&public_key, c.cert.issued_at - 10 * 60 * 1000), "未生效必须拒绝");

    let mut tampered = c.cert.clone();
    tampered.username = "mallory".to_string();
    assert!(!tampered.verify(&public_key, c.now), "篡改字段必须拒绝");

    let mut bad_key_id = c.cert.clone();
    bad_key_id.key_id = "00".repeat(32);
    assert!(!bad_key_id.verify(&public_key, c.now), "keyId 不匹配必须拒绝");

    let mut bad_signature = c.cert.clone();
    bad_signature.signature = "AAAA".to_string();
    assert!(!bad_signature.verify(&public_key, c.now), "非法签名必须拒绝");
}
