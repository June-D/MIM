package com.junesoon.mim.controller;

import com.junesoon.mim.auth.CurrentDevice;
import com.junesoon.mim.auth.CurrentUser;
import com.junesoon.mim.ratelimit.RateLimit;
import com.junesoon.mim.requests.TwoFactorCodeRequest;
import com.junesoon.mim.requests.TwoFactorDisableRequest;
import com.junesoon.mim.responses.ObjectRestResponse;
import com.junesoon.mim.responses.PasswordChangeResult;
import com.junesoon.mim.responses.RecoveryCodesResponse;
import com.junesoon.mim.responses.TwoFactorEnableResult;
import com.junesoon.mim.responses.TwoFactorSetupResponse;
import com.junesoon.mim.service.TwoFactorService;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * TOTP 二次验证管理接口。
 */
@Slf4j
@RestController
@RequestMapping("user/2fa")
public class TwoFactorController {

    private final TwoFactorService twoFactorService;

    public TwoFactorController(TwoFactorService twoFactorService) {
        this.twoFactorService = twoFactorService;
    }

    @GetMapping("/status")
    @RateLimit(limit = 30, windowSeconds = 60, dimension = RateLimit.Dimension.USER)
    public ObjectRestResponse<Map<String, Object>> status(@CurrentUser String username) {
        Map<String, Object> body = new LinkedHashMap<>(1);
        body.put("enabled", twoFactorService.isEnabled(username));
        return new ObjectRestResponse<Map<String, Object>>().data(body).rel(true);
    }

    @PostMapping("/setup")
    @RateLimit(limit = 5, windowSeconds = 60, dimension = RateLimit.Dimension.USER)
    public ObjectRestResponse<TwoFactorSetupResponse> setup(@CurrentUser String username) {
        return new ObjectRestResponse<TwoFactorSetupResponse>()
            .data(twoFactorService.setup(username)).rel(true);
    }

    @PostMapping("/enable")
    @RateLimit(limit = 10, windowSeconds = 60, dimension = RateLimit.Dimension.USER)
    public ObjectRestResponse<TwoFactorEnableResult> enable(@CurrentUser String username,
        @CurrentDevice String deviceId, @RequestBody TwoFactorCodeRequest request)
        throws Exception {
        TwoFactorEnableResult result = twoFactorService.enable(username, deviceId,
            request == null ? null : request.getCode());
        log.info("用户 {} 设备 {} 启用了二次验证", username, deviceId);
        return new ObjectRestResponse<TwoFactorEnableResult>().data(result).rel(true);
    }

    @PostMapping("/recovery-codes")
    @RateLimit(limit = 5, windowSeconds = 60, dimension = RateLimit.Dimension.USER)
    public ObjectRestResponse<RecoveryCodesResponse> regenerateRecoveryCodes(
        @CurrentUser String username, @RequestBody TwoFactorDisableRequest request) {
        RecoveryCodesResponse result = new RecoveryCodesResponse(
            twoFactorService.regenerateRecoveryCodes(username,
                request == null ? null : request.getPassword(),
                request == null ? null : request.getCode()));
        log.info("用户 {} 重新生成了恢复码", username);
        return new ObjectRestResponse<RecoveryCodesResponse>().data(result).rel(true);
    }

    @PostMapping("/disable")
    @RateLimit(limit = 5, windowSeconds = 60, dimension = RateLimit.Dimension.USER)
    public ObjectRestResponse<PasswordChangeResult> disable(@CurrentUser String username,
        @CurrentDevice String deviceId, @RequestBody TwoFactorDisableRequest request)
        throws Exception {
        PasswordChangeResult result = twoFactorService.disable(username, deviceId,
            request == null ? null : request.getPassword(),
            request == null ? null : request.getCode());
        log.info("用户 {} 设备 {} 关闭了二次验证", username, deviceId);
        return new ObjectRestResponse<PasswordChangeResult>().data(result).rel(true);
    }
}
