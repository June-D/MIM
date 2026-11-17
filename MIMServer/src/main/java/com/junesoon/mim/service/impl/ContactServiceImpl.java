package com.junesoon.mim.service.impl;

import com.junesoon.mim.entity.ChatContact;
import com.junesoon.mim.entity.ChatGroup;
import com.junesoon.mim.entity.ChatUser;
import com.junesoon.mim.entity.FriendRequest;
import com.junesoon.mim.entity.User;
import com.junesoon.mim.exception.UserInvalidException;
import com.junesoon.mim.mapper.ChatGroupMapper;
import com.junesoon.mim.mapper.ChatUserMapper;
import com.junesoon.mim.mapper.FriendRequestMapper;
import com.junesoon.mim.mapper.UserKeyMapper;
import com.junesoon.mim.mapper.UserMapper;
import com.junesoon.mim.responses.FriendRequestView;
import com.junesoon.mim.responses.UserSummary;
import com.junesoon.mim.service.ContactService;
import com.junesoon.mim.service.FriendRequestResult;
import com.junesoon.mim.service.PresenceService;
import com.junesoon.mim.utils.ValidationUtil;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * 联系人相关逻辑。
 */
@Slf4j
@Service
public class ContactServiceImpl implements ContactService {

    private static final int SEARCH_LIMIT = 20;
    private static final int MIN_KEYWORD_LENGTH = 1;
    private static final int MAX_LABEL_LENGTH = 32;
    private static final int MAX_CONTACTS = 500;

    private final ChatGroupMapper chatGroupMapper;
    private final ChatUserMapper chatUserMapper;
    private final UserMapper userMapper;
    private final UserKeyMapper userKeyMapper;
    private final PresenceService presenceService;
    private final FriendRequestMapper friendRequestMapper;

    public ContactServiceImpl(ChatGroupMapper chatGroupMapper, ChatUserMapper chatUserMapper,
        UserMapper userMapper, UserKeyMapper userKeyMapper, PresenceService presenceService,
        FriendRequestMapper friendRequestMapper) {
        this.chatGroupMapper = chatGroupMapper;
        this.chatUserMapper = chatUserMapper;
        this.userMapper = userMapper;
        this.userKeyMapper = userKeyMapper;
        this.presenceService = presenceService;
        this.friendRequestMapper = friendRequestMapper;
    }

    @Override
    public List<ChatUser> getFlatContacts(String username) {
        List<ChatUser> chatUsers = chatUserMapper.selectChatUserListByOwnerUser(username);
        return chatUsers == null ? Collections.emptyList() : chatUsers;
    }

    @Override
    public List<ChatContact> getContactsByUsername(String username) {
        List<ChatContact> result = new ArrayList<>();
        List<ChatGroup> chatGroups = chatGroupMapper.selectChatGroupListByOwnerUser(username);
        if (chatGroups != null) {
            Collections.sort(chatGroups);
        } else {
            chatGroups = Collections.emptyList();
        }
        Map<String, List<ChatUser>> groupMap = new HashMap<>();
        for (ChatGroup group : chatGroups) {
            ChatContact contact = new ChatContact();
            contact.setGroupId(group.getGroupId());
            contact.setGroupName(group.getGroupName());
            List<ChatUser> users = new ArrayList<>();
            contact.setUsers(users);
            result.add(contact);
            groupMap.put(group.getGroupId(), users);
        }
        // 默认分组始终存在，便于收纳未指定分组的联系人
        ChatContact defaultGroup = new ChatContact();
        defaultGroup.setGroupId("0");
        defaultGroup.setGroupName("我的好友");
        List<ChatUser> defaultUsers = new ArrayList<>();
        defaultGroup.setUsers(defaultUsers);
        result.add(defaultGroup);
        groupMap.put("0", defaultUsers);

        List<ChatUser> chatUsers = chatUserMapper.selectChatUserListByOwnerUser(username);
        if (chatUsers == null || chatUsers.isEmpty()) {
            return result;
        }
        chatUsers.sort(ChatUser::compareTo);

        Set<String> online;
        try {
            List<String> names = new ArrayList<>(chatUsers.size());
            for (ChatUser user : chatUsers) {
                names.add(user.getUsername());
            }
            online = presenceService.filterOnline(names);
        } catch (Exception e) {
            online = Collections.emptySet();
        }
        for (ChatUser user : chatUsers) {
            user.setOnline(online.contains(user.getUsername()));
            List<ChatUser> users = groupMap.get(user.getGroupId());
            if (users == null) {
                users = defaultUsers;
            }
            users.add(user);
        }
        return result;
    }

