//! JNI 绑定（Android 与桌面 JVM 共用）。
//!
//! 这一层只是薄封装：把 Java 的 `byte[]` 转成指针，调用与 C ABI 相同的内部
//! 逻辑，再把结果转回 `byte[]`。错误一律抛 `IllegalStateException`，
//! panic 在边界处被捕获，绝不跨越 FFI。
//!
//! 对应 Java 类：`com.junesoon.mim.core.MimCoreNative`。

use crate::ffi::MimSession;
use crate::ratchet::{RatchetState, StateSnapshot, WireMessage};
use crate::secure_store::{decrypt_state, encrypt_state, fill_random};
use crate::session::{initiator_session, responder_session};
use jni::objects::{JByteArray, JClass};
use jni::sys::{jboolean, jbyteArray, jint, jlong};
use jni::JNIEnv;
use std::panic::{catch_unwind, AssertUnwindSafe};

const EXCEPTION_CLASS: &str = "java/lang/IllegalStateException";

fn bytes(env: &mut JNIEnv, array: &JByteArray) -> Option<Vec<u8>> {
    env.convert_byte_array(array).ok()
}

fn throw(env: &mut JNIEnv, message: &str) {
    let _ = env.throw_new(EXCEPTION_CLASS, message);
}

fn array(env: &mut JNIEnv, value: Vec<u8>) -> jbyteArray {
    match env.byte_array_from_slice(&value) {
        Ok(array) => array.into_raw(),
        Err(_) => {
            throw(env, "无法分配返回数组");
            std::ptr::null_mut()
        }
    }
}

unsafe fn session<'a>(handle: jlong) -> Option<&'a mut MimSession> {
    if handle == 0 {
        return None;
    }
    (handle as *mut MimSession).as_mut()
}

#[no_mangle]
pub extern "system" fn Java_com_junesoon_mim_core_MimCoreNative_initiatorSession<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    self_identity_priv: JByteArray<'local>,
    self_identity_pub: JByteArray<'local>,
    peer_identity_pub: JByteArray<'local>,
    peer_signed_pre_key_pub: JByteArray<'local>,
    peer_one_time_pre_key_pub: JByteArray<'local>,
    ephemeral_priv: JByteArray<'local>,
    ephemeral_pub: JByteArray<'local>,
) -> jlong {
    let result = catch_unwind(AssertUnwindSafe(|| {
        let self_priv = bytes(&mut env, &self_identity_priv)?;
        let self_pub = bytes(&mut env, &self_identity_pub)?;
        let peer_identity = bytes(&mut env, &peer_identity_pub)?;
        let peer_spk = bytes(&mut env, &peer_signed_pre_key_pub)?;
        let peer_opk = bytes(&mut env, &peer_one_time_pre_key_pub)?;
        let ephemeral_priv = bytes(&mut env, &ephemeral_priv)?;
        let ephemeral_pub = bytes(&mut env, &ephemeral_pub)?;
        let opk = if peer_opk.is_empty() { None } else { Some(peer_opk.as_slice()) };
        initiator_session(
            &self_priv,
            &self_pub,
            &peer_identity,
            &peer_spk,
            opk,
            &ephemeral_priv,
            &ephemeral_pub,
        )
    }));
    match result {
        Ok(Some(result)) => {
            let session = MimSession::from_initiator(result);
            Box::into_raw(Box::new(session)) as jlong
        }
        _ => {
            throw(&mut env, "发起方握手失败");
            0
        }
    }
}

