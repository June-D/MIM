package com.junesoon.mim.model;

import lombok.Data;

/**
 * 二次验证初始化结果。
 */
@Data
public class TwoFactorSetup {
    private String secret;
    private String otpauthUri;
}
