package com.junesoon.mim.app.state

import com.junesoon.mim.app.data.CredentialStore
import com.junesoon.mim.app.data.DemoBackend
import com.junesoon.mim.app.data.LoginResult
import com.junesoon.mim.app.data.MessagingBackend
import com.junesoon.mim.app.data.TwoFactorRequiredException
import com.junesoon.mim.app.domain.ChatMessage
import com.junesoon.mim.app.domain.ClearPoint
import com.junesoon.mim.app.domain.Contact
import com.junesoon.mim.app.domain.PeerIdentityKey
import com.junesoon.mim.app.domain.sortedForList
import java.nio.file.Files
import java.util.Base64
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 用 Unconfined 调度器让演示后端的调用同步完成，
 * 从而不必引入协程测试库就能断言状态。
 */
class AppStoreTest {

    private fun store() = AppStore(
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
        credentials = CredentialStore(Files.createTempDirectory("mim-remember")),
    )

    @Test
    fun login_moves_to_contacts() {
        val store = store()
        store.login("alice", "pass1234", "desktop")

        assertEquals(Screen.Contacts, store.state.value.screen)
        assertEquals("alice", store.state.value.selfName)
        assertEquals("desktop", store.state.value.deviceName)
        assertTrue(store.state.value.contacts.isNotEmpty())
    }

    @Test
    fun missing_password_keeps_login_screen_with_error() {
        val store = store()
        store.login("alice", "", "desktop")

        assertEquals(Screen.Login, store.state.value.screen)
        assertEquals("请输入密码", store.state.value.error)
    }

    @Test
    fun logout_returns_to_login_and_drops_session_state() {
        val store = store()
        store.login("alice", "pass1234", "desktop")
        store.openChat("bob")
        store.logout()

        assertEquals(Screen.Login, store.state.value.screen)
        assertEquals("", store.state.value.selfName)
        assertTrue(store.state.value.contacts.isEmpty())
        assertTrue(store.state.value.messages.isEmpty())
    }

    /**
     * 勾选记住后退出登录：表单仍预填账号密码，但不再自动登录。
     *
     * 这里用假存储避免依赖 Rust 动态库；只验证状态与调用意图。
     */
    @Test
    fun logout_keeps_remembered_credentials_for_prefill() {
        val dir = Files.createTempDirectory("mim-remember")
        val store = AppStore(
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
            credentials = CredentialStore(dir),
        )
        store.login("alice", "pass1234", "desktop", remember = true)
        store.logout()

        assertEquals("alice", store.state.value.rememberedUsername)
        assertEquals("pass1234", store.state.value.rememberedPassword)
        assertTrue(store.state.value.rememberMe)
        assertEquals(Screen.Login, store.state.value.screen)
    }

    @Test
    fun blocking_contact_marks_it_and_hides_composer_state() {
        val store = store()
        store.login("alice", "pass1234", "desktop")
        store.toggleBlock("bob")

        val bob = store.state.value.contacts.first { it.id == "bob" }
        assertTrue(bob.blocked, "屏蔽后联系人应带标记")
        assertTrue(bob.preview.contains("已屏蔽"))
        assertEquals(0, bob.unread)

        store.toggleBlock("bob")
        assertTrue(!store.state.value.contacts.first { it.id == "bob" }.blocked, "应能解除屏蔽")
    }

    /** 验证码输错后，登录页必须继续显示验证码输入框。 */
    @Test
    fun two_factor_field_stays_after_wrong_code() {
        var attempts = 0
        val backend = object : MessagingBackend by DemoBackend() {
            override suspend fun login(
                username: String,
                password: String,
                deviceName: String,
                code: String?,
            ): LoginResult {
                attempts++
                if (attempts == 1) {
                    throw TwoFactorRequiredException("请输入二次验证码或恢复码")
                }
                throw IllegalStateException("二次验证码错误或已被使用")
            }
        }
        val store = AppStore(
            backend = backend,
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
            credentials = CredentialStore(Files.createTempDirectory("mim-remember")),
        )

        store.login("alice", "pass1234", "desktop")
        assertTrue(store.state.value.needsTwoFactor, "首次登录应提示需要验证码")

        store.login("alice", "pass1234", "desktop", code = "000000")
        assertTrue(store.state.value.needsTwoFactor, "验证码错误后输入框不能消失")
        assertEquals("二次验证码错误或已被使用", store.state.value.error)
        assertEquals(Screen.Login, store.state.value.screen)
    }

