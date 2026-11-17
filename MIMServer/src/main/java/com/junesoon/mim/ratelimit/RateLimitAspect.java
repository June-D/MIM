package com.junesoon.mim.ratelimit;

import com.junesoon.mim.auth.CurrentUser;
import com.junesoon.mim.utils.UserAuthUtil;
import javax.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.servlet.HandlerMapping;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * 限流切面：只负责从上下文中提取限流主体，判定交给 {@link RateLimiter}。
 *
 * <p>这样拆分的好处是核心规则（计数、阈值、降级）不依赖 Web 容器，可以直接单测。
 */
@Slf4j
@Aspect
@Component
public class RateLimitAspect {

    private final RateLimiter rateLimiter;
    private final UserAuthUtil userAuthUtil;

    /**
     * 是否信任 X-Forwarded-For / X-Real-IP。
     *
     * <p>默认 false。只有部署在可信反向代理后面、且代理会覆盖客户端自带的
     * 转发头时才能开启；否则任何人换一个头就能绕过登录/注册的 IP 限流。
     */
    @Value("${mim.rate-limit.trust-forwarded-headers:false}")
    private boolean trustForwardedHeaders;

    public RateLimitAspect(RateLimiter rateLimiter, UserAuthUtil userAuthUtil) {
        this.rateLimiter = rateLimiter;
        this.userAuthUtil = userAuthUtil;
    }

    @Around("@annotation(rateLimit)")
    public Object around(ProceedingJoinPoint joinPoint, RateLimit rateLimit) throws Throwable {
        String path = requestPath(joinPoint);
        RateLimit.Dimension dimension = rateLimit.dimension();

        String userKey = null;
        String ipKey = null;

        if (dimension == RateLimit.Dimension.USER || dimension == RateLimit.Dimension.BOTH) {
            String username = resolveUsername(joinPoint);
            if (username != null) {
                userKey = "u:" + username;
            } else if (dimension == RateLimit.Dimension.USER) {
                // 声明按用户限流却拿不到身份：退化到 IP，否则等于没有限流
                ipKey = "ip:" + clientIp();
            }
        }
        if (dimension == RateLimit.Dimension.IP || dimension == RateLimit.Dimension.BOTH) {
            ipKey = "ip:" + clientIp();
        }

        if (userKey != null) {
            rateLimiter.tryAcquire(userKey, path, rateLimit.limit(), rateLimit.windowSeconds());
        }
        if (ipKey != null) {
            rateLimiter.tryAcquire(ipKey, path, rateLimit.limit(), rateLimit.windowSeconds());
        }
        return joinPoint.proceed();
    }

    /**
     * 优先取标注了 {@link CurrentUser} 的实参；否则从 Authorization 头解析。
     */
    private String resolveUsername(ProceedingJoinPoint joinPoint) {
        MethodSignature signature = (MethodSignature) joinPoint.getSignature();
        Object[] args = joinPoint.getArgs();
        java.lang.annotation.Annotation[][] annotations =
            signature.getMethod().getParameterAnnotations();
        for (int i = 0; i < annotations.length && i < args.length; i++) {
            for (java.lang.annotation.Annotation annotation : annotations[i]) {
                if (annotation instanceof CurrentUser && args[i] instanceof String) {
                    return (String) args[i];
                }
            }
        }
        HttpServletRequest request = currentRequest();
        if (request == null) {
            return null;
        }
        String header = request.getHeader("Authorization");
        if (!StringUtils.hasText(header)) {
            return null;
        }
        String token = header.trim();
        if (token.regionMatches(true, 0, "Bearer ", 0, 7)) {
            token = token.substring(7).trim();
        }
        try {
            return userAuthUtil.getUsername(token);
        } catch (Exception e) {
            // 凭证无效交给后续鉴权流程报错，这里不重复
            return null;
        }
    }

    private String requestPath(ProceedingJoinPoint joinPoint) {
        HttpServletRequest request = currentRequest();
        if (request != null) {
            // 使用路由模板而不是原始 URI：/message/recall/{id} 必须共用一个计数桶，
            // 否则攻击者每次换一个 id 就等于换了一份额度。
            Object pattern = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
            if (pattern instanceof String && StringUtils.hasText((String) pattern)) {
                return (String) pattern;
            }
            if (request.getRequestURI() != null) {
                return request.getRequestURI();
            }
        }
        // 无法取得请求时按方法名区分，保证不同接口仍有独立额度
        return joinPoint.getSignature().getName();
    }

    private HttpServletRequest currentRequest() {
        RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
        if (attributes instanceof ServletRequestAttributes) {
            return ((ServletRequestAttributes) attributes).getRequest();
        }
        return null;
    }

    private String clientIp() {
        HttpServletRequest request = currentRequest();
        if (request == null) {
            return "unknown";
        }
        if (!trustForwardedHeaders) {
            return request.getRemoteAddr();
        }
        String forwarded = request.getHeader("X-Forwarded-For");
        if (StringUtils.hasText(forwarded)) {
            for (String part : forwarded.split(",")) {
                String candidate = part.trim();
                if (StringUtils.hasText(candidate) && !"unknown".equalsIgnoreCase(candidate)) {
                    return candidate;
                }
            }
        }
        String realIp = request.getHeader("X-Real-IP");
        if (StringUtils.hasText(realIp)) {
            return realIp.trim();
        }
        return request.getRemoteAddr();
    }
}
