package com.junesoon.mim.utils;

import static org.junit.jupiter.api.Assertions.assertNotNull;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 网络层依赖的自检：OkHttp 必须能在当前依赖组合下初始化。
 *
 * <p>历史上 OkHttp 5.x 被 Spring Boot 2.7 的 kotlin-stdlib 1.6.21 卡住，
 * 运行时报 {@code NoClassDefFoundError: kotlin/enums/EnumEntriesKt}，
 * 表现为「登录点了没反应」。这个用例在测试阶段就能拦住同类冲突。
 *
 * [INPUT]: 只依赖 OkHttp
 * [OUTPUT]: 验证 OkHttpClient 可以构造
 * [POS]: 依赖升级后的最低限度运行时保护
 * [PROTOCOL]: Update this header on change, then check CLAUDE.md
 */
class HttpClientDependencyTest {

    @Test
    @DisplayName("OkHttp 客户端可以正常初始化（依赖版本兼容）")
    void okHttpInitialises() {
        okhttp3.OkHttpClient client = new okhttp3.OkHttpClient.Builder().build();
        assertNotNull(client);
    }
}
