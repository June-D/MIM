package com.junesoon.mim.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.junesoon.mim.entity.UserKey;
import com.junesoon.mim.exception.UserInvalidException;
import com.junesoon.mim.mapper.UserKeyMapper;
import com.junesoon.mim.responses.SenderCertKey;
import com.junesoon.mim.responses.SenderCertificate;
import com.junesoon.mim.service.impl.SenderCertServiceImpl;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 发送者证书签发与验签测试（Phase 2a）。
 *
 * <p>证书是首条消息归属的唯一依据，因此重点验证：签名覆盖全部字段、
 * 篡改必须失败、有效期正确、功能关闭时完全不产生密钥。
 *
 * [INPUT]: 只依赖 SenderCertServiceImpl 与内存版密钥/公钥存储
 * [OUTPUT]: 验证签发字段、签名可验、篡改拒绝、关闭开关与密钥复用
 * [POS]: 2a 的单元测试；不启动 Spring，也不连数据库
 * [PROTOCOL]: Update this header on change, then check CLAUDE.md
 */
class SenderCertServiceTest {

    private static final String ALICE = "alice";
    private static final String DEVICE = "alice-dev-a";

    /** 内存版密钥库，记录写入次数以验证密钥只生成一次。 */
    private static final class MemoryRsaKeyService implements RsaKeyService {
        final Map<String, String> values = new HashMap<>();
        int writes;

        @Override
        public boolean hasKey(String key) {
            return values.containsKey(key);
        }

        @Override
        public String get(String key) {
            return values.getOrDefault(key, "");
        }

        @Override
        public int set(String key, String value) {
            values.put(key, value);
            writes++;
            return 1;
        }

        @Override
        public boolean isEncrypted(String key) {
            return false;
        }

        @Override
        public boolean rotateEncryption(String key) {
            return false;
        }
    }

    private static final class MemoryUserKeyMapper implements UserKeyMapper {
        final List<UserKey> keys = new ArrayList<>();

        @Override
        public UserKey selectByUsername(String username) {
            return null;
        }

        @Override
        public UserKey selectByDevice(String username, String deviceId) {
            for (UserKey key : keys) {
                if (key.getUsername().equals(username) && key.getDeviceId().equals(deviceId)) {
                    return key;
                }
            }
            return null;
        }

        @Override
        public List<UserKey> selectAllByUsername(String username) {
            return keys;
        }

        @Override
        public int upsert(UserKey key) {
            keys.add(key);
            return 1;
        }
    }

    private MemoryRsaKeyService rsaKeys;
    private MemoryUserKeyMapper userKeys;
    private SenderCertServiceImpl service;

    @BeforeEach
    void setUp() throws Exception {
        rsaKeys = new MemoryRsaKeyService();
        userKeys = new MemoryUserKeyMapper();
        UserKey identity = new UserKey();
        identity.setUsername(ALICE);
        identity.setDeviceId(DEVICE);
        identity.setPublicKey(Base64.getEncoder().encodeToString(new byte[] {1, 2, 3, 4}));
        userKeys.upsert(identity);

        service = new SenderCertServiceImpl(rsaKeys, userKeys);
        setField(service, "enabled", true);
    }

    @Test
    @DisplayName("功能关闭时拒绝签发，也不生成密钥")
    void disabledWritesNothing() throws Exception {
        setField(service, "enabled", false);
        UserInvalidException ex = assertThrows(UserInvalidException.class,
            () -> service.issue(ALICE, DEVICE));
        assertTrue(ex.getMessage().contains("未启用"));
        assertEquals(0, rsaKeys.writes, "关闭状态不应产生任何密钥行");
    }

    @Test
    @DisplayName("证书签名可用分发的公钥验证，keyId 与公钥一致")
    void signatureVerifiesWithPublishedKey() throws Exception {
        SenderCertificate certificate = service.issue(ALICE, DEVICE);
        SenderCertKey key = service.key();
        assertEquals(key.getKeyId(), certificate.getKeyId());
        assertEquals(sha256Hex(Base64.getDecoder().decode(key.getPublicKey())),
            key.getKeyId(), "keyId 必须是公钥 DER 的 SHA-256");
        assertTrue(verify(certificate, key.getPublicKey()), "签发结果必须能验签通过");
        assertEquals(ALICE, certificate.getUsername());
        assertEquals(DEVICE, certificate.getDeviceId());
        assertEquals(24L * 60 * 60 * 1000,
            certificate.getExpiresAt() - certificate.getIssuedAt(), "有效期应为 24 小时");
    }

    @Test
    @DisplayName("篡改任一签名字段都会验签失败")
    void tamperedFieldsRejected() throws Exception {
        SenderCertificate certificate = service.issue(ALICE, DEVICE);
        String publicKey = service.key().getPublicKey();

        certificate.setUsername("bob");
        assertFalse(verify(certificate, publicKey), "换用户名必须失败");

        certificate.setUsername(ALICE);
        certificate.setIdentityKey("AAAA");
        assertFalse(verify(certificate, publicKey), "换身份公钥必须失败");

        certificate.setIdentityKey(userKeys.keys.get(0).getPublicKey());
        certificate.setExpiresAt(certificate.getExpiresAt() + 1);
        assertFalse(verify(certificate, publicKey), "改有效期必须失败");
    }

    @Test
    @DisplayName("密钥只生成一次，重复签发复用同一把")
    void keysGeneratedOnce() {
        service.key();
        service.issue(ALICE, DEVICE);
        service.issue(ALICE, DEVICE);
        assertEquals(2, rsaKeys.writes, "一对密钥只应写两行（公钥 + 私钥）");
    }

    @Test
    @DisplayName("设备未上传身份公钥时拒绝签发")
    void unknownDeviceRejected() {
        UserInvalidException ex = assertThrows(UserInvalidException.class,
            () -> service.issue(ALICE, "missing-device"));
        assertTrue(ex.getMessage().contains("加密公钥"));
    }

    private static boolean verify(SenderCertificate certificate, String publicKeyBase64)
        throws Exception {
        PublicKey publicKey = KeyFactory.getInstance("RSA")
            .generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(publicKeyBase64)));
        Signature verifier = Signature.getInstance("SHA256withRSA");
        verifier.initVerify(publicKey);
        verifier.update(certificate.canonical().getBytes(StandardCharsets.UTF_8));
        return verifier.verify(Base64.getDecoder().decode(certificate.getSignature()));
    }

    private static String sha256Hex(byte[] value) throws Exception {
        byte[] hash = MessageDigest.getInstance("SHA-256").digest(value);
        StringBuilder hex = new StringBuilder(hash.length * 2);
        for (byte b : hash) {
            hex.append(String.format("%02x", b & 0xff));
        }
        return hex.toString();
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        java.lang.reflect.Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }
}