#[no_mangle]
pub extern "system" fn Java_com_junesoon_mim_core_MimCoreNative_responderSession<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    self_identity_priv: JByteArray<'local>,
    self_identity_pub: JByteArray<'local>,
    self_signed_pre_key_priv: JByteArray<'local>,
    self_signed_pre_key_pub: JByteArray<'local>,
    self_one_time_pre_key_priv: JByteArray<'local>,
    peer_identity_pub: JByteArray<'local>,
    peer_ephemeral_pub: JByteArray<'local>,
    used_one_time_pre_key: jboolean,
) -> jlong {
    let result = catch_unwind(AssertUnwindSafe(|| {
        let self_priv = bytes(&mut env, &self_identity_priv)?;
        let self_pub = bytes(&mut env, &self_identity_pub)?;
        let spk_priv = bytes(&mut env, &self_signed_pre_key_priv)?;
        let spk_pub = bytes(&mut env, &self_signed_pre_key_pub)?;
        let opk_priv = bytes(&mut env, &self_one_time_pre_key_priv)?;
        let peer_identity = bytes(&mut env, &peer_identity_pub)?;
        let peer_ephemeral = bytes(&mut env, &peer_ephemeral_pub)?;
        let opk = if opk_priv.is_empty() { None } else { Some(opk_priv.as_slice()) };
        responder_session(
            &self_priv,
            &self_pub,
            &spk_priv,
            &spk_pub,
            opk,
            &peer_identity,
            &peer_ephemeral,
            used_one_time_pre_key != 0,
        )
    }));
    match result {
        Ok(Some(state)) => Box::into_raw(Box::new(MimSession::from_state(state))) as jlong,
        _ => {
            throw(&mut env, "接收方握手失败");
            0
        }
    }
}

#[no_mangle]
pub extern "system" fn Java_com_junesoon_mim_core_MimCoreNative_initiatorPubkeys<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    handle: jlong,
) -> jbyteArray {
    let session = match unsafe { session(handle) } {
        Some(s) => s,
        None => {
            throw(&mut env, "会话句柄无效");
            return std::ptr::null_mut();
        }
    };
    match session.initiator_pubkeys_json() {
        Some(json) => array(&mut env, json.into_bytes()),
        None => {
            throw(&mut env, "该会话不是发起方");
            std::ptr::null_mut()
        }
    }
}

#[no_mangle]
pub extern "system" fn Java_com_junesoon_mim_core_MimCoreNative_encrypt<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    handle: jlong,
    iv: JByteArray<'local>,
    plaintext: JByteArray<'local>,
) -> jbyteArray {
    let session = match unsafe { session(handle) } {
        Some(s) => s,
        None => {
            throw(&mut env, "会话句柄无效");
            return std::ptr::null_mut();
        }
    };
    let iv = match bytes(&mut env, &iv) {
        Some(v) if v.len() == 12 => v,
        _ => {
            throw(&mut env, "IV 必须 12 字节");
            return std::ptr::null_mut();
        }
    };
    let plaintext = match bytes(&mut env, &plaintext) {
        Some(v) => v,
        None => {
            throw(&mut env, "无法读取明文");
            return std::ptr::null_mut();
        }
    };
    let plaintext = match std::str::from_utf8(&plaintext) {
        Ok(v) => v,
        Err(_) => {
            throw(&mut env, "明文必须是 UTF-8");
            return std::ptr::null_mut();
        }
    };
    match session.encrypt_json(&iv, plaintext) {
        Some(json) => array(&mut env, json.into_bytes()),
        None => {
            throw(&mut env, "加密失败：会话尚未建立发送链");
            std::ptr::null_mut()
        }
    }
}

#[no_mangle]
pub extern "system" fn Java_com_junesoon_mim_core_MimCoreNative_decrypt<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    handle: jlong,
    wire_json: JByteArray<'local>,
) -> jbyteArray {
    let session = match unsafe { session(handle) } {
        Some(s) => s,
        None => {
            throw(&mut env, "会话句柄无效");
            return std::ptr::null_mut();
        }
    };
    let json = match bytes(&mut env, &wire_json) {
        Some(v) => v,
        None => {
            throw(&mut env, "无法读取消息");
            return std::ptr::null_mut();
        }
    };
    let message: WireMessage = match serde_json::from_slice(&json) {
        Ok(v) => v,
        Err(_) => {
            throw(&mut env, "消息 JSON 不合法");
            return std::ptr::null_mut();
        }
    };
    match session.state_mut().decrypt_wire(&message) {
        Some(plaintext) => array(&mut env, plaintext.into_bytes()),
        None => {
            throw(&mut env, "解密失败：密文被篡改或密钥不匹配");
            std::ptr::null_mut()
        }
    }
}

