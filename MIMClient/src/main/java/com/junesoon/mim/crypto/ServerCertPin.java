package com.junesoon.mim.crypto;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import lombok.extern.slf4j.Slf4j;

/**
 * 服务端发送者证书公钥的固定（pin）存储。
 *
 * <p>证书公钥是<b>公开</b>信息，不需要加密存储；但它必须固定：
 * 如果每次发消息都无条件采信服务端给的公钥，被攻破的服务端可以换成
 * 自己的密钥伪造任意发送者证书。
 *
 * <p>语义与 SSH known_hosts 相同：第一次自动固定，之后公钥变化一律
 * <b>保留旧值并告警</b>，新证书会因验签失败而被拒。安全失败优于静默信任。
 *
 * [INPUT]: 只依赖 JDK 文件 API
 * [OUTPUT]: resolve() 返回已固定的 keyId + publicKey；首次调用写入固定值
 * [POS]: SenderCertService 的信任锚；与对端 TrustStore 相互独立
 * [PROTOCOL]: Update this header on change, then check CLAUDE.md
 */
@Slf4j
public final class ServerCertPin {

    private static final String FILE_NAME = "server-cert.pub";

    private final Path file;

    private volatile String keyId;

    private volatile String publicKey;

    public ServerCertPin() {
        this(Paths.get(System.getProperty("user.home"), ".mim"));
    }

    public ServerCertPin(Path directory) {
        this.file = directory.resolve(FILE_NAME);
        load();
    }

    /**
     * 固定值（可能为 null，表示还没固定过）。
     */
    public synchronized Pin resolve(String fetchedKeyId, String fetchedPublicKey) {
        if (keyId != null && publicKey != null) {
            if (fetchedKeyId != null && !fetchedKeyId.equals(keyId)) {
                log.warn("服务端证书公钥发生变化（已固定 {}，当前 {}），保留旧值并拒绝新证书",
                    keyId, fetchedKeyId);
            }
            return new Pin(keyId, publicKey);
        }
        if (fetchedKeyId == null || fetchedPublicKey == null) {
            return null;
        }
        keyId = fetchedKeyId;
        publicKey = fetchedPublicKey;
        persist();
        log.info("已固定服务端发送者证书公钥 keyId={}", keyId);
        return new Pin(keyId, publicKey);
    }

    public String pinnedKeyId() {
        return keyId;
    }

    private void load() {
        try {
            if (!Files.exists(file)) {
                return;
            }
            String[] lines = new String(Files.readAllBytes(file), StandardCharsets.UTF_8)
                .split("\n");
            if (lines.length >= 2 && !lines[0].trim().isEmpty() && !lines[1].trim().isEmpty()) {
                keyId = lines[0].trim();
                publicKey = lines[1].trim();
            }
        } catch (IOException e) {
            // 读不到就当作未固定：下次会重新固定。文件损坏不应让客户端无法收消息。
            log.warn("读取服务端证书固定文件失败：{}", e.getMessage());
        }
    }

    private void persist() {
        try {
            Files.createDirectories(file.getParent());
            Files.write(file, (keyId + "\n" + publicKey + "\n").getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            log.warn("写入服务端证书固定文件失败：{}", e.getMessage());
        }
    }

    /**
     * 固定结果。
     */
    public static final class Pin {
        private final String keyId;
        private final String publicKey;

        public Pin(String keyId, String publicKey) {
            this.keyId = keyId;
            this.publicKey = publicKey;
        }

        public String getKeyId() {
            return keyId;
        }

        public String getPublicKey() {
            return publicKey;
        }
    }
}
