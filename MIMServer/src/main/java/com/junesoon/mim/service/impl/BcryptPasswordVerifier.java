package com.junesoon.mim.service.impl;

import com.junesoon.mim.constant.CommonConstants;
import com.junesoon.mim.entity.User;
import com.junesoon.mim.mapper.UserMapper;
import com.junesoon.mim.service.PasswordVerifier;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * 基于 BCrypt 的口令校验，供敏感操作复验身份。
 */
@Component
public class BcryptPasswordVerifier implements PasswordVerifier {

    private final UserMapper userMapper;
    private final BCryptPasswordEncoder encoder =
        new BCryptPasswordEncoder(CommonConstants.PW_ENCORDER_SALT);

    public BcryptPasswordVerifier(UserMapper userMapper) {
        this.userMapper = userMapper;
    }

    @Override
    public boolean matches(String username, String password) {
        if (!StringUtils.hasText(username) || !StringUtils.hasText(password)) {
            return false;
        }
        User user = userMapper.selectOne(username);
        return user != null && encoder.matches(password, user.getPassword());
    }
}
