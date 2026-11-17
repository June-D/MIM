package com.junesoon.mim.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.junesoon.mim.entity.ChatUser;
import com.junesoon.mim.entity.FriendRequest;
import com.junesoon.mim.entity.User;
import com.junesoon.mim.entity.UserKey;
import com.junesoon.mim.exception.UserInvalidException;
import com.junesoon.mim.mapper.ChatGroupMapper;
import com.junesoon.mim.mapper.ChatUserMapper;
import com.junesoon.mim.mapper.FriendRequestMapper;
import com.junesoon.mim.mapper.UserKeyMapper;
import com.junesoon.mim.mapper.UserMapper;
import com.junesoon.mim.responses.FriendRequestView;
import com.junesoon.mim.service.impl.ContactServiceImpl;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;

/**
 * 好友申请必须对方同意后才写入联系人表。
 */
class FriendRequestTest {

    private MemoryRequests requests;
    private MemoryContacts contacts;
    private ContactServiceImpl service;

    @BeforeEach
    void setUp() {
        requests = new MemoryRequests();
        contacts = new MemoryContacts();
        service = new ContactServiceImpl(username -> new ArrayList<>(), contacts,
            new MemoryUsers(), new MemoryKeys(), null, requests);
    }

    @Test
    @DisplayName("申请只产生待处理记录，双方都还不是好友")
    void requestDoesNotCreateContact() {
        FriendRequestResult result = service.requestFriend("alice", "bob");

        assertEquals(FriendRequestView.OUT, result.getView().getDirection());
        assertEquals(FriendRequestView.PENDING, result.getView().getStatus());
        assertEquals("bob", result.getNotifyUser());
        assertTrue(contacts.pairs.isEmpty());
        assertEquals(1, service.listRequests("bob").size());
        assertEquals(FriendRequestView.IN, service.listRequests("bob").get(0).getDirection());
    }

    @Test
    @DisplayName("重复申请同一人是幂等的，不再次通知")
    void duplicateRequestIsIdempotent() {
        FriendRequestResult first = service.requestFriend("alice", "bob");
        FriendRequestResult second = service.requestFriend("alice", "bob");

        assertEquals(first.getView().getId(), second.getView().getId());
        assertEquals(null, second.getNotifyUser());
        assertEquals(1, requests.rows.size());
    }

    @Test
    @DisplayName("对方已经申请我时，不会自动变成好友")
    void reverseRequestDoesNotAutoAccept() {
        service.requestFriend("bob", "alice");

        UserInvalidException error = assertThrows(UserInvalidException.class,
            () -> service.requestFriend("alice", "bob"));

        assertTrue(error.getMessage().contains("好友申请"));
        assertTrue(contacts.pairs.isEmpty());
    }

    @Test
    @DisplayName("只有被申请人同意后，双方才同时成为好友")
    void acceptCreatesMutualContacts() {
        long id = service.requestFriend("alice", "bob").getView().getId();

        UserInvalidException denied = assertThrows(UserInvalidException.class,
            () -> service.acceptRequest("alice", id));
        assertTrue(denied.getMessage().contains("对方"));
        assertTrue(contacts.pairs.isEmpty());

        FriendRequestResult accepted = service.acceptRequest("bob", id);
        assertEquals("alice", accepted.getNotifyUser());
        assertEquals(FriendRequestView.ACCEPTED, accepted.getView().getStatus());
        assertTrue(contacts.pairs.contains("alice|bob"));
        assertTrue(contacts.pairs.contains("bob|alice"));
        assertTrue(service.listRequests("alice").isEmpty());
    }

    @Test
    @DisplayName("拒绝不建立好友，之后可以再次申请")
    void rejectThenRequestAgain() {
        long id = service.requestFriend("alice", "bob").getView().getId();

        FriendRequestResult rejected = service.rejectRequest("bob", id);
        assertEquals(FriendRequestView.REJECTED, rejected.getView().getStatus());
        assertTrue(contacts.pairs.isEmpty());

        FriendRequestResult again = service.requestFriend("alice", "bob");
        assertEquals(id, again.getView().getId().longValue());
        assertEquals(FriendRequestView.PENDING, again.getView().getStatus());
        assertEquals("bob", again.getNotifyUser());
        assertTrue(contacts.pairs.isEmpty());
    }

    @Test
    @DisplayName("已是好友不能再申请")
    void alreadyFriends() {
        contacts.pairs.add("alice|bob");
        UserInvalidException error = assertThrows(UserInvalidException.class,
            () -> service.requestFriend("alice", "bob"));
        assertEquals("已是好友", error.getMessage());
    }

