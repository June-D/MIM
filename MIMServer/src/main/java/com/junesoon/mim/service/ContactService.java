package com.junesoon.mim.service;

import com.junesoon.mim.entity.ChatUser;
import com.junesoon.mim.responses.FriendRequestView;
import com.junesoon.mim.responses.UserSummary;
import java.util.List;

/**
 * 联系人管理：搜索用户、申请好友、同意或拒绝、删好友、改备注。
 */
public interface ContactService {

    /**
     * 按分组返回联系人，附带全局在线状态。
     */
    List<com.junesoon.mim.entity.ChatContact> getContactsByUsername(String username);

    /**
     * 平铺的联系人列表（不带分组结构），用于在线状态广播等场景。
     */
    List<ChatUser> getFlatContacts(String username);

    /**
     * 搜索可以添加的用户。
     *
     * @param keyword 用户名或昵称前缀，至少 1 个字符
     * @return 脱敏后的用户摘要，不含口令等内部字段
     */
    List<UserSummary> searchUsers(String self, String keyword);

    /**
     * 直接建立双向好友。只留给已经同意的流程内部使用，HTTP 不再暴露。
     *
     * @return 新增后的联系人记录
     */
    ChatUser addContact(String self, String peer, String labelName);

    /**
     * 向对方发出好友申请。对方同意之前，双方都不会出现在联系人里。
     */
    FriendRequestResult requestFriend(String self, String peer);

    /**
     * 我发出的和发给我的、仍待处理的申请。
     */
    List<FriendRequestView> listRequests(String self);

    /**
     * 被申请人同意。成功后双方成为好友。
     */
    FriendRequestResult acceptRequest(String self, long requestId);

    /**
     * 被申请人拒绝。不建立好友关系。
     */
    FriendRequestResult rejectRequest(String self, long requestId);

    /**
     * 删除好友。同样双向删除，避免出现「我能看到你但你看不到我」的错位。
     */
    void removeContact(String self, String peer);

    /**
     * 修改备注名。
     */
    void renameContact(String self, String peer, String labelName);
}