#[no_mangle]
pub extern "system" fn Java_com_junesoon_mim_core_MimCoreNative_save<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    handle: jlong,
) -> jbyteArray {
    let session = match unsafe { session(handle) } {
        Some(s) => s,
        None => {
            throw(&mut env, "会话句柄无效");
            return std::ptr::null_mut();
        }
    };
    match serde_json::to_vec(&session.state().snapshot()) {
        Ok(json) => array(&mut env, json),
        Err(_) => {
            throw(&mut env, "状态序列化失败");
            std::ptr::null_mut()
        }
    }
}

#[no_mangle]
pub extern "system" fn Java_com_junesoon_mim_core_MimCoreNative_load<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    state_json: JByteArray<'local>,
) -> jlong {
    let json = match bytes(&mut env, &state_json) {
        Some(v) => v,
        None => {
            throw(&mut env, "无法读取状态");
            return 0;
        }
    };
    let snapshot: StateSnapshot = match serde_json::from_slice(&json) {
        Ok(v) => v,
        Err(_) => {
            throw(&mut env, "状态 JSON 不合法");
            return 0;
        }
    };
    match RatchetState::from_snapshot(&snapshot) {
        Some(state) => Box::into_raw(Box::new(MimSession::from_state(state))) as jlong,
        None => {
            throw(&mut env, "状态损坏");
            0
        }
    }
}

#[no_mangle]
pub extern "system" fn Java_com_junesoon_mim_core_MimCoreNative_free(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
) {
    if handle == 0 {
        return;
    }
    unsafe {
        drop(Box::from_raw(handle as *mut MimSession));
    }
}

#[no_mangle]
pub extern "system" fn Java_com_junesoon_mim_core_MimCoreNative_encryptState<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    plaintext: JByteArray<'local>,
    key: JByteArray<'local>,
    nonce: JByteArray<'local>,
) -> jbyteArray {
    let value = bytes(&mut env, &plaintext);
    let key = bytes(&mut env, &key);
    let nonce = bytes(&mut env, &nonce);
    match (value, key, nonce) {
        (Some(value), Some(key), Some(nonce)) => match encrypt_state(&value, &key, &nonce) {
            Some(blob) => array(&mut env, blob.into_bytes()),
            None => {
                throw(&mut env, "加密状态失败：密钥必须 32 字节、nonce 必须 12 字节");
                std::ptr::null_mut()
            }
        },
        _ => {
            throw(&mut env, "无法读取参数");
            std::ptr::null_mut()
        }
    }
}

#[no_mangle]
pub extern "system" fn Java_com_junesoon_mim_core_MimCoreNative_decryptState<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    blob: JByteArray<'local>,
    key: JByteArray<'local>,
) -> jbyteArray {
    let value = bytes(&mut env, &blob);
    let key = bytes(&mut env, &key);
    match (value, key) {
        (Some(value), Some(key)) => {
            let text = match std::str::from_utf8(&value) {
                Ok(v) => v,
                Err(_) => {
                    throw(&mut env, "状态不是 UTF-8");
                    return std::ptr::null_mut();
                }
            };
            match decrypt_state(text, &key) {
                Some(plaintext) => array(&mut env, plaintext),
                None => {
                    throw(&mut env, "解密状态失败：格式损坏、密钥不匹配或密文被篡改");
                    std::ptr::null_mut()
                }
            }
        }
        _ => {
            throw(&mut env, "无法读取参数");
            std::ptr::null_mut()
        }
    }
}

