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
}
