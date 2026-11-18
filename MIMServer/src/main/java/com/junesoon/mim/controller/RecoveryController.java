package com.junesoon.mim.controller;

import com.junesoon.mim.ratelimit.RateLimit;
import com.junesoon.mim.requests.RecoverEmailRequest;
import com.junesoon.mim.requests.RecoverResetRequest;
import com.junesoon.mim.responses.ObjectRestResponse;
import com.junesoon.mim.service.RecoveryService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 邮箱找回密码。这些接口不需要登录。
 *
 * <p>发送验证码接口无论账号是否存在都返回成功，避免用户枚举；
 * 客户端提示统一为“如果该账号填写了邮箱，验证码已发送”。
 */
@RestController
@RequestMapping("recover")
public class RecoveryController {

    private final RecoveryService recoveryService;

    public RecoveryController(RecoveryService recoveryService) {
        this.recoveryService = recoveryService;
    }

    @PostMapping("/email")
    @RateLimit(limit = 5, windowSeconds = 60, dimension = RateLimit.Dimension.IP)
    public ObjectRestResponse<Boolean> sendCode(@RequestBody RecoverEmailRequest request) {
        recoveryService.sendCode(request == null ? null : request.getUsername());
        return new ObjectRestResponse<Boolean>().data(Boolean.TRUE).rel(true);
    }

    @PostMapping("/reset")
    @RateLimit(limit = 10, windowSeconds = 60, dimension = RateLimit.Dimension.IP)
    public ObjectRestResponse<Boolean> reset(@RequestBody RecoverResetRequest request) {
        if (request == null) {
            throw new com.junesoon.mim.exception.UserInvalidException("请求不能为空");
        }
        recoveryService.resetPassword(request.getUsername(), request.getCode(),
            request.getNewPassword());
        return new ObjectRestResponse<Boolean>().data(Boolean.TRUE).rel(true);
    }

    /**
     * 使用一次性恢复码重置密码。恢复码由启用 2FA 时生成。
     */
    @PostMapping("/code")
    @RateLimit(limit = 10, windowSeconds = 60, dimension = RateLimit.Dimension.IP)
    public ObjectRestResponse<Boolean> resetByRecoveryCode(
        @RequestBody RecoverResetRequest request) {
        if (request == null) {
            throw new com.junesoon.mim.exception.UserInvalidException("请求不能为空");
        }
        recoveryService.resetByRecoveryCode(request.getUsername(), request.getCode(),
            request.getNewPassword());
        return new ObjectRestResponse<Boolean>().data(Boolean.TRUE).rel(true);
    }
}
