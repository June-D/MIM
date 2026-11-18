package com.junesoon.mim.config;

import jakarta.servlet.http.HttpServletRequest;
import lombok.Data;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

@Data
@Configuration
public class UserAuthConfig {

    @Value("${auth.user.token-header}")
    private String tokenHeader;

    @Value("${jwt.token-header}")
    private String userTokenHeader;

    private byte[] pubKeyByte;

    public String getToken(HttpServletRequest request) {
        return request.getHeader(this.getTokenHeader());
    }

}
