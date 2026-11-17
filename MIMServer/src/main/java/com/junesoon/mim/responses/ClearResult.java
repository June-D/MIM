package com.junesoon.mim.responses;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ClearResult {

    private String peer;

    /** 这个 id 及更早的消息对执行方（以及选择双方清除时的对方）不再可见。 */
    private long clearedBeforeId;

    private boolean forBoth;
}
