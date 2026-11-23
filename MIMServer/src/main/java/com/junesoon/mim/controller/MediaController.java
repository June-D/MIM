package com.junesoon.mim.controller;

import com.junesoon.mim.auth.CurrentUser;
import com.junesoon.mim.entity.MediaObject;
import com.junesoon.mim.exception.UserInvalidException;
import com.junesoon.mim.ratelimit.RateLimit;
import com.junesoon.mim.requests.InitUploadRequest;
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
import org.springframework.web.bind.annotation.PutMapping;
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

    /**
     * 开始分片上传。
     *
     * <p>大文件不走单请求：一块一块传，断了只补缺的那片；服务端把每片落盘，
     * 合并时统一校验长度，避免「看起来传完了、内容缺一段」。
     */
    @PostMapping("/upload/init")
    @RateLimit(limit = 10, windowSeconds = 60, dimension = RateLimit.Dimension.USER)
    public ObjectRestResponse<Map<String, Object>> initUpload(@CurrentUser String username,
        @RequestBody InitUploadRequest request) {
        if (request == null) {
            throw new UserInvalidException("请求不能为空");
        }
        String uploadId = mediaService.initUpload(username, request.getSize(),
            request.getChunkSize());
        MediaService.UploadStatus status = mediaService.uploadStatus(username, uploadId);
        Map<String, Object> body = new LinkedHashMap<>(4);
        body.put("uploadId", uploadId);
        body.put("chunkSize", status.chunkSize());
        body.put("totalChunks", status.totalChunks());
        body.put("received", status.received());
        return new ObjectRestResponse<Map<String, Object>>().data(body).rel(true);
    }

    /** 上传一片（原始字节）。重复上传同一片是覆盖，不会破坏已完成的分片。 */
    @PutMapping(value = "/upload/{uploadId}/{index}",
        consumes = MediaType.APPLICATION_OCTET_STREAM_VALUE)
    @RateLimit(limit = 1200, windowSeconds = 60, dimension = RateLimit.Dimension.USER)
    public ObjectRestResponse<Map<String, Object>> putChunk(@CurrentUser String username,
        @PathVariable String uploadId, @PathVariable int index,
        @RequestBody byte[] chunk) {
        requireValidId(uploadId);
        int received = mediaService.putChunk(username, uploadId, index, chunk);
        Map<String, Object> body = new LinkedHashMap<>(1);
        body.put("received", received);
        return new ObjectRestResponse<Map<String, Object>>().data(body).rel(true);
    }

    /** 查询进度：客户端断点续传时用来知道「还缺哪几片」。 */
    @GetMapping("/upload/{uploadId}")
    @RateLimit(limit = 120, windowSeconds = 60, dimension = RateLimit.Dimension.USER)
    public ObjectRestResponse<Map<String, Object>> uploadStatus(@CurrentUser String username,
        @PathVariable String uploadId) {
        requireValidId(uploadId);
        MediaService.UploadStatus status = mediaService.uploadStatus(username, uploadId);
        Map<String, Object> body = new LinkedHashMap<>(3);
        body.put("chunkSize", status.chunkSize());
        body.put("totalChunks", status.totalChunks());
        body.put("received", status.received());
        return new ObjectRestResponse<Map<String, Object>>().data(body).rel(true);
    }

    /** 分片齐全后合并成正式密文，返回媒体 id。 */
    @PostMapping("/upload/{uploadId}/complete")
    @RateLimit(limit = 10, windowSeconds = 60, dimension = RateLimit.Dimension.USER)
    public ObjectRestResponse<Map<String, Object>> completeUpload(@CurrentUser String username,
        @PathVariable String uploadId) {
        requireValidId(uploadId);
        MediaObject object = mediaService.completeUpload(username, uploadId);
        Map<String, Object> body = new LinkedHashMap<>(2);
        body.put("id", object.getId());
        body.put("size", object.getSize());
        return new ObjectRestResponse<Map<String, Object>>().data(body).rel(true);
    }

    /** 放弃这次上传：删掉已传的分片，不留半成品。 */
    @DeleteMapping("/upload/{uploadId}")
    @RateLimit(limit = 30, windowSeconds = 60, dimension = RateLimit.Dimension.USER)
    public ObjectRestResponse<Boolean> abortUpload(@CurrentUser String username,
        @PathVariable String uploadId) {
        requireValidId(uploadId);
        return new ObjectRestResponse<Boolean>()
            .data(mediaService.abortUpload(username, uploadId))
            .rel(true);
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
