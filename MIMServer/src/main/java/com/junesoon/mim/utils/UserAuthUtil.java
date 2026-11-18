package com.junesoon.mim.utils;

import com.junesoon.mim.config.UserAuthConfig;
import com.junesoon.mim.exception.UserTokenException;
import com.junesoon.mim.jwt.JWTHelper;
import com.junesoon.mim.jwt.JWTInfo;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.SignatureException;
import jakarta.annotation.Resource;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;

@Configuration
public class UserAuthUtil {

    @Resource
    private UserAuthConfig userAuthConfig;

    public JWTInfo getInfoFromToken(String token) throws Exception {
        if (!StringUtils.hasText(token)) {
            throw new UserTokenException("缺少登录凭证");
        }
        try {
            return JWTHelper.getInfoFromToken(token.trim(), userAuthConfig.getPubKeyByte());
        } catch (ExpiredJwtException ex) {
            throw new UserTokenException("登录已过期，请重新登录");
        } catch (SignatureException ex) {
            throw new UserTokenException("登录凭证签名错误");
        } catch (IllegalArgumentException ex) {
            throw new UserTokenException("登录凭证为空");
        }
    }

    public String getUsername(String token) throws Exception {
        return getInfoFromToken(token).getUsername();
    }
}
