package com.junesoon.mim.jwt;

import com.junesoon.mim.constant.CommonConstants;
import com.junesoon.mim.utils.StringHelper;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jws;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
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
        String compactJws = Jwts.builder()
            .setSubject(jwtInfo.getUsername())
            .claim(CommonConstants.JWT_KEY_USER_ID, jwtInfo.getUserId())
            .claim(CommonConstants.JWT_KEY_NICKNAME, jwtInfo.getNickname())
            .setExpiration(DateTime.now().plusSeconds(expire).toDate())
            .signWith(SignatureAlgorithm.RS256, RsaKeyHelper.getPrivateKey(RsaKeyHelper.decode(priKeyPath)))
            .compact();
        return compactJws;
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
        String compactJws = Jwts.builder()
            .setSubject(jwtInfo.getUsername())
            .claim(CommonConstants.JWT_KEY_USER_ID, jwtInfo.getUserId())
            .claim(CommonConstants.JWT_KEY_NICKNAME, jwtInfo.getNickname())
            .setExpiration(DateTime.now().plusSeconds(expire).toDate())
            .signWith(SignatureAlgorithm.RS256, RsaKeyHelper.getPrivateKey(priKey))
            .compact();
        return compactJws;
    }

    /**
     * 公钥解析token
     *
     * @param token
     * @return
     * @throws Exception
     */
    public static Jws<Claims> parserToken(String token, String pubKeyPath) throws Exception {
        Jws<Claims> claimsJws = Jwts.parser().setSigningKey(RsaKeyHelper.getPublicKey(RsaKeyHelper.decode(pubKeyPath))).parseClaimsJws(token);
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
        Jws<Claims> claimsJws = Jwts.parser().setSigningKey(RsaKeyHelper.getPublicKey(pubKey)).parseClaimsJws(token);
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
        Claims body = claimsJws.getBody();
        return new JWTInfo(body.getSubject(), StringHelper.getObjectValue(body.get(CommonConstants.JWT_KEY_USER_ID)), StringHelper.getObjectValue(body.get(CommonConstants.JWT_KEY_NICKNAME)));
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
        Claims body = claimsJws.getBody();
        return new JWTInfo(body.getSubject(), StringHelper.getObjectValue(body.get(CommonConstants.JWT_KEY_USER_ID)), StringHelper.getObjectValue(body.get(CommonConstants.JWT_KEY_NICKNAME)));
    }
}
