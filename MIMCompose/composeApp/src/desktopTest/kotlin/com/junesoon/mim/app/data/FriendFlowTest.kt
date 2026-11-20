package com.junesoon.mim.app.data

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 好友链路联调：搜索 → 申请 → 对方同意 → 双方互为联系人。
 *
 * 用独立账号，避免污染聊天测试用的 composealice / composebob；
 * 每次先删除好友关系，保证连跑多遍都能重复走完整条链路。
 */
class FriendFlowTest {

    @Test
    fun request_accept_and_search() {
        val server = System.getenv("MIM_SERVER_URL") ?: return
        val first = "composefrienda"
        val second = "composefriendb"

        runBlocking {
            TestAccounts.ensure(server, first)
            TestAccounts.ensure(server, second)

            val firstBackend = HttpBackend(server)
            val firstLogin = TestAccounts.withLoginBudget {
                firstBackend.login(first, TestAccounts.PASSWORD, TestAccounts.uniqueDevice("frienda"))
            }
            val secondBackend = HttpBackend(server)
            val secondLogin = TestAccounts.withLoginBudget {
                secondBackend.login(second, TestAccounts.PASSWORD, TestAccounts.uniqueDevice("friendb"))
            }

            // 先解除关系，让流程可以重复跑；本来不是好友时服务端会拒绝，忽略即可。
            runCatching { SecureBackend(server).removeContact(firstLogin.token, second) }

            // 搜索：对方不在好友列表里时应能搜到。
            val found = SecureBackend(server).searchUsers(firstLogin.token, second)
            assertTrue(found.any { it.username == second }, "应能搜到 $second：$found")

            SecureBackend(server).requestFriend(firstLogin.token, second)

            val incoming = SecureBackend(server).friendRequests(secondLogin.token)
                .firstOrNull { it.incoming && it.pending && it.username == first }
            assertTrue(incoming != null, "对方应看到来自 $first 的申请：$incoming")

            SecureBackend(server).respondRequest(secondLogin.token, incoming!!.id, accept = true)

            val firstContacts = SecureBackend(server).contacts(firstLogin.token)
            assertTrue(firstContacts.any { it.id == second }, "同意后应互为联系人：$firstContacts")

            // 已是好友后，搜索不再返回对方。
            val afterAccept = SecureBackend(server).searchUsers(firstLogin.token, second)
            assertEquals(emptyList(), afterAccept, "已是好友不应出现在搜索结果里")
        }
    }
}
