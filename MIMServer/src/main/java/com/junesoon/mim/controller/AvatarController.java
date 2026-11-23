package com.junesoon.mim.controller;

import com.junesoon.mim.auth.CurrentUser;
import com.junesoon.mim.exception.UserInvalidException;
import com.junesoon.mim.mapper.ChatUserMapper;
import com.junesoon.mim.ratelimit.RateLimit;
import com.junesoon.mim.requests.AvatarRequest;
import com.junesoon.mim.responses.ObjectRestResponse;
import com.junesoon.mim.service.AvatarService;
import com.junesoon.mim.utils.ValidationUtil;
import java.util.Base64;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 账号头像。
 *
 * <p>头像不是端到端加密的：它要给联系人看，服务端必须能直接返回字节。
 * 因此只允许「本人 + 已建立联系人关系的账号」读取，避免拿用户名遍历头像；
 * 客户端上传前会重编码成 PNG（顺带丢掉 EXIF），服务端只接受 PNG。
 */
@RestController
@RequestMapping("user/avatar")
@Slf4j
public class AvatarController {

    private final AvatarService avatarService;
    private final ChatUserMapper chatUserMapper;

    public AvatarController(AvatarService avatarService, ChatUserMapper chatUserMapper) {
        this.avatarService = avatarService;
        this.chatUserMapper = chatUserMapper;
    }

    @PostMapping
    @RateLimit(limit = 10, windowSeconds = 60, dimension = RateLimit.Dimension.USER)
    public ObjectRestResponse<Integer> upload(@CurrentUser String username,
        @RequestBody AvatarRequest request) {
        if (request == null || !StringUtils.hasText(request.getImage())) {
            throw new UserInvalidException("头像内容为空");
        }
        byte[] png;
        try {
            png = Base64.getDecoder().decode(request.getImage());
        } catch (IllegalArgumentException e) {
            throw new UserInvalidException("头像不是合法的 Base64");
        }
        int version = avatarService.save(username, png);
        log.info("用户 {} 更新头像，版本 {}", username, version);
        return new ObjectRestResponse<Integer>().data(version).rel(true);
    }

    @DeleteMapping
    public ObjectRestResponse<Boolean> delete(@CurrentUser String username) {
        boolean removed = avatarService.delete(username);
        return new ObjectRestResponse<Boolean>().data(removed).rel(true);
    }

    /**
     * 读取头像。本人随时可读；其它账号必须先有联系人关系。
     */
    @GetMapping("/{target}")
    public ResponseEntity<byte[]> load(@CurrentUser String username,
        @PathVariable("target") String target) {
        if (!ValidationUtil.isUsername(target)) {
            return ResponseEntity.notFound().build();
        }
        boolean allowed = username.equals(target)
            || chatUserMapper.selectRelation(username, target) != null;
        if (!allowed) {
            return ResponseEntity.notFound().build();
        }
        byte[] png = avatarService.load(target);
        if (png == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok()
            .contentType(MediaType.IMAGE_PNG)
            .cacheControl(CacheControl.maxAge(java.time.Duration.ofMinutes(5)).cachePrivate())
            .body(png);
    }
}
