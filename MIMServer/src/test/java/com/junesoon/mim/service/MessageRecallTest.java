package com.junesoon.mim.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.junesoon.mim.entity.CipherMessage;
import com.junesoon.mim.entity.UserKey;
import com.junesoon.mim.exception.UserInvalidException;
import com.junesoon.mim.mapper.ConversationClearMapper;
import com.junesoon.mim.mapper.DeviceMapper;
import com.junesoon.mim.mapper.MessageMapper;
import com.junesoon.mim.mapper.UserKeyMapper;
import com.junesoon.mim.mapper.UserMapper;
import com.junesoon.mim.service.impl.MessageServiceImpl;
import com.junesoon.mim.utils.RecallToken;
import java.lang.reflect.Field;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 消息撤回的业务规则测试。
 *
 * <p>用最小手写 stub 而不是 Mockito：项目跑在 JDK 8 上，引入字节码代理框架
 * 的成本高于测试本身的价值，而这里依赖的 Mapper 方法只有寥寥几个。
 *
 * <p>重点验证三条不可妥协的规则：
 * <ol>
 *   <li>只有发信人能撤回；</li>
 *   <li>超过时间窗口不能撤回；</li>
 *   <li>重复撤回无效。</li>
 * </ol>
 */
class MessageRecallTest {

    private static final String CLIENT_MSG_ID = "client-msg-0001";

    private static final class StubMessageMapper implements MessageMapper {
        CipherMessage anyCopy;
        int recallAffected = 1;
        boolean recallCalled;
        boolean tokenPath;
        boolean senderPath;
        String usedTokenHash;

        @Override
        public int insert(CipherMessage message) {
            return 1;
        }

        @Override
        public CipherMessage selectByClientMsgId(String clientMsgId, String targetDeviceId) {
            return anyCopy;
        }

        @Override
        public CipherMessage selectOwnedCopy(String clientMsgId, String fromUser, String fromRef) {
            if (anyCopy == null) {
                return null;
            }
            boolean owned = fromUser != null && fromUser.equals(anyCopy.getFromUsername());
            if (!owned && fromRef != null) {
                owned = fromRef.equals(anyCopy.getFromRef())
                    || fromRef.equals(anyCopy.getFromUsername());
            }
            return owned ? anyCopy : null;
        }

        @Override
        public CipherMessage selectById(Long id) {
            return anyCopy;
        }

        @Override
        public List<CipherMessage> selectHistory(String a, String b, Long beforeId, int limit) {
            return Collections.emptyList();
        }

        @Override
        public CipherMessage selectParticipantCopy(String clientMsgId, String username,
            String userRef) {
            return null;
        }

        @Override
        public List<CipherMessage> selectHistoryForDevice(String viewer, String peer,
            String deviceId, Long beforeId, int limit) {
            return Collections.emptyList();
        }

        @Override
        public List<CipherMessage> selectRefHistory(String fromRef, String toUser,
            Long beforeId, int limit) {
            return Collections.emptyList();
        }

        @Override
        public List<CipherMessage> selectRefHistoryForDevice(String fromRef, String toUser,
            String viewer, String deviceId, String deviceRef, Long beforeId, int limit) {
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
        public int markReadByRef(String fromRef, String toUsername, Long beforeId) {
            return 0;
        }

        @Override
        public long countUnread(String fromUsername, String toUsername) {
            return 0;
        }

        @Override
        public long countUnreadByRef(String fromRef, String toUsername) {
            return 0;
        }

        @Override
        public int recallByToken(String clientMsgId, String recallTokenHash, long recalledAt) {
            recallCalled = true;
            tokenPath = true;
            usedTokenHash = recallTokenHash;
            return recallAffected;
        }

        @Override
        public int recallBySender(String clientMsgId, String fromUser, String fromRef,
            long recalledAt) {
            recallCalled = true;
            senderPath = true;
            return recallAffected;
        }

        @Override
        public long countUnreadAfter(String fromUsername, String toUsername, long afterId) {
            return 0;
        }

        @Override
        public long countUnreadAfterByRef(String fromRef, String toUsername, long afterId) {
            return 0;
        }

        @Override
        public Long maxConversationId(String a, String b) {
            return null;
        }

        @Override
        public Long maxRefConversationId(String fromRef, String toUser) {
            return null;
        }

        @Override
        public int wipeConversation(String a, String b, long beforeId) {
            return 0;
        }

        @Override
        public int wipeRefConversation(String fromRef, String toUser, long beforeId) {
            return 0;
        }
    }