    @Test
    fun search_finds_local_messages_and_opens_chat_at_hit() {
        val store = store()
        store.login("alice", "pass1234", "desktop")
        store.openChat("bob")
        store.send("周末去看展")

        store.openSearch()
        store.searchMessages("看展")
        assertEquals(Screen.Search, store.state.value.screen)
        val hits = store.state.value.messageHits
        assertTrue(hits.any { it.contactId == "bob" && it.text.contains("看展") }, "应命中本地消息：$hits")

        val hit = hits.first()
        store.openSearchHit(hit.contactId, hit.messageId)
        assertEquals(Screen.Chat(hit.contactId), store.state.value.screen)
        assertEquals(hit.messageId, store.state.value.pendingScrollMessageId)
        store.consumeScrollTarget()
        assertEquals(null, store.state.value.pendingScrollMessageId)
    }

    @Test
    fun sending_message_appends_to_history() {
        val store = store()
        store.login("alice", "pass1234", "desktop")
        store.openChat("bob")
        store.send("测试一条")

        val history = store.state.value.messages["bob"].orEmpty()
        assertTrue(history.any { it.fromMe && it.text == "测试一条" })
        assertEquals(0, store.state.value.contacts.first { it.id == "bob" }.unread)
    }

    @Test
    fun sending_file_goes_through_media_backend() {
        val stored = mutableListOf<ChatMessage>()
        val calls = mutableListOf<String>()
        val backend = object : MessagingBackend by DemoBackend() {
            override suspend fun login(
                username: String,
                password: String,
                deviceName: String,
                code: String?,
            ): LoginResult = LoginResult("t", username, listOf(Contact("bob", "bob", "")))

            override suspend fun sendFile(
                token: String,
                contactId: String,
                bytes: ByteArray,
                mime: String,
                name: String,
            ): ChatMessage {
                calls += "$name|${bytes.size}|$mime"
                val message = ChatMessage("f1", true, name, "10:00", epochMs = 1_000, kind = "file")
                stored += message
                return message
            }

            override suspend fun history(token: String, contactId: String): List<ChatMessage> =
                stored.toList()
        }
        val store = AppStore(
            backend = backend,
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
            credentials = CredentialStore(Files.createTempDirectory("mim-remember")),
        )
        store.login("alice", "pass1234", "desktop")
        store.openChat("bob")
        store.sendFile(ByteArray(2048) { 7 }, "季度预算.xlsx", "application/vnd.ms-excel")

        assertEquals(listOf("季度预算.xlsx|2048|application/vnd.ms-excel"), calls)
        assertTrue(store.state.value.messages["bob"].orEmpty().any { it.kind == "file" })
    }

    /** 屏蔽期间不允许发文件，且不应调用到后端。 */
    @Test
    fun blocked_contact_cannot_send_file() {
        var called = false
        val backend = object : MessagingBackend by DemoBackend() {
            override suspend fun login(
                username: String,
                password: String,
                deviceName: String,
                code: String?,
            ): LoginResult = LoginResult("t", username, listOf(Contact("bob", "bob", "")))

            override suspend fun sendFile(
                token: String,
                contactId: String,
                bytes: ByteArray,
                mime: String,
                name: String,
            ): ChatMessage {
                called = true
                return ChatMessage("f1", true, name, "10:00", kind = "file")
            }
        }
        val store = AppStore(
            backend = backend,
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
            credentials = CredentialStore(Files.createTempDirectory("mim-remember")),
        )
        store.login("alice", "pass1234", "desktop")
        store.openChat("bob")
        store.toggleBlock("bob")
        store.sendFile(ByteArray(16), "a.txt", "text/plain")

        assertTrue(!called, "屏蔽后不应调用发送")
        assertEquals("已屏蔽该联系人，解除屏蔽后才能发送", store.state.value.error)
    }

