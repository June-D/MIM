package com.junesoon.mim.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.junesoon.mim.entity.Device;
import com.junesoon.mim.entity.OneTimePreKey;
import com.junesoon.mim.entity.SignedPreKey;
import com.junesoon.mim.entity.UserKey;
import com.junesoon.mim.exception.UserInvalidException;
import com.junesoon.mim.mapper.DeviceMapper;
import com.junesoon.mim.mapper.PreKeyMapper;
import com.junesoon.mim.mapper.UserKeyMapper;
import com.junesoon.mim.service.impl.PreKeyServiceImpl;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 预密钥包服务测试（多设备）。
 *
 * <p>重点验证两件事：
 * <ol>
 *   <li><b>一次性语义</b>：同一个 OPK 绝不能被两个发起方拿到；</li>
 *   <li><b>按设备分发</b>：给多设备用户发消息，每个设备各自一个包、各自一个 OPK。</li>
 * </ol>
 */
class PreKeyServiceTest {

    private static final String BOB = "bob";
    private static final String DEVICE_A = "device-a";
    private static final String DEVICE_B = "device-b";

    /**
     * 内存版 PreKeyMapper，模拟唯一约束与删除语义。
     */
    private static final class InMemoryPreKeyMapper implements PreKeyMapper {
        final Map<String, SignedPreKey> signed = new HashMap<>();
        final Map<String, List<OneTimePreKey>> oneTime = new HashMap<>();
        final AtomicLong sequence = new AtomicLong();

        private static String key(String username, String deviceId) {
            return username + "@" + deviceId;
        }

        @Override
        public SignedPreKey selectSignedPreKey(String username, String deviceId) {
            return signed.get(key(username, deviceId));
        }

        @Override
        public int upsertSignedPreKey(SignedPreKey preKey) {
            signed.put(key(preKey.getUsername(), preKey.getDeviceId()), preKey);
            return 1;
        }

        @Override
        public int insertOneTimePreKey(OneTimePreKey preKey) {
            return batchInsertOneTimePreKeys(java.util.Collections.singletonList(preKey));
        }

        @Override
        public int batchInsertOneTimePreKeys(List<OneTimePreKey> keys) {
            int inserted = 0;
            for (OneTimePreKey preKey : keys) {
                List<OneTimePreKey> list = oneTime.computeIfAbsent(
                    key(preKey.getUsername(), preKey.getDeviceId()), k -> new ArrayList<>());
                boolean duplicated = list.stream()
                    .anyMatch(existing -> existing.getPreKeyId().equals(preKey.getPreKeyId()));
                if (duplicated) {
                    continue;
                }
                preKey.setId(sequence.incrementAndGet());
                list.add(preKey);
                inserted++;
            }
            return inserted;
        }

        @Override
        public OneTimePreKey selectOneTimePreKeyForUpdate(String username, String deviceId) {
            List<OneTimePreKey> list = oneTime.get(key(username, deviceId));
            if (list == null || list.isEmpty()) {
                return null;
            }
            return list.get(0);
        }

        @Override
        public int deleteOneTimePreKey(Long id, String username) {
            for (List<OneTimePreKey> list : oneTime.values()) {
                if (list.removeIf(preKey -> preKey.getId().equals(id))) {
                    return 1;
                }
            }
            return 0;
        }

        @Override
        public int countOneTimePreKeys(String username, String deviceId) {
            List<OneTimePreKey> list = oneTime.get(key(username, deviceId));
            return list == null ? 0 : list.size();
        }

        @Override
        public int deleteAllOneTimePreKeys(String username, String deviceId) {
            List<OneTimePreKey> removed = oneTime.remove(key(username, deviceId));
            return removed == null ? 0 : removed.size();
        }
    }

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
            List<UserKey> result = new ArrayList<>();
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

    private static final class InMemoryDeviceMapper implements DeviceMapper {
        final Map<String, Device> devices = new HashMap<>();

        private static String key(String username, String deviceId) {
            return username + "@" + deviceId;
        }

