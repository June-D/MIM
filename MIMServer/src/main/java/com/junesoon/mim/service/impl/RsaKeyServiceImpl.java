package com.junesoon.mim.service.impl;

import com.junesoon.mim.entity.RsaKey;
import com.junesoon.mim.mapper.RsaKeyMapper;
import com.junesoon.mim.service.RsaKeyService;
import javax.annotation.Resource;
import org.springframework.stereotype.Service;

@Service
public class RsaKeyServiceImpl implements RsaKeyService {

    @Resource
    private RsaKeyMapper rsaKeyMapper;

    public boolean hasKey(String key) {
        return rsaKeyMapper.selectByPrimaryKey(key) != null;
    }

    @Override
    public String get(String key) {
        RsaKey rsaKey = rsaKeyMapper.selectByPrimaryKey(key);
        return rsaKey == null ? "" : rsaKey.getValue();
    }

    @Override
    public int set(String key, String value) {
        RsaKey rsaKey = new RsaKey();
        rsaKey.setKey(key);
        rsaKey.setValue(value);
        return rsaKeyMapper.upsert(rsaKey);
    }
}
