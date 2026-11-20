package com.junesoon.mim.app.domain

/**
 * 服务端记录的「本人与该对端的清除点」。
 *
 * `clearedBeforeId` 及更早的消息对本人不再可见；`updatedAt` 是清除动作的
 * 服务端时间，用来判断本机缓存里哪一条属于被清掉的范围（旧版本缓存没有
 * 服务端消息 id，只能按时间兜底）。
 */
data class ClearPoint(
    val clearedBeforeId: Long,
    val updatedAt: Long,
)
