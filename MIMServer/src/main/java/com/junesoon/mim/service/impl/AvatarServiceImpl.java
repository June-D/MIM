package com.junesoon.mim.service.impl;

import com.junesoon.mim.entity.Avatar;
import com.junesoon.mim.exception.UserInvalidException;
import com.junesoon.mim.mapper.AvatarMapper;
import com.junesoon.mim.service.AvatarService;
import com.junesoon.mim.utils.ValidationUtil;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * 头像落盘：一个账号一份 PNG，元数据单独成表。
 *
 * <p>只接受 PNG 魔数：客户端上传前会把图片重新编码成 PNG，这既统一了格式，
 * 也顺手丢掉 EXIF（相机型号、GPS 坐标之类的元数据不该跟着头像进服务器）。
 */
@Slf4j
@Service
public class AvatarServiceImpl implements AvatarService {

    private static final byte[] PNG_MAGIC = {
        (byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A,
    };

    private final AvatarMapper avatarMapper;
    private final Path root;

    public AvatarServiceImpl(AvatarMapper avatarMapper,
        @Value("${mim.avatar.dir:data/avatar}") String dir) {
        this.avatarMapper = avatarMapper;
        this.root = Paths.get(dir).toAbsolutePath().normalize();
        try {
            Files.createDirectories(root);
        } catch (IOException e) {
            throw new IllegalStateException("无法创建头像目录：" + root, e);
        }
        log.info("头像目录：{}（单文件上限 {} 字节）", root, AvatarService.MAX_BYTES);
    }

    @Override
    public int save(String username, byte[] png) {
        requireValidUsername(username);
        if (png == null || png.length == 0) {
            throw new UserInvalidException("头像内容为空");
        }
        if (png.length > AvatarService.MAX_BYTES) {
            throw new UserInvalidException(
                "头像不能超过 " + (AvatarService.MAX_BYTES / 1024) + "KB");
        }
        if (!hasPngMagic(png)) {
            throw new UserInvalidException("头像必须是 PNG 图片");
        }
        Path file = fileOf(username);
        try {
            Files.createDirectories(root);
            // 先写临时文件再原子改名：中途失败不会留下半张图。
            Path temp = Files.createTempFile(root, "avatar-", ".part");
            Files.write(temp, png);
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new IllegalStateException("头像保存失败", e);
        }
        Avatar existing = avatarMapper.select(username);
        int version = (existing == null ? 0 : existing.getVersion()) + 1;
        avatarMapper.upsert(username, version, System.currentTimeMillis());
        return version;
    }

    @Override
    public byte[] load(String username) {
        if (!ValidationUtil.isUsername(username)) {
            return null;
        }
        Path file = fileOf(username);
        if (!Files.exists(file)) {
            return null;
        }
        try {
            return Files.readAllBytes(file);
        } catch (IOException e) {
            log.warn("读取头像失败：{}（{}）", username, e.getMessage());
            return null;
        }
    }

    @Override
    public Avatar metadata(String username) {
        return ValidationUtil.isUsername(username) ? avatarMapper.select(username) : null;
    }

    @Override
    public boolean delete(String username) {
        requireValidUsername(username);
        boolean removedFile;
        try {
            removedFile = Files.deleteIfExists(fileOf(username));
        } catch (IOException e) {
            throw new IllegalStateException("头像删除失败", e);
        }
        avatarMapper.delete(username);
        return removedFile;
    }

    private Path fileOf(String username) {
        // 用户名已在注册时限制字符集；这里再挡一次，避免任何路径拼接风险。
        Path path = root.resolve(username + ".png").normalize();
        if (!path.startsWith(root)) {
            throw new UserInvalidException("用户名不合法");
        }
        return path;
    }

    private static void requireValidUsername(String username) {
        if (!ValidationUtil.isUsername(username)) {
            throw new UserInvalidException("用户名不合法");
        }
    }

    private static boolean hasPngMagic(byte[] bytes) {
        if (bytes.length < PNG_MAGIC.length) {
            return false;
        }
        for (int i = 0; i < PNG_MAGIC.length; i++) {
            if (bytes[i] != PNG_MAGIC[i]) {
                return false;
            }
        }
        return true;
    }
}
