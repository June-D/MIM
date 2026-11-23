package com.junesoon.mim.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.junesoon.mim.entity.CipherMessage;
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
 * 撤回规则的单元测试。
 *
 * <p>Phase 2 断代后撤回只有一条路径：用客户端持有的会话标签定位副本，
 * 再用一次性 token 的哈希授权。没有标签或没有 token 都必须拒绝。
 *
 * [INPUT]: 只依赖 MessageServiceImpl 与手写 Mapper 桩
 * [OUTPUT]: 覆盖成功、缺标签、缺 token、错 token、超时、已撤回、不存在
 * [POS]: 撤回授权的最小回归集
 * [PROTOCOL]: Update this header on change, then check CLAUDE.md
 */
class MessageRecallTest {

    private static final String CLIENT_MSG_ID = "client-msg-0001";
    private static final String TOKEN = "3q2+7wAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=";
    private static final String TAG = "tagAAAAAAAAAAAAAAAAAAAAAAAA1";

    private static final class StubMessageMapper implements MessageMapper {
        CipherMessage tagsLookupResult;
        int recallAffected = 1;
        boolean recallCalled;
        boolean tokenPath;
        String usedTokenHash;

        @Override
        public int insert(CipherMessage message) {
            return 1;
        }

        @Override
        public CipherMessage selectByClientMsgId(String clientMsgId, String targetDeviceId) {
            return null;
        }

        @Override
        public CipherMessage selectById(Long id) {
            return null;
        }

        @Override
        public CipherMessage selectOwnedCopyByTags(List<String> tags, String clientMsgId) {
            return tagsLookupResult;
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
        public int markReadByClientMsgIds(String toUsername, java.util.List<String> clientMsgIds) {
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
            recallCalled = true;
            tokenPath = true;
            usedTokenHash = recallTokenHash;
            return recallAffected;
        }
    }

    private StubMessageMapper messageMapper;
    private MessageServiceImpl service;

    @BeforeEach
    void setUp() throws Exception {
        messageMapper = new StubMessageMapper();
        ConversationClearMapper clearMapper = new ConversationClearMapper() {
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
            public int advance(String username, String peer, long clearedBeforeId,
                long updatedAt) {
                return 1;
            }
        };
        service = new MessageServiceImpl(messageMapper, clearMapper,
            (UserMapper) null, (UserKeyMapper) null, (DeviceMapper) null,
            (com.junesoon.mim.service.PushService) null);
        setField(service, "recallWindowSeconds", 120L);
    }

    private CipherMessage taggedMessage() {
        CipherMessage message = new CipherMessage();
        message.setClientMsgId(CLIENT_MSG_ID);
        message.setToUsername("bob");
        message.setConversationTag(TAG);
        message.setRecallTokenHash(RecallToken.hash(TOKEN));
        message.setServerTime(System.currentTimeMillis());
        message.setRecalled(false);
        return message;
    }

    private CipherMessage recall(String token, List<String> tags) {
        return service.recall("alice", CLIENT_MSG_ID, token, tags);
    }

    @Test
    @DisplayName("标签 + 一次性 token 正确时可撤回，且只把哈希传给 Mapper")
    void recallWithTagAndToken() {
        CipherMessage sample = taggedMessage();
        messageMapper.tagsLookupResult = sample;

        CipherMessage result = recall(TOKEN, Collections.singletonList(TAG));

        assertTrue(messageMapper.recallCalled);
        assertTrue(messageMapper.tokenPath, "必须走 token 路径");
        assertEquals(RecallToken.hash(TOKEN), messageMapper.usedTokenHash,
            "传给 Mapper 的只能是哈希");
        assertSame(sample, result);
        assertTrue(Boolean.TRUE.equals(result.getRecalled()));
        assertTrue(result.getCiphertext() == null);
    }

    @Test
    @DisplayName("没有会话标签时拒绝：服务端已无法按用户名定位消息")
    void missingTagRejected() {
        messageMapper.tagsLookupResult = taggedMessage();
        UserInvalidException ex = assertThrows(UserInvalidException.class,
            () -> recall(TOKEN, Collections.emptyList()));
        assertTrue(ex.getMessage().contains("标签"));
        assertFalse(messageMapper.recallCalled);
    }

    @Test
    @DisplayName("token 缺失或不匹配时拒绝，不写库")
    void tokenRequired() {
        messageMapper.tagsLookupResult = taggedMessage();
        assertThrows(UserInvalidException.class,
            () -> recall(null, Collections.singletonList(TAG)));
        assertThrows(UserInvalidException.class,
            () -> recall("wrong-token-1234567890", Collections.singletonList(TAG)));
        assertFalse(messageMapper.recallCalled);
    }

    @Test
    @DisplayName("超过撤回窗口被拒绝")
    void expiredWindowRejected() {
        CipherMessage sample = taggedMessage();
        sample.setServerTime(System.currentTimeMillis() - 121_000L);
        messageMapper.tagsLookupResult = sample;
        UserInvalidException ex = assertThrows(UserInvalidException.class,
            () -> recall(TOKEN, Collections.singletonList(TAG)));
        assertTrue(ex.getMessage().contains("时限"));
    }

    @Test
    @DisplayName("已撤回的消息不能重复撤回")
    void alreadyRecalledRejected() {
        CipherMessage sample = taggedMessage();
        sample.setRecalled(true);
        messageMapper.tagsLookupResult = sample;
        assertThrows(UserInvalidException.class,
            () -> recall(TOKEN, Collections.singletonList(TAG)));
    }

    @Test
    @DisplayName("消息不存在时报错")
    void missingMessageRejected() {
        messageMapper.tagsLookupResult = null;
        UserInvalidException ex = assertThrows(UserInvalidException.class,
            () -> recall(TOKEN, Collections.singletonList(TAG)));
        assertTrue(ex.getMessage().contains("不存在"));
    }

    @Test
    @DisplayName("消息 ID 为空时报错")
    void blankClientMsgIdRejected() {
        assertThrows(UserInvalidException.class,
            () -> service.recall("alice", "", TOKEN, Collections.singletonList(TAG)));
    }

    @Test
    @DisplayName("并发撤回 affected=0 视为已撤回")
    void concurrentRecallRejected() {
        messageMapper.tagsLookupResult = taggedMessage();
        messageMapper.recallAffected = 0;
        UserInvalidException ex = assertThrows(UserInvalidException.class,
            () -> recall(TOKEN, Collections.singletonList(TAG)));
        assertTrue(ex.getMessage().contains("已撤回"));
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }
}
