package com.junesoon.mim.feign;

import com.junesoon.mim.components.ObjectRestResponse;
import com.junesoon.mim.model.JwtAuthenticationRequest;
import feign.Param;
import feign.RequestLine;

/**
 * @program: MIMClient
 * @description: 注册Feign
 * @version: 1.0
 * @create: 2024-03-06 16:44
 */
public interface RegisterFeign extends FeignAPI{

    @RequestLine("POST /register/token")
    ObjectRestResponse<String> register(JwtAuthenticationRequest request);

    @RequestLine("GET /register/available?username={username}")
    ObjectRestResponse<Boolean> available(@Param("username") String username);
}
