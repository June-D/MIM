package com.junesoon.mim.jwt;

import com.junesoon.mim.constant.CommonConstants;
import com.junesoon.mim.utils.StringHelper;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jws;
import io.jsonwebtoken.JwtBuilder;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import java.security.PrivateKey;
import java.util.Date;
import java.util.UUID;
import org.joda.time.DateTime;

public class JWTHelper {

    /**
     * 密钥加密token
     *
     * @param jwtInfo
     * @param priKeyPath
     * @param expire
     * @return
     * @throws Exception
     */
    public static String generateToken(JWTInfo jwtInfo, String priKeyPath, int expire) throws Exception {
        return generateToken(jwtInfo, RsaKeyHelper.getPrivateKey(RsaKeyHelper.decode(priKeyPath)), expire);
    }

    /**
     * 密钥加密token
     *
     * @param jwtInfo
     * @param priKey
     * @param expire
     * @return
     * @throws Exception
     */
    public static String generateToken(JWTInfo jwtInfo, byte priKey[], int expire) throws Exception {
        return generateToken(jwtInfo, RsaKeyHelper.getPrivateKey(priKey), expire);
    }

    /**
     * 统一构造令牌：登记 jti、签发时间、设备绑定与设备令牌版本。
     */
    private static String generateToken(JWTInfo jwtInfo, PrivateKey privateKey, int expire)
        throws Exception {
        String jti = jwtInfo.getJti() == null || jwtInfo.getJti().isEmpty()
            ? UUID.randomUUID().toString()
            : jwtInfo.getJti();
        JwtBuilder builder = Jwts.builder()
            .setSubject(jwtInfo.getUsername())
            .claim(CommonConstants.JWT_KEY_USER_ID, jwtInfo.getUserId())
            .claim(CommonConstants.JWT_KEY_NICKNAME, jwtInfo.getNickname())
            .claim(CommonConstants.JWT_KEY_TOKEN_VERSION, jwtInfo.getTokenVersion())
            .setId(jti)
            .setIssuedAt(new Date())
            .setExpiration(DateTime.now().plusSeconds(expire).toDate());
        if (jwtInfo.getDeviceId() != null && !jwtInfo.getDeviceId().isEmpty()) {
            builder.claim(CommonConstants.JWT_KEY_DEVICE_ID, jwtInfo.getDeviceId());
        }
        return builder.signWith(privateKey, SignatureAlgorithm.RS256).compact();
    }

    /**
     * 公钥解析token
     *
     * @param token
     * @return
     * @throws Exception
     */
    public static Jws<Claims> parserToken(String token, String pubKeyPath) throws Exception {
        Jws<Claims> claimsJws = Jwts.parserBuilder()
            .setSigningKey(RsaKeyHelper.getPublicKey(RsaKeyHelper.decode(pubKeyPath)))
            .build()
            .parseClaimsJws(token);
        return claimsJws;
    }

    /**
     * 公钥解析token
     *
     * @param token
     * @return
     * @throws Exception
     */
    public static Jws<Claims> parserToken(String token, byte[] pubKey) throws Exception {
        Jws<Claims> claimsJws = Jwts.parserBuilder()
            .setSigningKey(RsaKeyHelper.getPublicKey(pubKey))
            .build()
            .parseClaimsJws(token);
        return claimsJws;
    }

    /**
     * 获取token中的用户信息
     *
     * @param token
     * @param pubKeyPath
     * @return
     * @throws Exception
     */
    public static JWTInfo getInfoFromToken(String token, String pubKeyPath) throws Exception {
        Jws<Claims> claimsJws = parserToken(token, pubKeyPath);
        return toJwtInfo(claimsJws.getBody());
    }

    /**
     * 获取token中的用户信息
     *
     * @param token
     * @param pubKey
     * @return
     * @throws Exception
     */
    public static JWTInfo getInfoFromToken(String token, byte[] pubKey) throws Exception {
        Jws<Claims> claimsJws = parserToken(token, pubKey);
        return toJwtInfo(claimsJws.getBody());
    }

    /**
     * 从 Claims 还原载荷。缺省字段按 0 / null 处理，兼容升级前签发的令牌。
     */
    private static JWTInfo toJwtInfo(Claims body) {
        Object rawDevice = body.get(CommonConstants.JWT_KEY_DEVICE_ID);
        String deviceId = rawDevice == null ? null : rawDevice.toString();
        int tokenVersion = 0;
        Object rawVersion = body.get(CommonConstants.JWT_KEY_TOKEN_VERSION);
        if (rawVersion instanceof Number) {
            tokenVersion = ((Number) rawVersion).intValue();
        } else if (rawVersion != null) {
            try {
                tokenVersion = Integer.parseInt(rawVersion.toString());
            } catch (NumberFormatException ignored) {
                tokenVersion = 0;
            }
        }
        long issuedAt = body.getIssuedAt() == null ? 0L : body.getIssuedAt().getTime();
        long expiresAt = body.getExpiration() == null ? 0L : body.getExpiration().getTime();
        return new JWTInfo(body.getSubject(),
            StringHelper.getObjectValue(body.get(CommonConstants.JWT_KEY_USER_ID)),
            StringHelper.getObjectValue(body.get(CommonConstants.JWT_KEY_NICKNAME)),
            deviceId, tokenVersion, body.getId(), issuedAt, expiresAt);
    }
}
