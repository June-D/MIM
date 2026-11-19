//! 信封层：标签、同步副本、发送者证书（spike）。
//!
//! 与 `MIMCore` 的同名类逐字节对齐。三条格式都以 `U+0001` 开头的魔数区分，
//! 结构不合法时返回 `None`，调用方必须把原串当正文，绝不能猜。

use crate::sender_cert::SenderCertificate;

pub const TAG_MAGIC: &str = "\u{1}mimtag1\u{1}";
pub const SYNC_MAGIC: &str = "\u{1}mimsync1\u{1}";
pub const CERT_MAGIC: &str = "\u{1}mimcert1\u{1}";

const BODY_SEP: char = '\n';
const FIELD_SEP: char = '\u{1}';
const MAX_ID_LENGTH: usize = 64;
const MAX_TAG_LENGTH: usize = 64;

/// 标签信封拆包结果。
#[derive(Debug, PartialEq, Eq)]
pub struct TagEnvelope<'a> {
    pub tag: &'a str,
    pub text: &'a str,
}

pub fn tag_envelope_pack(tag: &str, payload: &str) -> String {
    if tag.is_empty() {
        return payload.to_string();
    }
    format!("{}{}{}{}", TAG_MAGIC, tag, BODY_SEP, payload)
}

pub fn tag_envelope_unpack(text: &str) -> Option<TagEnvelope<'_>> {
    let rest = text.strip_prefix(TAG_MAGIC)?;
    let sep = rest.find(BODY_SEP)?;
    let (tag, body) = rest.split_at(sep);
    if tag.is_empty() || tag.len() > MAX_TAG_LENGTH {
        return None;
    }
    Some(TagEnvelope {
        tag,
        text: &body[1..],
    })
}

/// 同步副本信封拆包结果（撤回凭证 + 正文）。
#[derive(Debug, PartialEq, Eq)]
pub struct SyncEnvelope<'a> {
    pub recall_token: &'a str,
    pub text: &'a str,
}

pub fn sync_envelope_pack(recall_token: &str, payload: &str) -> String {
    if recall_token.is_empty() {
        return payload.to_string();
    }
    format!("{}{}{}{}", SYNC_MAGIC, recall_token, BODY_SEP, payload)
}

pub fn sync_envelope_unpack(payload: &str) -> Option<SyncEnvelope<'_>> {
    let rest = payload.strip_prefix(SYNC_MAGIC)?;
    let sep = rest.find(BODY_SEP)?;
    let (token, body) = rest.split_at(sep);
    if !is_valid_recall_token(token) {
        return None;
    }
    Some(SyncEnvelope {
        recall_token: token,
        text: &body[1..],
    })
}

/// 与服务端 `RecallToken` 约束一致：16–128 个 URL 安全 Base64 字符。
fn is_valid_recall_token(token: &str) -> bool {
    let len = token.len();
    if !(16..=128).contains(&len) {
        return false;
    }
    token.bytes().all(|b| {
        b.is_ascii_alphanumeric() || b == b'+' || b == b'/' || b == b'=' || b == b'_' || b == b'-'
    })
}

/// 证书信封拆包结果。
#[derive(Debug, PartialEq, Eq)]
pub struct SenderCertEnvelope {
    pub certificate: SenderCertificate,
    pub conversation_tag: Option<String>,
    pub payload: String,
}

pub fn sender_cert_envelope_pack(
    certificate: &SenderCertificate,
    conversation_tag: Option<&str>,
    payload: &str,
) -> String {
    let mut out = String::with_capacity(payload.len() + 512);
    out.push_str(CERT_MAGIC);
    out.push_str(&certificate.username);
    out.push(FIELD_SEP);
    out.push_str(&certificate.device_id);
    out.push(FIELD_SEP);
    out.push_str(&certificate.identity_key);
    out.push(FIELD_SEP);
    out.push_str(&certificate.issued_at.to_string());
    out.push(FIELD_SEP);
    out.push_str(&certificate.expires_at.to_string());
    out.push(FIELD_SEP);
    out.push_str(&certificate.key_id);
    out.push(FIELD_SEP);
    out.push_str(&certificate.signature);
    if let Some(tag) = conversation_tag.filter(|t| !t.is_empty()) {
        out.push(FIELD_SEP);
        out.push_str(tag);
    }
    out.push(BODY_SEP);
    out.push_str(payload);
    out
}

pub fn sender_cert_envelope_unpack(text: &str) -> Option<SenderCertEnvelope> {
    let rest = text.strip_prefix(CERT_MAGIC)?;
    let body_start = rest.find(BODY_SEP)?;
    let header = &rest[..body_start];
    let payload = rest[body_start + 1..].to_string();

    let fields: Vec<&str> = header.split(FIELD_SEP).collect();
    if fields.len() != 7 && fields.len() != 8 {
        return None;
    }
    if fields[0].is_empty()
        || fields[0].len() > MAX_ID_LENGTH
        || fields[1].is_empty()
        || fields[1].len() > MAX_ID_LENGTH
        || fields[2].is_empty()
        || fields[6].is_empty()
    {
        return None;
    }
    let issued_at: i64 = fields[3].parse().ok()?;
    let expires_at: i64 = fields[4].parse().ok()?;
    if expires_at <= issued_at {
        return None;
    }

    let conversation_tag = if fields.len() == 8 && !fields[7].is_empty() {
        Some(fields[7].to_string())
    } else {
        None
    };

    Some(SenderCertEnvelope {
        certificate: SenderCertificate {
            username: fields[0].to_string(),
            device_id: fields[1].to_string(),
            identity_key: fields[2].to_string(),
            issued_at,
            expires_at,
            key_id: fields[5].to_string(),
            signature: fields[6].to_string(),
        },
        conversation_tag,
        payload,
    })
}
