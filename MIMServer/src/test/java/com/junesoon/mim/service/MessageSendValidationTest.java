package com.junesoon.mim.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.junesoon.mim.config.KeyConfiguration;
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

        private static String key(String clientMsgId, String target) {
            return clientMsgId + "|" + target;
        }

        @Override
        public int insert(CipherMessage message) {
            inserted.add(message);
            byKey.put(key(message.getClientMsgId(), message.getTargetDeviceId()), message);
            return 1;
        }

        @Override
        public CipherMessage selectByClientMsgId(String clientMsgId, String targetDeviceId) {
            return byKey.get(key(clientMsgId, targetDeviceId));
        }

        @Override
        public CipherMessage selectById(Long id) {
            return null;
        }

        @Override
        public CipherMessage selectOwnedCopyByTags(List<String> tags, String clientMsgId) {
            return null;
        }

        @Override
        public CipherMessage selectParticipantCopyByTag(String clientMsgId, String tag) {
            return null;
        }

        @Override
        public List<CipherMessage> selectTagHistoryForDevice(List<String> tags, String viewer,
            String peer, String deviceId, Long beforeId, int limit) {
            return Collections.emptyList();
        }

        @Override
        public List<CipherMessage> selectTagHistory(List<String> tags, String viewer, String peer,
            Long beforeId, int limit) {
            return Collections.emptyList();
        }

        @Override
        public int markReadByTags(List<String> tags, String toUsername, Long beforeId) {
            return 0;
        }

        @Override
        public long countUnreadByTags(List<String> tags, String toUsername, Long afterId) {
            return 0;
        }

        @Override
        public Long maxTagConversationId(List<String> tags, String viewer, String peer) {
            return null;
        }

        @Override
        public int wipeTagConversation(List<String> tags, String viewer, String peer,
            long beforeId) {
            return 0;
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
        public int recallByToken(String clientMsgId, String recallTokenHash, long recalledAt) {
            return 1;
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
        public int bumpTokenVersion(String username, String deviceId) {
            Device device = devices.get(key(username, deviceId));
            if (device == null) {
                return 0;
            }
            device.setTokenVersion(
                (device.getTokenVersion() == null ? 0 : device.getTokenVersion()) + 1);
            return 1;
        }

        @Override
        public int bumpAllTokenVersions(String username) {
            int affected = 0;
            for (Device device : devices.values()) {
                if (device.getUsername().equals(username)) {
                    device.setTokenVersion(
                        (device.getTokenVersion() == null ? 0 : device.getTokenVersion()) + 1);
                    affected++;
                }
            }
            return affected;
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

    /**
     * 只提供「收件人存在」判定的桩；其它方法不会被这些用例调用。
     */
    private static final class InMemoryUserMapper implements UserMapper {
        @Override
        public User selectOne(String username) {
            User user = new User();
            user.setUsername(username);
            return user;
        }

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
            return 0;
        }

        @Override
        public User selectByPrimaryKey(Integer id) {
            return null;
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
            public com.junesoon.mim.responses.ClearPoint selectPoint(String username, String peer) {
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
        // 协议断代后所有消息都必须是 sealed + 会话标签。
        message.setSealed(true);
        message.setSessionId("session-00000001");
        message.setConversationTag("tagAAAAAAAAAAAAAAAAAAAAAAAA1");
        return message;
    }

    private List<CipherMessage> single(String targetDeviceId) {
        return Collections.singletonList(replica(targetDeviceId));
    }

    // ==================== 结构校验 ====================

    @Test
    @DisplayName("客户端时间戳偏离过大被拒绝")
    void wildClientTimeRejected() {
        List<CipherMessage> future = single(BOB_DEVICE_A);
        future.get(0).setClientTime(System.currentTimeMillis() + 24L * 60 * 60 * 1000);
        assertThrows(UserInvalidException.class,
            () -> service.send(ALICE, ALICE_DEVICE, future));

        List<CipherMessage> past = single(BOB_DEVICE_A);
        past.get(0).setClientTime(
            System.currentTimeMillis() - 400L * 24 * 60 * 60 * 1000);
        assertThrows(UserInvalidException.class,
            () -> service.send(ALICE, ALICE_DEVICE, past));
    }

    @Test
    @DisplayName("结构合法的双棘轮消息可以发送")
    void validMessageAccepted() {
        List<CipherMessage> saved = service.send(ALICE, ALICE_DEVICE, single(BOB_DEVICE_A));
        assertEquals(1, saved.size());
        assertEquals(BOB_DEVICE_A, saved.get(0).getTargetDeviceId());
        assertEquals("tagAAAAAAAAAAAAAAAAAAAAAAAA1", saved.get(0).getConversationTag());
        assertEquals(Boolean.FALSE, saved.get(0).getRecalled());
    }




    @Test
    @DisplayName("会话标签落库；缺失或非法标签直接拒绝")
    void conversationTagRequired() {
        CipherMessage tagged = replica(BOB_DEVICE_A);
        tagged.setClientMsgId("client-tag-0001");
        CipherMessage stored = service.send(ALICE, ALICE_DEVICE,
            Collections.singletonList(tagged)).get(0);
        assertEquals("tagAAAAAAAAAAAAAAAAAAAAAAAA1", stored.getConversationTag());

        CipherMessage bad = replica(BOB_DEVICE_A);
        bad.setClientMsgId("client-tag-0002");
        bad.setConversationTag("bad tag!");
        assertThrows(UserInvalidException.class,
            () -> service.send(ALICE, ALICE_DEVICE, Collections.singletonList(bad)),
            "非法标签必须拒绝，避免写出无法归属的消息");

        CipherMessage missing = replica(BOB_DEVICE_A);
        missing.setClientMsgId("client-tag-0003");
        missing.setConversationTag(null);
        assertThrows(UserInvalidException.class,
            () -> service.send(ALICE, ALICE_DEVICE, Collections.singletonList(missing)));
    }

    @Test
    @DisplayName("握手参数按请求落库：sealed 首条消息也要保留，投递后才由 mapper 擦除")
    void handshakeParamsSurviveUntilDelivery() throws Exception {

        // 首条 sealed 消息必须带参数，否则收信端无法离线建出会话
        CipherMessage sealed = replica(BOB_DEVICE_A);
        sealed.setSealed(true);
        sealed.setSessionId("session-abcdef12");
        sealed.setSenderIdentityKey(ALICE + "-key-" + ALICE_DEVICE);
        sealed.setSenderEphemeralKey("ZXBoZW1lcmFs");
        sealed.setSenderUsedOneTimePreKey(Boolean.TRUE);
        sealed.setConsumedOneTimePreKeyId(7);
        sealed.setRecipientSignedPreKeyId(3);

        CipherMessage stored = service.send(ALICE, ALICE_DEVICE,
            Collections.singletonList(sealed)).get(0);
        assertEquals(ALICE + "-key-" + ALICE_DEVICE, stored.getSenderIdentityKey(),
            "首条 sealed 消息必须保留握手参数");
        assertEquals(Integer.valueOf(7), stored.getConsumedOneTimePreKeyId());
        assertEquals(Boolean.TRUE, stored.getSenderUsedOneTimePreKey());

        // 桥接期消息同样保留（收信端首次建链要用）
        CipherMessage bridge = replica(BOB_DEVICE_A);
        bridge.setClientMsgId("client-msg-0002");
        bridge.setSenderIdentityKey(ALICE + "-key-" + ALICE_DEVICE);
        bridge.setSenderEphemeralKey("ZXBoZW1lcmFs");
        CipherMessage bridgeStored = service.send(ALICE, ALICE_DEVICE,
            Collections.singletonList(bridge)).get(0);
        assertEquals(ALICE + "-key-" + ALICE_DEVICE, bridgeStored.getSenderIdentityKey());
    }

    @Test
    @DisplayName("sealed 消息缺少 sessionId 被拒绝")
    void sealedWithoutSessionRejected() throws Exception {
        CipherMessage sealed = replica(BOB_DEVICE_A);
        sealed.setSealed(true);
        sealed.setSessionId(null);
        assertThrows(UserInvalidException.class,
            () -> service.send(ALICE, ALICE_DEVICE, Collections.singletonList(sealed)));
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


}