    @Test
    @DisplayName("双方各发一条时，同意一条会把反向申请一并结案")
    void acceptingOneSettlesTheReverse() {
        long aliceToBob = service.requestFriend("alice", "bob").getView().getId();
        // 直接写入反向待处理，绕过「对方已申请」的拦截，覆盖并发各发一条的收尾
        FriendRequest reverse = new FriendRequest();
        reverse.setFromUser("bob");
        reverse.setToUser("alice");
        reverse.setStatus(FriendRequest.PENDING);
        reverse.setCreatedAt(1L);
        reverse.setUpdatedAt(1L);
        requests.insert(reverse);

        service.acceptRequest("bob", aliceToBob);

        assertEquals(FriendRequest.ACCEPTED, requests.selectById(reverse.getId()).getStatus());
        assertTrue(service.listRequests("alice").isEmpty());
        assertTrue(service.listRequests("bob").isEmpty());
    }

    private static final class MemoryUsers implements UserMapper {
        @Override
        public User selectOne(String username) {
            if ("alice".equals(username) || "bob".equals(username)) {
                User user = new User();
                user.setUsername(username);
                return user;
            }
            return null;
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

    private static final class MemoryKeys implements UserKeyMapper {
        @Override
        public UserKey selectByUsername(String username) {
            return null;
        }

        @Override
        public UserKey selectByDevice(String username, String deviceId) {
            return null;
        }

        @Override
        public java.util.List<UserKey> selectAllByUsername(String username) {
            return new ArrayList<>();
        }

        @Override
        public int upsert(UserKey key) {
            return 0;
        }
    }

    private static final class MemoryContacts implements ChatUserMapper {
        final Set<String> pairs = new HashSet<>();

        @Override
        public List<ChatUser> selectChatUserListByOwnerUser(String username) {
            return new ArrayList<>();
        }

        @Override
        public List<User> searchAddableUsers(String keyword, String self, int limit) {
            return new ArrayList<>();
        }

        @Override
        public ChatUser selectRelation(String ownerUser, String username) {
            if (!pairs.contains(ownerUser + "|" + username)) {
                return null;
            }
            ChatUser row = new ChatUser();
            row.setOwnerUser(ownerUser);
            row.setUsername(username);
            return row;
        }

        @Override
        public int insertContact(String ownerUser, String username, String labelName) {
            pairs.add(ownerUser + "|" + username);
            return 1;
        }

        @Override
        public int deleteContact(String ownerUser, String username) {
            pairs.remove(ownerUser + "|" + username);
            return 1;
        }

        @Override
        public int updateLabel(String ownerUser, String username, String labelName) {
            return 1;
        }

        @Override
        public int countContacts(String ownerUser) {
            int count = 0;
            for (String pair : pairs) {
                if (pair.startsWith(ownerUser + "|")) {
                    count++;
                }
            }
            return count;
        }
    }

    private static final class MemoryRequests implements FriendRequestMapper {
        final Map<Long, FriendRequest> rows = new HashMap<>();
        private long seq;

        @Override
        public int insert(FriendRequest request) {
            for (FriendRequest row : rows.values()) {
                if (row.getFromUser().equals(request.getFromUser())
                    && row.getToUser().equals(request.getToUser())) {
                    throw new DuplicateKeyException("dup");
                }
            }
            request.setId(++seq);
            rows.put(request.getId(), copy(request));
            return 1;
        }

        @Override
        public FriendRequest selectById(long id) {
            FriendRequest row = rows.get(id);
            return row == null ? null : copy(row);
        }

        @Override
        public FriendRequest selectByPair(String fromUser, String toUser) {
            for (FriendRequest row : rows.values()) {
                if (row.getFromUser().equals(fromUser) && row.getToUser().equals(toUser)) {
                    return copy(row);
                }
            }
            return null;
        }

        @Override
        public int reopen(long id, long updatedAt) {
            FriendRequest row = rows.get(id);
            if (row == null || row.getStatus() == FriendRequest.PENDING) {
                return 0;
            }
            row.setStatus(FriendRequest.PENDING);
            row.setUpdatedAt(updatedAt);
            return 1;
        }

        @Override
        public int resolve(long id, int status, long updatedAt) {
            FriendRequest row = rows.get(id);
            if (row == null || row.getStatus() != FriendRequest.PENDING) {
                return 0;
            }
            row.setStatus(status);
            row.setUpdatedAt(updatedAt);
            return 1;
        }

        @Override
        public List<FriendRequest> selectPending(String username) {
            List<FriendRequest> found = new ArrayList<>();
            for (FriendRequest row : rows.values()) {
                if (row.getStatus() == FriendRequest.PENDING
                    && (username.equals(row.getFromUser()) || username.equals(row.getToUser()))) {
                    found.add(copy(row));
                }
            }
            return found;
        }

        private static FriendRequest copy(FriendRequest source) {
            FriendRequest copy = new FriendRequest();
            copy.setId(source.getId());
            copy.setFromUser(source.getFromUser());
            copy.setToUser(source.getToUser());
            copy.setStatus(source.getStatus());
            copy.setCreatedAt(source.getCreatedAt());
            copy.setUpdatedAt(source.getUpdatedAt());
            return copy;
        }
    }
}
