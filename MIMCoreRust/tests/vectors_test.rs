//! 与 Java 协议核心的逐字节对拍。
//!
//! 向量文件由 `MIMCore/tools/ExportVectors.java` 生成；
//! 任何一处不一致都说明 Rust 移植会破坏与现有客户端的兼容性。

use mim_core_rust::*;
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
    let (tag, text) = tag_envelope_unpack(&packed).expect("unpack");
    assert_eq!(tag, v.tag_envelope.tag);
    assert_eq!(text, body);

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
