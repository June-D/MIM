package com.junesoon.mim.requests;

import java.util.List;
import lombok.Data;

@Data
public class ClearRequest {

    private String peer;

    /** 为 true 时，对方的这段对话也一并清除。 */
    private boolean forBoth;

    /** 客户端持有的会话标签；提供后按标签写清除点并抹除密文。 */
    private List<String> tags;
}