    @Test
    fun pinned_contact_sorts_before_recent_ones() {
        val store = store()
        store.login("alice", "pass1234", "desktop")
        // 先让 bob 有最新一条消息，未置顶时它排第一。
        store.openChat("bob")
        store.send("让 bob 成为最近会话")
        assertEquals("bob", store.state.value.contacts.sortedForList().first().id)

        store.togglePin("alice")

        val state = store.state.value
        assertTrue("alice" in state.pinnedPeers)
        assertTrue(state.contacts.first { it.id == "alice" }.pinned)
        assertEquals("alice", state.contacts.sortedForList().first().id)

        store.togglePin("alice")
        assertTrue("alice" !in store.state.value.pinnedPeers)
        assertTrue(!store.state.value.contacts.first { it.id == "alice" }.pinned)
        assertEquals("bob", store.state.value.contacts.sortedForList().first().id)
    }

    /**
     * 打开有未读的会话：定位到本地已读水位之后的第一条对方消息，
     * 并把它作为「以下为新消息」的分界。
     */
    @Test
    fun opening_chat_anchors_at_first_unread() {
        var mark = 1_000L
        val backend = object : MessagingBackend by DemoBackend() {
            override suspend fun login(
                username: String,
                password: String,
                deviceName: String,
                code: String?,
            ): LoginResult = LoginResult("t", username, listOf(Contact("bob", "bob", "", unread = 2)))

            override suspend fun unread(token: String, contactId: String): Int = 2

            override suspend fun history(token: String, contactId: String): List<ChatMessage> = listOf(
                ChatMessage("m1", false, "旧消息", "10:00", epochMs = 1_000),
                ChatMessage("m2", true, "我的回复", "10:01", epochMs = 2_000),
                ChatMessage("m3", false, "新消息一", "10:02", epochMs = 3_000),
                ChatMessage("m4", false, "新消息二", "10:03", epochMs = 4_000),
            )

            override fun readMark(contactId: String): Long = mark

            override fun setReadMark(contactId: String, epochMs: Long) {
                mark = maxOf(mark, epochMs)
            }
        }
        val store = AppStore(
            backend = backend,
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
            credentials = CredentialStore(Files.createTempDirectory("mim-remember")),
        )
        store.login("alice", "pass1234", "desktop")
        assertEquals(2, store.state.value.contacts.first { it.id == "bob" }.unread)

        store.openChat("bob")

        assertEquals("m3", store.state.value.firstUnreadMessageId)
        assertEquals("m3", store.state.value.pendingScrollMessageId)
        assertEquals(4_000L, mark, "打开会话后水位应推进到最新一条")
    }

