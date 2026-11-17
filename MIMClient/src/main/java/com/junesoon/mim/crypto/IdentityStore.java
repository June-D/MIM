package com.junesoon.mim.crypto;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.PosixFilePermission;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashSet;
import java.util.Set;
import javax.crypto.Cipher;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

/**
 * 本地身份密钥库。
 *
 * <p>私钥只存在于客户端磁盘，且<b>以用户口令派生的密钥加密后落盘</b>
 * （PBKDF2-HMAC-SHA256 + AES-256-GCM）。即使本地文件被拷走，
 * 没有登录口令也无法解出私钥；服务器侧永远拿不到私钥。
 *
 * <p>文件格式（每行一个 key=value，便于人工排查，但不含明文密钥）：
 * <pre>
 * username=alice
 * version=1
 * salt=&lt;base64&gt;
 * nonce=&lt;base64&gt;
 * privateKey=&lt;base64(AES-GCM 加密后的 PKCS8)&gt;
 * publicKey=&lt;base64(X.509)&gt;
 * fingerprint=&lt;hex&gt;
 * </pre>
 */
@Slf4j
public class IdentityStore {

    private static final String ALGORITHM = "EC";
    private static final String CURVE = "secp256r1";
    private static final int PBKDF2_ITERATIONS = 120_000;
    private static final int PBKDF2_KEY_BITS = 256;
    private static final int GCM_NONCE_BYTES = 12;
    private static final int GCM_TAG_BITS = 128;
    private static final int SALT_BYTES = 16;

    private static final SecureRandom RANDOM = new SecureRandom();

    /**
     * 缺省设备标识，兼容单设备时期的本地文件。
     */
    public static final String DEFAULT_DEVICE_ID = "default";

    /**
     * 解密后的身份。私钥仅驻留内存，退出即丢。
     */
    @Getter
    public static final class Identity {
        private final String username;
        private final int version;
        private final PrivateKey privateKey;
        private final PublicKey publicKey;
        private final String publicKeyBase64;
        private final String fingerprint;

        Identity(String username, int version, PrivateKey privateKey, PublicKey publicKey,
            String publicKeyBase64, String fingerprint) {
            this.username = username;
            this.version = version;
            this.privateKey = privateKey;
            this.publicKey = publicKey;
            this.publicKeyBase64 = publicKeyBase64;
            this.fingerprint = fingerprint;
        }
    }

    private final Path directory;

    public IdentityStore() {
        this(Paths.get(System.getProperty("user.home"), ".mim", "identity"));
    }

    public IdentityStore(Path directory) {
        this.directory = directory;
    }

    /**
     * 加载已存在的身份。口令错误或文件损坏时抛出异常，调用方需明确告知用户，
     * 不能静默换钥（换钥会导致历史消息永久无法解密）。
     */
    public Identity load(String username, String password) throws IdentityException {
        return load(username, DEFAULT_DEVICE_ID, password);
    }

