package com.junesoon.mim.controller;

import com.junesoon.mim.auth.CurrentUser;
import com.junesoon.mim.entity.MediaObject;
import com.junesoon.mim.exception.UserInvalidException;
import com.junesoon.mim.ratelimit.RateLimit;
import com.junesoon.mim.responses.ObjectRestResponse;
import com.junesoon.mim.service.MediaService;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 加密媒体上传与下载。
 *
 * <p>客户端上传的是已经用消息密钥加密过的文件，密钥只存在于端到端密文里，
 * 服务端拿到密文也解不开。因此这里不做任何解密、转码或缩略图。
 *
 * <p>下载只要求登录：id 是 32 字节随机数，不知道 id 就取不到；
 * 即使拿到密文，没有消息里的密钥也无法还原内容。
 */
@Slf4j
@RestController
@RequestMapping("media")
public class MediaController {

    private static final java.util.regex.Pattern ID_PATTERN =
        java.util.regex.Pattern.compile("^[A-Za-z0-9_-]{16,64}$");

    private final MediaService mediaService;

    public MediaController(MediaService mediaService) {
        this.mediaService = mediaService;
    }

    /**
     * 上传密文。请求体是原始字节，`Content-Type` 固定为 octet-stream。
     */
    @PostMapping(consumes = MediaType.APPLICATION_OCTET_STREAM_VALUE)
    @RateLimit(limit = 30, windowSeconds = 60, dimension = RateLimit.Dimension.USER)
    public ObjectRestResponse<Map<String, Object>> upload(@CurrentUser String username,
        @RequestBody byte[] encrypted) {
        MediaObject object = mediaService.save(username, encrypted);
        Map<String, Object> body = new LinkedHashMap<>(2);
        body.put("id", object.getId());
        body.put("size", object.getSize());
        return new ObjectRestResponse<Map<String, Object>>().data(body).rel(true);
    }

    @GetMapping("/{id}")
    @RateLimit(limit = 120, windowSeconds = 60, dimension = RateLimit.Dimension.USER)
    public ResponseEntity<Resource> download(@CurrentUser String username,
        @PathVariable String id) {
        requireValidId(id);
        MediaObject object = mediaService.metadata(id);
        Path path = mediaService.load(id);
        if (object == null || path == null) {
            throw new UserInvalidException("媒体不存在或已删除");
        }
        return ResponseEntity.ok()
            .header(HttpHeaders.CACHE_CONTROL, "private, max-age=3600")
            .contentType(MediaType.APPLICATION_OCTET_STREAM)
            .contentLength(object.getSize())
            .body(new FileSystemResource(path));
    }

    /** 发送方可以删除自己上传的密文；接收方删除不影响他人。 */
    @DeleteMapping("/{id}")
    @RateLimit(limit = 30, windowSeconds = 60, dimension = RateLimit.Dimension.USER)
    public ObjectRestResponse<Boolean> delete(@CurrentUser String username,
        @PathVariable String id) {
        requireValidId(id);
        return new ObjectRestResponse<Boolean>()
            .data(mediaService.delete(username, id))
            .rel(true);
    }

    private void requireValidId(String id) {
        if (id == null || !ID_PATTERN.matcher(id).matches()) {
            throw new UserInvalidException("媒体标识不合法");
        }
    }
}