        @Override
        public int upsert(Device device) {
            devices.put(key(device.getUsername(), device.getDeviceId()), device);
            return 1;
        }

        @Override
        public Device selectOne(String username, String deviceId) {
            return devices.get(key(username, deviceId));
        }

        @Override
        public List<Device> selectActiveDevices(String username) {
            List<Device> result = new ArrayList<>();
            for (Device d : devices.values()) {
                if (d.getUsername().equals(username) && !Boolean.TRUE.equals(d.getRevoked())) {
                    result.add(d);
                }
            }
            return result;
        }

        @Override
        public int touch(String username, String deviceId, long lastSeen) {
            Device device = devices.get(key(username, deviceId));
            if (device == null) {
                return 0;
            }
            device.setLastSeen(lastSeen);
            return 1;
        }

        @Override
        public int revoke(String username, String deviceId) {
            Device device = devices.get(key(username, deviceId));
            if (device == null) {
                return 0;
            }
            device.setRevoked(true);
            return 1;
        }

        @Override
        public int countActiveDevices(String username) {
            int count = 0;
            for (Device d : devices.values()) {
                if (d.getUsername().equals(username) && !Boolean.TRUE.equals(d.getRevoked())) {
                    count++;
                }
            }
            return count;
        }
    }

    private InMemoryPreKeyMapper preKeyMapper;
    private InMemoryUserKeyMapper userKeyMapper;
    private InMemoryDeviceMapper deviceMapper;
    private PreKeyServiceImpl service;

    private static String fakePublicKey() {
        byte[] bytes = new byte[65];
        bytes[0] = 0x04;
        new java.security.SecureRandom().nextBytes(bytes);
        return Base64.getEncoder().encodeToString(bytes);
    }

    private void registerDevice(String username, String deviceId) {
        Device device = new Device();
        device.setUsername(username);
        device.setDeviceId(deviceId);
        device.setCrtTime(System.currentTimeMillis());
        device.setLastSeen(System.currentTimeMillis());
        device.setRevoked(false);
        deviceMapper.upsert(device);

        UserKey key = new UserKey();
        key.setUsername(username);
        key.setDeviceId(deviceId);
        key.setPublicKey(fakePublicKey());
        key.setAlgorithm("EC-P256");
        key.setVersion(1);
        key.setFingerprint("FP");
        userKeyMapper.upsert(key);
    }

    private SignedPreKey signedRequest(int id) {
        SignedPreKey key = new SignedPreKey();
        key.setPreKeyId(id);
        key.setPublicKey(fakePublicKey());
        key.setSignature(Base64.getEncoder().encodeToString(new byte[64]));
        return key;
    }

