package com.junesoon.mim.feign;

import com.junesoon.mim.components.ObjectRestResponse;
import feign.Body;
import feign.Param;
import feign.RequestLine;

/**
 * 设备管理接口。
 */
public interface DeviceFeign extends FeignAPI {

    @RequestLine("POST /device")
    @Body("%7B\"deviceId\":\"{deviceId}\",\"deviceName\":\"{deviceName}\"%7D")
    ObjectRestResponse<Object> register(@Param("deviceId") String deviceId,
        @Param("deviceName") String deviceName);

    @RequestLine("GET /device")
    ObjectRestResponse<Object> list();

    @RequestLine("DELETE /device/{deviceId}")
    ObjectRestResponse<Boolean> revoke(@Param("deviceId") String deviceId);
}
