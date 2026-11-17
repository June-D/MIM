package com.junesoon.mim.feign;

import com.junesoon.mim.components.ObjectRestResponse;
import com.junesoon.mim.model.ChatContact;
import com.junesoon.mim.model.UserInfo;
import com.junesoon.mim.model.JwtAuthenticationRequest;
import feign.RequestLine;
import java.util.List;

/**
 * 登录与基础信息接口。
 *
 * <p>原先把 token 拼在 URL 路径里（{@code /user/info/{token}}），
 * token 会进访问日志与代理日志。改为统一走 Authorization 头，
 * 由 {@code AppRequestInterceptor} 注入。
 */
public interface LoginFeign extends FeignAPI {

    @RequestLine("POST /login/token")
    ObjectRestResponse<String> login(JwtAuthenticationRequest request);

    @RequestLine("GET /user/info")
    ObjectRestResponse<UserInfo> getInfo();

    @RequestLine("GET /chat/contacts")
    List<ChatContact> getChatContacts();

    @RequestLine("POST /user/heartbeat")
    ObjectRestResponse<Boolean> heartbeat();
}
