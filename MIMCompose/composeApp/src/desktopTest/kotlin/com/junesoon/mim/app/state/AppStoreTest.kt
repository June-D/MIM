package com.junesoon.mim.app.state

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
