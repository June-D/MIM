//! C ABI（FFI）边界。
//!
//! 这是移动端的公共底座：Android 通过 JNI 调它，iOS 通过 cinterop 调它。
//! 设计约束：
//! - 不暴露 Rust 类型，只用不透明句柄与字节缓冲；
//! - 消息与状态用 JSON 传输，新增字段不会破坏 ABI；
//! - 所有输出缓冲由库分配，调用方用 [`mim_bytes_free`] 归还；
//! - 错误一律用返回码表示，不 panic 穿过边界。
//!
//! 头文件见 `include/mim_core.h`。

use crate::ratchet::{RatchetState, StateSnapshot, WireMessage};
use crate::session::{initiator_session, responder_session};
use std::ffi::c_char;
use std::panic::{catch_unwind, AssertUnwindSafe};

pub const MIM_OK: i32 = 0;
pub const MIM_ERR_INVALID_ARG: i32 = 1;
pub const MIM_ERR_HANDSHAKE: i32 = 2;
pub const MIM_ERR_ENCRYPT: i32 = 3;
pub const MIM_ERR_DECRYPT: i32 = 4;
pub const MIM_ERR_STATE: i32 = 5;

/// 不透明会话句柄。
pub struct MimSession {
    state: RatchetState,
    /// 发起方建会话时要随首条消息带过去的参数。
    initiator_keys: Option<InitiatorKeys>,
}

struct InitiatorKeys {
    identity_key: Vec<u8>,
    ephemeral_key: Vec<u8>,
    used_one_time_pre_key: bool,
}

unsafe fn input_slice<'a>(ptr: *const u8, len: usize) -> Option<&'a [u8]> {
    if len == 0 {
        return Some(&[]);
    }
    if ptr.is_null() {
        return None;
    }
    Some(std::slice::from_raw_parts(ptr, len))
}

/// 把 Vec 交给 C：库分配，调用方用 `mim_bytes_free` 释放。
unsafe fn give_bytes(bytes: Vec<u8>, out_ptr: *mut *mut u8, out_len: *mut usize) -> i32 {
    if out_ptr.is_null() || out_len.is_null() {
        return MIM_ERR_INVALID_ARG;
    }
    let boxed: Box<[u8]> = bytes.into_boxed_slice();
    let len = boxed.len();
    *out_len = len;
    *out_ptr = Box::into_raw(boxed) as *mut u8;
    MIM_OK
}

/// 释放库分配的输出缓冲。
#[no_mangle]
pub unsafe extern "C" fn mim_bytes_free(ptr: *mut u8, len: usize) {
    if ptr.is_null() {
        return;
    }
    let slice = std::ptr::slice_from_raw_parts_mut(ptr, len);
    drop(Box::from_raw(slice));
}

#[no_mangle]
pub unsafe extern "C" fn mim_session_free(session: *mut MimSession) {
    if !session.is_null() {
        drop(Box::from_raw(session));
    }
}

/// 发起方建会话。`peer_one_time_pre_key` 传 null/0 表示不使用一次性预密钥。
#[no_mangle]
pub unsafe extern "C" fn mim_initiator_session(
    self_identity_priv: *const u8,
    self_identity_priv_len: usize,
    self_identity_pub: *const u8,
    self_identity_pub_len: usize,
    peer_identity_pub: *const u8,
    peer_identity_pub_len: usize,
    peer_signed_pre_key_pub: *const u8,
    peer_signed_pre_key_pub_len: usize,
    peer_one_time_pre_key_pub: *const u8,
    peer_one_time_pre_key_pub_len: usize,
    ephemeral_priv: *const u8,
    ephemeral_priv_len: usize,
    ephemeral_pub: *const u8,
    ephemeral_pub_len: usize,
    out_error: *mut i32,
) -> *mut MimSession {
    let result = catch_unwind(AssertUnwindSafe(|| {
        let self_priv = input_slice(self_identity_priv, self_identity_priv_len)?;
        let self_pub = input_slice(self_identity_pub, self_identity_pub_len)?;
        let peer_identity = input_slice(peer_identity_pub, peer_identity_pub_len)?;
        let peer_spk = input_slice(peer_signed_pre_key_pub, peer_signed_pre_key_pub_len)?;
        let peer_opk = input_slice(peer_one_time_pre_key_pub, peer_one_time_pre_key_pub_len)?;
        let ephemeral_priv = input_slice(ephemeral_priv, ephemeral_priv_len)?;
        let ephemeral_pub = input_slice(ephemeral_pub, ephemeral_pub_len)?;

        let opk = if peer_opk.is_empty() { None } else { Some(peer_opk) };
        initiator_session(
            self_priv,
            self_pub,
            peer_identity,
            peer_spk,
            opk,
            ephemeral_priv,
            ephemeral_pub,
        )
    }));

    match result {
        Ok(Some(result)) => {
            if !out_error.is_null() {
                *out_error = MIM_OK;
            }
            Box::into_raw(Box::new(MimSession {
                initiator_keys: Some(InitiatorKeys {
                    identity_key: result.sender_identity_key,
                    ephemeral_key: result.sender_ephemeral_key,
                    used_one_time_pre_key: result.used_one_time_pre_key,
                }),
                state: result.state,
            }))
        }
        _ => {
            if !out_error.is_null() {
                *out_error = MIM_ERR_HANDSHAKE;
            }
            std::ptr::null_mut()
        }
    }
}

