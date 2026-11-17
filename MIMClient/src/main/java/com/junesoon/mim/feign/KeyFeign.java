package com.junesoon.mim.feign;

import com.junesoon.mim.components.ObjectRestResponse;
import com.junesoon.mim.model.KeyBundle;
import feign.Param;
import feign.RequestLine;

/**
 * 公钥目录接口。
 */
public interface KeyFeign extends FeignAPI {

    @RequestLine("POST /key")
    ObjectRestResponse<KeyBundle> upload(KeyUploadRequest request);

    @RequestLine("GET /key/{username}")
    ObjectRestResponse<KeyBundle> get(@Param("username") String username);

    @RequestLine("GET /key/self")
    ObjectRestResponse<KeyBundle> self();
}