#[no_mangle]
pub extern "system" fn Java_com_junesoon_mim_core_MimCoreNative_randomBytes<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    length: jint,
) -> jbyteArray {
    if length <= 0 {
        throw(&mut env, "长度必须为正");
        return std::ptr::null_mut();
    }
    let mut buffer = vec![0u8; length as usize];
    fill_random(&mut buffer);
    array(&mut env, buffer)
}

#[no_mangle]
pub extern "system" fn Java_com_junesoon_mim_core_MimCoreNative_generateKeyBundle<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    version: jint,
    one_time_count: jint,
) -> jbyteArray {
    if version < 0 || one_time_count < 0 {
        throw(&mut env, "版本与数量必须非负");
        return std::ptr::null_mut();
    }
    match crate::keys::KeyBundle::generate(version as u32, one_time_count as u32) {
        Some(bundle) => match bundle.to_json() {
            Some(json) => array(&mut env, json.into_bytes()),
            None => {
                throw(&mut env, "密钥包序列化失败");
                std::ptr::null_mut()
            }
        },
        None => {
            throw(&mut env, "生成密钥包失败");
            std::ptr::null_mut()
        }
    }
}

#[no_mangle]
pub extern "system" fn Java_com_junesoon_mim_core_MimCoreNative_verifySignedPreKey<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    identity_public: JByteArray<'local>,
    pre_key_id: jint,
    signed_pre_key_public: JByteArray<'local>,
    signature: JByteArray<'local>,
) -> jboolean {
    let identity = bytes(&mut env, &identity_public);
    let spk = bytes(&mut env, &signed_pre_key_public);
    let signature = bytes(&mut env, &signature)
        .and_then(|v| String::from_utf8(v).ok());
    match (identity, spk, signature) {
        (Some(identity), Some(spk), Some(signature)) => {
            if crate::keys::verify_signed_pre_key(
                &identity,
                pre_key_id as u32,
                &spk,
                &signature,
            ) {
                1
            } else {
                0
            }
        }
        _ => {
            throw(&mut env, "无法读取参数");
            0
        }
    }
}

#[no_mangle]
pub extern "system" fn Java_com_junesoon_mim_core_MimCoreNative_signWithIdentity<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    identity_private: JByteArray<'local>,
    payload: JByteArray<'local>,
) -> jbyteArray {
    let private = bytes(&mut env, &identity_private)
        .and_then(|value| String::from_utf8(value).ok());
    let payload = bytes(&mut env, &payload);
    match (private, payload) {
        (Some(private), Some(payload)) => match crate::keys::sign_payload(&private, &payload) {
            Some(signature) => array(&mut env, signature.into_bytes()),
            None => {
                throw(&mut env, "签名失败");
                std::ptr::null_mut()
            }
        },
        _ => {
            throw(&mut env, "无法读取参数");
            std::ptr::null_mut()
        }
    }
}

#[no_mangle]
pub extern "system" fn Java_com_junesoon_mim_core_MimCoreNative_verifyWithIdentity<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    identity_public: JByteArray<'local>,
    payload: JByteArray<'local>,
    signature: JByteArray<'local>,
) -> jboolean {
    let public = bytes(&mut env, &identity_public);
    let payload = bytes(&mut env, &payload);
    let signature = bytes(&mut env, &signature)
        .and_then(|value| String::from_utf8(value).ok());
    match (public, payload, signature) {
        (Some(public), Some(payload), Some(signature)) => {
            if crate::keys::verify_payload(&public, &payload, &signature) {
                1
            } else {
                0
            }
        }
        _ => {
            throw(&mut env, "无法读取参数");
            0
        }
    }
}

