package com.junesoon.mim.service;

import com.junesoon.mim.components.ApplicatonStore;
import com.junesoon.mim.components.ObjectRestResponse;
import com.junesoon.mim.components.Request;
import com.junesoon.mim.crypto.IdentityException;
import com.junesoon.mim.feign.LoginFeign;
import com.junesoon.mim.model.PasswordChangeRequest;
import com.junesoon.mim.model.PasswordChangeResult;
import com.junesoon.mim.utils.EncryptUtil;
import com.junesoon.mim.websocket.WSClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.stream.Stream;
import lombok.extern.slf4j.Slf4j;

/**
 * 修改登录密码。
 *
 * <p>服务端保存的是 Base64 口令的 BCrypt；本地密钥文件则用原始登录口令派生
 * PBKDF2 密钥加密。因此改密必须同时做两件事：
 * <ol>
 *   <li>调用服务端更新 BCrypt，并让当前设备拿到新 JWT；</li>
 *   <li>用新口令重新加密本机身份、预密钥、会话、信任库、明文缓存。</li>
 * </ol>
 *
 * <p>改密前会把 {@code ~/.mim} 备份到 {@code ~/.mim-backup-<时间戳>}，
 * 防止本地重加密中途失败导致历史消息永久不可读。
 */
@Slf4j
public final class PasswordChangeService {

    private PasswordChangeService() {
    }

    public static void change(String oldPassword, String newPassword) throws IdentityException {
        if (ApplicatonStore.getUsername() == null) {
            throw new IdentityException("当前未登录");
        }
        Path backup = backupMimDirectory();

        ObjectRestResponse<PasswordChangeResult> response;
        try {
            response = Request.connector(LoginFeign.class).changePassword(
                new PasswordChangeRequest(EncryptUtil.base64Encode(oldPassword),
                    EncryptUtil.base64Encode(newPassword)));
        } catch (Exception e) {
            throw new IdentityException("修改密码失败：" + e.getMessage());
        }
        if (response == null || response.getStatus() != 200 || response.getData() == null
            || response.getData().getToken() == null) {
            String error = response == null ? "服务端无响应" : response.getMessage();
            throw new IdentityException(error == null ? "修改密码失败" : error);
        }

        List<String> failures = new ArrayList<>();
        try {
            MessageService.getInstance().changePassword(oldPassword, newPassword);
        } catch (Exception e) {
            failures.add("会话/明文：" + e.getMessage());
        }
        try {
            PreKeyManager.getInstance().changePassword(oldPassword, newPassword);
        } catch (Exception e) {
            failures.add("预密钥：" + e.getMessage());
        }
        try {
            KeyManager.getInstance().changePassword(newPassword);
        } catch (Exception e) {
            failures.add("身份/信任库：" + e.getMessage());
        }

        ApplicatonStore.setToken(response.getData().getToken());
        ApplicatonStore.setSessionPassword(newPassword.toCharArray());
        try {
            WSClient.discard();
            WSClient.getInstance().connectWithToken();
        } catch (Exception e) {
            log.warn("改密后重连 WebSocket 失败：{}", e.getMessage());
        }
        if (!failures.isEmpty()) {
            throw new IdentityException("服务端密码已更新，但本地部分文件重加密失败："
                + String.join("；", failures)
                + "。旧口令备份位于：" + (backup == null ? "无" : backup.toString()));
        }
        log.info("登录密码已更新，本地密钥已用新口令重新加密");
    }

    /**
     * 改密前备份整个 {@code ~/.mim}。失败时拒绝改密，避免无法回滚。
     */
    private static Path backupMimDirectory() throws IdentityException {
        Path source = Paths.get(System.getProperty("user.home"), ".mim");
        if (!Files.isDirectory(source)) {
            return null;
        }
        Path target = Paths.get(System.getProperty("user.home"),
            ".mim-backup-" + System.currentTimeMillis());
        try (Stream<Path> stream = Files.walk(source)) {
            Iterator<Path> iterator = stream.iterator();
            while (iterator.hasNext()) {
                Path path = iterator.next();
                Path destination = target.resolve(source.relativize(path).toString());
                if (Files.isDirectory(path)) {
                    Files.createDirectories(destination);
                } else {
                    Path parent = destination.getParent();
                    if (parent != null) {
                        Files.createDirectories(parent);
                    }
                    Files.copy(path, destination, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        } catch (Exception e) {
            throw new IdentityException("无法备份本地密钥，已取消改密：" + e.getMessage());
        }
        return target;
    }
}
