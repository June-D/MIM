package com.junesoon.mim.feign;

import com.junesoon.mim.components.ObjectRestResponse;
import com.junesoon.mim.model.CipherMessage;
import com.junesoon.mim.model.ClearRequest;
import com.junesoon.mim.model.ClearResult;
import com.junesoon.mim.model.ReadRequest;
import feign.Param;
import feign.RequestLine;
import java.util.List;

/**
 * 密文消息接口。
 *
 * <p>发信人身份由服务端从 JWT 解析、发信设备从 {@code X-Device-Id} 头读取，
 * 请求体里的对应字段不作数。
 *
 * <p>发送接口接受<b>数组</b>：多设备下要给收信人的每个设备各发一份密文。
 */
public interface MessageFeign extends FeignAPI {

    @RequestLine("POST /message")
    ObjectRestResponse<List<CipherMessage>> send(List<CipherMessage> messages);

    @RequestLine("GET /message/history/{peer}?beforeId={beforeId}&limit={limit}&tags={tags}")
    ObjectRestResponse<List<CipherMessage>> history(@Param("peer") String peer,
        @Param("beforeId") Long beforeId, @Param("limit") int limit,
        @Param("tags") String tags);

    @RequestLine("GET /message/undelivered?limit={limit}")
    ObjectRestResponse<List<CipherMessage>> undelivered(@Param("limit") int limit);

    @RequestLine("POST /message/delivered?beforeId={beforeId}")
    ObjectRestResponse<Integer> delivered(@Param("beforeId") long beforeId);

    @RequestLine("POST /message/read")
    ObjectRestResponse<Integer> read(ReadRequest request);

    @RequestLine("GET /message/unread/{peer}")
    ObjectRestResponse<Long> unread(@Param("peer") String peer);

    /**
     * 撤回自己发送的消息（按 clientMsgId 撤回所有设备副本）。
     */
    @RequestLine("POST /message/recall/{clientMsgId}")
    ObjectRestResponse<CipherMessage> recall(@Param("clientMsgId") String clientMsgId,
        com.junesoon.mim.model.RecallRequest request);

    @RequestLine("POST /message/clear")
    ObjectRestResponse<ClearResult> clear(ClearRequest request);

    /**
     * 把已有消息重新加密给自己的另一台设备。
     */
    @RequestLine("POST /message/sync")
    ObjectRestResponse<List<CipherMessage>> sync(List<CipherMessage> replicas);

    @RequestLine("POST /message/sync/request")
    @feign.Body("%7B%7D")
    ObjectRestResponse<Boolean> requestSync();
}
