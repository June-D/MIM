package com.junesoon.mim.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.junesoon.mim.entity.CipherMessage;
import com.junesoon.mim.entity.Device;
import com.junesoon.mim.exception.UserInvalidException;
import com.junesoon.mim.mapper.ConversationClearMapper;
import com.junesoon.mim.mapper.DeviceMapper;
import com.junesoon.mim.mapper.MessageMapper;
import com.junesoon.mim.mapper.UserKeyMapper;
import com.junesoon.mim.mapper.UserMapper;
import com.junesoon.mim.service.JPushService;
import com.junesoon.mim.service.PushService;
import com.junesoon.mim.service.impl.MessageServiceImpl;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * 设备间历史转发：服务端只能复制原消息的收发双方，不能改信谁发的。
 */
class MessageSyncTest {

    private MemoryMessages messages;
    private MessageServiceImpl service;

    /** 记录每次推送的 target|excludedDevice，用于断言「只发给其它设备」。 */
    private final List<String> pushes = new java.util.ArrayList<>();

    @BeforeEach
    void setUp() {
        messages = new MemoryMessages();
        service = new MessageServiceImpl(messages, new ConversationClearMapper() {
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
                return 0;
            }

            @Override
            public int advance(String username, String peer, long clearedBeforeId, long updatedAt) {
                return 0;
            }
        }, unusedUsers(), unusedKeys(), new DeviceMapper() {
            @Override
            public int upsert(Device device) {
                return 0;
            }

            @Override
            public Device selectOne(String username, String deviceId) {
                if (!"alice".equals(username)) {
                    return null;
                }
                if (!"dev-a".equals(deviceId) && !"dev-b".equals(deviceId)) {
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
                return Collections.emptyList();
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
                return 0;
            }

            @Override
            public int bumpAllTokenVersions(String username) {
                return 0;
            }

            @Override
            public int countActiveDevices(String username) {
                return 0;
            }
        }, new PushService(null, null, null) {
            @Override
            public void pushToDevice(String target, String deviceId, Object notify) {
                pushes.add(target + "|" + deviceId);
            }

            @Override
            public void pushToUserExcept(String target, String excludedDeviceId, Object notify) {
                pushes.add(target + "|" + excludedDeviceId);
            }
        }, (JPushService) null);
        ReflectionTestUtils.setField(service, "maxCiphertextBytes", 262144);
        messages.rows.add(original());
    }

    @Test
    @DisplayName("转发副本沿用原消息的收发双方，并记下来源设备")
    void copyKeepsOriginalParties() {
        List<CipherMessage> saved = service.syncReplicas("alice", "dev-a",
            Collections.singletonList(wrap("carol")));
        assertEquals(1, saved.size());
        CipherMessage copy = saved.get(0);
        assertEquals("alice", copy.getToUsername(), "副本收信人是本账号自己的设备");
        assertEquals("msg-sync-01", copy.getClientMsgId());
        assertEquals("dev-b", copy.getTargetDeviceId());
        assertEquals("dev-a", copy.getSyncFromDevice());
        assertEquals(Long.valueOf(1_700_000_000_000L), copy.getClientTime());
        assertEquals("bob", copy.getSyncOriginalFrom());
        assertEquals("alice", copy.getSyncOriginalTo());
        assertEquals("bob-1", copy.getSyncOriginalDevice());
        assertEquals("dummy-signature", copy.getSyncSignature());
        assertEquals(wrap("carol").getCiphertext(), copy.getCiphertext());
        // sessionId 由客户端生成、服务端不理解，但接收设备靠它路由密文；
        // 漏掉这一列，副本会永远解不开（真实 e2e 就是这么发现漏的）。
        assertEquals("session-01", copy.getSessionId());
    }

    @Test
    @DisplayName("已读只通知本人其它设备，不回推当前设备")
    void markReadNotifiesOtherOwnDevices() {
        messages.rows.clear();
        CipherMessage incoming = new CipherMessage();
        incoming.setClientMsgId("msg-read-01");
        incoming.setConversationTag("tagAAAAAAAAAAAAAAAAAAAAAAAA1");
        incoming.setToUsername("alice");
        incoming.setTargetDeviceId("dev-a");
        incoming.setReadFlag(false);
        messages.rows.add(incoming);

        int affected = service.markRead("alice", "bob", null,
            Collections.singletonList("tagAAAAAAAAAAAAAAAAAAAAAAAA1"), "dev-a");

        assertEquals(1, affected);
        assertEquals(Boolean.TRUE, messages.rows.get(0).getReadFlag());
        assertTrue(pushes.contains("alice|dev-a"), "应把已读同步给同账号其它设备：" + pushes);
    }