/// 接收方建会话。
#[no_mangle]
pub unsafe extern "C" fn mim_responder_session(
    self_identity_priv: *const u8,
    self_identity_priv_len: usize,
    self_identity_pub: *const u8,
    self_identity_pub_len: usize,
    self_signed_pre_key_priv: *const u8,
    self_signed_pre_key_priv_len: usize,
    self_signed_pre_key_pub: *const u8,
    self_signed_pre_key_pub_len: usize,
    self_one_time_pre_key_priv: *const u8,
    self_one_time_pre_key_priv_len: usize,
    peer_identity_pub: *const u8,
    peer_identity_pub_len: usize,
    peer_ephemeral_pub: *const u8,
    peer_ephemeral_pub_len: usize,
    used_one_time_pre_key: i32,
    out_error: *mut i32,
) -> *mut MimSession {
    let result = catch_unwind(AssertUnwindSafe(|| {
        let self_priv = input_slice(self_identity_priv, self_identity_priv_len)?;
        let self_pub = input_slice(self_identity_pub, self_identity_pub_len)?;
        let self_spk_priv = input_slice(self_signed_pre_key_priv, self_signed_pre_key_priv_len)?;
        let self_spk_pub = input_slice(self_signed_pre_key_pub, self_signed_pre_key_pub_len)?;
        let self_opk_priv = input_slice(self_one_time_pre_key_priv, self_one_time_pre_key_priv_len)?;
        let peer_identity = input_slice(peer_identity_pub, peer_identity_pub_len)?;
        let peer_ephemeral = input_slice(peer_ephemeral_pub, peer_ephemeral_pub_len)?;

        let opk = if self_opk_priv.is_empty() { None } else { Some(self_opk_priv) };
        responder_session(
            self_priv,
            self_pub,
            self_spk_priv,
            self_spk_pub,
            opk,
            peer_identity,
            peer_ephemeral,
            used_one_time_pre_key != 0,
        )
    }));

    match result {
        Ok(Some(state)) => {
            if !out_error.is_null() {
                *out_error = MIM_OK;
            }
            Box::into_raw(Box::new(MimSession {
                state,
                initiator_keys: None,
            }))
        }
        _ => {
            if !out_error.is_null() {
                *out_error = MIM_ERR_HANDSHAKE;
            }
            std::ptr::null_mut()
        }
    }
}

/// 导出发起方首条消息需要携带的参数（JSON：identity/ephemeral/used_opk）。
#[no_mangle]
pub unsafe extern "C" fn mim_initiator_pubkeys(
    session: *mut MimSession,
    out_json: *mut *mut u8,
    out_len: *mut usize,
) -> i32 {
    let session = match session.as_ref() {
        Some(s) => s,
        None => return MIM_ERR_INVALID_ARG,
    };
    let keys = match session.initiator_keys.as_ref() {
        Some(k) => k,
        None => return MIM_ERR_STATE,
    };
    let json = format!(
        "{{\"identity\":\"{}\",\"ephemeral\":\"{}\",\"used_opk\":{}}}",
        b64(&keys.identity_key),
        b64(&keys.ephemeral_key),
        keys.used_one_time_pre_key
    );
    give_bytes(json.into_bytes(), out_json, out_len)
}

