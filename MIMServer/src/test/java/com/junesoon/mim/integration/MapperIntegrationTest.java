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
import com.junesoon.mim.mapper.ConversationClearMapper;
import com.junesoon.mim.mapper.DeviceMapper;
import com.junesoon.mim.mapper.MessageMapper;
import com.junesoon.mim.mapper.PreKeyMapper;
import com.junesoon.mim.mapper.UserKeyMapper;
import java.util.ArrayList;
import java.util.Collections;
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

    @Autowired
    private ConversationClearMapper conversationClearMapper;

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

    @Test
    @DisplayName("能力位持久化：撤回凭证与发送者证书声明可读回，缺省为 false")
    void userKeyCapabilitiesPersist() {
        registerDevice(BOB, DEVICE_A);
        registerDevice(BOB, DEVICE_B);
        UserKey modern = userKey(BOB, DEVICE_A, "key-modern");
        modern.setSupportsPadding(true);
        modern.setSupportsRecallToken(true);
        modern.setSupportsSenderCert(true);
        userKeyMapper.upsert(modern);
        userKeyMapper.upsert(userKey(BOB, DEVICE_B, "key-legacy"));

        UserKey stored = userKeyMapper.selectByDevice(BOB, DEVICE_A);
        assertTrue(Boolean.TRUE.equals(stored.getSupportsPadding()));
        assertTrue(Boolean.TRUE.equals(stored.getSupportsRecallToken()),
            "跨设备撤回能力必须能读回，否则同步副本不会带凭证");
        assertTrue(Boolean.TRUE.equals(stored.getSupportsSenderCert()),
            "证书能力必须能读回，否则首条消息不会走新格式");

        UserKey legacy = userKeyMapper.selectByDevice(BOB, DEVICE_B);
        assertFalse(Boolean.TRUE.equals(legacy.getSupportsRecallToken()),
            "旧设备能力位缺省为 false");

        List<UserKey> all = userKeyMapper.selectAllByUsername(BOB);
        assertTrue(all.stream().anyMatch(k -> Boolean.TRUE.equals(k.getSupportsRecallToken())),
            "selectAllByUsername 也要带出能力位");
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
        message.setConversationTag("tagAAAAAAAAAAAAAAAAAAAAAAAA1");
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
    @DisplayName("消息插入后可按 (clientMsgId, targetDevice) 精确查回")
    void insertAndSelectByClientMsgId() {
        CipherMessage msg = message(ALICE, DEVICE_A, BOB, DEVICE_B, "msg-0000001", 0);
        assertEquals(1, messageMapper.insert(msg));
        assertNotNull(msg.getId(), "自增主键应回填");

        CipherMessage found = messageMapper.selectByClientMsgId("msg-0000001", DEVICE_B);
        assertNotNull(found);
        assertEquals(BOB, found.getToUsername());
        assertEquals("ratchet-pub", found.getRatchetPublic());
        assertEquals(0, found.getMessageNumber());

        // 不同目标设备查不到
        assertNull(messageMapper.selectByClientMsgId("msg-0000001", DEVICE_A));
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
    @DisplayName("投递确认后擦除握手参数与身份公钥，保留内容与盲引用")
    void markDeliveredWipesHandshakeParams() {
        // 首条 sealed 消息必须带握手参数，收信端才能离线建会话；
        // 投递确认后这些参数应立即消失，避免用公钥目录 join 出用户名。
        CipherMessage first = sealedMessage("msg-first-01", BOB, DEVICE_A, TAG_ONE);
        first.setSenderIdentityKey("alice-identity-key");
        first.setSenderEphemeralKey("alice-ephemeral-key");
        first.setSenderUsedOneTimePreKey(Boolean.TRUE);
        first.setConsumedOneTimePreKeyId(7);
        first.setRecipientSignedPreKeyId(3);
        messageMapper.insert(first);

        messageMapper.markDeliveredUpTo(BOB, DEVICE_A, Long.MAX_VALUE);

        CipherMessage stored = messageMapper.selectByClientMsgId("msg-first-01", DEVICE_A);
        assertEquals(Boolean.TRUE, stored.getDelivered());
        assertNull(stored.getSenderIdentityKey(), "投递后身份公钥必须擦除");
        assertNull(stored.getSenderEphemeralKey(), "临时公钥必须擦除");
        assertNull(stored.getConsumedOneTimePreKeyId());
        assertNull(stored.getRecipientSignedPreKeyId());
        assertFalse(Boolean.TRUE.equals(stored.getSenderUsedOneTimePreKey()));
        assertNotNull(stored.getCiphertext(), "擦除的是握手参数，不是消息内容");
        assertEquals(TAG_ONE, stored.getConversationTag(), "会话标签必须保留，历史查询还要用");
    }



    @Test
    @DisplayName("撤回按 clientMsgId 一次清掉所有设备副本的密文")
    void recallClearsAllReplicas() {
        String hash = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
        CipherMessage a = sealedMessage("msg-recall-01", BOB, DEVICE_A, TAG_ONE);
        CipherMessage b = sealedMessage("msg-recall-01", BOB, DEVICE_B, TAG_ONE);
        a.setRecallTokenHash(hash);
        b.setRecallTokenHash(hash);
        messageMapper.insert(a);
        messageMapper.insert(b);

        int affected = messageMapper.recallByToken("msg-recall-01", hash,
            System.currentTimeMillis());
        assertEquals(2, affected, "两台设备的副本都应被撤回");

        List<CipherMessage> history = messageMapper.selectTagHistory(
            Collections.singletonList(TAG_ONE), BOB, ALICE, null, 50);
        assertEquals(1, history.size());
        assertTrue(history.get(0).getRecalled());
        assertNull(history.get(0).getCiphertext(), "密文应被物理清除");
        assertNull(history.get(0).getIv(), "IV 应被清除");
    }


    @Test
    @DisplayName("重复撤回返回 0 行（recalled = 0 条件保证幂等）")
    void recallIsIdempotent() {
        String hash = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";
        CipherMessage row = sealedMessage("msg-recall-03", BOB, DEVICE_A, TAG_ONE);
        row.setRecallTokenHash(hash);
        messageMapper.insert(row);
        assertEquals(1, messageMapper.recallByToken("msg-recall-03", hash,
            System.currentTimeMillis()));
        assertEquals(0, messageMapper.recallByToken("msg-recall-03", hash,
            System.currentTimeMillis()));
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

        CipherMessage found = messageMapper.selectByClientMsgId("msg-x3dh-001", DEVICE_B);
        assertEquals(identityKey, found.getSenderIdentityKey(), "TEXT 字段不应被截断");
        assertEquals(ephemeralKey, found.getSenderEphemeralKey());
        assertEquals(Boolean.TRUE, found.getSenderUsedOneTimePreKey());
        assertEquals(7, found.getConsumedOneTimePreKeyId());
    }

    // ==================== sealed Phase 1.5 ====================

    private static final String REF_ALICE = "0123456789abcdef0123456789abcdef"
        + "0123456789abcdef0123456789abcdef";
    private static final String REF_ALICE_DEVICE = "fedcba98765432103333333333333333"
        + "fedcba98765432103333333333333333";
    private static final String REF_BOB = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
        + "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";
    private static final String TAG_ONE = "tagAAAAAAAAAAAAAAAAAAAAAAAA1";
    private static final String TAG_TWO = "tagBBBBBBBBBBBBBBBBBBBBBBBB2";

    private CipherMessage sealedMessage(String clientMsgId, String toUser, String target,
        String tag) {
        CipherMessage message = message(null, null, toUser, target, clientMsgId, 0);
        message.setSealed(true);
        message.setSessionId("session-0001");
        message.setConversationTag(tag);
        return message;
    }

    @Test
    @DisplayName("会话标签：按标签查历史，不依赖盲引用")
    void tagHistoryFindsRowsWithoutRef() {
        // 模拟「停写 from_ref 之后」的数据：没有盲引用，只有不透明标签
        CipherMessage tagged = message(null, null, BOB, DEVICE_A, "msg-tag-001", 0);
        tagged.setSealed(true);
        tagged.setSessionId("session-tag");
        tagged.setConversationTag(TAG_ONE);
        messageMapper.insert(tagged);

        CipherMessage other = message(null, null, BOB, DEVICE_A, "msg-tag-002", 1);
        other.setSealed(true);
        other.setSessionId("session-tag");
        other.setConversationTag(TAG_TWO);
        messageMapper.insert(other);

        List<CipherMessage> found = messageMapper.selectTagHistory(
            Collections.singletonList(TAG_ONE), BOB, ALICE, null, 50);
        assertEquals(1, found.size(), "只应命中同一标签的消息");
        assertEquals("msg-tag-001", found.get(0).getClientMsgId());
        assertEquals(TAG_ONE, found.get(0).getConversationTag(), "标签查询只依赖会话标签");
    }

    @Test
    @DisplayName("会话标签：查询被限定在会话双方，不会串到别人")
    void tagHistoryScopedToParticipants() {
        CipherMessage tagged = message(null, null, BOB, DEVICE_A, "msg-tag-003", 0);
        tagged.setSealed(true);
        tagged.setSessionId("session-tag");
        tagged.setConversationTag(TAG_ONE);
        messageMapper.insert(tagged);

        assertTrue(messageMapper.selectTagHistory(Collections.singletonList(TAG_ONE),
            ALICE, "carol", null, 50).isEmpty(),
            "to_user 不在会话双方时不能返回，防止标签复用串消息");
        assertEquals(1, messageMapper.selectTagHistory(Collections.singletonList(TAG_ONE),
            ALICE, BOB, null, 50).size());
    }


    @Test
    @DisplayName("唯一索引不含发信人：同一目标设备不能有第二份副本")
    void uniqueIndexIgnoresSender() {
        messageMapper.insert(sealedMessage("msg-sealed-dup", BOB, DEVICE_A, REF_ALICE));
        boolean rejected = false;
        try {
            CipherMessage second = sealedMessage("msg-sealed-dup", BOB, DEVICE_A, REF_BOB);
            messageMapper.insert(second);
        } catch (org.springframework.dao.DuplicateKeyException e) {
            rejected = true;
        }
        assertTrue(rejected, "同一 clientMsgId + 目标设备只能存在一份密文");
    }



    @Test
    @DisplayName("一次性 token 撤回：哈希匹配才清空全部副本")
    void recallByTokenClearsAllReplicas() {
        String hash = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
        CipherMessage a = sealedMessage("msg-token-01", BOB, DEVICE_A, REF_ALICE);
        CipherMessage b = sealedMessage("msg-token-01", BOB, DEVICE_B, REF_ALICE);
        a.setRecallTokenHash(hash);
        b.setRecallTokenHash(hash);
        messageMapper.insert(a);
        messageMapper.insert(b);

        assertEquals(0, messageMapper.recallByToken("msg-token-01", "wrong-hash",
            System.currentTimeMillis()), "哈希不匹配不能撤回");

        assertEquals(2, messageMapper.recallByToken("msg-token-01", hash,
            System.currentTimeMillis()), "正确 token 应清空两台设备的副本");
        for (CipherMessage history : messageMapper.selectTagHistory(
            Collections.singletonList(REF_ALICE), BOB, ALICE, null, 50)) {
            assertTrue(history.getRecalled());
            assertNull(history.getCiphertext());
        }
    }




    @Test
    @DisplayName("未投递查询用会话标签匹配清除点（无 from_ref 也能过滤）")
    void undeliveredHonoursTagClearCursor() {
        CipherMessage received = message(null, null, BOB, DEVICE_A, "msg-tag-clear", 0);
        received.setSealed(true);
        received.setSessionId("session-tag");
        received.setConversationTag(TAG_ONE);
        messageMapper.insert(received);
        long id = received.getId();

        conversationClearMapper.insert(BOB, "tag:" + TAG_ONE, id, System.currentTimeMillis());

        assertTrue(messageMapper.selectUndelivered(BOB, DEVICE_A, 100).isEmpty(),
            "标签维度的清除点应在拿不到 from_ref 时生效");
    }

    @Test
    @DisplayName("会话标签的未读统计与已读标记按标签生效，不串到别的会话")
    void tagUnreadAndRead() {
        insertTagged("msg-tag-unread-01", TAG_ONE);
        insertTagged("msg-tag-unread-02", TAG_TWO);

        assertEquals(1L, messageMapper.countUnreadByTags(
            Collections.singletonList(TAG_ONE), BOB, null));
        assertEquals(1, messageMapper.markReadByTags(
            Collections.singletonList(TAG_ONE), BOB, null));
        assertEquals(0L, messageMapper.countUnreadByTags(
            Collections.singletonList(TAG_ONE), BOB, null));
        assertEquals(1L, messageMapper.countUnreadByTags(
            Collections.singletonList(TAG_TWO), BOB, null), "其它会话不受影响");
    }

    @Test
    @DisplayName("会话标签的清除点取最大 id，并按标签抹除密文")
    void tagClearCursorAndWipe() {
        insertTagged("msg-tag-wipe-01", TAG_ONE);
        insertTagged("msg-tag-wipe-02", TAG_ONE);

        Long max = messageMapper.maxTagConversationId(
            Collections.singletonList(TAG_ONE), BOB, ALICE);
        assertNotNull(max);
        assertEquals(2, messageMapper.wipeTagConversation(
            Collections.singletonList(TAG_ONE), BOB, ALICE, max));

        CipherMessage wiped = messageMapper.selectByClientMsgId("msg-tag-wipe-01", DEVICE_A);
        assertNull(wiped.getCiphertext(), "清除应抹掉密文");
    }

    @Test
    @DisplayName("会话标签可定位原消息，供跨设备同步使用")
    void participantCopyByTag() {
        insertTagged("msg-tag-sync-01", TAG_ONE);

        CipherMessage original = messageMapper.selectParticipantCopyByTag("msg-tag-sync-01",
            TAG_ONE);
        assertNotNull(original);
        assertEquals("msg-tag-sync-01", original.getClientMsgId());

        assertNull(messageMapper.selectParticipantCopyByTag("msg-tag-sync-01", TAG_TWO),
            "错误标签不能取到原消息");
    }

    @Test
    @DisplayName("标签历史按设备选副本，不能返回别的设备解不开的密文")
    void tagHistoryPrefersOwnDeviceCopy() {
        CipherMessage forA = message(null, null, BOB, DEVICE_A, "msg-tag-dev-01", 0);
        forA.setSealed(true);
        forA.setSessionId("session-tag");
        forA.setConversationTag(TAG_ONE);
        messageMapper.insert(forA);

        CipherMessage forB = message(null, null, BOB, DEVICE_B, "msg-tag-dev-01", 0);
        forB.setSealed(true);
        forB.setSessionId("session-tag");
        forB.setConversationTag(TAG_ONE);
        messageMapper.insert(forB);

        List<CipherMessage> viewA = messageMapper.selectTagHistoryForDevice(
            Collections.singletonList(TAG_ONE), BOB, ALICE, DEVICE_A, null, 50);
        assertEquals(1, viewA.size());
        assertEquals(DEVICE_A, viewA.get(0).getTargetDeviceId(), "必须选本设备的那份");

        List<CipherMessage> viewB = messageMapper.selectTagHistoryForDevice(
            Collections.singletonList(TAG_ONE), BOB, ALICE, DEVICE_B, null, 50);
        assertEquals(1, viewB.size());
        assertEquals(DEVICE_B, viewB.get(0).getTargetDeviceId());
    }

    @Test
    @DisplayName("按标签取本人副本：标签匹配才返回")
    void ownedCopyByTags() {
        insertTagged("msg-tag-owned", TAG_ONE);
        assertNotNull(messageMapper.selectOwnedCopyByTags(
            Collections.singletonList(TAG_ONE), "msg-tag-owned"));
        assertNull(messageMapper.selectOwnedCopyByTags(
            Collections.singletonList(TAG_TWO), "msg-tag-owned"), "错误标签不能取到副本");
    }


    private void insertTagged(String clientMsgId, String tag) {
        CipherMessage tagged = message(ALICE, DEVICE_A, BOB, DEVICE_A, clientMsgId, 0);
        tagged.setSealed(true);
        tagged.setSessionId("session-tag");
        tagged.setConversationTag(tag);
        messageMapper.insert(tagged);
    }
}
