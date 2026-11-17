package com.junesoon.mim.feign;

import com.junesoon.mim.components.ObjectRestResponse;
import com.junesoon.mim.model.KeyBundle;
import feign.Param;
import feign.RequestLine;
import java.util.List;

/**
 * 公钥目录接口。
 */
public interface KeyFeign extends FeignAPI {

    @RequestLine("POST /key")
    ObjectRestResponse<KeyBundle> upload(KeyUploadRequest request);

    /**
     * 查询某用户全部有效设备的身份公钥。不消耗一次性预密钥，
     * 用于先确定「需要为哪些设备建立会话」。
     */
    @RequestLine("GET /key/{username}")
    ObjectRestResponse<List<KeyBundle>> getDevices(@Param("username") String username);

    @RequestLine("GET /key/{username}?deviceId={deviceId}")
    ObjectRestResponse<List<KeyBundle>> getDevice(@Param("username") String username,
        @Param("deviceId") String deviceId);

    @RequestLine("GET /key/self")
    ObjectRestResponse<KeyBundle> self();
}
