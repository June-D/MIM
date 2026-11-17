package com.junesoon.mim.mapper;

import com.junesoon.mim.entity.RsaKey;

public interface RsaKeyMapper {
    int deleteByPrimaryKey(String key);

    int insert(RsaKey record);

    int upsert(RsaKey record);

    int insertSelective(RsaKey record);

    RsaKey selectByPrimaryKey(String key);

    int updateByPrimaryKeySelective(RsaKey record);

    int updateByPrimaryKey(RsaKey record);
}
