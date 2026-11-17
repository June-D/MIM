package com.junesoon.mim.components;

import com.junesoon.mim.feign.FeignAPI;
import com.junesoon.mim.feign.codec.AppRequestErrorDecoder;
import com.junesoon.mim.feign.codec.AppRequestInterceptor;
import com.junesoon.mim.utils.TlsSupport;
import com.netflix.client.ClientFactory;
import com.netflix.client.config.IClientConfig;
import com.netflix.loadbalancer.BestAvailableRule;
import com.netflix.loadbalancer.ILoadBalancer;
import com.netflix.loadbalancer.ZoneAwareLoadBalancer;
import feign.Feign;
import feign.Logger;
import feign.Retryer;
import feign.jackson.JacksonDecoder;
import feign.jackson.JacksonEncoder;
import feign.okhttp.OkHttpClient;
import feign.ribbon.LBClient;
import feign.ribbon.RibbonClient;
import feign.slf4j.Slf4jLogger;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import okhttp3.ConnectionPool;

public class Request {
    private static final Map<String, FeignAPI> CONNECTORS = new ConcurrentHashMap<>();
    private static RibbonClient ribbonClient;
    private static final String API_URL = "http://feign-client";
    private static final int CONNECT_TIME_OUT_MILLIS = 1000;
    private static final int READ_TIME_OUT_MILLIS = 60000;
    private static JacksonDecoder jacksonDecoder;
    private static JacksonEncoder jacksonEncoder;
    private static AppRequestInterceptor requestInterceptor;
    private static AppRequestErrorDecoder requestErrorDecoder;
    private static Slf4jLogger slf4jLogger;

    public static <T extends FeignAPI> T connector(Class<T> connectorClass, int readTimeOut) {
        final String commandConfigKey = connectorClass.getSimpleName() + readTimeOut;

        return (T) CONNECTORS.computeIfAbsent(commandConfigKey, k -> {
            return Feign.builder()
                .client(ribbonClient())
                .decoder(getJacksonDecoder())
                .encoder(getJacksonEncoder())
                .errorDecoder(getRequestErrorDecoder())
                .requestInterceptor(getRequestInterceptor())
                .logger(getSlf4jLogger())
                // 绝不使用 FULL：Feign 的 FULL 会打印 Authorization 头和登录请求体，
                // 等于把 JWT 与口令 Base64 写进本地日志文件。
                .logLevel(Logger.Level.NONE)
                .retryer(Retryer.NEVER_RETRY)
                .options(new feign.Request.Options(CONNECT_TIME_OUT_MILLIS, readTimeOut))
                .target(connectorClass, API_URL);

        });
    }

    public static <T extends FeignAPI> T connector(Class<T> connectorClass) {
        return connector(connectorClass, READ_TIME_OUT_MILLIS);
    }

    private static JacksonDecoder getJacksonDecoder() {
        if (jacksonDecoder == null) {
            jacksonDecoder = new JacksonDecoder();
        }
        return jacksonDecoder;
    }

    private static JacksonEncoder getJacksonEncoder() {
        if (jacksonEncoder == null) {
            jacksonEncoder = new JacksonEncoder();
        }
        return jacksonEncoder;
    }

    private static AppRequestInterceptor getRequestInterceptor() {
        if (requestInterceptor == null) {
            requestInterceptor = new AppRequestInterceptor();
        }
        return requestInterceptor;
    }

    private static AppRequestErrorDecoder getRequestErrorDecoder() {
        if (requestErrorDecoder == null) {
            requestErrorDecoder = new AppRequestErrorDecoder();
        }
        return requestErrorDecoder;
    }

    private static Slf4jLogger getSlf4jLogger() {
        if (slf4jLogger == null) {
            slf4jLogger = new Slf4jLogger();
        }
        return slf4jLogger;
    }

    private static RibbonClient ribbonClient() {
        if (ribbonClient == null) {
            ribbonClient = RibbonClient.builder().delegate(new OkHttpClient(createOkHttpClient())).lbClientFactory(clientName -> {
                System.err.println("--------------------" + clientName + "--------------------");
                IClientConfig config = ClientFactory.getNamedConfig(clientName);
                ILoadBalancer lb = ClientFactory.getNamedLoadBalancer(clientName);

                ZoneAwareLoadBalancer zb = (ZoneAwareLoadBalancer) lb;
                zb.setRule(new BestAvailableRule());
                LBClient lbClient = LBClient.create(lb, config);
                return lbClient;
            }).build();
        }
        return ribbonClient;
    }

    private static okhttp3.OkHttpClient createOkHttpClient() {
        okhttp3.OkHttpClient.Builder builder = new okhttp3.OkHttpClient.Builder()
            .connectionPool(new ConnectionPool());
        boolean verify = TlsSupport.verifyEnabled();
        String caFile = System.getProperty("mim.tls.caFile");
        boolean customCa = caFile != null && !caFile.trim().isEmpty();
        if (!verify) {
            // 关闭校验：仅本地调试。生产环境务必保持 mim.tls.verifyCertificate=true
            builder.sslSocketFactory(TlsSupport.socketFactory(), TlsSupport.trustManager());
            builder.hostnameVerifier((hostname, session) -> true);
        } else if (customCa) {
            // 自建 CA：用我们自己的信任库替换默认信任链，主机名校验保留
            builder.sslSocketFactory(TlsSupport.socketFactory(), TlsSupport.trustManager());
        }
        // 其余情况（系统信任库 + 默认主机名校验）交给 OkHttp 默认实现
        return builder.build();
    }
}
