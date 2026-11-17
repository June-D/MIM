package com.junesoon.mim.mapper;

import com.junesoon.mim.entity.User;
import org.apache.ibatis.annotations.Param;

public interface UserMapper {
    int deleteByPrimaryKey(Integer id);

    int insert(User record);

    int insertSelective(User record);

    User selectByPrimaryKey(Integer id);

    User selectOne(String username);

    int updateLastLoginTime(@Param("username") String username, @Param("lastLoginTime") java.util.Date lastLoginTime);

    int updateByPrimaryKeySelective(User record);

    int updateByPrimaryKey(User record);
}
