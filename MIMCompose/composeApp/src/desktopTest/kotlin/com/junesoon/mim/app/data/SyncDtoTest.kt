package com.junesoon.mim.app.data

import com.junesoon.mim.app.data.messaging.CipherMessageDto
import com.junesoon.mim.app.data.messaging.CipherMessageOut
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 同步副本的字段名必须与服务端逐字一致。
 *
 * <p>服务端用 Jackson 按字段名取 `syncOriginalFrom/To/Device` 与 `syncSignature`，
 * 少一个就直接 400「同步消息缺少原消息归属」。这里把契约钉住：
 * 改名字时这个测试会先失败，而不是等到线上提交才发现。
 */
class SyncDtoTest {

    @Test
    fun outgoing_replica_carries_attribution_and_signature() {
        val replica = CipherMessageOut(
            clientMsgId = "m-1",
            conversationTag = "tagA",
            toUsername = "alice",
            targetDeviceId = "device-b",
            alg = "ratchet",
            iv = "iv",
            ciphertext = "cipher",
            ratchetPublic = "dh",
            previousChainLength = 0,
            messageNumber = 1,
            clientTime = 1_000,
            sessionId = "session-1",
            syncOriginalFrom = "alice",
            syncOriginalTo = "bob",
            syncOriginalDevice = "device-a",
            syncSignature = "c2ln",
        )
        val json = JsonCodec.encode(replica)

        listOf(
            "\"syncOriginalFrom\"",
            "\"syncOriginalTo\"",
            "\"syncOriginalDevice\"",
            "\"syncSignature\"",
        ).forEach { key ->
            assertTrue(json.contains(key), "序列化结果缺少 $key：$json")
        }
    }

    @Test
    fun incoming_replica_can_be_told_apart_from_normal_messages() {
        val normal = JsonCodec.decode<CipherMessageDto>(
            """{"clientMsgId":"m-1","toUsername":"alice"}""",
        )
        assertEquals(null, normal.syncFromDevice)

        val synced = JsonCodec.decode<CipherMessageDto>(
            """{"clientMsgId":"m-1","toUsername":"alice","syncFromDevice":"device-a",
               "syncOriginalFrom":"alice","syncOriginalTo":"bob"}""",
        )
        assertEquals("device-a", synced.syncFromDevice)
        assertEquals("bob", synced.syncOriginalTo)
    }
}
