//! Rust ⇄ Java 真实互通联调工具。
//!
//! 两个子命令由 `tools/interop.sh` 串起来：
//! - `init`：Rust 作为发起方完成 X3DH，加密首条消息，导出握手参数与状态；
//! - `finish`：读取 Java 的回信，用同一会话解密并断言正文。
//!
//! 这不是测试替身：Java 侧用的是 `MIMCore` 的 `RatchetService`，
//! 两边的协议实现各自独立，只共享线上格式。

use mim_core_rust::ratchet::{RatchetState, StateSnapshot, WireMessage};
use mim_core_rust::session::initiator_session;
use std::fs;
use std::path::Path;

// 固定的测试密钥（与 MIMCoreRust/tests/fixtures/ratchet-vectors.json 同源）
const A_IDENTITY_PRIV: &str = "308187020100301306072a8648ce3d020106082a8648ce3d030107046d306b0201010420ed1aaac79c7a0a07e89016ca9fd088faecb674bfdb364c61772b304dc47656b0a14403420004bd83be1776a292497bcc717e9d2a57c7972047276c92d61aacc5396c08585444383fc266331d647d8c234700ef125f03b03c546d8f7cae09e899ec2cc0bf36a9";
const A_IDENTITY_PUB: &str = "3059301306072a8648ce3d020106082a8648ce3d03010703420004bd83be1776a292497bcc717e9d2a57c7972047276c92d61aacc5396c08585444383fc266331d647d8c234700ef125f03b03c546d8f7cae09e899ec2cc0bf36a9";
const A_EPHEMERAL_PRIV: &str = "308187020100301306072a8648ce3d020106082a8648ce3d030107046d306b0201010420db9918aa00a325c358939f01c7657587656f46c8bf97f05d84a396ca933f3df0a144034200044f0d04460e020585e77a12355bc68035cca8dc78e53d2cd96544e6d775372ac9cd98f6896818f8b385c82970129e14b517a669a73a01000b0842ba12d9aa95e7";
const A_EPHEMERAL_PUB: &str = "3059301306072a8648ce3d020106082a8648ce3d030107034200044f0d04460e020585e77a12355bc68035cca8dc78e53d2cd96544e6d775372ac9cd98f6896818f8b385c82970129e14b517a669a73a01000b0842ba12d9aa95e7";
const B_IDENTITY_PUB: &str = "3059301306072a8648ce3d020106082a8648ce3d03010703420004f4fe50329464acf44684d4f2fda4152e99cbc9482621b54b4beb2360285a16cfee3a4589299efd66b6c9f40fe43d16b2e0c9d781f76b44aa6d6d0a453df25413";
const B_SIGNED_PRE_KEY_PUB: &str = "3059301306072a8648ce3d020106082a8648ce3d03010703420004eb2835ee70b0c7188a75cfe531a87c10d6c186b4a4a361aac3ad2072cd1807d1403f3d97c3fa8f0259363864b3c70c382cc840e5a6040f762b5ac5ec75e28013";
const B_ONE_TIME_PRE_KEY_PUB: &str = "3059301306072a8648ce3d020106082a8648ce3d030107034200047f6741ab9b140f1e035e38553708afa1f57872118495c5fb7f0e4a339dadc443dc51596720c6a59f4a8e8c82c80ea19569a32cddbf66ed6a3c700c38aecd5836";

const FIRST_MESSAGE: &str = "hello from Rust";
const EXPECTED_REPLY: &str = "hello from Java";

fn main() {
    let args: Vec<String> = std::env::args().collect();
    if args.len() < 3 {
        eprintln!("用法: interop <init|finish> <dir>");
        std::process::exit(2);
    }
    let dir = Path::new(&args[2]);
    match args[1].as_str() {
        "init" => init(dir),
        "finish" => finish(dir),
        other => {
            eprintln!("未知子命令: {}", other);
            std::process::exit(2);
        }
    }
}

fn init(dir: &Path) {
    fs::create_dir_all(dir).expect("创建目录");
    let result = initiator_session(
        &hex::decode(A_IDENTITY_PRIV).unwrap(),
        &hex::decode(A_IDENTITY_PUB).unwrap(),
        &hex::decode(B_IDENTITY_PUB).unwrap(),
        &hex::decode(B_SIGNED_PRE_KEY_PUB).unwrap(),
        Some(&hex::decode(B_ONE_TIME_PRE_KEY_PUB).unwrap()),
        &hex::decode(A_EPHEMERAL_PRIV).unwrap(),
        &hex::decode(A_EPHEMERAL_PUB).unwrap(),
    )
    .expect("发起方握手");

    let mut state = result.state;
    let iv = [0x20u8; 12];
    let message = state.encrypt_wire(&iv, FIRST_MESSAGE).expect("加密首条消息");

    let base64 = |v: &[u8]| {
        use base64::Engine;
        base64::engine::general_purpose::STANDARD.encode(v)
    };

    let mut out = String::new();
    out.push_str(&format!("sender_identity={}\n", base64(&result.sender_identity_key)));
    out.push_str(&format!("sender_ephemeral={}\n", base64(&result.sender_ephemeral_key)));
    out.push_str(&format!("used_opk={}\n", result.used_one_time_pre_key as u8));
    out.push_str(&write_wire(&message));
    fs::write(dir.join("a_init.txt"), out).expect("写 a_init.txt");

    let snapshot = serde_json::to_string_pretty(&state.snapshot()).expect("序列化状态");
    fs::write(dir.join("a_state.json"), snapshot).expect("写 a_state.json");
    println!("init: 已写 {} 与 a_state.json", dir.join("a_init.txt").display());
}

fn finish(dir: &Path) {
    let snapshot_text = fs::read_to_string(dir.join("a_state.json")).expect("读 a_state.json");
    let snapshot: StateSnapshot = serde_json::from_str(&snapshot_text).expect("解析状态");
    let mut state = RatchetState::from_snapshot(&snapshot).expect("恢复状态");

    let reply_text = fs::read_to_string(dir.join("java_reply.txt")).expect("读 java_reply.txt");
    let fields = parse_kv(&reply_text);
    let message = read_wire(&fields);
    let plaintext = state.decrypt_wire(&message).expect("解密 Java 回信");
    println!("finish: 解密 Java 回信 -> {}", plaintext);
    assert_eq!(plaintext, EXPECTED_REPLY, "回信正文不一致");
    println!("INTEROP_RUST_OK");
}

fn write_wire(message: &WireMessage) -> String {
    format!(
        "dh={}\npn={}\nn={}\niv={}\nciphertext={}\n",
        message.dh, message.pn, message.n, message.iv, message.ciphertext
    )
}

fn read_wire(fields: &std::collections::HashMap<String, String>) -> WireMessage {
    WireMessage {
        dh: fields["dh"].clone(),
        pn: fields["pn"].parse().expect("pn"),
        n: fields["n"].parse().expect("n"),
        iv: fields["iv"].clone(),
        ciphertext: fields["ciphertext"].clone(),
    }
}

fn parse_kv(text: &str) -> std::collections::HashMap<String, String> {
    text.lines()
        .filter_map(|line| line.split_once('='))
        .map(|(k, v)| (k.to_string(), v.trim().to_string()))
        .collect()
}
