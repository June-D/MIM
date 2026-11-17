package com.junesoon.mim.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.junesoon.mim.entity.CipherMessage;
import com.junesoon.mim.entity.Device;
import com.junesoon.mim.entity.User;
import com.junesoon.mim.entity.UserKey;
import com.junesoon.mim.exception.UserInvalidException;
import com.junesoon.mim.mapper.DeviceMapper;
import com.junesoon.mim.mapper.ConversationClearMapper;
import com.junesoon.mim.mapper.MessageMapper;
import com.junesoon.mim.mapper.UserKeyMapper;
import com.junesoon.mim.mapper.UserMapper;
import com.junesoon.mim.service.impl.MessageServiceImpl;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 消息发送的结构校验与多设备分发测试。
 *
 * <p>服务端对内容零知识，但必须拦住结构非法的消息，
 * 并且保证多设备副本正确路由到各自的目标设备。
 */
class MessageSendValidationTest {

    private static final String ALICE = "alice";
    private static final String BOB = "bob";
    private static final String ALICE_DEVICE = "alice-dev-1";
    private static final String BOB_DEVICE_A = "bob-dev-a";
    private static final String BOB_DEVICE_B = "bob-dev-b";

    private static final class RecordingMessageMapper implements MessageMapper {
        final Map<String, CipherMessage> byKey = new HashMap<>();
        final List<CipherMessage> inserted = new ArrayList<>();

        private static String key(String from, String clientMsgId, String target) {
            return from + "|" + clientMsgId + "|" + target;
        }

        @Override
        public int insert(CipherMessage message) {
            inserted.add(message);
            byKey.put(key(message.getFromUsername(), message.getClientMsgId(),
                message.getTargetDeviceId()), message);
            return 1;
        }

        @Override
        public CipherMessage selectByClientMsgId(String fromUsername, String clientMsgId,
            String targetDeviceId) {
            return byKey.get(key(fromUsername, clientMsgId, targetDeviceId));
        }

        @Override
        public CipherMessage selectAnyByClientMsgId(String fromUsername, String clientMsgId) {
            for (CipherMessage message : inserted) {
                if (message.getFromUsername().equals(fromUsername)
                    && message.getClientMsgId().equals(clientMsgId)) {
                    return message;
                }
            }
            return null;
        }

        @Override
        public CipherMessage selectById(Long id) {
            return null;
        }

        @Override
        public List<CipherMessage> selectHistory(String a, String b, Long beforeId, int limit) {
            return Collections.emptyList();
        }

        @Override
        public List<CipherMessage> selectUndelivered(String toUsername, String deviceId, int limit) {
            return Collections.emptyList();
        }

        @Override
        public int markDelivered(Long id) {
            return 1;
        }

        @Override
        public int markDeliveredUpTo(String toUsername, String deviceId, long beforeId) {
            return 0;
        }

        @Override
        public int markRead(String fromUsername, String toUsername, Long beforeId) {
            return 0;
        }

        @Override
        public long countUnread(String fromUsername, String toUsername) {
            return 0;
        }

        @Override
        public int recall(String clientMsgId, String fromUsername, long recalledAt) {
            return 0;
        }

        @Override
        public long countUnreadAfter(String fromUsername, String toUsername, long afterId) {
            return 0;
        }

        @Override
        public Long maxConversationId(String a, String b) {
            return null;
        }

        @Override
        public int wipeConversation(String a, String b, long beforeId) {
            return 0;
        }
    }

    private static final class InMemoryUserMapper implements UserMapper {
        final Map<String, User> users = new HashMap<>();

        @Override
        public int deleteByPrimaryKey(Integer id) {
            return 0;
        }

        @Override
        public int insert(User record) {
            return 0;
        }

        @Override
        public int insertSelective(User record) {
            users.put(record.getUsername(), record);
            return 1;
        }

        @Override
        public User selectByPrimaryKey(Integer id) {
            return null;
        }

        @Override
        public User selectOne(String username) {
            User user = users.get(username);
            if (user == null) {
                user = new User();
                user.setUsername(username);
                user.setId(1);
                users.put(username, user);
            }
            return user;
        }

        @Override
        public int updateLastLoginTime(String username, java.util.Date lastLoginTime) {
            return 0;
        }

