package com.junesoon.mim.model;

import lombok.Data;

/**
 * 改密结果：服务端为当前设备重新签发的 JWT。
 */
@Data
public class PasswordChangeResult {
    private String token;
}
