package com.junesoon.mim.responses;

import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * 改密结果：当前设备的新 JWT。其它设备的旧令牌会因设备版本自增而失效。
 */
@Data
@AllArgsConstructor
public class PasswordChangeResult {
    private String token;
}
