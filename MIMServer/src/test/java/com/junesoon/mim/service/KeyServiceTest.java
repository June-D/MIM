package com.junesoon.mim.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.junesoon.mim.entity.Device;
import com.junesoon.mim.entity.UserKey;
import com.junesoon.mim.exception.UserInvalidException;
import com.junesoon.mim.mapper.DeviceMapper;
import com.junesoon.mim.mapper.UserKeyMapper;
import com.junesoon.mim.requests.KeyCapabilities;
import com.junesoon.mim.responses.KeyBundle;
import com.junesoon.mim.service.impl.KeyServiceImpl;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.spec.ECGenParameterSpec;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 公钥目录测试。
 *
 * <p>核心是版本语义：同一把公钥重复上传必须幂等（不涨版本），
 * 换钥才涨版本。版本被用于消息的密钥一致性校验，乱涨会让消息被误拒。
 */
class KeyServiceTest {

    private static final class InMemoryUserKeyMapper implements UserKeyMapper {
        final Map<String, UserKey> keys = new HashMap<>();

        private static String key(String username, String deviceId) {
            return username + "@" + deviceId;
        }

        @Override
        public UserKey selectByUsername(String username) {
            return keys.get(key(username, UserKey.DEFAULT_DEVICE_ID));
        }

        @Override
        public UserKey selectByDevice(String username, String deviceId) {
            return keys.get(key(username, deviceId));
        }

        @Override
        public List<UserKey> selectAllByUsername(String username) {
            List<UserKey> result = new java.util.ArrayList<>();
            for (UserKey k : keys.values()) {
                if (k.getUsername().equals(username)) {
                    result.add(k);
                }
            }
            return result;
        }

        @Override
        public int upsert(UserKey userKey) {
            keys.put(key(userKey.getUsername(), userKey.getDeviceId()), userKey);
            return 1;
        }
    }

    /**
     * 只提供"设备已注册"判定的桩。
     */
    private static final class InMemoryDeviceMapper implements DeviceMapper {
        final java.util.Set<String> registered = new java.util.HashSet<>();

        private static String key(String username, String deviceId) {
            return username + "@" + deviceId;
        }

        void register(String username, String deviceId) {
            registered.add(key(username, deviceId));
        }

        @Override
        public int upsert(Device device) {
            registered.add(key(device.getUsername(), device.getDeviceId()));
            return 1;
        }

        @Override
        public Device selectOne(String username, String deviceId) {
            if (!registered.contains(key(username, deviceId))) {
                return null;
            }
            Device device = new Device();
            device.setUsername(username);
            device.setDeviceId(deviceId);
            device.setRevoked(false);
            return device;
        }

        @Override
        public List<Device> selectActiveDevices(String username) {
            return java.util.Collections.emptyList();
        }

        @Override
        public int touch(String username, String deviceId, long lastSeen) {
            return 0;
        }

        @Override
        public int revoke(String username, String deviceId) {
            return 0;
        }

        @Override
        public int bumpTokenVersion(String username, String deviceId) {
            return registered.contains(key(username, deviceId)) ? 1 : 0;
        }

        @Override
        public int bumpAllTokenVersions(String username) {
            return registered.size();
        }

        @Override
        public int countActiveDevices(String username) {
            return 0;
        }
    }

    private InMemoryUserKeyMapper mapper;
    private InMemoryDeviceMapper deviceMapper;
    private KeyServiceImpl service;

    private static final String DEVICE = "device-1";

    @BeforeEach
    void setUp() {
        mapper = new InMemoryUserKeyMapper();
        deviceMapper = new InMemoryDeviceMapper();
        service = new KeyServiceImpl(mapper, deviceMapper,
            (username, password) -> "pw".equals(password));
        // 默认给 alice/bob 都注册好设备，让上传能通过
        deviceMapper.register("alice", DEVICE);
        deviceMapper.register("bob", DEVICE);
        deviceMapper.register("nobody", DEVICE);
        deviceMapper.register("a b", DEVICE);
    }

