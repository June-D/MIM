package com.junesoon.mim.config;

import java.io.IOException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 请求体大小上限。
 *
 * <p>Tomcat 的 {@code maxPostSize} 只约束 form 提交，不约束
 * {@code application/json}。Spring/Jackson 会把整个 JSON 读进内存后才进入
 * Controller，因此登录、注册这类未鉴权接口可以被一个超大 body 直接打爆。
 *
 * <p>这里在过滤器层按路径设置硬上限，并对未声明 Content-Length 的 chunked
 * 请求做流式计数，超过即中断。上限按接口的合法最大载荷设置，不影响正常协议。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestSizeLimitFilter extends OncePerRequestFilter {

    private static final long KB = 1024L;
    private static final long MB = 1024L * 1024L;

    /**
     * 默认 1MB：足够覆盖所有普通 JSON 请求。
     */
    private static final long DEFAULT_LIMIT = 1 * MB;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
        FilterChain filterChain) throws ServletException, IOException {
        long limit = limitFor(request.getRequestURI());
        long declared = request.getContentLengthLong();
        if (declared > limit) {
            reject(response, limit);
            return;
        }
        filterChain.doFilter(new LimitedRequest(request, limit), response);
    }

    private long limitFor(String uri) {
        if (uri == null) {
            return DEFAULT_LIMIT;
        }
        // 未鉴权接口：只允许很小的登录/注册 JSON
        if (uri.startsWith("/login/") || uri.startsWith("/register/")) {
            return 16 * KB;
        }
        // 同步请求本身是空 body，必须先于 /message/sync 判断
        if (uri.startsWith("/message/sync/request")) {
            return 16 * KB;
        }
        // 50 份密文 × 单份最大 256KB（Base64 后约 350KB）
        if (uri.startsWith("/message/sync")) {
            return 20 * MB;
        }
        // 20 份设备副本 × 单份最大 256KB（Base64 后约 350KB）
        if (uri.equals("/message")) {
            return 8 * MB;
        }
        // 200 个一次性预密钥公钥
        if (uri.startsWith("/prekey/one-time")) {
            return 512 * KB;
        }
        return DEFAULT_LIMIT;
    }

    private void reject(HttpServletResponse response, long limit) throws IOException {
        response.setStatus(413);
        response.setCharacterEncoding("UTF-8");
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write("{\"status\":413,\"rel\":false,\"message\":\"请求体过大（上限 "
            + limit + " 字节）\"}");
    }

    /**
     * 把输入流换成一个带计数上限的包装。
     */
    private static final class LimitedRequest extends HttpServletRequestWrapper {

        private final long limit;

        private ServletInputStream stream;

        LimitedRequest(HttpServletRequest request, long limit) {
            super(request);
            this.limit = limit;
        }

        @Override
        public ServletInputStream getInputStream() throws IOException {
            if (stream == null) {
                stream = new LimitedInputStream(super.getInputStream(), limit);
            }
            return stream;
        }
    }

    /**
     * 只读取最多 limit 字节，超出直接抛异常，避免继续占用内存。
     */
    private static final class LimitedInputStream extends ServletInputStream {

        private final ServletInputStream delegate;
        private final long limit;
        private long count;

        LimitedInputStream(ServletInputStream delegate, long limit) {
            this.delegate = delegate;
            this.limit = limit;
        }

        @Override
        public int read() throws IOException {
            int value = delegate.read();
            if (value != -1) {
                advance(1);
            }
            return value;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            int read = delegate.read(buffer, offset, length);
            if (read > 0) {
                advance(read);
            }
            return read;
        }

        private void advance(int bytes) throws IOException {
            count += bytes;
            if (count > limit) {
                throw new IOException("request body exceeds limit " + limit);
            }
        }

        @Override
        public boolean isFinished() {
            return delegate.isFinished();
        }

        @Override
        public boolean isReady() {
            return delegate.isReady();
        }

        @Override
        public void setReadListener(ReadListener listener) {
            delegate.setReadListener(listener);
        }
    }
}