#[no_mangle]
pub extern "system" fn Java_com_junesoon_mim_core_MimCoreNative_composeFirstMessage<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    request: JByteArray<'local>,
) -> jbyteArray {
    let json = match bytes(&mut env, &request) {
        Some(v) => v,
        None => {
            throw(&mut env, "无法读取请求");
            return std::ptr::null_mut();
        }
    };
    let parsed: crate::messaging::ComposeRequest = match serde_json::from_slice(&json) {
        Ok(v) => v,
        Err(_) => {
            throw(&mut env, "请求 JSON 不合法");
            return std::ptr::null_mut();
        }
    };
    match crate::messaging::compose_first_message(&parsed) {
        Some(response) => match serde_json::to_vec(&response) {
            Ok(json) => array(&mut env, json),
            Err(_) => {
                throw(&mut env, "响应序列化失败");
                std::ptr::null_mut()
            }
        },
        None => {
            throw(&mut env, "组装首条消息失败：密钥材料不合法");
            std::ptr::null_mut()
        }
    }
}

#[no_mangle]
pub extern "system" fn Java_com_junesoon_mim_core_MimCoreNative_openFirstMessage<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    request: JByteArray<'local>,
) -> jbyteArray {
    let json = match bytes(&mut env, &request) {
        Some(v) => v,
        None => {
            throw(&mut env, "无法读取请求");
            return std::ptr::null_mut();
        }
    };
    let parsed: crate::messaging::OpenFirstRequest = match serde_json::from_slice(&json) {
        Ok(v) => v,
        Err(_) => {
            throw(&mut env, "请求 JSON 不合法");
            return std::ptr::null_mut();
        }
    };
    match crate::messaging::open_first_message(&parsed) {
        Some(response) => match serde_json::to_vec(&response) {
            Ok(json) => array(&mut env, json),
            Err(_) => {
                throw(&mut env, "响应序列化失败");
                std::ptr::null_mut()
            }
        },
        None => {
            throw(&mut env, "解密首条消息失败：密文或密钥不匹配");
            std::ptr::null_mut()
        }
    }
}

#[no_mangle]
pub extern "system" fn Java_com_junesoon_mim_core_MimCoreNative_sessionStoreInsert<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    request: JByteArray<'local>,
) -> jbyteArray {
    json_call(&mut env, &request, |value| {
        Some(crate::session_store::insert_session(value))
    })
}

#[no_mangle]
pub extern "system" fn Java_com_junesoon_mim_core_MimCoreNative_composeSealed<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    request: JByteArray<'local>,
) -> jbyteArray {
    json_call(&mut env, &request, crate::session_store::compose_sealed_message)
}

#[no_mangle]
pub extern "system" fn Java_com_junesoon_mim_core_MimCoreNative_openSealed<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    request: JByteArray<'local>,
) -> jbyteArray {
    json_call(&mut env, &request, crate::session_store::open_sealed_message)
}

/// JSON 进、JSON 出的小工具：统一错误处理，避免每个方法重复。
fn json_call<Req, Res>(
    env: &mut JNIEnv,
    request: &JByteArray,
    call: impl FnOnce(Req) -> Option<Res>,
) -> jbyteArray
where
    Req: serde::de::DeserializeOwned,
    Res: serde::Serialize,
{
    let json = match bytes(env, request) {
        Some(v) => v,
        None => {
            throw(env, "无法读取请求");
            return std::ptr::null_mut();
        }
    };
    let parsed: Req = match serde_json::from_slice(&json) {
        Ok(v) => v,
        Err(_) => {
            throw(env, "请求 JSON 不合法");
            return std::ptr::null_mut();
        }
    };
    match call(parsed) {
        Some(response) => match serde_json::to_vec(&response) {
            Ok(bytes) => array(env, bytes),
            Err(_) => {
                throw(env, "响应序列化失败");
                std::ptr::null_mut()
            }
        },
        None => {
            throw(env, "操作失败：会话不存在、缺少标签或密文不匹配");
            std::ptr::null_mut()
        }
    }
}