    private List<OneTimePreKey> oneTimeRequest(int startId, int count) {
        List<OneTimePreKey> keys = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            OneTimePreKey key = new OneTimePreKey();
            key.setPreKeyId(startId + i);
            key.setPublicKey(fakePublicKey());
            keys.add(key);
        }
        return keys;
    }

    private void publish(String username, String deviceId, int opkCount) {
        service.uploadSignedPreKey(username, deviceId, signedRequest(1));
        if (opkCount > 0) {
            service.uploadOneTimePreKeys(username, deviceId, oneTimeRequest(1, opkCount));
        }
    }

    @BeforeEach
    void setUp() {
        preKeyMapper = new InMemoryPreKeyMapper();
        userKeyMapper = new InMemoryUserKeyMapper();
        deviceMapper = new InMemoryDeviceMapper();
        service = new PreKeyServiceImpl(preKeyMapper, userKeyMapper, deviceMapper);
        registerDevice(BOB, DEVICE_A);
    }

    // ==================== 一次性语义 ====================

    @Test
    @DisplayName("拉取预密钥包会消耗一个一次性预密钥")
    void fetchConsumesOneTimePreKey() {
        publish(BOB, DEVICE_A, 5);
        assertEquals(5, service.countOneTimePreKeys(BOB, DEVICE_A));

        List<PreKeyService.PreKeyBundle> bundles = service.fetchBundles(BOB);
        assertEquals(1, bundles.size());
        assertTrue(bundles.get(0).hasOneTimePreKey());
        assertEquals(4, service.countOneTimePreKeys(BOB, DEVICE_A));
    }

    @Test
    @DisplayName("同一个一次性预密钥不会被发放两次")
    void oneTimePreKeyNeverReused() {
        publish(BOB, DEVICE_A, 3);
        Integer first = service.fetchBundles(BOB).get(0).getOneTimePreKeyId();
        Integer second = service.fetchBundles(BOB).get(0).getOneTimePreKeyId();
        Integer third = service.fetchBundles(BOB).get(0).getOneTimePreKeyId();
        Integer fourth = service.fetchBundles(BOB).get(0).getOneTimePreKeyId();

        assertNotNull(first);
        assertNotNull(second);
        assertNotNull(third);
        assertFalse(first.equals(second));
        assertFalse(second.equals(third));
        assertFalse(first.equals(third));
        assertNull(fourth, "耗尽后应返回 null 而不是重复发放");
    }

    @Test
    @DisplayName("一次性预密钥耗尽后仍能拿到签名预密钥（省略 DH4）")
    void bundleStillAvailableWithoutOneTimePreKey() {
        publish(BOB, DEVICE_A, 0);
        List<PreKeyService.PreKeyBundle> bundles = service.fetchBundles(BOB);
        assertEquals(1, bundles.size());
        assertNotNull(bundles.get(0).getSignedPreKeyPublic());
        assertFalse(bundles.get(0).hasOneTimePreKey());
    }

    @Test
    @DisplayName("重复上传相同编号的一次性预密钥被忽略")
    void duplicateOneTimePreKeysIgnored() {
        service.uploadSignedPreKey(BOB, DEVICE_A, signedRequest(1));
        assertEquals(3, service.uploadOneTimePreKeys(BOB, DEVICE_A, oneTimeRequest(1, 3)));
        assertEquals(0, service.uploadOneTimePreKeys(BOB, DEVICE_A, oneTimeRequest(1, 3)));
        assertEquals(3, service.countOneTimePreKeys(BOB, DEVICE_A));
    }

    // ==================== 多设备分发 ====================

    @Test
    @DisplayName("多设备用户会拿到每个设备各一个包")
    void multiDeviceBundles() {
        registerDevice(BOB, DEVICE_B);
        publish(BOB, DEVICE_A, 3);
        publish(BOB, DEVICE_B, 3);

        List<PreKeyService.PreKeyBundle> bundles = service.fetchBundles(BOB);
        assertEquals(2, bundles.size(), "两个设备应返回两个包");

        java.util.Set<String> devices = new java.util.HashSet<>();
        java.util.Set<Integer> opkIds = new java.util.HashSet<>();
        for (PreKeyService.PreKeyBundle bundle : bundles) {
            devices.add(bundle.getDeviceId());
            assertTrue(bundle.hasOneTimePreKey(), "每个设备都应各有自己的 OPK");
            opkIds.add(bundle.getOneTimePreKeyId());
        }
        assertEquals(2, devices.size());
        for (PreKeyService.PreKeyBundle bundle : bundles) {
            assertTrue(bundle.hasOneTimePreKey(),
                "每个设备都应从自己的池里领到 OPK：" + bundle.getDeviceId());
        }
        // 各设备独立扣减
        assertEquals(2, service.countOneTimePreKeys(BOB, DEVICE_A));
        assertEquals(2, service.countOneTimePreKeys(BOB, DEVICE_B));
    }

    @Test
    @DisplayName("未发布预密钥的设备被跳过，不影响其它设备")
    void deviceWithoutPreKeySkipped() {
        registerDevice(BOB, DEVICE_B);
        publish(BOB, DEVICE_A, 3);
        // DEVICE_B 只注册设备，不发布预密钥

        List<PreKeyService.PreKeyBundle> bundles = service.fetchBundles(BOB);
        assertEquals(1, bundles.size());
        assertEquals(DEVICE_A, bundles.get(0).getDeviceId());
    }

    @Test
    @DisplayName("已注销设备不再出现在预密钥分发里")
    void revokedDeviceExcluded() {
        registerDevice(BOB, DEVICE_B);
        publish(BOB, DEVICE_A, 3);
        publish(BOB, DEVICE_B, 3);
        assertEquals(2, service.fetchBundles(BOB).size());

        deviceMapper.revoke(BOB, DEVICE_B);
        List<PreKeyService.PreKeyBundle> bundles = service.fetchBundles(BOB);
        assertEquals(1, bundles.size());
        assertEquals(DEVICE_A, bundles.get(0).getDeviceId());
    }

    @Test
    @DisplayName("没有任何设备的用户无法被拉取")
    void noDeviceRejected() {
        UserInvalidException ex = assertThrows(UserInvalidException.class,
            () -> service.fetchBundles("nobody"));
        assertTrue(ex.getMessage().contains("尚未注册任何设备"));
    }

    // ==================== 结构校验 ====================

    @Test
    @DisplayName("未注册设备不能上传预密钥")
    void unregisteredDeviceRejected() {
        UserInvalidException ex = assertThrows(UserInvalidException.class,
            () -> service.uploadSignedPreKey(BOB, "ghost-device", signedRequest(1)));
        assertTrue(ex.getMessage().contains("设备未注册"));
    }

    @Test
    @DisplayName("设备没有身份公钥时不能发布预密钥")
    void deviceWithoutIdentityRejected() {
        Device device = new Device();
        device.setUsername(BOB);
        device.setDeviceId("device-no-key");
        device.setCrtTime(System.currentTimeMillis());
        device.setLastSeen(System.currentTimeMillis());
        device.setRevoked(false);
        deviceMapper.upsert(device);

        assertThrows(UserInvalidException.class,
            () -> service.uploadSignedPreKey(BOB, "device-no-key", signedRequest(1)));
    }

    @Test
    @DisplayName("非法设备标识被拒绝")
    void invalidDeviceIdRejected() {
        assertThrows(UserInvalidException.class,
            () -> service.uploadSignedPreKey(BOB, "../../etc", signedRequest(1)));
        assertThrows(UserInvalidException.class,
            () -> service.countOneTimePreKeys(BOB, "bad id with space"));
    }

    @Test
    @DisplayName("预密钥编号非法时拒绝")
    void invalidPreKeyIdRejected() {
        assertThrows(UserInvalidException.class,
            () -> service.uploadSignedPreKey(BOB, DEVICE_A, signedRequest(0)));
    }

    @Test
    @DisplayName("缺少签名时拒绝")
    void missingSignatureRejected() {
        SignedPreKey request = signedRequest(1);
        request.setSignature(null);
        assertThrows(UserInvalidException.class,
            () -> service.uploadSignedPreKey(BOB, DEVICE_A, request));
    }

    @Test
    @DisplayName("非 Base64 公钥被拒绝")
    void invalidBase64Rejected() {
        SignedPreKey request = signedRequest(1);
        request.setPublicKey("not-base64!!!");
        assertThrows(UserInvalidException.class,
            () -> service.uploadSignedPreKey(BOB, DEVICE_A, request));
    }

    @Test
    @DisplayName("单次上传超过 200 个一次性预密钥被拒绝")
    void tooManyOneTimePreKeysRejected() {
        assertThrows(UserInvalidException.class,
            () -> service.uploadOneTimePreKeys(BOB, DEVICE_A, oneTimeRequest(1, 201)));
    }

    @Test
    @DisplayName("签名预密钥是覆盖语义，不会累积多把")
    void signedPreKeyUpsert() {
        service.uploadSignedPreKey(BOB, DEVICE_A, signedRequest(1));
        service.uploadSignedPreKey(BOB, DEVICE_A, signedRequest(2));
        assertEquals(2, preKeyMapper.selectSignedPreKey(BOB, DEVICE_A).getPreKeyId());
    }
}
