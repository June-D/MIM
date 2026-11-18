package com.junesoon.mim.model;

import java.util.List;
import lombok.Data;

/**
 * 启用二次验证的结果：当前设备的新 JWT 与只显示一次的恢复码。
 */
@Data
public class TwoFactorEnableResult {

    private String token;

    private List<String> recoveryCodes;
}
