package com.junesoon.mim.mapper;

import com.junesoon.mim.entity.UserKey;
import java.util.List;
import org.apache.ibatis.annotations.Param;

public interface UserKeyMapper {

    /**
     * 取缺省设备的公钥，兼容单设备客户端。
     */
    UserKey selectByUsername(@Param("username") String username);

    UserKey selectByDevice(@Param("username") String username, @Param("deviceId") String deviceId);

    /**
     * 取某用户所有<b>未注销设备</b>的公钥。发消息时按设备逐个加密需要它。
     */
    List<UserKey> selectAllByUsername(@Param("username") String username);

    int upsert(UserKey key);
}
