package com.junesoon.mim.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.junesoon.mim.entity.CipherMessage;
import com.junesoon.mim.entity.Device;
import com.junesoon.mim.entity.OneTimePreKey;
import com.junesoon.mim.entity.SignedPreKey;
import com.junesoon.mim.entity.UserKey;
import com.junesoon.mim.mapper.DeviceMapper;
import com.junesoon.mim.mapper.MessageMapper;
import com.junesoon.mim.mapper.PreKeyMapper;
import com.junesoon.mim.mapper.UserKeyMapper;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Mapper 集成测试：验证 MyBatis XML 在真实 MySQL 上的行为。
 *
 * <p>这层能抓到单元测试抓不到的问题：
 * <ul>
 *   <li>XML 里列名拼错、字段与 resultMap 不匹配；</li>
 *   <li>{@code ON DUPLICATE KEY UPDATE} / {@code INSERT IGNORE} 的真实语义；</li>
 *   <li>{@code FOR UPDATE} 在 InnoDB 下的加锁行为；</li>
 *   <li>去重子查询与分页游标的配合；</li>
 *   <li>唯一索引是否真的生效。</li>
 * </ul>
 */
class MapperIntegrationTest extends IntegrationTestBase {

    private static final Database DB = prepare("mapper");

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        register(registry, DB);
    }

    @BeforeAll
    static void requireDatabase() {
        requireReady(DB);
    }

    @Override
    protected Database database() {
        return DB;
    }

    private static final String ALICE = "alice";
    private static final String BOB = "bob";
    private static final String DEVICE_A = "dev-a";
    private static final String DEVICE_B = "dev-b";

    @Autowired
    private UserKeyMapper userKeyMapper;

    @Autowired
    private PreKeyMapper preKeyMapper;

    @Autowired
    private DeviceMapper deviceMapper;

    @Autowired
    private MessageMapper messageMapper;

    @BeforeEach
    void setUp() throws Exception {
        truncateAll();
    }

    // ==================== 设备 ====================

    private void registerDevice(String username, String deviceId) {
        Device device = new Device();
        device.setUsername(username);
        device.setDeviceId(deviceId);
        device.setDeviceName("测试设备 " + deviceId);
        device.setCrtTime(System.currentTimeMillis());
        device.setLastSeen(System.currentTimeMillis());
        device.setRevoked(false);
        deviceMapper.upsert(device);
    }

    @Test
    @DisplayName("设备注册是幂等的，重复注册只刷新活跃时间")
    void deviceUpsertIdempotent() {
        registerDevice(BOB, DEVICE_A);
        long firstSeen = deviceMapper.selectOne(BOB, DEVICE_A).getLastSeen();

        Device again = new Device();
        again.setUsername(BOB);
        again.setDeviceId(DEVICE_A);
        again.setDeviceName("改名后的设备");
        again.setCrtTime(System.currentTimeMillis());
        again.setLastSeen(firstSeen + 5000);
        again.setRevoked(false);
        deviceMapper.upsert(again);

        Device stored = deviceMapper.selectOne(BOB, DEVICE_A);
        assertEquals(1, deviceMapper.countActiveDevices(BOB), "不应产生第二行");
        assertEquals("改名后的设备", stored.getDeviceName());
        assertEquals(firstSeen + 5000, stored.getLastSeen(), "last_seen 应被更新");
    }

    @Test
    @DisplayName("重新注册会把已注销设备恢复为有效")
    void reRegisterRevivedDevice() {
        registerDevice(BOB, DEVICE_A);
        deviceMapper.revoke(BOB, DEVICE_A);
        assertEquals(0, deviceMapper.countActiveDevices(BOB));

        registerDevice(BOB, DEVICE_A);
        assertEquals(1, deviceMapper.countActiveDevices(BOB), "重新注册应解除注销");
        assertFalse(deviceMapper.selectOne(BOB, DEVICE_A).getRevoked());
    }

    @Test
    @DisplayName("selectActiveDevices 只返回未注销设备")
    void activeDevicesExcludeRevoked() {
        registerDevice(BOB, DEVICE_A);
        registerDevice(BOB, DEVICE_B);
        deviceMapper.revoke(BOB, DEVICE_B);

        List<Device> devices = deviceMapper.selectActiveDevices(BOB);
        assertEquals(1, devices.size());
        assertEquals(DEVICE_A, devices.get(0).getDeviceId());
    }

    // ==================== 公钥 ====================

    private UserKey userKey(String username, String deviceId, String publicKey) {
        UserKey key = new UserKey();
        key.setUsername(username);
        key.setDeviceId(deviceId);
        key.setPublicKey(publicKey);
        key.setAlgorithm("EC-P256");
        key.setVersion(1);
        key.setFingerprint("FP-" + publicKey);
        key.setCrtTime(new Date());
        key.setUpdTime(new Date());
        return key;
    }

    @Test
    @DisplayName("公钥按 (username, device) 唯一：同一设备换钥是更新而非新增")
    void userKeyUpsertByDevice() {
        // selectAllByUsername 会 JOIN t_device，因此必须先注册设备
        registerDevice(BOB, DEVICE_A);
        userKeyMapper.upsert(userKey(BOB, DEVICE_A, "key-v1"));
        userKeyMapper.upsert(userKey(BOB, DEVICE_A, "key-v2"));

        UserKey stored = userKeyMapper.selectByDevice(BOB, DEVICE_A);
        assertEquals("key-v2", stored.getPublicKey(), "ON DUPLICATE KEY UPDATE 应覆盖");
        assertEquals(1, userKeyMapper.selectAllByUsername(BOB).size(), "不应产生第二行");
    }

    @Test
    @DisplayName("同一用户的不同设备各自保存公钥")
    void userKeyPerDevice() {
        registerDevice(BOB, DEVICE_A);
        registerDevice(BOB, DEVICE_B);
        userKeyMapper.upsert(userKey(BOB, DEVICE_A, "key-a"));
        userKeyMapper.upsert(userKey(BOB, DEVICE_B, "key-b"));

        assertEquals("key-a", userKeyMapper.selectByDevice(BOB, DEVICE_A).getPublicKey());
        assertEquals("key-b", userKeyMapper.selectByDevice(BOB, DEVICE_B).getPublicKey());
        assertEquals(2, userKeyMapper.selectAllByUsername(BOB).size());
    }

    @Test
    @DisplayName("selectAllByUsername 只返回未注销设备的公钥（JOIN t_device）")
    void selectAllExcludesRevokedDevices() {
        registerDevice(BOB, DEVICE_A);
        registerDevice(BOB, DEVICE_B);
        userKeyMapper.upsert(userKey(BOB, DEVICE_A, "key-a"));
        userKeyMapper.upsert(userKey(BOB, DEVICE_B, "key-b"));

        assertEquals(2, userKeyMapper.selectAllByUsername(BOB).size());

        deviceMapper.revoke(BOB, DEVICE_B);
        List<UserKey> keys = userKeyMapper.selectAllByUsername(BOB);
        assertEquals(1, keys.size(), "注销设备的公钥不应出现在分发列表里");
        assertEquals(DEVICE_A, keys.get(0).getDeviceId());
    }

    @Test
    @DisplayName("未注册设备即使有公钥也不会被分发（JOIN 保护）")
    void orphanKeyNotDistributed() {
        // 只写公钥不注册设备，模拟历史脏数据
        userKeyMapper.upsert(userKey(BOB, "ghost", "key-ghost"));
        assertTrue(userKeyMapper.selectAllByUsername(BOB).isEmpty(),
            "JOIN t_device 后孤儿公钥应被过滤");
    }

    // ==================== 预密钥 ====================

    private SignedPreKey signedPreKey(String username, String deviceId, int id) {
        SignedPreKey key = new SignedPreKey();
        key.setUsername(username);
        key.setDeviceId(deviceId);
        key.setPreKeyId(id);
        key.setPublicKey("spk-" + id);
        key.setSignature(Base64.getEncoder().encodeToString(new byte[64]));
        key.setCrtTime(System.currentTimeMillis());
        key.setUpdTime(System.currentTimeMillis());
        return key;
    }

    @Test
    @DisplayName("签名预密钥 upsert：同设备只保留一把")
    void signedPreKeyUpsert() {
        preKeyMapper.upsertSignedPreKey(signedPreKey(BOB, DEVICE_A, 1));
        preKeyMapper.upsertSignedPreKey(signedPreKey(BOB, DEVICE_A, 2));

        SignedPreKey stored = preKeyMapper.selectSignedPreKey(BOB, DEVICE_A);
        assertEquals(2, stored.getPreKeyId(), "应被覆盖为新编号");
    }

    @Test
    @DisplayName("一次性预密钥 INSERT IGNORE：重复编号被静默跳过，不报错")
    void oneTimePreKeyInsertIgnore() {
        List<OneTimePreKey> batch = new ArrayList<>();
        for (int i = 1; i <= 3; i++) {
            OneTimePreKey key = new OneTimePreKey();
            key.setUsername(BOB);
            key.setDeviceId(DEVICE_A);
            key.setPreKeyId(i);
            key.setPublicKey("opk-" + i);
            key.setCrtTime(System.currentTimeMillis());
            batch.add(key);
        }
        assertEquals(3, preKeyMapper.batchInsertOneTimePreKeys(batch));

        // 同一批再插一次，唯一索引应让它们被忽略
        List<OneTimePreKey> duplicate = new ArrayList<>();
        for (int i = 1; i <= 3; i++) {
            OneTimePreKey key = new OneTimePreKey();
            key.setUsername(BOB);
            key.setDeviceId(DEVICE_A);
            key.setPreKeyId(i);
            key.setPublicKey("opk-dup-" + i);
            key.setCrtTime(System.currentTimeMillis());
            duplicate.add(key);
        }
        assertEquals(0, preKeyMapper.batchInsertOneTimePreKeys(duplicate),
            "重复编号应返回 0 行受影响");
        assertEquals(3, preKeyMapper.countOneTimePreKeys(BOB, DEVICE_A));
    }

    @Test
    @DisplayName("一次性预密钥按设备隔离计数")
    void oneTimePreKeyPerDevice() {
        for (String device : new String[] {DEVICE_A, DEVICE_B}) {
            List<OneTimePreKey> batch = new ArrayList<>();
            for (int i = 1; i <= 2; i++) {
                OneTimePreKey key = new OneTimePreKey();
                key.setUsername(BOB);
                key.setDeviceId(device);
                key.setPreKeyId(i);
                key.setPublicKey(device + "-opk-" + i);
                key.setCrtTime(System.currentTimeMillis());
                batch.add(key);
            }
            preKeyMapper.batchInsertOneTimePreKeys(batch);
        }
        assertEquals(2, preKeyMapper.countOneTimePreKeys(BOB, DEVICE_A));
        assertEquals(2, preKeyMapper.countOneTimePreKeys(BOB, DEVICE_B));
    }

    @Test
    @DisplayName("selectOneTimePreKeyForUpdate 按 id 升序取第一条，删除后不再返回")
    void oneTimePreKeyClaimAndDelete() {
        List<OneTimePreKey> batch = new ArrayList<>();
        for (int i = 10; i <= 12; i++) {
            OneTimePreKey key = new OneTimePreKey();
            key.setUsername(BOB);
            key.setDeviceId(DEVICE_A);
            key.setPreKeyId(i);
            key.setPublicKey("opk-" + i);
            key.setCrtTime(System.currentTimeMillis());
            batch.add(key);
        }
        preKeyMapper.batchInsertOneTimePreKeys(batch);

        OneTimePreKey first = preKeyMapper.selectOneTimePreKeyForUpdate(BOB, DEVICE_A);
        assertNotNull(first);
        assertEquals(10, first.getPreKeyId(), "应按 id 升序取最早插入的");

        assertEquals(1, preKeyMapper.deleteOneTimePreKey(first.getId(), BOB));
        OneTimePreKey second = preKeyMapper.selectOneTimePreKeyForUpdate(BOB, DEVICE_A);
        assertEquals(11, second.getPreKeyId(), "删除后应取下一个");

        // 插入 3 个、领走 1 个，剩余 2 个
        assertEquals(2, preKeyMapper.countOneTimePreKeys(BOB, DEVICE_A));
    }

    @Test
    @DisplayName("一次性预密钥耗尽时返回 null 而非抛异常")
    void oneTimePreKeyExhausted() {
        assertNull(preKeyMapper.selectOneTimePreKeyForUpdate(BOB, DEVICE_A));
    }

    // ==================== 消息 ====================

    private CipherMessage message(String from, String fromDevice, String to, String targetDevice,
        String clientMsgId, int messageNumber) {
        CipherMessage message = new CipherMessage();
        message.setClientMsgId(clientMsgId);
        message.setFromUsername(from);
        message.setFromDeviceId(fromDevice);
        message.setToUsername(to);
        message.setTargetDeviceId(targetDevice);
        message.setAlg("X3DH-P256-DOUBLE-RATCHET-AES-256-GCM");
        message.setIv(Base64.getEncoder().encodeToString(new byte[12]));
        message.setCiphertext(Base64.getEncoder().encodeToString(
            ("cipher-" + clientMsgId + "-" + targetDevice).getBytes()));
        message.setRatchetPublic("ratchet-pub");
        message.setPreviousChainLength(0);
        message.setMessageNumber(messageNumber);
        message.setClientTime(System.currentTimeMillis());
        message.setServerTime(System.currentTimeMillis());
        message.setDelivered(false);
        message.setReadFlag(false);
        message.setRecalled(false);
        return message;
    }

    @Test
    @DisplayName("消息插入后可按 (from, clientMsgId, targetDevice) 精确查回")
    void insertAndSelectByClientMsgId() {
        CipherMessage msg = message(ALICE, DEVICE_A, BOB, DEVICE_B, "msg-0000001", 0);
        assertEquals(1, messageMapper.insert(msg));
        assertNotNull(msg.getId(), "自增主键应回填");

        CipherMessage found = messageMapper.selectByClientMsgId(ALICE, "msg-0000001", DEVICE_B);
        assertNotNull(found);
        assertEquals(BOB, found.getToUsername());
        assertEquals("ratchet-pub", found.getRatchetPublic());
        assertEquals(0, found.getMessageNumber());

        // 不同目标设备查不到
        assertNull(messageMapper.selectByClientMsgId(ALICE, "msg-0000001", DEVICE_A));
    }

    @Test
    @DisplayName("唯一索引阻止同一条消息对同一目标设备重复落库")
    void uniqueIndexPreventsDuplicateReplica() {
        messageMapper.insert(message(ALICE, DEVICE_A, BOB, DEVICE_B, "msg-dup-0001", 0));
        boolean rejected = false;
        try {
            messageMapper.insert(message(ALICE, DEVICE_A, BOB, DEVICE_B, "msg-dup-0001", 0));
        } catch (org.springframework.dao.DuplicateKeyException e) {
            rejected = true;
        }
        assertTrue(rejected, "唯一索引应拒绝重复副本，这是幂等的基础");
    }

    @Test
    @DisplayName("历史消息按 clientMsgId 去重，多设备副本只出现一次")
    void historyDeduplicatesReplicas() {
        // 同一条逻辑消息，发给 bob 的两台设备 = 两行
        messageMapper.insert(message(ALICE, DEVICE_A, BOB, DEVICE_A, "msg-multi-001", 0));
        messageMapper.insert(message(ALICE, DEVICE_A, BOB, DEVICE_B, "msg-multi-001", 0));
        // 第二条逻辑消息
        messageMapper.insert(message(ALICE, DEVICE_A, BOB, DEVICE_A, "msg-multi-002", 1));
        messageMapper.insert(message(ALICE, DEVICE_A, BOB, DEVICE_B, "msg-multi-002", 1));

        List<CipherMessage> history = messageMapper.selectHistory(ALICE, BOB, null, 50);
        assertEquals(2, history.size(), "两条逻辑消息应返回两行，实际：" + history.size());
        assertEquals("msg-multi-002", history.get(0).getClientMsgId(), "应按 id 倒序（新的在前）");
        assertEquals("msg-multi-001", history.get(1).getClientMsgId());
    }

    @Test
    @DisplayName("历史优先返回当前设备解得开的副本，而不是最早的那一份")
    void historyPrefersReplicaForThisDevice() {
        CipherMessage original = message(ALICE, DEVICE_A, BOB, DEVICE_A, "msg-sync-001", 0);
        CipherMessage copy = message(ALICE, DEVICE_A, BOB, DEVICE_B, "msg-sync-001", 0);
        copy.setSyncFromDevice(DEVICE_A);
        messageMapper.insert(original);
        messageMapper.insert(copy);

        List<CipherMessage> forNewDevice = messageMapper.selectHistoryForDevice(
            ALICE, BOB, DEVICE_B, null, 10);
        assertEquals(1, forNewDevice.size());
        assertEquals(DEVICE_B, forNewDevice.get(0).getTargetDeviceId());
        assertEquals(DEVICE_A, forNewDevice.get(0).getSyncFromDevice());

        List<CipherMessage> forOldDevice = messageMapper.selectHistoryForDevice(
            ALICE, BOB, DEVICE_A, null, 10);
        assertEquals(1, forOldDevice.size());
        assertEquals(DEVICE_A, forOldDevice.get(0).getTargetDeviceId());
        assertNull(forOldDevice.get(0).getSyncFromDevice());
    }

    @Test
    @DisplayName("历史消息的 beforeId 分页在去重后仍然正确")
    void historyPaginationWithDedup() {
        for (int i = 1; i <= 5; i++) {
            messageMapper.insert(message(ALICE, DEVICE_A, BOB, DEVICE_A,
                "msg-page-00" + i, i - 1));
        }
        List<CipherMessage> firstPage = messageMapper.selectHistory(ALICE, BOB, null, 2);
        assertEquals(2, firstPage.size());
        assertEquals("msg-page-005", firstPage.get(0).getClientMsgId());

        Long cursor = firstPage.get(1).getId();
        List<CipherMessage> secondPage = messageMapper.selectHistory(ALICE, BOB, cursor, 2);
        assertEquals(2, secondPage.size());
        assertEquals("msg-page-003", secondPage.get(0).getClientMsgId());
        assertFalse(secondPage.get(0).getClientMsgId()
            .equals(firstPage.get(0).getClientMsgId()), "分页不应重复上一页内容");
    }

    @Test
    @DisplayName("未投递消息按目标设备隔离")
    void undeliveredPerDevice() {
        messageMapper.insert(message(ALICE, DEVICE_A, BOB, DEVICE_A, "msg-inbox-001", 0));
        messageMapper.insert(message(ALICE, DEVICE_A, BOB, DEVICE_B, "msg-inbox-002", 0));

        List<CipherMessage> forA = messageMapper.selectUndelivered(BOB, DEVICE_A, 100);
        assertEquals(1, forA.size());
        assertEquals(DEVICE_A, forA.get(0).getTargetDeviceId());

        List<CipherMessage> forB = messageMapper.selectUndelivered(BOB, DEVICE_B, 100);
        assertEquals(1, forB.size());
        assertEquals(DEVICE_B, forB.get(0).getTargetDeviceId());
    }

    @Test
    @DisplayName("批量已投递只作用于指定设备")
    void markDeliveredUpToPerDevice() {
        messageMapper.insert(message(ALICE, DEVICE_A, BOB, DEVICE_A, "msg-dlv-001", 0));
        messageMapper.insert(message(ALICE, DEVICE_A, BOB, DEVICE_B, "msg-dlv-002", 0));

        int affected = messageMapper.markDeliveredUpTo(BOB, DEVICE_A, Long.MAX_VALUE);
        assertEquals(1, affected);
        assertTrue(messageMapper.selectUndelivered(BOB, DEVICE_A, 100).isEmpty());
        assertEquals(1, messageMapper.selectUndelivered(BOB, DEVICE_B, 100).size(),
            "另一台设备的队列不应被动过");
    }

    @Test
    @DisplayName("未读数按 clientMsgId 去重统计")
    void unreadCountsLogicalMessages() {
        // 一条逻辑消息发给两台设备
        messageMapper.insert(message(ALICE, DEVICE_A, BOB, DEVICE_A, "msg-unread-01", 0));
        messageMapper.insert(message(ALICE, DEVICE_A, BOB, DEVICE_B, "msg-unread-01", 0));
        // 第二条逻辑消息
        messageMapper.insert(message(ALICE, DEVICE_A, BOB, DEVICE_A, "msg-unread-02", 1));
        messageMapper.insert(message(ALICE, DEVICE_A, BOB, DEVICE_B, "msg-unread-02", 1));

        assertEquals(2L, messageMapper.countUnread(ALICE, BOB),
            "应统计 2 条逻辑消息，而非 4 条设备副本");
    }

    @Test
    @DisplayName("已读标记跳过已撤回消息")
    void markReadSkipsRecalled() {
        messageMapper.insert(message(ALICE, DEVICE_A, BOB, DEVICE_A, "msg-read-001", 0));
        messageMapper.insert(message(ALICE, DEVICE_A, BOB, DEVICE_A, "msg-read-002", 1));
        messageMapper.recall("msg-read-002", ALICE, System.currentTimeMillis());

        int affected = messageMapper.markRead(ALICE, BOB, Long.MAX_VALUE);
        assertEquals(1, affected, "已撤回消息不应被标记为已读");
    }

    @Test
    @DisplayName("撤回按 clientMsgId 一次清掉所有设备副本的密文")
    void recallClearsAllReplicas() {
        messageMapper.insert(message(ALICE, DEVICE_A, BOB, DEVICE_A, "msg-recall-01", 0));
        messageMapper.insert(message(ALICE, DEVICE_A, BOB, DEVICE_B, "msg-recall-01", 0));

        int affected = messageMapper.recall("msg-recall-01", ALICE, System.currentTimeMillis());
        assertEquals(2, affected, "两台设备的副本都应被撤回");

        List<CipherMessage> history = messageMapper.selectHistory(ALICE, BOB, null, 50);
        assertEquals(1, history.size());
        assertTrue(history.get(0).getRecalled());
        assertNull(history.get(0).getCiphertext(), "密文应被物理清除");
        assertNull(history.get(0).getIv(), "IV 应被清除");
    }

    @Test
    @DisplayName("非发信人无法撤回（SQL 条件保证）")
    void recallRequiresSender() {
        messageMapper.insert(message(ALICE, DEVICE_A, BOB, DEVICE_A, "msg-recall-02", 0));
        int affected = messageMapper.recall("msg-recall-02", BOB, System.currentTimeMillis());
        assertEquals(0, affected, "from_user 条件应阻止他人撤回");
        assertFalse(messageMapper.selectById(
            messageMapper.selectAnyByClientMsgId(ALICE, "msg-recall-02").getId()).getRecalled());
    }

    @Test
    @DisplayName("重复撤回返回 0 行（recalled = 0 条件保证幂等）")
    void recallIsIdempotent() {
        messageMapper.insert(message(ALICE, DEVICE_A, BOB, DEVICE_A, "msg-recall-03", 0));
        assertEquals(1, messageMapper.recall("msg-recall-03", ALICE, System.currentTimeMillis()));
        assertEquals(0, messageMapper.recall("msg-recall-03", ALICE, System.currentTimeMillis()));
    }

    @Test
    @DisplayName("selectAnyByClientMsgId 不限设备取任意副本")
    void selectAnyCopy() {
        messageMapper.insert(message(ALICE, DEVICE_A, BOB, DEVICE_B, "msg-any-0001", 0));
        CipherMessage any = messageMapper.selectAnyByClientMsgId(ALICE, "msg-any-0001");
        assertNotNull(any, "应能取到非 default 设备的副本");
        assertEquals(DEVICE_B, any.getTargetDeviceId());
    }

    @Test
    @DisplayName("X3DH 握手参数完整往返（长文本字段不被截断）")
    void handshakeFieldsRoundTrip() {
        CipherMessage msg = message(ALICE, DEVICE_A, BOB, DEVICE_B, "msg-x3dh-001", 0);
        // 模拟真实长度的身份公钥与临时公钥
        String identityKey = Base64.getEncoder().encodeToString(new byte[91]);
        String ephemeralKey = Base64.getEncoder().encodeToString(new byte[91]);
        msg.setSenderIdentityKey(identityKey);
        msg.setSenderEphemeralKey(ephemeralKey);
        msg.setSenderUsedOneTimePreKey(true);
        msg.setConsumedOneTimePreKeyId(7);
        messageMapper.insert(msg);

        CipherMessage found = messageMapper.selectByClientMsgId(ALICE, "msg-x3dh-001", DEVICE_B);
        assertEquals(identityKey, found.getSenderIdentityKey(), "TEXT 字段不应被截断");
        assertEquals(ephemeralKey, found.getSenderEphemeralKey());
        assertEquals(Boolean.TRUE, found.getSenderUsedOneTimePreKey());
        assertEquals(7, found.getConsumedOneTimePreKeyId());
    }
}
