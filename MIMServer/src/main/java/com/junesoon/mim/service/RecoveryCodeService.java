package com.junesoon.mim.service;

import java.util.List;

/**
 * 一次性恢复码：用于 TOTP 设备丢失时完成登录。
 */
public interface RecoveryCodeService {

    /**
     * 重新生成 10 个恢复码，旧的（含已使用的）全部删除。
     *
     * @return 明文恢复码，只在本次返回中可见
     */
    List<String> generate(String username);

    /**
     * 原子消费一个恢复码；成功后该码不能再使用。
     */
    boolean consume(String username, String code);

    void deleteAll(String username);

    int remaining(String username);
}
