//! C ABI 的端到端检查（iOS 走这条路径，不走 JNI）。
//!
//! 这些用例在 macOS 上就能跑：不需要 Xcode、不需要设备，验证的是
//! 「头文件里声明的函数真的链接得上，并且语义与 JNI 一致」。
//! 如果这里红了，iOS 侧一定也起不来——比等到装好 Xcode 再发现要早得多。

use mim_core_rust::ffi::{
    mim_bytes_free, mim_compose_first_message, mim_key_bundle_generate, mim_open_first_message,
    mim_sign_with_identity, mim_verify_with_identity, MIM_ERR_INVALID_ARG,
    MIM_ERR_STATE, MIM_OK,
};
use mim_core_rust::keys::KeyBundle;
use mim_core_rust::messaging::{ComposeRequest, OpenFirstRequest};
use mim_core_rust::ratchet::WireMessage;
use mim_core_rust::sender_cert::SenderCertificate;
use serde_json::{json, Value};

const TAG: &str = "gTq0Y2v5Qm9m8Zt0k3Wc2Xr7Lp4Nb1Hs6Jd9Ff2Ka5E";

/// 调用 ABI 并把库分配的缓冲读成 Vec，随后立即归还——测试里也不能漏 free。
unsafe fn take(out_ptr: *mut *mut u8, out_len: usize) -> Vec<u8> {
    let len = out_len;
    let bytes = std::slice::from_raw_parts(*out_ptr, len).to_vec();
    mim_bytes_free(*out_ptr, len);
    bytes
}

fn hex_to_base64(hex: &str) -> String {
    use base64::Engine;
    base64::engine::general_purpose::STANDARD.encode(hex::decode(hex).expect("hex"))
}

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

/// 首条消息：ABI 组装 → ABI 拆解，明文必须原样回来。
#[test]
fn first_message_round_trips_through_c_abi() {
    let alice = KeyBundle::generate(1, 1).expect("A 密钥包");
    let bob = KeyBundle::generate(1, 1).expect("B 密钥包");
    let bob_spk = bob.current_signed_pre_key().expect("B 签名预密钥");
    let bob_opk = bob.one_time_pre_keys.first().expect("B 一次性预密钥");

    let request = ComposeRequest {
        identity_private: alice.identity.private_key.clone(),
        identity_public: alice.identity.public_key.clone(),
        peer_identity_public: bob.identity.public_key.clone(),
        peer_signed_pre_key_id: bob_spk.id,
        peer_signed_pre_key_public: bob_spk.public_key.clone(),
        peer_one_time_pre_key_id: Some(bob_opk.id),
        peer_one_time_pre_key_public: Some(bob_opk.public_key.clone()),
        plaintext: "来自 iOS 的首条消息".to_string(),
        conversation_tag: Some(TAG.to_string()),
        session_id: "session-abi".to_string(),
        certificate: certificate("alice-iphone", &alice.identity.public_key),
        iv: None,
    };
    let request_json = serde_json::to_vec(&request).expect("请求 JSON");

    let mut out_ptr: *mut u8 = std::ptr::null_mut();
    let mut out_len: usize = 0;
    let code = unsafe {
        mim_compose_first_message(request_json.as_ptr(), request_json.len(), &mut out_ptr, &mut out_len)
    };
    assert_eq!(MIM_OK, code, "组装首条消息应成功");
    let composed: Value = unsafe { serde_json::from_slice(&take(&mut out_ptr, out_len)) }.expect("响应 JSON");

    assert_eq!(composed["conversation_tag"], json!(TAG));
    assert_eq!(composed["session_id"], json!("session-abi"));
    assert_eq!(composed["used_one_time_pre_key"], json!(true));

    let wire: WireMessage = serde_json::from_value(composed["wire"].clone()).expect("wire");
    let open = OpenFirstRequest {
        identity_private: bob.identity.private_key.clone(),
        identity_public: bob.identity.public_key.clone(),
        signed_pre_key_private: bob_spk.private_key.clone(),
        signed_pre_key_public: bob_spk.public_key.clone(),
        one_time_pre_key_private: Some(bob_opk.private_key.clone()),
        peer_identity_public: composed["sender_identity_key"].as_str().expect("ik").to_string(),
        peer_ephemeral_public: composed["sender_ephemeral_key"].as_str().expect("ek").to_string(),
        used_one_time_pre_key: true,
        wire,
        next_dh_private: None,
        next_dh_public: None,
    };
    let open_json = serde_json::to_vec(&open).expect("请求 JSON");
    let mut open_out: *mut u8 = std::ptr::null_mut();
    let mut open_len: usize = 0;
    let code = unsafe {
        mim_open_first_message(open_json.as_ptr(), open_json.len(), &mut open_out, &mut open_len)
    };
    assert_eq!(MIM_OK, code, "拆首条消息应成功");
    let opened: Value = unsafe { serde_json::from_slice(&take(&mut open_out, open_len)) }
        .expect("响应 JSON");

    assert_eq!(opened["plaintext"], json!("来自 iOS 的首条消息"));
    assert_eq!(opened["certificate"]["device_id"], json!("alice-iphone"));
}

