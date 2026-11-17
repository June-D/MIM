package com.junesoon.mim.requests;

import lombok.Data;

@Data
public class ClearRequest {

    private String peer;

    /** 为 true 时，对方的这段对话也一并清除。 */
    private boolean forBoth;
}
