package com.junesoon.mim.controller;

import com.junesoon.mim.auth.CurrentUser;
import com.junesoon.mim.constant.CommonConstants;
import com.junesoon.mim.entity.BaseNotify;
import com.junesoon.mim.entity.ChatContact;
import com.junesoon.mim.ratelimit.RateLimit;
import com.junesoon.mim.responses.FriendRequestView;
import com.junesoon.mim.responses.ObjectRestResponse;
import com.junesoon.mim.responses.UserSummary;
import com.junesoon.mim.service.ContactService;
import com.junesoon.mim.service.FriendRequestResult;
import com.junesoon.mim.service.PushService;
import java.util.List;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 联系人与好友管理。
 *
 * <p>这是「用户怎么和别人建立联系」的入口：没有它，端到端加密再正确也没人能用到。
 */
@Slf4j
@RestController
@RequestMapping("chat")
public class ChatController {

    private final ContactService contactService;
    private final PushService pushService;

    public ChatController(ContactService contactService, PushService pushService) {
        this.contactService = contactService;
        this.pushService = pushService;
    }

    /**
     * 按分组返回我的联系人，附带在线状态。
     */
    @GetMapping("/contacts")
    public List<ChatContact> getContacts(@CurrentUser String username) {
        return contactService.getContactsByUsername(username);
    }

    /**
     * 搜索可以添加的用户（排除自己与已是好友的人）。
     *
     * <p>返回 DTO 而非实体：实体含 password 字段，序列化出去会泄漏口令哈希。
     */
    @GetMapping("/search")
    @RateLimit(limit = 60, windowSeconds = 60, dimension = RateLimit.Dimension.USER)
    public ObjectRestResponse<List<UserSummary>> search(@CurrentUser String self,
        @RequestParam("keyword") String keyword) {
        return new ObjectRestResponse<List<UserSummary>>()
            .data(contactService.searchUsers(self, keyword))
            .rel(true);
    }

    /**
     * 添加好友请求。
     */
    @Data
    public static class AddContactRequest {
        private String username;
        private String labelName;
    }

    /**
     * 申请加好友。对方同意之前不会建立联系人关系。
     *
     * <p>保留旧路径，避免还有人直接 POST /chat/contacts 把对方静默加进来。
     */
    @PostMapping("/contacts")
    @RateLimit(limit = 30, windowSeconds = 60, dimension = RateLimit.Dimension.USER)
    public ObjectRestResponse<FriendRequestView> addContact(@CurrentUser String self,
        @RequestBody AddContactRequest request) {
        return respond(contactService.requestFriend(self, request.getUsername()),
            CommonConstants.NOTIFY_TYPE_FRIEND_REQUEST, self);
    }

    /**
     * 与 {@link #addContact} 相同，给新客户端一条语义更清楚的路径。
     */
    @PostMapping("/requests")
    @RateLimit(limit = 30, windowSeconds = 60, dimension = RateLimit.Dimension.USER)
    public ObjectRestResponse<FriendRequestView> requestFriend(@CurrentUser String self,
        @RequestBody AddContactRequest request) {
        return addContact(self, request);
    }

    /**
     * 待处理的好友申请：我收到的和我发出的。
     */
    @GetMapping("/requests")
    public ObjectRestResponse<List<FriendRequestView>> listRequests(@CurrentUser String self) {
        return new ObjectRestResponse<List<FriendRequestView>>()
            .data(contactService.listRequests(self))
            .rel(true);
    }

    /**
     * 同意一条发给我的申请。同意后双方成为好友。
     */
    @PostMapping("/requests/{id}/accept")
    @RateLimit(limit = 30, windowSeconds = 60, dimension = RateLimit.Dimension.USER)
    public ObjectRestResponse<FriendRequestView> acceptRequest(@CurrentUser String self,
        @PathVariable("id") long id) {
        return respond(contactService.acceptRequest(self, id),
            CommonConstants.NOTIFY_TYPE_FRIEND_ACCEPTED, self);
    }

    /**
     * 拒绝一条发给我的申请。
     */
    @PostMapping("/requests/{id}/reject")
    @RateLimit(limit = 30, windowSeconds = 60, dimension = RateLimit.Dimension.USER)
    public ObjectRestResponse<FriendRequestView> rejectRequest(@CurrentUser String self,
        @PathVariable("id") long id) {
        return respond(contactService.rejectRequest(self, id),
            CommonConstants.NOTIFY_TYPE_FRIEND_REJECTED, self);
    }

    /**
     * 把结果返回给调用方，并在需要时通知对方。
     *
     * <p>推送是提醒，不是事实来源。对方没连上时，下次打开列表或轮询仍能看到申请。
     * 推给对方的视图要换成对方的视角：他看到的是「来自谁」。
     */
    private ObjectRestResponse<FriendRequestView> respond(FriendRequestResult result, int type,
        String self) {
        if (result.getNotifyUser() != null && result.getView() != null) {
            FriendRequestView mine = result.getView();
            String theirDirection = FriendRequestView.OUT.equals(mine.getDirection())
                ? FriendRequestView.IN : FriendRequestView.OUT;
            FriendRequestView theirs = FriendRequestView.of(mine.getId(), self, theirDirection,
                mine.getStatus());
            pushService.pushToUser(result.getNotifyUser(), BaseNotify.of(type, theirs));
        }
        return new ObjectRestResponse<FriendRequestView>().data(result.getView()).rel(true);
    }

    /**
     * 删除好友（双向）。
     */
    @DeleteMapping("/contacts/{peer}")
    @RateLimit(limit = 30, windowSeconds = 60, dimension = RateLimit.Dimension.USER)
    public ObjectRestResponse<Boolean> removeContact(@CurrentUser String self,
        @PathVariable String peer) {
        contactService.removeContact(self, peer);
        return new ObjectRestResponse<Boolean>().data(true).rel(true);
    }

    /**
     * 修改好友备注名。
     */
    @PostMapping("/contacts/{peer}/label")
    @RateLimit(limit = 60, windowSeconds = 60, dimension = RateLimit.Dimension.USER)
    public ObjectRestResponse<Boolean> renameContact(@CurrentUser String self,
        @PathVariable String peer, @RequestBody AddContactRequest request) {
        contactService.renameContact(self, peer, request.getLabelName());
        return new ObjectRestResponse<Boolean>().data(true).rel(true);
    }
}