    /**
     * 对方执行「双方清除」时本机不在线：登录后必须按服务端清除点丢掉旧缓存，
     * 否则会一直显示服务端已经抹掉的内容。
     */
    @Test
    fun login_drops_local_cache_after_peer_cleared() {
        val cached = mutableListOf(
            ChatMessage("old", false, "清除前的内容", "10:00", epochMs = 1_000),
            ChatMessage("fresh", false, "清除后的内容", "10:05", epochMs = 5_000),
        )
        val calls = mutableListOf<Triple<String, Long, Long>>()
        val backend = object : MessagingBackend by DemoBackend() {
            override suspend fun login(
                username: String,
                password: String,
                deviceName: String,
                code: String?,
            ): LoginResult = LoginResult("t", username, listOf(Contact("bob", "bob", "")))

            override suspend fun history(token: String, contactId: String): List<ChatMessage> =
                cached.toList()

            override suspend fun clearPoints(token: String, peers: List<String>): Map<String, ClearPoint> =
                if ("bob" in peers) mapOf("bob" to ClearPoint(3_000, 4_000)) else emptyMap()

            override fun dropClearedMessages(contactId: String, cursor: Long, clearedAt: Long): Boolean {
                calls += Triple(contactId, cursor, clearedAt)
                val before = cached.size
                cached.removeAll { it.epochMs <= clearedAt }
                return cached.size != before
            }
        }
        val store = AppStore(
            backend = backend,
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
            credentials = CredentialStore(Files.createTempDirectory("mim-remember")),
        )

        store.login("alice", "pass1234", "desktop")

        assertEquals(1, calls.size, "登录时必须按服务端清除点对账一次")
        assertEquals(Triple("bob", 3_000L, 4_000L), calls.first())
        assertEquals(
            listOf("fresh"),
            store.state.value.messages["bob"].orEmpty().map { it.id },
            "清除点之前的内容必须从本机缓存里消失",
        )
    }

    /** 没有清除点时不动本机缓存（只清对方自己时不应影响到我）。 */
    @Test
    fun login_keeps_cache_when_peer_only_cleared_their_side() {
        val backend = object : MessagingBackend by DemoBackend() {
            override suspend fun login(
                username: String,
                password: String,
                deviceName: String,
                code: String?,
            ): LoginResult = LoginResult("t", username, listOf(Contact("bob", "bob", "")))

            override suspend fun clearPoints(token: String, peers: List<String>) = emptyMap<String, ClearPoint>()

            override fun dropClearedMessages(contactId: String, cursor: Long, clearedAt: Long): Boolean =
                error("没有清除点时不应触碰本机缓存")
        }
        val store = AppStore(
            backend = backend,
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
            credentials = CredentialStore(Files.createTempDirectory("mim-remember")),
        )

        store.login("alice", "pass1234", "desktop")
        assertTrue(store.state.value.messages.isEmpty())
    }

    /** 对端换密钥后安全码会变：必须提示，并等用户核对后才重新记基准。 */
    @Test
    fun safety_code_change_warns_until_acknowledged() {
        val own = Base64.getEncoder().encodeToString(ByteArray(65) { index -> index.toByte() })
        var peer = Base64.getEncoder().encodeToString(ByteArray(65) { index -> (index + 1).toByte() })
        val known = mutableMapOf<String, String>()
        val backend = object : MessagingBackend by DemoBackend() {
            override suspend fun login(
                username: String,
                password: String,
                deviceName: String,
                code: String?,
            ): LoginResult = LoginResult("t", username, listOf(Contact("bob", "bob", "")))

            override fun ownIdentityKeyBase64(): String = own

            override suspend fun peerIdentityKeys(token: String, contactId: String): List<PeerIdentityKey> =
                listOf(PeerIdentityKey("desktop-peer", peer))

            override fun safetyCodes(contactId: String): Map<String, String> = known.toMap()

            override fun rememberSafetyCodes(contactId: String, codes: Map<String, String>) {
                known.putAll(codes)
            }
        }
        val store = AppStore(
            backend = backend,
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
            credentials = CredentialStore(Files.createTempDirectory("mim-remember")),
        )
        store.login("alice", "pass1234", "desktop")

        store.openChat("bob")
        assertTrue(store.state.value.safetyChanges.isEmpty(), "首次见到设备只记基准，不打扰用户")
        assertEquals(1, store.state.value.safetyNumbers.size)

        peer = Base64.getEncoder().encodeToString(ByteArray(65) { index -> (index + 9).toByte() })
        store.openChat("bob")
        assertEquals(setOf("desktop-peer"), store.state.value.safetyChanges)

        store.acknowledgeSafetyChange()
        assertTrue(store.state.value.safetyChanges.isEmpty(), "核对后提醒消失")

        store.openChat("bob")
        assertTrue(store.state.value.safetyChanges.isEmpty(), "同一把密钥不应重复提醒")
    }
}
