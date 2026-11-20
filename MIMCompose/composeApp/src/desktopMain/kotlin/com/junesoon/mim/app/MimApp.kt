package com.junesoon.mim.app

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import com.junesoon.mim.app.data.DemoBackend
import com.junesoon.mim.app.data.SecureBackend
import com.junesoon.mim.app.data.MessagingBackend
import com.junesoon.mim.app.state.AppStore
import com.junesoon.mim.app.state.Screen
import com.junesoon.mim.app.ui.ChatScreen
import com.junesoon.mim.app.ui.ContactsScreen
import com.junesoon.mim.app.ui.LoginScreen
import com.junesoon.mim.app.ui.MimTheme

/**
 * 顶层组装：只做路由与状态分发。
 *
 * 换成真实后端时，只需在构造 [AppStore] 时传入另一个实现。
 */
@Composable
fun MimApp() {
    // 设置了 MIM_SERVER_URL 就走真实服务端，否则用内存演示后端。
    val store = remember {
        val server = System.getenv("MIM_SERVER_URL")
        val backend: MessagingBackend =
            if (server.isNullOrBlank()) DemoBackend() else SecureBackend(server.trim())
        AppStore(backend)
    }
    val state by store.state.collectAsState()

    MimTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
            when (val screen = state.screen) {
                Screen.Login -> LoginScreen(
                    busy = state.busy,
                    error = state.error,
                    onLogin = store::login,
                )

                Screen.Contacts -> ContactsScreen(
                    selfName = state.selfName,
                    deviceName = state.deviceName,
                    contacts = state.contacts,
                    onOpenChat = store::openChat,
                )

                is Screen.Chat -> {
                    val contact = state.activeContact
                    if (contact == null) {
                        ContactsScreen(
                            selfName = state.selfName,
                            deviceName = state.deviceName,
                            contacts = state.contacts,
                            onOpenChat = store::openChat,
                        )
                    } else {
                        ChatScreen(
                            contact = contact,
                            messages = state.messages[screen.contactId].orEmpty(),
                            onBack = store::backToContacts,
                            onSend = store::send,
                            error = state.error,
                        )
                    }
                }
            }
        }
    }
}
