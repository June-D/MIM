package com.junesoon.mim.service;

public interface RsaKeyService {

    boolean hasKey(String key);

    String get(String key);

    int set(String key, String value);

    boolean isEncrypted(String key);

    /**
     * 如果该行密钥是用上一把 MIM_MASTER_KEY 加密的，改用当前主密钥重加密。
     *
     * @return 是否发生了重加密
     */
    boolean rotateEncryption(String key);
}