    private static final class StubUserMapper implements UserMapper {
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
    }

    private static final class StubUserKeyMapper implements UserKeyMapper {
        @Override
        public UserKey selectByUsername(String username) {
            return null;
        }

        @Override
        public UserKey selectByDevice(String username, String deviceId) {
            return null;
        }

        @Override
        public List<UserKey> selectAllByUsername(String username) {
            return Collections.emptyList();
        }

        @Override
        public int upsert(UserKey key) {
            return 0;
        }
    }

    private static final class StubDeviceMapper implements DeviceMapper {
        @Override
        public int upsert(com.junesoon.mim.entity.Device device) {
            return 0;
        }

        @Override
        public com.junesoon.mim.entity.Device selectOne(String username, String deviceId) {
            return null;
        }

        @Override
        public List<com.junesoon.mim.entity.Device> selectActiveDevices(String username) {
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
    }

    private static final class StubPushService extends PushService {
        StubPushService() {
            super(null, null, null);
        }

        @Override
        public void push(String target, Object notify) {
        }
    }

    private StubMessageMapper messageMapper;
    private MessageServiceImpl service;

    @BeforeEach
    void setUp() throws Exception {
        messageMapper = new StubMessageMapper();
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
        }, new StubUserMapper(), new StubUserKeyMapper(), new StubDeviceMapper(),
            new StubPushService());
        setField(service, "maxCiphertextBytes", 262144);
        setField(service, "recallWindowSeconds", 120L);
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    private CipherMessage message(String from, long serverTime) {
        CipherMessage message = new CipherMessage();
        message.setClientMsgId(CLIENT_MSG_ID);
        message.setFromUsername(from);
        message.setToUsername("bob");
        message.setServerTime(serverTime);
        message.setRecalled(false);
        return message;
    }

    @Test
    @DisplayName("发信人在窗口内可以撤回")
    void senderCanRecallWithinWindow() {
        messageMapper.anyCopy = message("alice", System.currentTimeMillis());
        CipherMessage result = service.recall("alice", CLIENT_MSG_ID);
        assertTrue(messageMapper.recallCalled, "应当调用 Mapper 的 recall");
        assertTrue(messageMapper.senderPath, "没有凭证哈希的旧消息走发信人兼容路径");
        assertSame(messageMapper.anyCopy, result);
        assertTrue(Boolean.TRUE.equals(result.getRecalled()));
        assertTrue(result.getCiphertext() == null, "撤回后快照里的密文应被清空");
    }

    @Test
    @DisplayName("新消息带正确 recallToken 才能撤回，且只比对哈希")
    void newMessageRequiresMatchingToken() {
        String token = "3q2+7wAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=";
        CipherMessage fresh = message("alice", System.currentTimeMillis());
        fresh.setRecallTokenHash(RecallToken.hash(token));
        messageMapper.anyCopy = fresh;

        CipherMessage result = service.recall("alice", CLIENT_MSG_ID, token);
        assertTrue(messageMapper.tokenPath, "有凭证哈希的消息必须走 token 路径");
        assertFalse(messageMapper.senderPath, "不能退回发信人路径");
        assertEquals(RecallToken.hash(token), messageMapper.usedTokenHash,
            "传给 Mapper 的必须是哈希，不是原始 token");
        assertTrue(Boolean.TRUE.equals(result.getRecalled()));
    }

