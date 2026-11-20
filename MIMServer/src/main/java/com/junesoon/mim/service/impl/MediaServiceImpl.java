package com.junesoon.mim.service.impl;

import com.junesoon.mim.entity.MediaObject;
import com.junesoon.mim.exception.UserInvalidException;
import com.junesoon.mim.mapper.MediaMapper;
import com.junesoon.mim.service.MediaService;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.security.SecureRandom;
import java.util.Base64;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 加密媒体落盘实现。
 *
 * <p>写入用「临时文件 + 原子移动」，避免下载到写了一半的文件。
 * 记录落库失败时删掉文件，磁盘与数据库不会各留一半。
 */
@Slf4j
@Service
public class MediaServiceImpl implements MediaService {

    private static final int ID_BYTES = 32;

    private final MediaMapper mediaMapper;
    private final Path root;
    private final long maxBytes;
    private final SecureRandom random = new SecureRandom();

    public MediaServiceImpl(
        MediaMapper mediaMapper,
        @Value("${mim.media.dir:data/media}") String dir,
        @Value("${mim.media.max-bytes:" + (8 * 1024 * 1024) + "}") long maxBytes) {
        this.mediaMapper = mediaMapper;
        this.root = Paths.get(dir).toAbsolutePath().normalize();
        this.maxBytes = maxBytes;
        try {
            Files.createDirectories(root);
        } catch (IOException e) {
            throw new IllegalStateException("无法创建媒体目录：" + root, e);
        }
        log.info("加密媒体目录：{}（单文件上限 {} 字节）", root, maxBytes);
    }

    @Override
    public MediaObject save(String owner, byte[] encrypted) {
        if (encrypted == null || encrypted.length == 0) {
            throw new UserInvalidException("媒体内容为空");
        }
        if (encrypted.length > maxBytes) {
            throw new UserInvalidException("媒体文件过大，上限 " + (maxBytes / 1024 / 1024) + "MB");
        }
        String id = newId();
        Path target = root.resolve(id);
        Path temp = root.resolve(id + ".part");
        try {
            Files.write(temp, encrypted);
            Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            deleteQuietly(temp);
            throw new IllegalStateException("媒体写入失败", e);
        }

        MediaObject object = new MediaObject();
        object.setId(id);
        object.setOwner(owner);
        object.setSize(encrypted.length);
        object.setCrtTime(System.currentTimeMillis());
        try {
            mediaMapper.insert(object);
        } catch (RuntimeException e) {
            deleteQuietly(target);
            throw e;
        }
        return object;
    }

    @Override
    public Path load(String id) {
        MediaObject object = mediaMapper.selectById(id);
        if (object == null) {
            return null;
        }
        Path path = root.resolve(object.getId());
        return Files.isRegularFile(path) ? path : null;
    }

    @Override
    public MediaObject metadata(String id) {
        return mediaMapper.selectById(id);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean delete(String owner, String id) {
        int affected = mediaMapper.deleteByIdAndOwner(id, owner);
        if (affected == 0) {
            return false;
        }
        deleteQuietly(root.resolve(id));
        return true;
    }

    private String newId() {
        byte[] bytes = new byte[ID_BYTES];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // 清理失败不影响主流程：下次巡检可以再删
        }
    }
}
