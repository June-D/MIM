package com.junesoon.mim.feign;

import com.junesoon.mim.components.ObjectRestResponse;
import com.junesoon.mim.model.ChatContact;
import com.junesoon.mim.model.FriendRequest;
import com.junesoon.mim.model.UserSummary;
import feign.Body;
import feign.Param;
import feign.RequestLine;
import java.util.List;

/**
 * 联系人与好友管理接口。
 *
 * <p>请求体统一用对象 + Jackson 序列化，而不是 {@code @Body} 模板字符串。
 * 原因：模板里 {@code {labelName}} 这样的占位符在参数为 null 时<b>不会被替换</b>，
 * 会把字面量 "{labelName}" 原样发给服务端（已实际踩过）。
 * 走对象序列化后，null 字段就是 null，语义明确。
 */
public interface ChatFeign extends FeignAPI {

    @RequestLine("GET /chat/contacts")
    List<ChatContact> getContacts();

    /**
     * 搜索可添加的用户。
     */
    @RequestLine("GET /chat/search?keyword={keyword}")
    ObjectRestResponse<List<UserSummary>> search(@Param("keyword") String keyword);

    /**
     * 申请加好友。对方同意之前不会成为联系人。
     */
    @RequestLine("POST /chat/requests")
    ObjectRestResponse<FriendRequest> requestFriend(AddContactRequest request);

    /**
     * 待处理的好友申请：我收到的和我发出的。
     */
    @RequestLine("GET /chat/requests")
    ObjectRestResponse<List<FriendRequest>> listRequests();

    @RequestLine("POST /chat/requests/{id}/accept")
    @Body("%7B%7D")
    ObjectRestResponse<FriendRequest> acceptRequest(@Param("id") long id);

    @RequestLine("POST /chat/requests/{id}/reject")
    @Body("%7B%7D")
    ObjectRestResponse<FriendRequest> rejectRequest(@Param("id") long id);

    @RequestLine("DELETE /chat/contacts/{peer}")
    ObjectRestResponse<Boolean> removeContact(@Param("peer") String peer);

    /**
     * 修改备注名。
     */
    @RequestLine("POST /chat/contacts/{peer}/label")
    ObjectRestResponse<Boolean> renameContact(@Param("peer") String peer,
        RenameRequest request);
}
