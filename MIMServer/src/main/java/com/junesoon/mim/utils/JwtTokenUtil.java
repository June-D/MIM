package com.junesoon.mim.utils;

import com.junesoon.mim.config.KeyConfiguration;
import com.junesoon.mim.jwt.JWTHelper;
import com.junesoon.mim.jwt.JWTInfo;
import javax.annotation.Resource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;


@Component
public class JwtTokenUtil {

    @Value("${jwt.expire}")
    private int expire;

    @Resource
    private KeyConfiguration keyConfiguration;

    public String generateToken(JWTInfo jwtInfo) throws Exception {
        return JWTHelper.generateToken(jwtInfo, keyConfiguration.getUserPriKey(), expire);
    }

    public JWTInfo getInfoFromToken(String token) throws Exception {
        return JWTHelper.getInfoFromToken(token, keyConfiguration.getUserPubKey());
    }

}
