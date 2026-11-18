package com.junesoon.mim.service.impl;

import com.junesoon.mim.entity.RsaKey;
import com.junesoon.mim.mapper.RsaKeyMapper;
import com.junesoon.mim.service.RsaKeyService;
import com.junesoon.mim.utils.MasterKeyCrypto;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;

@Service
public class RsaKeyServiceImpl implements RsaKeyService {

    @Resource
    private RsaKeyMapper rsaKeyMapper;

    @Resource
    private MasterKeyCrypto masterKeyCrypto;

    public boolean hasKey(String key) {
        return rsaKeyMapper.selectByPrimaryKey(key) != null;
    }

    @Override
    public String get(String key) {
        RsaKey rsaKey = rsaKeyMapper.selectByPrimaryKey(key);
        if (rsaKey == null) {
            return "";
        }
        return masterKeyCrypto.decrypt(key, rsaKey.getValue());
    }

    @Override
    public int set(String key, String value) {
        RsaKey rsaKey = new RsaKey();
        rsaKey.setKey(key);
        rsaKey.setValue(masterKeyCrypto.encrypt(key, value));
        return rsaKeyMapper.upsert(rsaKey);
    }

    @Override
    public boolean isEncrypted(String key) {
        RsaKey rsaKey = rsaKeyMapper.selectByPrimaryKey(key);
        return rsaKey != null && MasterKeyCrypto.isEncrypted(rsaKey.getValue());
    }

    @Override
    public boolean rotateEncryption(String key) {
        RsaKey rsaKey = rsaKeyMapper.selectByPrimaryKey(key);
        if (rsaKey == null) {
            return false;
        }
        String rotated = masterKeyCrypto.reencryptIfNeeded(key, rsaKey.getValue());
        if (rotated.equals(rsaKey.getValue())) {
            return false;
        }
        rsaKey.setValue(rotated);
        rsaKeyMapper.upsert(rsaKey);
        return true;
    }
}
