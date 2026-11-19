//! 会话存储与 sealed 消息（spike）。
//!
//! 按 `sessionId` 索引双棘轮状态，并记录从标签信封学到的会话标签。
//! 状态含私钥与链密钥，落盘前必须经 [`crate::secure_store`] 加密。
//!
//! 首条消息由 [`crate::messaging::compose_first_message`] /
//! [`crate::messaging::open_first_message`] 处理；本模块负责之后的
//! 「已确认会话」：发送时包标签信封，接收时拆标签信封并学习。

use crate::envelope::{tag_envelope_pack, tag_envelope_unpack};
use crate::ratchet::{RatchetState, StateSnapshot, WireMessage};
use crate::secure_store;
use serde::{Deserialize, Serialize};

#[derive(Serialize, Deserialize, Clone, Debug, PartialEq, Eq)]
pub struct StoredSession {
    pub session_id: String,
    /// 对端用户名。
    pub peer: String,
    /// 对端设备标识。
    pub device_id: String,
    pub state: StateSnapshot,
    /// 从标签信封学到的会话标签（去重，最近学习的在前）。
    pub tags: Vec<String>,
    /// 是否已确认（双方都发出过带同一 sessionId 的消息）。
    pub confirmed: bool,
}

#[derive(Serialize, Deserialize, Clone, Debug, Default, PartialEq, Eq)]
pub struct SessionStore {
    pub sessions: Vec<StoredSession>,
}

impl SessionStore {
    /// 插入或替换一个会话。会话 ID 相同即视为同一条会话。
    pub fn insert(&mut self, session_id: &str, peer: &str, device_id: &str, state: StateSnapshot) {
        let existing = self
            .sessions
            .iter()
            .position(|session| session.session_id == session_id);
        match existing {
            Some(index) => {
                let tags = self.sessions[index].tags.clone();
                self.sessions[index] = StoredSession {
                    session_id: session_id.to_string(),
                    peer: peer.to_string(),
                    device_id: device_id.to_string(),
                    state,
                    tags,
                    confirmed: true,
                };
            }
            None => self.sessions.push(StoredSession {
                session_id: session_id.to_string(),
                peer: peer.to_string(),
                device_id: device_id.to_string(),
                state,
                tags: Vec::new(),
                confirmed: true,
            }),
        }
    }

    pub fn get(&self, session_id: &str) -> Option<&StoredSession> {
        self.sessions
            .iter()
            .find(|session| session.session_id == session_id)
    }

    pub fn get_mut(&mut self, session_id: &str) -> Option<&mut StoredSession> {
        self.sessions
            .iter_mut()
            .find(|session| session.session_id == session_id)
    }

    pub fn remove(&mut self, session_id: &str) -> Option<StoredSession> {
        let index = self
            .sessions
            .iter()
            .position(|session| session.session_id == session_id)?;
        Some(self.sessions.remove(index))
    }

    /// 学到一个标签：去重并放到最前。
    pub fn learn_tag(&mut self, session_id: &str, tag: &str) -> bool {
        let Some(session) = self.get_mut(session_id) else {
            return false;
        };
        if session.tags.iter().any(|known| known == tag) {
            return false;
        }
        session.tags.insert(0, tag.to_string());
        true
    }

    /// 组装一条 sealed 消息：包标签信封 + 双棘轮加密。
    ///
    /// 没有已知标签时返回 `None`：协议要求已确认会话的消息必须带标签。
    pub fn compose_sealed(
        &mut self,
        session_id: &str,
        plaintext: &str,
        iv: &[u8],
    ) -> Option<WireMessage> {
        let session = self.get_mut(session_id)?;
        let tag = session.tags.first()?.clone();
        let payload = tag_envelope_pack(&tag, plaintext);
        let mut state = RatchetState::from_snapshot(&session.state)?;
        let wire = state.encrypt_wire(iv, &payload)?;
        session.state = state.snapshot();
        Some(wire)
    }