    /**
     * 按设备加载身份私钥。同一账号在不同设备上各有独立密钥。
     */
    public Identity load(String username, String deviceId, String password) throws IdentityException {
        Path file = fileOf(username, deviceId);
        if (!Files.exists(file)) {
            return null;
        }
        try {
            String content = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
            String saltB64 = readField(content, "salt");
            String nonceB64 = readField(content, "nonce");
            String encryptedKeyB64 = readField(content, "privateKey");
            String publicKeyB64 = readField(content, "publicKey");
            int version = Integer.parseInt(readField(content, "version"));

            byte[] salt = Base64.getDecoder().decode(saltB64);
            byte[] nonce = Base64.getDecoder().decode(nonceB64);
            byte[] encryptedKey = Base64.getDecoder().decode(encryptedKeyB64);

            byte[] wrappingKey = deriveWrappingKey(password, salt);
            byte[] pkcs8;
            try {
                Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
                cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(wrappingKey, "AES"),
                    new GCMParameterSpec(GCM_TAG_BITS, nonce));
                // 与 create() 保持一致：用户名作为 AAD，防止密文被跨账号搬运
                cipher.updateAAD(aad(username, deviceId));
                pkcs8 = cipher.doFinal(encryptedKey);
            } catch (Exception e) {
                throw new IdentityException("本地身份密钥解锁失败，可能是登录口令已变更");
            } finally {
                Hkdf.wipe(wrappingKey);
            }

            KeyFactory keyFactory = KeyFactory.getInstance(ALGORITHM);
            PrivateKey privateKey = keyFactory.generatePrivate(new PKCS8EncodedKeySpec(pkcs8));
            PublicKey publicKey = keyFactory.generatePublic(
                new X509EncodedKeySpec(Base64.getDecoder().decode(publicKeyB64)));
            String fingerprint = Hkdf.sha256Hex(Base64.getDecoder().decode(publicKeyB64));
            Hkdf.wipe(pkcs8);
            return new Identity(username, version, privateKey, publicKey, publicKeyB64, fingerprint);
        } catch (IdentityException e) {
            throw e;
        } catch (Exception e) {
            throw new IdentityException("读取本地身份密钥失败： " + e.getMessage());
        }
    }

    /**
     * 生成新的身份密钥对并加密落盘。
     */
    public Identity create(String username, String password, int version) throws IdentityException {
        return create(username, DEFAULT_DEVICE_ID, password, version);
    }

    /**
     * 为指定设备生成身份密钥对。AAD 绑定用户名与设备，
     * 防止密钥文件被跨设备搬运。
     */
    public Identity create(String username, String deviceId, String password, int version)
        throws IdentityException {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance(ALGORITHM);
            generator.initialize(new ECGenParameterSpec(CURVE), RANDOM);
            KeyPair keyPair = generator.generateKeyPair();

            byte[] pkcs8 = keyPair.getPrivate().getEncoded();
            String publicKeyB64 = Base64.getEncoder().encodeToString(keyPair.getPublic().getEncoded());

            byte[] salt = new byte[SALT_BYTES];
            RANDOM.nextBytes(salt);
            byte[] nonce = new byte[GCM_NONCE_BYTES];
            RANDOM.nextBytes(nonce);

            byte[] wrappingKey = deriveWrappingKey(password, salt);
            byte[] encrypted;
            try {
                Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
                cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(wrappingKey, "AES"),
                    new GCMParameterSpec(GCM_TAG_BITS, nonce));
                cipher.updateAAD(aad(username, deviceId));
                encrypted = cipher.doFinal(pkcs8);
            } finally {
                Hkdf.wipe(wrappingKey);
            }
            Hkdf.wipe(pkcs8);

            String content = "username=" + username + "\n"
                + "device=" + deviceId + "\n"
                + "version=" + version + "\n"
                + "salt=" + Base64.getEncoder().encodeToString(salt) + "\n"
                + "nonce=" + Base64.getEncoder().encodeToString(nonce) + "\n"
                + "privateKey=" + Base64.getEncoder().encodeToString(encrypted) + "\n"
                + "publicKey=" + publicKeyB64 + "\n"
                + "fingerprint=" + Hkdf.sha256Hex(Base64.getDecoder().decode(publicKeyB64)) + "\n";

            Files.createDirectories(directory);
            Path file = fileOf(username, deviceId);
            // 原子写：先落临时文件、设好 0600，再 rename。
            // 直接写目标文件时，崩溃可能留下半个密钥文件，且权限收紧前有窗口期。
            Path temp = Files.createTempFile(directory, "identity-", ".tmp");
            Files.write(temp, content.getBytes(StandardCharsets.UTF_8));
            restrictPermissions(temp);
            Files.move(temp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                java.nio.file.StandardCopyOption.ATOMIC_MOVE);

            return new Identity(username, version, keyPair.getPrivate(), keyPair.getPublic(),
                publicKeyB64, Hkdf.sha256Hex(Base64.getDecoder().decode(publicKeyB64)));
        } catch (Exception e) {
            throw new IdentityException("生成身份密钥失败： " + e.getMessage());
        }
    }

    /**
     * 用新口令重新加密并保存已有身份。私钥本身不变，只更换本地密封口令。
     */
    public void saveExisting(Identity identity, String username, String deviceId, String password)
        throws IdentityException {
        if (identity == null || identity.getPrivateKey() == null) {
            throw new IdentityException("本地身份密钥为空，无法重新加密");
        }
        try {
            byte[] pkcs8 = identity.getPrivateKey().getEncoded();
            byte[] salt = new byte[SALT_BYTES];
            RANDOM.nextBytes(salt);
            byte[] nonce = new byte[GCM_NONCE_BYTES];
            RANDOM.nextBytes(nonce);

            byte[] wrappingKey = deriveWrappingKey(password, salt);
            byte[] encrypted;
            try {
                Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
                cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(wrappingKey, "AES"),
                    new GCMParameterSpec(GCM_TAG_BITS, nonce));
                cipher.updateAAD(aad(username, deviceId));
                encrypted = cipher.doFinal(pkcs8);
            } finally {
                Hkdf.wipe(wrappingKey);
                Hkdf.wipe(pkcs8);
            }

            String content = "username=" + username + "\n"
                + "device=" + deviceId + "\n"
                + "version=" + identity.getVersion() + "\n"
                + "salt=" + Base64.getEncoder().encodeToString(salt) + "\n"
                + "nonce=" + Base64.getEncoder().encodeToString(nonce) + "\n"
                + "privateKey=" + Base64.getEncoder().encodeToString(encrypted) + "\n"
                + "publicKey=" + identity.getPublicKeyBase64() + "\n"
                + "fingerprint=" + identity.getFingerprint() + "\n";

            Files.createDirectories(directory);
            Path file = fileOf(username, deviceId);
            Path temp = Files.createTempFile(directory, "identity-", ".tmp");
            Files.write(temp, content.getBytes(StandardCharsets.UTF_8));
            restrictPermissions(temp);
            Files.move(temp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        } catch (Exception e) {
            throw new IdentityException("重新加密本地身份密钥失败：" + e.getMessage());
        }
    }

    /**
     * 服务器上已有更高版本的公钥时，说明用户在其他设备换过钥。
     * 这里只做提示，不自动覆盖本地私钥。
     */
    public boolean isStale(Identity identity, int serverVersion) {
        return identity != null && serverVersion > identity.getVersion();
    }

    private Path fileOf(String username, String deviceId) {
        // 用户名与设备标识已通过服务端校验，这里再做一次防御，避免路径穿越
        String safe = sanitize(username);
        String device = sanitize(deviceId);
        return directory.resolve(safe + "__" + device + ".key");
    }

    private String sanitize(String value) {
        return value == null ? "unknown"
            : value.replaceAll("[^A-Za-z0-9_.\\-\\p{L}\\p{N}]", "_");
    }

    /**
     * AAD 绑定「哪个用户的哪个设备」。
     */
    private byte[] aad(String username, String deviceId) {
        String device = deviceId == null || deviceId.isEmpty() ? DEFAULT_DEVICE_ID : deviceId;
        return (username + "|" + device).getBytes(StandardCharsets.UTF_8);
    }

    private void restrictPermissions(Path file) {
        FilePermissions.ownerOnly(file);
    }

    private byte[] deriveWrappingKey(String password, byte[] salt) throws Exception {
        PBEKeySpec spec = new PBEKeySpec(password.toCharArray(), salt, PBKDF2_ITERATIONS, PBKDF2_KEY_BITS);
        try {
            SecretKeyFactory factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
            return factory.generateSecret(spec).getEncoded();
        } finally {
            spec.clearPassword();
        }
    }

    private String readField(String content, String key) throws IdentityException {
        String prefix = key + "=";
        for (String line : content.split("\n")) {
            if (line.startsWith(prefix)) {
                return line.substring(prefix.length()).trim();
            }
        }
        throw new IdentityException("本地身份文件缺少字段： " + key);
    }

    public static File[] listFiles(Path directory) {
        File dir = directory.toFile();
        File[] files = dir.listFiles();
        return files == null ? new File[0] : files;
    }
}