    /**
     * 生成真实的 P-256 公钥，让曲线校验有意义。
     */
    private static String realP256PublicKey() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec("secp256r1"));
        KeyPair pair = generator.generateKeyPair();
        return Base64.getEncoder().encodeToString(pair.getPublic().getEncoded());
    }

    @Test
    @DisplayName("首次上传公钥，版本为 1，指纹为 SHA-256")
    void firstUpload() throws Exception {
        String publicKey = realP256PublicKey();
        KeyBundle bundle = service.upload("alice", DEVICE, publicKey, "EC-P256", "pw");
        assertEquals("alice", bundle.getUsername());
        assertEquals(1, bundle.getVersion());
        assertEquals(publicKey, bundle.getPublicKey());
        assertEquals(64, bundle.getFingerprint().length(), "SHA-256 十六进制应为 64 位");
    }

    @Test
    @DisplayName("重复上传同一把公钥是幂等的，版本不增长")
    void idempotentUpload() throws Exception {
        String publicKey = realP256PublicKey();
        service.upload("alice", DEVICE, publicKey, "EC-P256", "pw");
        KeyBundle second = service.upload("alice", DEVICE, publicKey, "EC-P256", "pw");
        assertEquals(1, second.getVersion(), "同一指纹不应涨版本");
    }

    @Test
    @DisplayName("能力位随公钥上传保存并在返回包里可见")
    void capabilityRoundTrip() throws Exception {
        KeyBundle bundle = service.upload("alice", DEVICE, realP256PublicKey(), "EC-P256", "pw",
            KeyCapabilities.of(Boolean.TRUE, Boolean.TRUE, Boolean.TRUE));
        assertTrue(bundle.getSupportsPadding());
        assertTrue(bundle.getSupportsRecallToken(),
            "跨设备撤回能力必须回传给客户端，否则同步副本永远不带凭证");
        assertTrue(bundle.getSupportsSenderCert(),
            "证书能力必须回传，否则首条消息永远走明文发送者格式");
        assertTrue(service.get("alice", DEVICE).getSupportsRecallToken());
    }

    @Test
    @DisplayName("旧客户端重登未上报能力位时降级为 false，且不涨版本")
    void capabilitiesDowngradeWhenNotDeclared() throws Exception {
        String key = realP256PublicKey();
        service.upload("alice", DEVICE, key, "EC-P256", "pw",
            KeyCapabilities.of(Boolean.TRUE, Boolean.TRUE, Boolean.TRUE));
        KeyBundle legacy = service.upload("alice", DEVICE, key, "EC-P256", "pw");
        assertFalse(Boolean.TRUE.equals(legacy.getSupportsRecallToken()));
        assertFalse(Boolean.TRUE.equals(legacy.getSupportsPadding()));
        assertEquals(1, legacy.getVersion(), "能力变化不是换钥，不应涨版本");
    }

    @Test
    @DisplayName("修改能力位需要验证当前密码")
    void capabilityChangeRequiresPassword() throws Exception {
        String key = realP256PublicKey();
        service.upload("alice", DEVICE, key, "EC-P256", "pw",
            KeyCapabilities.of(Boolean.FALSE, Boolean.FALSE, Boolean.FALSE));
        assertThrows(UserInvalidException.class,
            () -> service.upload("alice", DEVICE, key, "EC-P256", "wrong",
                KeyCapabilities.of(Boolean.TRUE, Boolean.TRUE, Boolean.TRUE)),
            "仅凭被盗 JWT 不能把设备标成支持新能力");
    }

    @Test
    @DisplayName("上传不同公钥时版本自增")
    void keyRotationBumpsVersion() throws Exception {
        service.upload("alice", DEVICE, realP256PublicKey(), "EC-P256", "pw");
        KeyBundle rotated = service.upload("alice", DEVICE, realP256PublicKey(), "EC-P256", "pw");
        assertEquals(2, rotated.getVersion());
        KeyBundle again = service.upload("alice", DEVICE, realP256PublicKey(), "EC-P256", "pw");
        assertEquals(3, again.getVersion());
    }

    @Test
    @DisplayName("同一公钥的指纹稳定")
    void fingerprintStable() throws Exception {
        String publicKey = realP256PublicKey();
        service.upload("alice", DEVICE, publicKey, "EC-P256", "pw");
        KeyBundle a = service.get("alice", DEVICE);
        service.upload("bob", DEVICE, publicKey, "EC-P256", "pw");
        KeyBundle b = service.get("bob", DEVICE);
        assertEquals(a.getFingerprint(), b.getFingerprint(), "相同公钥指纹必须一致");
    }

    @Test
    @DisplayName("查询未建立加密身份的用户给出明确提示")
    void unknownUser() {
        UserInvalidException ex = assertThrows(UserInvalidException.class,
            () -> service.get("nobody", DEVICE));
        assertTrue(ex.getMessage().contains("尚未建立加密身份"));
    }

    @Test
    @DisplayName("拒绝非 P-256 曲线（例如 P-384）")
    void rejectWrongCurve() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec("secp384r1"));
        String publicKey = Base64.getEncoder().encodeToString(
            generator.generateKeyPair().getPublic().getEncoded());
        UserInvalidException ex = assertThrows(UserInvalidException.class,
            () -> service.upload("alice", DEVICE, publicKey, "EC-P256", "pw"));
        assertTrue(ex.getMessage().contains("P-256"), "实际：" + ex.getMessage());
    }

    @Test
    @DisplayName("拒绝非法 Base64 与空公钥")
    void rejectMalformedKey() {
        assertThrows(UserInvalidException.class,
            () -> service.upload("alice", DEVICE, "not-base64!!!", "EC-P256", "pw"));
        assertThrows(UserInvalidException.class,
            () -> service.upload("alice", DEVICE, null, "EC-P256", "pw"));
        assertThrows(UserInvalidException.class,
            () -> service.upload("alice", DEVICE, "", "EC-P256", "pw"));
    }

    @Test
    @DisplayName("拒绝不支持的算法标识")
    void rejectUnsupportedAlgorithm() throws Exception {
        String publicKey = realP256PublicKey();
        assertThrows(UserInvalidException.class,
            () -> service.upload("alice", DEVICE, publicKey, "RSA-2048", "pw"));
    }

    @Test
    @DisplayName("拒绝过短的公钥字节（防止塞入垃圾数据）")
    void rejectTooShortKey() {
        String shortKey = Base64.getEncoder().encodeToString(new byte[8]);
        assertThrows(UserInvalidException.class,
            () -> service.upload("alice", DEVICE, shortKey, "EC-P256", "pw"));
    }

    @Test
    @DisplayName("非法用户名被拒绝")
    void rejectInvalidUsername() throws Exception {
        String publicKey = realP256PublicKey();
        assertThrows(UserInvalidException.class,
            () -> service.upload("a b", DEVICE, publicKey, "EC-P256", "pw"));
    }

    @Test
    @DisplayName("更换身份公钥缺少正确密码被拒绝")
    void keyRotationRequiresPassword() throws Exception {
        service.upload("alice", DEVICE, realP256PublicKey(), "EC-P256", "pw");
        assertThrows(UserInvalidException.class,
            () -> service.upload("alice", DEVICE, realP256PublicKey(), "EC-P256", "wrong"));
        KeyBundle rotated =
            service.upload("alice", DEVICE, realP256PublicKey(), "EC-P256", "pw");
        assertEquals(2, rotated.getVersion());
    }

    @Test
    @DisplayName("上传后能查到自己的公钥")
    void getAfterUpload() throws Exception {
        service.upload("alice", DEVICE, realP256PublicKey(), "EC-P256", "pw");
        KeyBundle bundle = service.get("alice", DEVICE);
        assertNotNull(bundle);
        assertEquals("alice", bundle.getUsername());
    }
}
