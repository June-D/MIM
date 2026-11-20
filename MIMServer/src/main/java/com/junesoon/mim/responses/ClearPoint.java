package com.junesoon.mim.responses;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 本人与某个对端的清除点。
 *
 * <p>只描述「哪些消息对本人不可见」，不含明文、不含对方与第三方的任何数据。
 * 客户端换设备、重装或断线期间对方执行了「双方清除」时，靠它把本机缓存对齐，
 * 否则本地加密缓存会继续显示服务端已经抹掉的内容。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ClearPoint {

    /** 这个 id 及更早的消息对本人不再可见。 */
    private long clearedBeforeId;

    /** 清除动作的服务端时间（毫秒），给客户端判断本机缓存是否早于这次清除。 */
    private long updatedAt;
}
