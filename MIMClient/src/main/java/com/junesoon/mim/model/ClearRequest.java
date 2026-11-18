package com.junesoon.mim.model;

import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ClearRequest {

    private String peer;

    private boolean forBoth;

    /** 会话标签。服务端按标签写清除点并抹除密文。 */
    private List<String> tags;
}