    /// 拆一条 sealed 消息：解密 + 拆标签信封 + 学习标签。
    ///
    /// 解密失败时不写回状态（棘轮不能因为一条坏消息而失步）。
    pub fn open_sealed(&mut self, session_id: &str, wire: &WireMessage) -> Option<String> {
        let session = self.get_mut(session_id)?;
        let mut state = RatchetState::from_snapshot(&session.state)?;
        let payload = state.decrypt_wire(wire)?;
        let (plaintext, learned) = match tag_envelope_unpack(&payload) {
            Some(envelope) => (envelope.text.to_string(), Some(envelope.tag.to_string())),
            None => (payload, None),
        };
        session.state = state.snapshot();
        session.confirmed = true;
        if let Some(tag) = learned {
            if !session.tags.iter().any(|known| known == &tag) {
                session.tags.insert(0, tag);
            }
        }
        Some(plaintext)
    }

    pub fn to_json(&self) -> Option<String> {
        serde_json::to_string(self).ok()
    }

    pub fn from_json(json: &str) -> Option<Self> {
        serde_json::from_str(json).ok()
    }

    /// 加密落盘：整个存储加密成一段文本。
    pub fn save_encrypted(&self, key: &[u8], nonce: &[u8]) -> Option<String> {
        let json = self.to_json()?;
        secure_store::encrypt_state(json.as_bytes(), key, nonce)
    }

    pub fn load_encrypted(blob: &str, key: &[u8]) -> Option<Self> {
        let plaintext = secure_store::decrypt_state(blob, key)?;
        let json = std::str::from_utf8(&plaintext).ok()?;
        Self::from_json(json)
    }
}

// ==================== JSON 边界（FFI / JNI） ====================

#[derive(Deserialize, Clone, Debug)]
pub struct InsertRequest {
    pub store: SessionStore,
    pub session_id: String,
    pub peer: String,
    pub device_id: String,
    pub state: StateSnapshot,
    /// 已知的会话标签（首条消息学到的）。
    #[serde(default)]
    pub tags: Vec<String>,
}

#[derive(Deserialize, Clone, Debug)]
pub struct ComposeSealedRequest {
    pub store: SessionStore,
    pub session_id: String,
    pub plaintext: String,
    /// 12 字节 IV 的十六进制；缺省则随机。
    pub iv: Option<String>,
}

#[derive(Deserialize, Clone, Debug)]
pub struct OpenSealedRequest {
    pub store: SessionStore,
    pub session_id: String,
    pub wire: WireMessage,
}

#[derive(Serialize, Clone, Debug)]
pub struct StoreResponse {
    pub store: SessionStore,
}

#[derive(Serialize, Clone, Debug)]
pub struct ComposeSealedResponse {
    pub store: SessionStore,
    pub wire: WireMessage,
}

#[derive(Serialize, Clone, Debug)]
pub struct OpenSealedResponse {
    pub store: SessionStore,
    pub plaintext: String,
}

/// 把首条消息解出的会话写入存储。
pub fn insert_session(request: InsertRequest) -> StoreResponse {
    let mut store = request.store;
    store.insert(
        &request.session_id,
        &request.peer,
        &request.device_id,
        request.state,
    );
    for tag in &request.tags {
        store.learn_tag(&request.session_id, tag);
    }
    StoreResponse { store }
}

/// 组装 sealed 消息，并返回推进后的存储。
pub fn compose_sealed_message(request: ComposeSealedRequest) -> Option<ComposeSealedResponse> {
    let mut store = request.store;
    let iv = match request.iv.as_deref() {
        Some(value) => hex::decode(value).ok()?,
        None => {
            let mut iv = [0u8; 12];
            crate::secure_store::fill_random(&mut iv);
            iv.to_vec()
        }
    };
    let wire = store.compose_sealed(&request.session_id, &request.plaintext, &iv)?;
    Some(ComposeSealedResponse { store, wire })
}

/// 拆 sealed 消息，并返回推进后的存储。
pub fn open_sealed_message(request: OpenSealedRequest) -> Option<OpenSealedResponse> {
    let mut store = request.store;
    let plaintext = store.open_sealed(&request.session_id, &request.wire)?;
    Some(OpenSealedResponse { store, plaintext })
}
