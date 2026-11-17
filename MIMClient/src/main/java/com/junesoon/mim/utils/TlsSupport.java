package com.junesoon.mim.utils;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.KeyStore;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;
import lombok.extern.slf4j.Slf4j;

/**
 * 客户端 TLS 信任配置。
 *
 * <p>优先使用系统信任库；若配置了 {@code mim.tls.caFile}，则在该信任库基础上
 * 追加自定义 CA（用于自建 CA 或内网证书）。
 *
 * <p>只有在显式设置 {@code mim.tls.verifyCertificate=false} 时才会使用
 * 「信任所有证书」的降级模式 —— 这会让中间人攻击成为可能，
 * 因此日志会打警告，且默认值是 true。
 */
@Slf4j
public final class TlsSupport {

    private static final String PROP_VERIFY = "mim.tls.verifyCertificate";
    private static final String PROP_CA_FILE = "mim.tls.caFile";

    private TlsSupport() {
    }

    /**
     * 是否启用证书校验。默认 true。
     */
    public static boolean verifyEnabled() {
        String value = System.getProperty(PROP_VERIFY);
        if (value == null) {
            value = loadFromProperties(PROP_VERIFY);
        }
        return value == null || !"false".equalsIgnoreCase(value.trim());
    }

    /**
     * 构建 SSLSocketFactory。HTTP 与 WebSocket 共用。
     */
    public static SSLSocketFactory socketFactory() {
        try {
            SSLContext context = SSLContext.getInstance("TLS");
            if (verifyEnabled()) {
                TrustManager[] managers = trustManagers();
                context.init(null, managers, new java.security.SecureRandom());
            } else {
                log.warn("已关闭 TLS 证书校验，连接可能被中间人劫持。仅限本地调试使用");
                context.init(null, new TrustManager[] {trustAll()}, new java.security.SecureRandom());
            }
            return context.getSocketFactory();
        } catch (Exception e) {
            throw new IllegalStateException("初始化 TLS 失败：" + e.getMessage(), e);
        }
    }

    /**
     * 给 OkHttp 用的 X509TrustManager。
     *
     * <p>OkHttp 的 {@code sslSocketFactory(factory, trustManager)} 需要这个参数。
     * 关闭校验时返回一个「全部放行」的实现。
     */
    public static X509TrustManager trustManager() {
        if (!verifyEnabled()) {
            return trustAll();
        }
        try {
            TrustManager[] managers = trustManagers();
            if (managers == null) {
                TrustManagerFactory factory =
                    TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
                factory.init((KeyStore) null);
                managers = factory.getTrustManagers();
            }
            for (TrustManager manager : managers) {
                if (manager instanceof X509TrustManager) {
                    return (X509TrustManager) manager;
                }
            }
            throw new IllegalStateException("找不到可用的 X509TrustManager");
        } catch (Exception e) {
            throw new IllegalStateException("初始化信任管理器失败：" + e.getMessage(), e);
        }
    }

    private static TrustManager[] trustManagers() throws Exception {
        String caFile = System.getProperty(PROP_CA_FILE);
        if (caFile == null) {
            caFile = loadFromProperties(PROP_CA_FILE);
        }
        if (caFile == null || caFile.trim().isEmpty()) {
            // 使用 JVM 默认信任库
            return null;
        }
        Path path = Paths.get(caFile.trim());
        if (!Files.exists(path)) {
            log.warn("配置的自定义 CA 文件不存在，回退到系统信任库：{}", path);
            return null;
        }
        KeyStore keyStore = KeyStore.getInstance(KeyStore.getDefaultType());
        keyStore.load(null, null);
        try (InputStream input = Files.newInputStream(path)) {
            CertificateFactory factory = CertificateFactory.getInstance("X.509");
            int index = 0;
            for (java.security.cert.Certificate cert : factory.generateCertificates(input)) {
                keyStore.setCertificateEntry("mim-ca-" + index, cert);
                index++;
            }
        }
        // 合并系统信任库与自定义 CA
        TrustManagerFactory systemFactory =
            TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        systemFactory.init((KeyStore) null);
        TrustManagerFactory customFactory =
            TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        customFactory.init(keyStore);

        return new TrustManager[] {
            new CompositeTrustManager(
                (X509TrustManager) systemFactory.getTrustManagers()[0],
                (X509TrustManager) customFactory.getTrustManagers()[0])
        };
    }

    private static X509TrustManager trustAll() {
        return new X509TrustManager() {
            @Override
            public void checkClientTrusted(X509Certificate[] chain, String authType) {
            }

            @Override
            public void checkServerTrusted(X509Certificate[] chain, String authType) {
            }

            @Override
            public X509Certificate[] getAcceptedIssuers() {
                return new X509Certificate[0];
            }
        };
    }

    /**
     * 系统信任库或自定义 CA 任一通过即可。
     */
    private static final class CompositeTrustManager implements X509TrustManager {

        private final X509TrustManager primary;
        private final X509TrustManager secondary;

        CompositeTrustManager(X509TrustManager primary, X509TrustManager secondary) {
            this.primary = primary;
            this.secondary = secondary;
        }

        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType)
            throws java.security.cert.CertificateException {
            try {
                primary.checkClientTrusted(chain, authType);
            } catch (java.security.cert.CertificateException e) {
                secondary.checkClientTrusted(chain, authType);
            }
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType)
            throws java.security.cert.CertificateException {
            try {
                primary.checkServerTrusted(chain, authType);
            } catch (java.security.cert.CertificateException e) {
                secondary.checkServerTrusted(chain, authType);
            }
        }

        @Override
        public X509Certificate[] getAcceptedIssuers() {
            return primary.getAcceptedIssuers();
        }
    }

    private static String loadFromProperties(String key) {
        try (InputStream input = TlsSupport.class.getClassLoader()
            .getResourceAsStream("feign-client.properties")) {
            if (input == null) {
                return null;
            }
            java.util.Properties properties = new java.util.Properties();
            properties.load(input);
            return properties.getProperty(key);
        } catch (Exception e) {
            return null;
        }
    }
}