        @Override
        public int updateByPrimaryKeySelective(User record) {
            return 0;
        }

        @Override
        public int updateByPrimaryKey(User record) {
            return 0;
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
            return 0;
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

    private static final class NoopPushService extends PushService {
        NoopPushService() {
            super(null, null, null);
        }

        @Override
        public void push(String target, Object notify) {
        }

        @Override
        public void pushAll(Iterable<String> targets, Object notify) {
        }
    }

    private RecordingMessageMapper messageMapper;
    private InMemoryUserKeyMapper keyMapper;
    private InMemoryDeviceMapper deviceMapper;
    private MessageServiceImpl service;

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
        key.setVersion(1);
        key.setPublicKey(username + "-key-" + deviceId);
        keyMapper.upsert(key);
    }

    @BeforeEach
    void setUp() throws Exception {
        messageMapper = new RecordingMessageMapper();
        keyMapper = new InMemoryUserKeyMapper();
        deviceMapper = new InMemoryDeviceMapper();
        service = new MessageServiceImpl(messageMapper, new ConversationClearMapper() {
            @Override
            public Long selectCursor(String username, String peer) {
                return null;
            }

            @Override
            public int insert(String username, String peer, long clearedBeforeId, long updatedAt) {
                return 1;
            }

            @Override
            public int advance(String username, String peer, long clearedBeforeId, long updatedAt) {
                return 1;
            }
        }, new InMemoryUserMapper(), keyMapper, deviceMapper, new NoopPushService());
        setField(service, "maxCiphertextBytes", 262144);
        setField(service, "recallWindowSeconds", 120L);

        registerDevice(ALICE, ALICE_DEVICE);
        registerDevice(BOB, BOB_DEVICE_A);
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    private CipherMessage replica(String targetDeviceId) {
        CipherMessage message = new CipherMessage();
        message.setClientMsgId("client-msg-0001");
        message.setToUsername(BOB);
        message.setTargetDeviceId(targetDeviceId);
        message.setAlg(MessageServiceImpl.ALGORITHM_RATCHET);
        message.setIv(Base64.getEncoder().encodeToString(new byte[12]));
        message.setCiphertext(Base64.getEncoder().encodeToString(new byte[32]));
        message.setRatchetPublic("cmF0Y2hldC1wdWJsaWMta2V5");
        message.setPreviousChainLength(0);
        message.setMessageNumber(0);
        message.setClientTime(System.currentTimeMillis());
        return message;
    }

    private List<CipherMessage> single(String targetDeviceId) {
        return Collections.singletonList(replica(targetDeviceId));
    }

    // ==================== 结构校验 ====================

    @Test
    @DisplayName("结构合法的双棘轮消息可以发送")
    void validMessageAccepted() {
        List<CipherMessage> saved = service.send(ALICE, ALICE_DEVICE, single(BOB_DEVICE_A));
        assertEquals(1, saved.size());
        assertEquals(ALICE, saved.get(0).getFromUsername(), "发信人必须取自鉴权身份");
        assertEquals(ALICE_DEVICE, saved.get(0).getFromDeviceId(), "发信设备取自请求头");
        assertEquals(BOB_DEVICE_A, saved.get(0).getTargetDeviceId());
        assertEquals(Boolean.FALSE, saved.get(0).getRecalled());
    }

    @Test
    @DisplayName("请求体里的 fromUsername 被忽略")
    void spoofedSenderIgnored() {
        List<CipherMessage> saved = service.send(ALICE, ALICE_DEVICE, single(BOB_DEVICE_A));
        assertEquals(ALICE, saved.get(0).getFromUsername());
    }

    @Test
    @DisplayName("不能给自己发消息")
    void selfMessageRejected() {
        List<CipherMessage> messages = single(BOB_DEVICE_A);
        messages.get(0).setToUsername(ALICE);
        assertThrows(UserInvalidException.class,
            () -> service.send(ALICE, ALICE_DEVICE, messages));
    }

    @Test
    @DisplayName("不支持的算法被拒绝")
    void unsupportedAlgorithmRejected() {
        List<CipherMessage> messages = single(BOB_DEVICE_A);
        messages.get(0).setAlg("AES-ECB");
        assertThrows(UserInvalidException.class,
            () -> service.send(ALICE, ALICE_DEVICE, messages));
    }

    @Test
    @DisplayName("IV 长度不是 12 字节被拒绝")
    void wrongIvLengthRejected() {
        List<CipherMessage> messages = single(BOB_DEVICE_A);
        messages.get(0).setIv(Base64.getEncoder().encodeToString(new byte[16]));
        UserInvalidException ex = assertThrows(UserInvalidException.class,
            () -> service.send(ALICE, ALICE_DEVICE, messages));
        assertTrue(ex.getMessage().contains("12 字节"));
    }

    @Test
    @DisplayName("密文过短（缺少 GCM tag）被拒绝")
    void tooShortCiphertextRejected() {
        List<CipherMessage> messages = single(BOB_DEVICE_A);
        messages.get(0).setCiphertext(Base64.getEncoder().encodeToString(new byte[16]));
        assertThrows(UserInvalidException.class,
            () -> service.send(ALICE, ALICE_DEVICE, messages));
    }

    @Test
    @DisplayName("非法 Base64 密文被拒绝")
    void invalidBase64Rejected() {
        List<CipherMessage> messages = single(BOB_DEVICE_A);
        messages.get(0).setCiphertext("!!!not-base64!!!");
        assertThrows(UserInvalidException.class,
            () -> service.send(ALICE, ALICE_DEVICE, messages));
    }

    @Test
    @DisplayName("缺少棘轮公钥被拒绝")
    void missingRatchetPublicRejected() {
        List<CipherMessage> messages = single(BOB_DEVICE_A);
        messages.get(0).setRatchetPublic(null);
        UserInvalidException ex = assertThrows(UserInvalidException.class,
            () -> service.send(ALICE, ALICE_DEVICE, messages));
        assertTrue(ex.getMessage().contains("棘轮公钥"));
    }

    @Test
    @DisplayName("消息序号为负被拒绝")
    void negativeMessageNumberRejected() {
        List<CipherMessage> messages = single(BOB_DEVICE_A);
        messages.get(0).setMessageNumber(-1);
        assertThrows(UserInvalidException.class,
            () -> service.send(ALICE, ALICE_DEVICE, messages));
    }

    @Test
    @DisplayName("客户端消息 ID 格式非法被拒绝")
    void invalidClientMsgIdRejected() {
        List<CipherMessage> messages = single(BOB_DEVICE_A);
        messages.get(0).setClientMsgId("short");
        assertThrows(UserInvalidException.class,
            () -> service.send(ALICE, ALICE_DEVICE, messages));
    }

    @Test
    @DisplayName("时间戳缺失被拒绝")
    void missingClientTimeRejected() {
        List<CipherMessage> messages = single(BOB_DEVICE_A);
        messages.get(0).setClientTime(null);
        assertThrows(UserInvalidException.class,
            () -> service.send(ALICE, ALICE_DEVICE, messages));
    }

    @Test
    @DisplayName("空消息列表被拒绝")
    void emptyListRejected() {
        assertThrows(UserInvalidException.class,
            () -> service.send(ALICE, ALICE_DEVICE, new ArrayList<>()));
        assertThrows(UserInvalidException.class,
            () -> service.send(ALICE, ALICE_DEVICE, null));
    }

    // ==================== 多设备分发 ====================

    @Test
    @DisplayName("多设备收信人会落多行，每个目标设备一份")
    void multiDeviceFanout() {
        registerDevice(BOB, BOB_DEVICE_B);
        List<CipherMessage> messages = new ArrayList<>();
        messages.add(replica(BOB_DEVICE_A));
        messages.add(replica(BOB_DEVICE_B));

        List<CipherMessage> saved = service.send(ALICE, ALICE_DEVICE, messages);
        assertEquals(2, saved.size());
        assertEquals(2, messageMapper.inserted.size(), "两个设备应各落一行");
        assertEquals("client-msg-0001", saved.get(0).getClientMsgId());
        assertEquals("client-msg-0001", saved.get(1).getClientMsgId());
        assertTrue(!saved.get(0).getTargetDeviceId().equals(saved.get(1).getTargetDeviceId()));
    }

    @Test
    @DisplayName("目标设备不属于收信人时拒绝，避免消息被加密到不存在的设备")
    void unknownTargetDeviceRejected() {
        List<CipherMessage> messages = single("ghost-device");
        UserInvalidException ex = assertThrows(UserInvalidException.class,
            () -> service.send(ALICE, ALICE_DEVICE, messages));
        assertTrue(ex.getMessage().contains("目标设备不存在"));
    }

    @Test
    @DisplayName("已注销的目标设备被拒绝")
    void revokedTargetDeviceRejected() {
        deviceMapper.revoke(BOB, BOB_DEVICE_A);
        List<CipherMessage> messages = single(BOB_DEVICE_A);
        assertThrows(UserInvalidException.class,
            () -> service.send(ALICE, ALICE_DEVICE, messages));
    }

    @Test
    @DisplayName("同一目标设备出现两份密文被拒绝")
    void duplicateTargetRejected() {
        List<CipherMessage> messages = new ArrayList<>();
        messages.add(replica(BOB_DEVICE_A));
        messages.add(replica(BOB_DEVICE_A));
        assertThrows(UserInvalidException.class,
            () -> service.send(ALICE, ALICE_DEVICE, messages));
    }

    @Test
    @DisplayName("一次发送的副本必须属于同一条逻辑消息")
    void mismatchedClientMsgIdRejected() {
        registerDevice(BOB, BOB_DEVICE_B);
        List<CipherMessage> messages = new ArrayList<>();
        messages.add(replica(BOB_DEVICE_A));
        CipherMessage other = replica(BOB_DEVICE_B);
        other.setClientMsgId("other-msg-0002");
        messages.add(other);
        assertThrows(UserInvalidException.class,
            () -> service.send(ALICE, ALICE_DEVICE, messages));
    }

    @Test
    @DisplayName("相同 clientMsgId 重发同一目标设备是幂等的")
    void idempotentResend() {
        service.send(ALICE, ALICE_DEVICE, single(BOB_DEVICE_A));
        service.send(ALICE, ALICE_DEVICE, single(BOB_DEVICE_A));
        assertEquals(1, messageMapper.inserted.size(), "不应重复插入");
    }

    @Test
    @DisplayName("单次发送超过设备副本上限被拒绝")
    void tooManyReplicasRejected() {
        List<CipherMessage> messages = new ArrayList<>();
        for (int i = 0; i < 25; i++) {
            CipherMessage message = replica(BOB_DEVICE_A);
            message.setMessageNumber(i);
            messages.add(message);
        }
        assertThrows(UserInvalidException.class,
            () -> service.send(ALICE, ALICE_DEVICE, messages));
    }

    @Test
    @DisplayName("旧算法仍可用，但必须带合法密钥版本")
    void legacyAlgorithmStillWorks() {
        // 旧算法校验读的是双方的 default 设备公钥（历史兼容路径），需先注册
        registerDevice(ALICE, UserKey.DEFAULT_DEVICE_ID);
        registerDevice(BOB, UserKey.DEFAULT_DEVICE_ID);
        List<CipherMessage> messages = single(UserKey.DEFAULT_DEVICE_ID);
        messages.get(0).setAlg(MessageServiceImpl.ALGORITHM_LEGACY);
        messages.get(0).setRatchetPublic(null);
        messages.get(0).setSenderKeyVersion(1);
        messages.get(0).setRecipientKeyVersion(1);
        List<CipherMessage> saved = service.send(ALICE, UserKey.DEFAULT_DEVICE_ID, messages);
        assertEquals(MessageServiceImpl.ALGORITHM_LEGACY, saved.get(0).getAlg());
    }

    @Test
    @DisplayName("旧算法用过期版本被拒绝（防降级）")
    void legacyStaleVersionRejected() {
        registerDevice(ALICE, UserKey.DEFAULT_DEVICE_ID);
        registerDevice(BOB, UserKey.DEFAULT_DEVICE_ID);
        List<CipherMessage> messages = single(UserKey.DEFAULT_DEVICE_ID);
        messages.get(0).setAlg(MessageServiceImpl.ALGORITHM_LEGACY);
        messages.get(0).setRatchetPublic(null);
        messages.get(0).setSenderKeyVersion(99);
        messages.get(0).setRecipientKeyVersion(1);
        assertThrows(UserInvalidException.class,
            () -> service.send(ALICE, UserKey.DEFAULT_DEVICE_ID, messages));
    }
}
