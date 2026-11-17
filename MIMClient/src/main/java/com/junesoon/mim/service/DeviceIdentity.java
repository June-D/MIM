package com.junesoon.mim.service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.SecureRandom;
import java.util.Base64;
import lombok.extern.slf4j.Slf4j;

/**
 * 本机设备标识（按账号隔离）。
 *
 * <p>设备标识由客户端生成并持久化，服务端只登记。每个与加密相关的请求都要带上它，
 * 因为<b>密钥是按设备划分的</b>。
 *
 * <p>关键设计：<b>设备标识与账号绑定</b>，不是"一台机器一个"。
 * 同一个人在同一台机器上用两个账号登录，是两个独立设备——
 * 它们有不同的身份密钥、预密钥与棘轮链。若全机共用一个 device_id，
 * 切换账号后新账号会顶掉旧账号的设备记录，导致历史消息指向已被覆盖的密钥。
 *
 * <p>用随机串而非 MAC/机器码：后者会泄漏硬件信息，也可能被当作跨设备追踪的指纹。
 */
@Slf4j
public class DeviceIdentity {

    private static final String DIR_NAME = "devices";
    private static final int ID_BYTES = 16;

    private final Path directory;

    /**
     * 当前账号的设备标识缓存。切换账号时必须用 {@link #reset()} 清掉，
     * 否则会继续沿用上一个账号的标识。
     */
    private volatile String deviceId;

    private volatile String currentUsername;

    public DeviceIdentity() {
        this(Paths.get(System.getProperty("user.home"), ".mim"));
    }

    public DeviceIdentity(Path directory) {
        this.directory = directory;
    }

    /**
     * 取指定账号在本机的设备标识，不存在则生成并落盘。
     *
     * @param username 当前登录账号。为 null 时退化为按"未登录"分区，
     *                 仅用于登录前的占位调用。
     */
    public synchronized String getOrCreate(String username) {
        String owner = username == null ? "anonymous" : username;
        if (deviceId != null && owner.equals(currentUsername)) {
            return deviceId;
        }
        currentUsername = owner;
        deviceId = loadOrCreate(owner);
        return deviceId;
    }

    /**
     * 重置缓存。切换账号时调用，强制下次按新账号读取标识。
     */
    public synchronized void reset() {
        deviceId = null;
        currentUsername = null;
    }

    /**
     * 当前会话使用的设备标识，未初始化时返回 null。
     */
    public String current() {
        return deviceId;
    }

    /**
     * 设备名，用于服务端设备列表展示。缺省用平台信息。
     */
    public String deviceName(String username) {
        String os = System.getProperty("os.name", "Unknown");
        String id = getOrCreate(username);
        String suffix = id.length() > 6 ? id.substring(0, 6) : id;
        String base = os.length() > 40 ? os.substring(0, 40) : os;
        return base + " (" + suffix + ")";
    }

    // ==================== 内部 ====================

    private String loadOrCreate(String owner) {
        Path file = fileOf(owner);
        try {
            if (Files.exists(file)) {
                String existing = new String(Files.readAllBytes(file), StandardCharsets.UTF_8).trim();
                if (existing.matches("^[A-Za-z0-9._-]{1,64}$")) {
                    return existing;
                }
                log.warn("设备标识格式损坏，将重新生成：{}", file);
            }
            String generated = generate();
            Files.createDirectories(directory.resolve(DIR_NAME));
            Files.write(file, generated.getBytes(StandardCharsets.UTF_8));
            restrictPermissions(file);
            log.info("为账号 {} 生成新设备标识：{}", owner, generated);
            return generated;
        } catch (IOException e) {
            // 落盘失败时退化为会话内临时标识：重启后会变成"新设备"，
            // 但不阻断使用，且日志能说明原因。
            log.warn("无法持久化设备标识（{}），本次会话使用临时标识", e.getMessage());
            return generate();
        }
    }

    private Path fileOf(String owner) {
        String safe = owner.replaceAll("[^A-Za-z0-9_.\\-\\p{L}\\p{N}]", "_");
        return directory.resolve(DIR_NAME).resolve(safe + ".id");
    }

    private String generate() {
        byte[] bytes = new byte[ID_BYTES];
        new SecureRandom().nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private void restrictPermissions(Path file) {
        try {
            Files.setPosixFilePermissions(file, new java.util.HashSet<>(java.util.Arrays.asList(
                java.nio.file.attribute.PosixFilePermission.OWNER_READ,
                java.nio.file.attribute.PosixFilePermission.OWNER_WRITE)));
        } catch (Exception e) {
            log.debug("无法设置设备标识文件权限：{}", e.getMessage());
        }
    }
}