    @Test
    @DisplayName("token 不匹配时拒绝撤回，不写库")
    void wrongTokenRejected() {
        String token = "3q2+7wAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=";
        CipherMessage fresh = message("alice", System.currentTimeMillis());
        fresh.setRecallTokenHash(RecallToken.hash(token));
        messageMapper.anyCopy = fresh;

        UserInvalidException ex = assertThrows(UserInvalidException.class,
            () -> service.recall("alice", CLIENT_MSG_ID, "wrong-token-1234567890"));
        assertEquals("撤回凭证无效", ex.getMessage());
        assertFalse(messageMapper.recallCalled, "凭证不匹配不应触达写操作");
    }

    @Test
    @DisplayName("新消息缺少 token 时拒绝撤回")
    void missingTokenRejected() {
        String token = "3q2+7wAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=";
        CipherMessage fresh = message("alice", System.currentTimeMillis());
        fresh.setRecallTokenHash(RecallToken.hash(token));
        messageMapper.anyCopy = fresh;

        UserInvalidException ex = assertThrows(UserInvalidException.class,
            () -> service.recall("alice", CLIENT_MSG_ID));
        assertEquals("撤回凭证无效", ex.getMessage());
        assertFalse(messageMapper.recallCalled);
    }

    @Test
    @DisplayName("非发信人不能撤回他人消息")
    void nonSenderCannotRecall() {
        messageMapper.anyCopy = message("alice", System.currentTimeMillis());
        // bob 用 alice 的 clientMsgId 找不到「本人拥有」的副本
        //（selectOwnedCopy 按 from_user / from_ref 过滤）
        messageMapper.anyCopy = null;
        UserInvalidException ex = assertThrows(UserInvalidException.class,
            () -> service.recall("bob", CLIENT_MSG_ID));
        assertEquals("消息不存在", ex.getMessage());
        assertTrue(!messageMapper.recallCalled, "越权时不应触达数据库写操作");
    }

    @Test
    @DisplayName("超过撤回窗口被拒绝")
    void recallOutsideWindowRejected() {
        messageMapper.anyCopy = message("alice", System.currentTimeMillis() - 121_000L);
        UserInvalidException ex = assertThrows(UserInvalidException.class,
            () -> service.recall("alice", CLIENT_MSG_ID));
        assertTrue(ex.getMessage().contains("超过撤回时限"), "实际：" + ex.getMessage());
        assertTrue(!messageMapper.recallCalled);
    }

    @Test
    @DisplayName("已撤回的消息不能重复撤回")
    void doubleRecallRejected() {
        CipherMessage already = message("alice", System.currentTimeMillis());
        already.setRecalled(true);
        messageMapper.anyCopy = already;
        UserInvalidException ex = assertThrows(UserInvalidException.class,
            () -> service.recall("alice", CLIENT_MSG_ID));
        assertEquals("消息已撤回", ex.getMessage());
    }

    @Test
    @DisplayName("消息不存在时报错")
    void missingMessageRejected() {
        messageMapper.anyCopy = null;
        assertThrows(UserInvalidException.class, () -> service.recall("alice", "not-exist-0001"));
    }

    @Test
    @DisplayName("消息 ID 为空时报错")
    void nullIdRejected() {
        assertThrows(UserInvalidException.class, () -> service.recall("alice", null));
    }

    @Test
    @DisplayName("并发撤回时 affected=0 也报已撤回")
    void concurrentRecallRejected() {
        messageMapper.anyCopy = message("alice", System.currentTimeMillis());
        messageMapper.recallAffected = 0;
        UserInvalidException ex = assertThrows(UserInvalidException.class,
            () -> service.recall("alice", CLIENT_MSG_ID));
        assertEquals("消息已撤回", ex.getMessage());
    }
}
