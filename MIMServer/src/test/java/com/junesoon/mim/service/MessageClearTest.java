package com.junesoon.mim.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.junesoon.mim.entity.CipherMessage;
import com.junesoon.mim.exception.UserInvalidException;
import com.junesoon.mim.mapper.ConversationClearMapper;
import com.junesoon.mim.mapper.DeviceMapper;
import com.junesoon.mim.mapper.MessageMapper;
import com.junesoon.mim.mapper.UserKeyMapper;
import com.junesoon.mim.mapper.UserMapper;
import com.junesoon.mim.responses.ClearResult;
import com.junesoon.mim.service.impl.MessageServiceImpl;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 清除会话：只清自己时对方仍能读；清双方时两边都看不到，并且密文被抹掉。
 * 清除点只增不减。
 */
class MessageClearTest {

    private MemoryClear clear;
    private MemoryMessages messages;
    private MessageServiceImpl service;

    @BeforeEach
    void setUp() {
        clear = new MemoryClear();
        messages = new MemoryMessages();
        service = new MessageServiceImpl(messages, clear, new UserMapper() {
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
        }, new UserKeyMapper() {
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
        }, new DeviceMapper() {
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
            public int countActiveDevices(String username) {
                return 0;
            }
        }, new PushService(null, null, null) {
            @Override
            public void pushToUser(String target, Object notify) {
            }
        });
    }

    @Test
    @DisplayName("只清除我这边时，对方的清除点不动，密文还在")
    void clearMineLeavesTheOtherSide() {
        ClearResult result = service.clearConversation("alice", "bob", false);
        assertEquals(12L, result.getClearedBeforeId());
        assertEquals(Long.valueOf(12L), clear.cursor("alice", "bob"));
        assertTrue(clear.cursor("bob", "alice") == null);
        assertEquals(-1L, messages.wipedBefore);
    }

    @Test
    @DisplayName("清除双方时两边都推进，并抹掉密文")
    void clearBothWipesCiphertext() {
        ClearResult result = service.clearConversation("alice", "bob", true);
        assertTrue(result.isForBoth());
        assertEquals(Long.valueOf(12L), clear.cursor("alice", "bob"));
        assertEquals(Long.valueOf(12L), clear.cursor("bob", "alice"));
        assertEquals(12L, messages.wipedBefore);
    }

    @Test
    @DisplayName("更早的清除不会把已经清掉的消息重新露出来")
    void cursorNeverMovesBackward() {
        clear.put("alice", "bob", 30L);
        messages.maxId = 12L;
        service.clearConversation("alice", "bob", false);
        assertEquals(Long.valueOf(30L), clear.cursor("alice", "bob"));
    }

    @Test
    @DisplayName("历史不再返回清除点及更早的消息")
    void historyHidesClearedMessages() {
        clear.put("alice", "bob", 10L);
        messages.history.add(row(20L));
        messages.history.add(row(5L));
        List<CipherMessage> visible = service.history("alice", "bob", null, 50);
        assertEquals(1, visible.size());
        assertEquals(Long.valueOf(20L), visible.get(0).getId());
    }

    @Test
    @DisplayName("不能清除与自己的会话")
    void cannotClearSelf() {
        assertThrows(UserInvalidException.class,
            () -> service.clearConversation("alice", "alice", true));
    }

    private static CipherMessage row(long id) {
        CipherMessage message = new CipherMessage();
        message.setId(id);
        return message;
    }

    private static final class MemoryClear implements ConversationClearMapper {
        private final Map<String, Long> cursors = new HashMap<>();

        Long cursor(String username, String peer) {
            return cursors.get(username + "|" + peer);
        }

        void put(String username, String peer, long id) {
            cursors.put(username + "|" + peer, id);
        }

        @Override
        public Long selectCursor(String username, String peer) {
            return cursor(username, peer);
        }

        @Override
        public int insert(String username, String peer, long clearedBeforeId, long updatedAt) {
            put(username, peer, clearedBeforeId);
            return 1;
        }

        @Override
        public int advance(String username, String peer, long clearedBeforeId, long updatedAt) {
            Long existing = cursor(username, peer);
            long next = existing == null ? clearedBeforeId : Math.max(existing, clearedBeforeId);
            put(username, peer, next);
            return 1;
        }
    }

    private static final class MemoryMessages implements MessageMapper {
        Long maxId = 12L;
        long wipedBefore = -1L;
        final List<CipherMessage> history = new ArrayList<>();

        @Override
        public int insert(CipherMessage message) {
            return 1;
        }

        @Override
        public CipherMessage selectByClientMsgId(String fromUsername, String clientMsgId,
            String targetDeviceId) {
            return null;
        }

        @Override
        public CipherMessage selectById(Long id) {
            return null;
        }

        @Override
        public CipherMessage selectAnyByClientMsgId(String fromUsername, String clientMsgId) {
            return null;
        }

        @Override
        public List<CipherMessage> selectHistory(String a, String b, Long beforeId, int limit) {
            return new ArrayList<>(history);
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
        public Long maxConversationId(String a, String b) {
            return maxId;
        }

        @Override
        public int wipeConversation(String a, String b, long beforeId) {
            wipedBefore = beforeId;
            return 1;
        }

        @Override
        public long countUnreadAfter(String fromUsername, String toUsername, long afterId) {
            return 0;
        }
    }
}
