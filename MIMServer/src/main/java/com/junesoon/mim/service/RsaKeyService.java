package com.junesoon.mim.service;

public interface RsaKeyService {

    boolean hasKey(String key);

    String get(String key);

    int set(String key, String value);
}