    @Test
    @DisplayName("同一逻辑消息在其它设备上的副本也要置已读（副本标签不同）")
    void markReadByIdsCoversOtherDeviceCopies() {
        messages.rows.clear();
        CipherMessage onA = new CipherMessage();
        onA.setClientMsgId("msg-multi-01");
        onA.setConversationTag("tagAAAAAAAAAAAAAAAAAAAAAAAA1");
        onA.setToUsername("alice");
        onA.setTargetDeviceId("dev-a");
        onA.setReadFlag(false);
        CipherMessage onB = new CipherMessage();
        onB.setClientMsgId("msg-multi-01");
        // 同一逻辑消息，另一台设备上的会话标签不同——按标签永远命中不到它。
        onB.setConversationTag("tagBBBBBBBBBBBBBBBBBBBBBBBB2");
        onB.setToUsername("alice");
        onB.setTargetDeviceId("dev-b");
        onB.setReadFlag(false);
        messages.rows.add(onA);
        messages.rows.add(onB);

        int affected = service.markReadByIds("alice", "bob",
            Collections.singletonList("msg-multi-01"), "dev-a");

        assertEquals(2, affected, "两份副本都应置已读");
        assertTrue(Boolean.TRUE.equals(onA.getReadFlag()) && Boolean.TRUE.equals(onB.getReadFlag()));
    }

    @Test
    @DisplayName("同一目标设备再转发一次时不新增副本")
    void secondSyncReturnsExisting() {
        service.syncReplicas("alice", "dev-a", Collections.singletonList(wrap("carol")));
        service.syncReplicas("alice", "dev-a", Collections.singletonList(wrap("carol")));
        int copies = 0;
        for (CipherMessage row : messages.rows) {
            if ("dev-b".equals(row.getTargetDeviceId())) {
                copies++;
            }
        }
        assertEquals(1, copies);
    }

    @Test
    @DisplayName("不是这条消息的参与者不能转发")
    void outsiderIsRejected() {
        UserInvalidException error = assertThrows(UserInvalidException.class,
            () -> service.syncReplicas("alice", "dev-a",
                Collections.singletonList(wrap("carol", "missing-msg"))));
        assertTrue(error.getMessage().contains("没有可同步"));
    }

    @Test
    @DisplayName("不能转发给当前这台设备")
    void cannotTargetCurrentDevice() {
        CipherMessage replica = wrap("carol");
        replica.setTargetDeviceId("dev-a");
        assertThrows(UserInvalidException.class,
            () -> service.syncReplicas("alice", "dev-a", Collections.singletonList(replica)));
    }

    @Test
    @DisplayName("已撤回的原消息只同步占位，不要求新密文")
    void recalledOriginalBecomesTombstone() {
        messages.rows.get(0).setRecalled(true);
        CipherMessage replica = new CipherMessage();
        replica.setClientMsgId("msg-sync-01");
        replica.setConversationTag("tagAAAAAAAAAAAAAAAAAAAAAAAA1");
        replica.setTargetDeviceId("dev-b");
        CipherMessage saved = service.syncReplicas("alice", "dev-a",
            Collections.singletonList(replica)).get(0);
        assertEquals(Boolean.TRUE, saved.getRecalled());
        assertNull(saved.getCiphertext());
        assertEquals("tagAAAAAAAAAAAAAAAAAAAAAAAA1", saved.getConversationTag());
    }

    private static CipherMessage original() {
        CipherMessage message = new CipherMessage();
        message.setId(5L);
        message.setClientMsgId("msg-sync-01");
        message.setConversationTag("tagAAAAAAAAAAAAAAAAAAAAAAAA1");
        message.setToUsername("alice");
        message.setTargetDeviceId("dev-a");
        message.setAlg(MessageServiceImpl.ALGORITHM_RATCHET);
        message.setClientTime(1_700_000_000_000L);
        message.setRecalled(false);
        message.setCiphertext(Base64.getEncoder().encodeToString("old".getBytes()));
        return message;
    }

    private static CipherMessage wrap(String forgedFrom) {
        return wrap(forgedFrom, "msg-sync-01");
    }

