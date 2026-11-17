package com.junesoon.mim.feign;

import com.junesoon.mim.components.ObjectRestResponse;
import com.junesoon.mim.model.ChatContact;
import com.junesoon.mim.model.UserInfo;
import com.junesoon.mim.model.JwtAuthenticationRequest;
import com.junesoon.mim.model.PasswordChangeRequest;
import com.junesoon.mim.model.PasswordChangeResult;
import feign.Headers;
import feign.Param;
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

    /**
     * 通知服务端登出，使当前设备令牌立即失效。
     *
     * <p>显式带 Authorization：登出线程可能在本地 token 清理之后才发出请求。
     */
    @Headers("Authorization: {authorization}")
    @RequestLine("POST /login/logout")
    ObjectRestResponse<Boolean> logout(@Param("authorization") String authorization);

    @RequestLine("GET /user/info")
    ObjectRestResponse<UserInfo> getInfo();

    /**
     * 修改登录密码。成功后返回当前设备的新令牌。
     */
    @RequestLine("POST /user/password")
    ObjectRestResponse<PasswordChangeResult> changePassword(PasswordChangeRequest request);

    @RequestLine("GET /chat/contacts")
    List<ChatContact> getChatContacts();

    @RequestLine("POST /user/heartbeat")
    ObjectRestResponse<Boolean> heartbeat();
}
