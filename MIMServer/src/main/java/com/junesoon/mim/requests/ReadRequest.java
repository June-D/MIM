package com.junesoon.mim.requests;

import java.util.List;
import lombok.Data;

@Data
public class ReadRequest {

    private String peer;

    private Long beforeId;

    /**
     * 客户端持有的会话标签。提供后服务端按标签标记已读，
     * 不再依赖「用户名 → 盲引用」。
     */
    private List<String> tags;

    /**
     * 本次读到的逻辑消息 id。
     *
     * <p>多设备副本的会话标签各不相同，只有 client_msg_id 相同；提供它才能真正
     * 做到「账号级已读」。有值时服务端优先按它更新，忽略 tags。
     */
    private List<String> clientMsgIds;
}
