package com.junesoon.mim.service.impl;

import com.junesoon.mim.exception.UserInvalidException;
import com.junesoon.mim.jwt.RsaKeyHelper;
import com.junesoon.mim.responses.SenderTokenKey;
import com.junesoon.mim.service.RsaKeyService;
import com.junesoon.mim.service.SenderTokenService;
import com.junesoon.mim.utils.BlindRsa;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

/**
 * 发送者令牌签发与消费（Phase 2c）。
 *
 * <p>三件事必须同时成立，缺一条就会把服务端变成开放垃圾投递口：
 * <ol>
 *   <li>签发鉴权 + 每账号每日配额（Redis 计数器）；</li>
 *   <li>令牌带时间戳，过期即拒；</li>
 *   <li>消费一次性（Redis SETNX），防重放。</li>
 * </ol>
 *
 * <p>密钥与 JWT / 证书密钥分离（{@code MIM:AUTH:SENDER-TOKEN:*}），
 * 存 {@code t_rsa_key} 并沿用主密钥加密。
 *
 * [INPUT]: 依赖 RsaKeyService 与 StringRedisTemplate（配额/重放）
 * [OUTPUT]: key / signBlinded / consume
 * [POS]: 2c 的信任材料；匿名提交接口用它替代 JWT
 * [PROTOCOL]: Update this header on change, then check CLAUDE.md
 */
@Slf4j
@Service
public class SenderTokenServiceImpl implements SenderTokenService {

    private static final String PRI_KEY = "MIM:AUTH:SENDER-TOKEN:PRI";
    private static final String PUB_KEY = "MIM:AUTH:SENDER-TOKEN:PUB";

    /** 令牌时间戳允许的偏差：拒绝明显倒填/穿越的令牌。 */
    private static final long WINDOW_MS = 15L * 60 * 1000;

    /** 重放记录保留时间：窗口的 2 倍即可覆盖令牌有效期。 */
    private static final long REPLAY_TTL_SECONDS = WINDOW_MS / 1000 * 2;

    /** 每账号每日令牌配额：一次会话的每条消息各消耗一个令牌。 */
    private static final long DAILY_QUOTA = 500;

    private static final String QUOTA_PREFIX = "mim:token:quota:";
    private static final String USED_PREFIX = "mim:token:used:";

    private static final int NONCE_BYTES = 32;

    private final RsaKeyService rsaKeyService;

    private final StringRedisTemplate redisTemplate;

    @Value("${mim.sender-token.enabled:false}")
    private boolean enabled;

    private volatile PrivateKey privateKey;

    private volatile PublicKey publicKey;

    private volatile String keyId;

    public SenderTokenServiceImpl(RsaKeyService rsaKeyService, StringRedisTemplate redisTemplate) {
        this.rsaKeyService = rsaKeyService;
        this.redisTemplate = redisTemplate;
    }

    @Override
    public boolean enabled() {
        return enabled;
    }

    @Override
    public SenderTokenKey key() {
        requireEnabled();
        ensureKeys();
        SenderTokenKey view = new SenderTokenKey();
        view.setKeyId(keyId);
        view.setPublicKey(Base64.getEncoder().encodeToString(publicKey.getEncoded()));
        view.setAlgorithm("RSA");
        return view;
    }

    @Override
    public String signBlinded(String username, String blinded) {
        requireEnabled();
        if (blinded == null || blinded.isEmpty() || blinded.length() > 1024) {
            throw new UserInvalidException("致盲值不合法");
        }
        BigInteger value;
        try {
            value = new BigInteger(1, Base64.getUrlDecoder().decode(blinded));
        } catch (IllegalArgumentException e) {
            throw new UserInvalidException("致盲值格式错误");
        }
        ensureKeys();
        BigInteger modulus = ((RSAPrivateKey) privateKey).getModulus();
        if (value.compareTo(modulus) >= 0) {
            throw new UserInvalidException("致盲值超出模数范围");
        }
        consumeQuota(username);
        BigInteger signature = BlindRsa.signBlinded(value, (RSAPrivateKey) privateKey);
        int modulusBytes = (modulus.bitLength() + 7) / 8;
        return Base64.getUrlEncoder().withoutPadding()
            .encodeToString(fixedLength(signature, modulusBytes));
    }

