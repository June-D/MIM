package com.junesoon.mim.model;

import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 已读回执请求。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ReadRequest {

    private String peer;

    private Long beforeId;

    /** 会话标签。服务端按标签标记已读，不再依赖盲引用。 */
    private List<String> tags;
}