    @Override
    public List<UserSummary> searchUsers(String self, String keyword) {
        ValidationUtil.validateUsername(self);
        if (!StringUtils.hasText(keyword)) {
            throw new UserInvalidException("请输入要搜索的用户名");
        }
        String trimmed = keyword.trim();
        if (trimmed.length() < MIN_KEYWORD_LENGTH) {
            throw new UserInvalidException("搜索关键词太短");
        }
        if (trimmed.length() > 32) {
            throw new UserInvalidException("搜索关键词过长");
        }
        List<User> users = chatUserMapper.searchAddableUsers(trimmed, self, SEARCH_LIMIT);
        if (users == null || users.isEmpty()) {
            return Collections.emptyList();
        }
        List<UserSummary> result = new ArrayList<>(users.size());
        for (User user : users) {
            // canReceive 反映对方是否已建立加密身份：没登录过的人收不到消息。
            // 注意要查"所有设备"，不能只查 default——用户可能只在手机端登录过。
            boolean canReceive = !userKeyMapper.selectAllByUsername(user.getUsername()).isEmpty();
            result.add(UserSummary.of(user.getUsername(), user.getNickname(), user.getPic(),
                user.getSignature(), canReceive));
        }
        return result;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ChatUser addContact(String self, String peer, String labelName) {
        ValidationUtil.validateUsername(self);
        if (!StringUtils.hasText(peer)) {
            throw new UserInvalidException("请输入要添加的用户名");
        }
        String target = peer.trim();
        ValidationUtil.validateUsername(target);
        if (self.equals(target)) {
            throw new UserInvalidException("不能添加自己为好友");
        }
        User user = userMapper.selectOne(target);
        if (user == null) {
            throw new UserInvalidException("用户不存在：" + target);
        }
        if (chatUserMapper.countContacts(self) >= MAX_CONTACTS) {
            throw new UserInvalidException("好友数量已达上限（" + MAX_CONTACTS + "）");
        }

        String label = normalizeLabel(labelName, target);
        ChatUser existing = chatUserMapper.selectRelation(self, target);
        if (existing != null) {
            // 已是好友：只更新备注，不报错，让重复点击是幂等的
            if (labelName != null && !labelName.trim().isEmpty()) {
                chatUserMapper.updateLabel(self, target, label);
                existing.setLabelName(label);
            }
            return existing;
        }

        // 双向建立关系：对方也会看到我，避免出现单向好友导致消息能发但对方找不到人
        chatUserMapper.insertContact(self, target, label);
        chatUserMapper.insertContact(target, self, null);

        ChatUser created = chatUserMapper.selectRelation(self, target);
        if (created == null) {
            throw new UserInvalidException("添加好友失败，请重试");
        }
        log.info("{} 添加好友 {}", self, target);
        return created;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void removeContact(String self, String peer) {
        ValidationUtil.validateUsername(self);
        if (!StringUtils.hasText(peer)) {
            throw new UserInvalidException("请输入要删除的用户名");
        }
        String target = peer.trim();
        int removed = chatUserMapper.deleteContact(self, target);
        if (removed == 0) {
            throw new UserInvalidException("对方不在你的好友列表里");
        }
        // 双向删除：只删一边会留下「我能看到你、你看不到我」的错位
        chatUserMapper.deleteContact(target, self);
        log.info("{} 删除好友 {}", self, target);
    }

    @Override
    public void renameContact(String self, String peer, String labelName) {
        ValidationUtil.validateUsername(self);
        if (!StringUtils.hasText(peer)) {
            throw new UserInvalidException("请输入要修改的用户名");
        }
        String target = peer.trim();
        if (chatUserMapper.selectRelation(self, target) == null) {
            throw new UserInvalidException("对方不在你的好友列表里");
        }
        chatUserMapper.updateLabel(self, target, normalizeLabel(labelName, target));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public FriendRequestResult requestFriend(String self, String peer) {
        String target = requireOtherUser(self, peer);
        if (chatUserMapper.selectRelation(self, target) != null) {
            throw new UserInvalidException("已是好友");
        }
        // 对方已经向我申请时，不自动同意。同意必须是被申请人的明确动作。
        FriendRequest incoming = friendRequestMapper.selectByPair(target, self);
        if (incoming != null && incoming.getStatus() == FriendRequest.PENDING) {
            throw new UserInvalidException("对方已向你发出申请，请在好友申请里同意");
        }

        long now = System.currentTimeMillis();
        FriendRequest existing = friendRequestMapper.selectByPair(self, target);
        if (existing != null && existing.getStatus() == FriendRequest.PENDING) {
            return FriendRequestResult.of(toView(existing, self), null);
        }
        if (existing != null) {
            int reopened = friendRequestMapper.reopen(existing.getId(), now);
            if (reopened == 0) {
                FriendRequest again = friendRequestMapper.selectByPair(self, target);
                if (again != null && again.getStatus() == FriendRequest.PENDING) {
                    return FriendRequestResult.of(toView(again, self), null);
                }
                throw new UserInvalidException("申请发送失败，请重试");
            }
            existing.setStatus(FriendRequest.PENDING);
            existing.setUpdatedAt(now);
            log.info("{} 再次向 {} 申请好友", self, target);
            return FriendRequestResult.of(toView(existing, self), target);
        }

        FriendRequest created = new FriendRequest();
        created.setFromUser(self);
        created.setToUser(target);
        created.setStatus(FriendRequest.PENDING);
        created.setCreatedAt(now);
        created.setUpdatedAt(now);
        try {
            friendRequestMapper.insert(created);
        } catch (DuplicateKeyException e) {
            FriendRequest raced = friendRequestMapper.selectByPair(self, target);
            if (raced != null && raced.getStatus() == FriendRequest.PENDING) {
                return FriendRequestResult.of(toView(raced, self), null);
            }
            throw new UserInvalidException("申请发送失败，请重试");
        }
        log.info("{} 向 {} 申请好友", self, target);
        return FriendRequestResult.of(toView(created, self), target);
    }

    @Override
    public List<FriendRequestView> listRequests(String self) {
        ValidationUtil.validateUsername(self);
        List<FriendRequest> rows = friendRequestMapper.selectPending(self);
        if (rows == null || rows.isEmpty()) {
            return Collections.emptyList();
        }
        List<FriendRequestView> views = new ArrayList<>(rows.size());
        for (FriendRequest row : rows) {
            views.add(toView(row, self));
        }
        return views;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public FriendRequestResult acceptRequest(String self, long requestId) {
        FriendRequest request = requireIncomingPending(self, requestId);
        String peer = request.getFromUser();
        if (chatUserMapper.selectRelation(self, peer) == null) {
            ensureCapacity(self, peer);
            chatUserMapper.insertContact(self, peer, peer);
            chatUserMapper.insertContact(peer, self, self);
        }
        int updated = friendRequestMapper.resolve(request.getId(), FriendRequest.ACCEPTED,
            System.currentTimeMillis());
        if (updated == 0) {
            throw new UserInvalidException("这条申请已经处理过了");
        }
        settleReverse(self, peer);
        log.info("{} 同意了 {} 的好友申请", self, peer);
        request.setStatus(FriendRequest.ACCEPTED);
        return FriendRequestResult.of(toView(request, self), peer);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public FriendRequestResult rejectRequest(String self, long requestId) {
        FriendRequest request = requireIncomingPending(self, requestId);
        int updated = friendRequestMapper.resolve(request.getId(), FriendRequest.REJECTED,
            System.currentTimeMillis());
        if (updated == 0) {
            throw new UserInvalidException("这条申请已经处理过了");
        }
        log.info("{} 拒绝了 {} 的好友申请", self, request.getFromUser());
        request.setStatus(FriendRequest.REJECTED);
        return FriendRequestResult.of(toView(request, self), request.getFromUser());
    }

    /**
     * 校验申请人与被申请人，并确认对方账号存在。
     */
    private String requireOtherUser(String self, String peer) {
        ValidationUtil.validateUsername(self);
        if (!StringUtils.hasText(peer)) {
            throw new UserInvalidException("请输入要添加的用户名");
        }
        String target = peer.trim();
        ValidationUtil.validateUsername(target);
        if (self.equals(target)) {
            throw new UserInvalidException("不能添加自己为好友");
        }
        if (userMapper.selectOne(target) == null) {
            throw new UserInvalidException("用户不存在：" + target);
        }
        return target;
    }

    /**
     * 只有被申请人能处理这条待同意的申请。
     */
    private FriendRequest requireIncomingPending(String self, long requestId) {
        ValidationUtil.validateUsername(self);
        FriendRequest request = friendRequestMapper.selectById(requestId);
        if (request == null) {
            throw new UserInvalidException("申请不存在");
        }
        if (!self.equals(request.getToUser())) {
            throw new UserInvalidException("只能由对方同意或拒绝");
        }
        if (request.getStatus() != FriendRequest.PENDING) {
            throw new UserInvalidException("这条申请已经处理过了");
        }
        return request;
    }

    private void ensureCapacity(String self, String peer) {
        Integer mine = chatUserMapper.countContacts(self);
        Integer theirs = chatUserMapper.countContacts(peer);
        if (mine != null && mine >= MAX_CONTACTS) {
            throw new UserInvalidException("好友数量已达上限（" + MAX_CONTACTS + "）");
        }
        if (theirs != null && theirs >= MAX_CONTACTS) {
            throw new UserInvalidException("对方好友数量已达上限");
        }
    }

    /**
     * 双方同时各发了一条申请时，同意其中一条就把反向的待处理申请一并结案。
     */
    private void settleReverse(String acceptor, String requester) {
        FriendRequest reverse = friendRequestMapper.selectByPair(acceptor, requester);
        if (reverse != null && reverse.getStatus() == FriendRequest.PENDING) {
            friendRequestMapper.resolve(reverse.getId(), FriendRequest.ACCEPTED,
                System.currentTimeMillis());
        }
    }

    private FriendRequestView toView(FriendRequest request, String self) {
        boolean incoming = self.equals(request.getToUser());
        String username = incoming ? request.getFromUser() : request.getToUser();
        String direction = incoming ? FriendRequestView.IN : FriendRequestView.OUT;
        String status;
        if (request.getStatus() == FriendRequest.ACCEPTED) {
            status = FriendRequestView.ACCEPTED;
        } else if (request.getStatus() == FriendRequest.REJECTED) {
            status = FriendRequestView.REJECTED;
        } else {
            status = FriendRequestView.PENDING;
        }
        return FriendRequestView.of(request.getId(), username, direction, status);
    }

    /**
     * 备注名：空则回退到用户名，长度受限。
     *
     * <p>额外挡住形如 {@code {labelName}} 的模板占位符：这类值通常是客户端
     * 模板替换失败的产物，存进去会让界面上显示一串花括号。
     */
    private String normalizeLabel(String labelName, String fallback) {
        if (!StringUtils.hasText(labelName)) {
            return fallback;
        }
        String label = labelName.trim();
        if (label.matches("^\\{[A-Za-z0-9_]+}$")) {
            // 模板占位符泄漏，视为未提供备注名
            return fallback;
        }
        if (label.length() > MAX_LABEL_LENGTH) {
            throw new UserInvalidException("备注名不能超过 " + MAX_LABEL_LENGTH + " 个字");
        }
        return label;
    }
}