/// 加密。输出为线上消息 JSON（dh/pn/n/iv/ciphertext）。
#[no_mangle]
pub unsafe extern "C" fn mim_encrypt(
    session: *mut MimSession,
    iv: *const u8,
    iv_len: usize,
    plaintext: *const u8,
    plaintext_len: usize,
    out_json: *mut *mut u8,
    out_len: *mut usize,
) -> i32 {
    let session = match session.as_mut() {
        Some(s) => s,
        None => return MIM_ERR_INVALID_ARG,
    };
    let iv = match input_slice(iv, iv_len) {
        Some(v) if v.len() == 12 => v,
        _ => return MIM_ERR_INVALID_ARG,
    };
    let plaintext = match input_slice(plaintext, plaintext_len) {
        Some(v) => v,
        None => return MIM_ERR_INVALID_ARG,
    };
    let plaintext = match std::str::from_utf8(plaintext) {
        Ok(v) => v,
        Err(_) => return MIM_ERR_INVALID_ARG,
    };
    match session.state.encrypt_wire(iv, plaintext) {
        Some(message) => match serde_json::to_vec(&message) {
            Ok(json) => give_bytes(json, out_json, out_len),
            Err(_) => MIM_ERR_STATE,
        },
        None => MIM_ERR_ENCRYPT,
    }
}

/// 解密。输入为线上消息 JSON，输出明文。
#[no_mangle]
pub unsafe extern "C" fn mim_decrypt(
    session: *mut MimSession,
    wire_json: *const u8,
    wire_json_len: usize,
    out_plaintext: *mut *mut u8,
    out_len: *mut usize,
) -> i32 {
    let session = match session.as_mut() {
        Some(s) => s,
        None => return MIM_ERR_INVALID_ARG,
    };
    let json = match input_slice(wire_json, wire_json_len) {
        Some(v) => v,
        None => return MIM_ERR_INVALID_ARG,
    };
    let message: WireMessage = match serde_json::from_slice(json) {
        Ok(v) => v,
        Err(_) => return MIM_ERR_INVALID_ARG,
    };
    match session.state.decrypt_wire(&message) {
        Some(plaintext) => give_bytes(plaintext.into_bytes(), out_plaintext, out_len),
        None => MIM_ERR_DECRYPT,
    }
}

/// 导出状态快照（JSON，含私钥；调用方必须加密后落盘）。
#[no_mangle]
pub unsafe extern "C" fn mim_session_save(
    session: *mut MimSession,
    out_json: *mut *mut u8,
    out_len: *mut usize,
) -> i32 {
    let session = match session.as_ref() {
        Some(s) => s,
        None => return MIM_ERR_INVALID_ARG,
    };
    match serde_json::to_vec(&session.state.snapshot()) {
        Ok(json) => give_bytes(json, out_json, out_len),
        Err(_) => MIM_ERR_STATE,
    }
}

/// 从状态快照恢复会话。
#[no_mangle]
pub unsafe extern "C" fn mim_session_load(
    json: *const u8,
    json_len: usize,
    out_error: *mut i32,
) -> *mut MimSession {
    let result = catch_unwind(AssertUnwindSafe(|| {
        let bytes = input_slice(json, json_len)?;
        let snapshot: StateSnapshot = serde_json::from_slice(bytes).ok()?;
        RatchetState::from_snapshot(&snapshot)
    }));
    match result {
        Ok(Some(state)) => {
            if !out_error.is_null() {
                *out_error = MIM_OK;
            }
            Box::into_raw(Box::new(MimSession {
                state,
                initiator_keys: None,
            }))
        }
        _ => {
            if !out_error.is_null() {
                *out_error = MIM_ERR_STATE;
            }
            std::ptr::null_mut()
        }
    }
}

fn b64(value: &[u8]) -> String {
    use base64::Engine;
    base64::engine::general_purpose::STANDARD.encode(value)
}

/// 保留给未来需要 C 字符串的调用方。
#[no_mangle]
pub unsafe extern "C" fn mim_version() -> *const c_char {
    static VERSION: &[u8] = b"mim-core-rust 0.1.0\0";
    VERSION.as_ptr() as *const c_char
}