/// 身份签名：同一把钥匙能验回来，改一个字节就失效。
#[test]
fn identity_signature_round_trips_through_c_abi() {
    let bundle = KeyBundle::generate(1, 0).expect("密钥包");
    let private = bundle.identity.private_key.clone().into_bytes();
    let public = hex::decode(&bundle.identity.public_key).expect("公钥 hex");
    let payload = b"msg-1|tagA|alice|bob|device-9";

    let mut out_ptr: *mut u8 = std::ptr::null_mut();
    let mut out_len: usize = 0;
    let code = unsafe {
        mim_sign_with_identity(
            private.as_ptr(), private.len(),
            payload.as_ptr(), payload.len(),
            &mut out_ptr, &mut out_len,
        )
    };
    assert_eq!(MIM_OK, code, "签名应成功");
    let signature = unsafe { take(&mut out_ptr, out_len) };

    let valid = unsafe {
        mim_verify_with_identity(
            public.as_ptr(), public.len(),
            payload.as_ptr(), payload.len(),
            signature.as_ptr(), signature.len(),
        )
    };
    assert_eq!(MIM_OK, valid, "同一载荷应验签通过");

    let tampered = b"msg-1|tagA|alice|bob|device-8";
    let invalid = unsafe {
        mim_verify_with_identity(
            public.as_ptr(), public.len(),
            tampered.as_ptr(), tampered.len(),
            signature.as_ptr(), signature.len(),
        )
    };
    assert_eq!(MIM_ERR_STATE, invalid, "载荷改动后必须验签失败");
}

/// 非法输入不能 panic 穿过 FFI 边界，也不能假装成功。
#[test]
fn garbage_input_returns_error_codes() {
    let junk = b"not json";
    let mut out_ptr: *mut u8 = std::ptr::null_mut();
    let mut out_len: usize = 0;

    let code = unsafe {
        mim_compose_first_message(junk.as_ptr(), junk.len(), &mut out_ptr, &mut out_len)
    };
    assert_eq!(MIM_ERR_INVALID_ARG, code);

    let code = unsafe { mim_compose_first_message(std::ptr::null(), 0, &mut out_ptr, &mut out_len) };
    assert_eq!(MIM_ERR_INVALID_ARG, code, "空指针必须判非法，不能解引用");

    let code = unsafe { mim_open_first_message(junk.as_ptr(), junk.len(), &mut out_ptr, &mut out_len) };
    assert_eq!(MIM_ERR_INVALID_ARG, code);

    // 语义无效的请求（字段缺失）同样是 INVALID_ARG，而不是崩溃。
    let partial = br#"{"identity_private":"00"}"#;
    let code = unsafe {
        mim_compose_first_message(partial.as_ptr(), partial.len(), &mut out_ptr, &mut out_len)
    };
    assert_eq!(MIM_ERR_INVALID_ARG, code);
}

/// 密钥包生成走 ABI 也要能用（iOS 首次登录时第一个会调的函数）。
#[test]
fn key_bundle_generation_works_through_c_abi() {
    let mut out_ptr: *mut u8 = std::ptr::null_mut();
    let mut out_len: usize = 0;
    let code = unsafe { mim_key_bundle_generate(1, 2, &mut out_ptr, &mut out_len) };
    assert_eq!(MIM_OK, code);
    let raw = unsafe { take(&mut out_ptr, out_len) };
    let bundle: Value = serde_json::from_slice(&raw).expect("密钥包 JSON");
    assert_eq!(bundle["one_time_pre_keys"].as_array().expect("opk").len(), 2);
    assert!(bundle["identity"]["private_key"].as_str().expect("ik").len() > 32);
}