    private static CipherMessage wrap(String forgedFrom, String clientMsgId) {
        CipherMessage message = new CipherMessage();
        message.setClientMsgId(clientMsgId);
        message.setConversationTag("tagAAAAAAAAAAAAAAAAAAAAAAAA1");
        message.setTargetDeviceId("dev-b");
        message.setAlg(MessageServiceImpl.ALGORITHM_RATCHET);
        message.setIv(Base64.getEncoder().encodeToString(new byte[12]));
        message.setCiphertext(Base64.getEncoder().encodeToString(new byte[17]));
        message.setRatchetPublic("ratchet-pub");
        message.setPreviousChainLength(0);
        message.setMessageNumber(0);
        message.setSyncOriginalFrom("bob");
        message.setSyncOriginalTo("alice");
        message.setSyncOriginalDevice("bob-1");
        message.setSyncSignature("dummy-signature");
        message.setSessionId("session-01");
        message.setRecipientSignedPreKeyId(1);
        return message;
    }

    private static UserMapper unusedUsers() {
        return new UserMapper() {
            @Override
            public int deleteByPrimaryKey(Integer id) {
                return 0;
            }

            @Override
            public int insert(com.junesoon.mim.entity.User record) {
                return 0;
            }

            @Override
            public int insertSelective(com.junesoon.mim.entity.User record) {
                return 0;
            }

            @Override
            public com.junesoon.mim.entity.User selectByPrimaryKey(Integer id) {
                return null;
            }

            @Override
            public com.junesoon.mim.entity.User selectOne(String username) {
                return null;
            }

            @Override
            public int updateLastLoginTime(String username, java.util.Date lastLoginTime) {
                return 0;
            }

            @Override
            public int updateByPrimaryKeySelective(com.junesoon.mim.entity.User record) {
                return 0;
            }

            @Override
            public int updateByPrimaryKey(com.junesoon.mim.entity.User record) {
                return 0;
            }
        };
    }

    private static UserKeyMapper unusedKeys() {
        return new UserKeyMapper() {
            @Override
            public com.junesoon.mim.entity.UserKey selectByUsername(String username) {
                return null;
            }

            @Override
            public com.junesoon.mim.entity.UserKey selectByDevice(String username, String deviceId) {
                return null;
            }

            @Override
            public List<com.junesoon.mim.entity.UserKey> selectAllByUsername(String username) {
                return Collections.emptyList();
            }

            @Override
            public int upsert(com.junesoon.mim.entity.UserKey key) {
                return 0;
            }
        };
    }

    private static final class MemoryMessages implements MessageMapper {
        final List<CipherMessage> rows = new ArrayList<>();
        long seq = 10;

        @Override
        public int insert(CipherMessage message) {
            if (message.getId() == null) {
                message.setId(++seq);
            }
            rows.add(message);
            return 1;
        }

        @Override
        public CipherMessage selectByClientMsgId(String clientMsgId, String targetDeviceId) {
            for (CipherMessage row : rows) {
                if (clientMsgId.equals(row.getClientMsgId())
                    && targetDeviceId.equals(row.getTargetDeviceId())) {
                    return row;
                }
            }
            return null;
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
            for (CipherMessage row : rows) {
                if (clientMsgId.equals(row.getClientMsgId())
                    && tag.equals(row.getConversationTag())) {
                    return row;
                }
            }
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
            int affected = 0;
            for (CipherMessage row : rows) {
                if (!toUsername.equals(row.getToUsername())
                    || !tags.contains(row.getConversationTag())
                    || Boolean.TRUE.equals(row.getReadFlag())) {
                    continue;
                }
                if (beforeId != null && row.getId() != null && row.getId() > beforeId) {
                    continue;
                }
                row.setReadFlag(true);
                affected++;
            }
            return affected;
        }

        @Override
        public int markReadByClientMsgIds(String toUsername, List<String> clientMsgIds) {
            int affected = 0;
            for (CipherMessage row : rows) {
                if (!toUsername.equals(row.getToUsername())
                    || !clientMsgIds.contains(row.getClientMsgId())
                    || Boolean.TRUE.equals(row.getReadFlag())) {
                    continue;
                }
                row.setReadFlag(true);
                affected++;
            }
            return affected;
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
            return 0;
        }

        @Override
        public int markDeliveredUpTo(String toUsername, String deviceId, long beforeId) {
            return 0;
        }

        @Override
        public int recallByToken(String clientMsgId, String recallTokenHash, long recalledAt) {
            return 0;
        }
    }
}