    @Override
    public String consume(String token) {
        requireEnabled();
        if (token == null) {
            throw new UserInvalidException("缺少发送者令牌");
        }
        int dot = token.indexOf('.');
        if (dot <= 0 || dot == token.length() - 1) {
            throw new UserInvalidException("发送者令牌格式不合法");
        }
        byte[] nonce;
        BigInteger signature;
        try {
            nonce = Base64.getUrlDecoder().decode(token.substring(0, dot));
            signature = new BigInteger(1,
                Base64.getUrlDecoder().decode(token.substring(dot + 1)));
        } catch (IllegalArgumentException e) {
            throw new UserInvalidException("发送者令牌格式不合法");
        }
        if (nonce.length != NONCE_BYTES) {
            throw new UserInvalidException("发送者令牌格式不合法");
        }
        ensureKeys();
        long issuedAt = readTimestamp(nonce);
        long now = System.currentTimeMillis();
        if (issuedAt < now - WINDOW_MS || issuedAt > now + WINDOW_MS) {
            throw new UserInvalidException("发送者令牌已过期");
        }
        if (!BlindRsa.verify(signature, nonce, (RSAPublicKey) publicKey)) {
            throw new UserInvalidException("发送者令牌无效");
        }
        String usedKey = USED_PREFIX + sha256Hex(nonce);
        Boolean first = redisTemplate.opsForValue()
            .setIfAbsent(usedKey, "1", REPLAY_TTL_SECONDS, TimeUnit.SECONDS);
        if (!Boolean.TRUE.equals(first)) {
            throw new UserInvalidException("发送者令牌已使用");
        }
        return Base64.getUrlEncoder().withoutPadding().encodeToString(nonce);
    }

    /**
     * 每日配额。Redis 不可用时直接失败：匿名通道没有账号可追，
     * 限流失效比暂时不可用更危险。
     */
    private void consumeQuota(String username) {
        String day = LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE);
        String key = QUOTA_PREFIX + username + ":" + day;
        Long used;
        try {
            used = redisTemplate.opsForValue().increment(key);
            if (used != null && used == 1L) {
                redisTemplate.expire(key, 2, TimeUnit.DAYS);
            }
        } catch (Exception e) {
            throw new UserInvalidException("令牌服务暂时不可用，请稍后重试");
        }
        if (used != null && used > DAILY_QUOTA) {
            throw new UserInvalidException("今日令牌配额已用完");
        }
    }

    private static long readTimestamp(byte[] nonce) {
        long value = 0L;
        for (int i = 0; i < 8; i++) {
            value = (value << 8) | (nonce[i] & 0xffL);
        }
        return value;
    }

    private static byte[] fixedLength(BigInteger value, int length) {
        byte[] raw = value.toByteArray();
        // toByteArray 可能带前导 0；统一按模数字节数左补零
        if (raw.length == length) {
            return raw;
        }
        byte[] out = new byte[length];
        int copy = Math.min(raw.length, length);
        System.arraycopy(raw, raw.length - copy, out, length - copy, copy);
        return out;
    }

    private static String sha256Hex(byte[] value) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(value);
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                hex.append(String.format("%02x", b & 0xff));
            }
            return hex.toString();
        } catch (Exception e) {
            throw new IllegalStateException("当前 JDK 不支持 SHA-256", e);
        }
    }

    private void requireEnabled() {
        if (!enabled) {
            throw new UserInvalidException("发送者令牌功能未启用");
        }
    }

    private void ensureKeys() {
        if (privateKey != null && publicKey != null) {
            return;
        }
        synchronized (this) {
            if (privateKey != null && publicKey != null) {
                return;
            }
            try {
                byte[] publicBytes;
                byte[] privateBytes;
                if (rsaKeyService.hasKey(PRI_KEY) && rsaKeyService.hasKey(PUB_KEY)) {
                    publicBytes = RsaKeyHelper.decode(rsaKeyService.get(PUB_KEY));
                    privateBytes = RsaKeyHelper.decode(rsaKeyService.get(PRI_KEY));
                } else {
                    Map<String, byte[]> generated = RsaKeyHelper.generateKey();
                    publicBytes = generated.get("pub");
                    privateBytes = generated.get("pri");
                    rsaKeyService.set(PRI_KEY, RsaKeyHelper.encode(privateBytes));
                    rsaKeyService.set(PUB_KEY, RsaKeyHelper.encode(publicBytes));
                    log.info("生成发送者令牌 RSA {} 位密钥对", RsaKeyHelper.KEY_SIZE);
                }
                publicKey = RsaKeyHelper.getPublicKey(publicBytes);
                privateKey = RsaKeyHelper.getPrivateKey(privateBytes);
                keyId = sha256Hex(publicBytes);
            } catch (Exception e) {
                throw new IllegalStateException("发送者令牌密钥不可用", e);
            }
        }
    }
}
