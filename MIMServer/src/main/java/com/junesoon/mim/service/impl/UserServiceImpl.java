package com.junesoon.mim.service.impl;

import com.junesoon.mim.constant.CommonConstants;
import com.junesoon.mim.entity.User;
import com.junesoon.mim.entity.UserInfo;
import com.junesoon.mim.mapper.UserMapper;
import com.junesoon.mim.requests.JwtAuthenticationRequest;
import com.junesoon.mim.service.PresenceService;
import com.junesoon.mim.service.UserService;
import com.junesoon.mim.utils.EmailCryptoService;
import com.junesoon.mim.utils.ValidationUtil;
import java.util.Date;
import org.springframework.beans.BeanUtils;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class UserServiceImpl implements UserService {

    private final UserMapper userMapper;
    private final PresenceService presenceService;
    private final EmailCryptoService emailCryptoService;

    private final BCryptPasswordEncoder encoder =
        new BCryptPasswordEncoder(CommonConstants.PW_ENCORDER_SALT);

    public UserServiceImpl(UserMapper userMapper, PresenceService presenceService,
        EmailCryptoService emailCryptoService) {
        this.userMapper = userMapper;
        this.presenceService = presenceService;
        this.emailCryptoService = emailCryptoService;
    }

    @Override
    public UserInfo validate(JwtAuthenticationRequest request) {
        ValidationUtil.validateUsername(request.getUsername());
        ValidationUtil.validatePassword(request.getPassword());
        UserInfo info = new UserInfo();
        User user = getUserByUsername(request.getUsername());
        if (user != null && encoder.matches(request.getPassword(), user.getPassword())) {
            BeanUtils.copyProperties(user, info);
            info.setId(user.getId().toString());
            userMapper.updateLastLoginTime(user.getUsername(), new Date());
        }
        return info;
    }

    @Override
    public User getUserByUsername(String username) {
        return userMapper.selectOne(username);
    }

    @Override
    public UserInfo getUserInfo(String username) {
        if (!StringUtils.hasText(username)) {
            return null;
        }
        User user = getUserByUsername(username);
        if (user == null) {
            return null;
        }
        UserInfo info = new UserInfo();
        BeanUtils.copyProperties(user, info);
        info.setId(user.getId().toString());
        info.setEmail(emailCryptoService.decrypt(username, user.getEmailCipher()));
        return info;
    }

    @Override
    public void register(JwtAuthenticationRequest request) {
        ValidationUtil.validateUsername(request.getUsername());
        ValidationUtil.validatePassword(request.getPassword());
        ValidationUtil.validateEmail(request.getEmail());
        User user = new User();
        user.setUsername(request.getUsername());
        user.setPassword(encoder.encode(request.getPassword()));
        user.setNickname(request.getUsername());
        user.setCrtTime(new Date());
        user.setUpdTime(new Date());
        user.setStatus(CommonConstants.USER_STATUS_NORMAL);
        if (StringUtils.hasText(request.getEmail())) {
            user.setEmailCipher(emailCryptoService.encrypt(request.getUsername(),
                request.getEmail().trim()));
        }
        user.setTermsVersion(CommonConstants.TERMS_VERSION);
        user.setAgreedAt(new Date());
        userMapper.insertSelective(user);
    }

    @Override
    public boolean heartbeat(String username) {
        presenceService.touch(username);
        return true;
    }
}
