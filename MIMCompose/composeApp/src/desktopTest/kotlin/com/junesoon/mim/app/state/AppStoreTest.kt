package com.junesoon.mim.app.state

import com.junesoon.mim.app.data.CredentialStore
import com.junesoon.mim.app.data.DemoBackend
import com.junesoon.mim.app.data.LoginResult
import com.junesoon.mim.app.data.MessagingBackend
import com.junesoon.mim.app.data.TwoFactorRequiredException
import java.nio.file.Files
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
}
